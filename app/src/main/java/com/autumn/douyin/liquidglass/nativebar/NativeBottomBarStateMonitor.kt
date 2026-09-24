package com.autumn.douyin.liquidglass.nativebar

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.autumn.douyin.liquidglass.ModuleLog

/**
 * Tracks whether Douyin still keeps the native bottom-bar row logically present.
 * The module makes the five button children invisible, so the shared parent is the signal.
 */
class NativeBottomBarStateMonitor(
    private val nativeBar: NativeBottomBar,
    private val onPresentChanged: (Boolean, Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastObservedPresent: Boolean? = null
    private var confirmedPresent: Boolean? = null
    private var absentSinceMs: Long? = null

    private val poller = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            mainHandler.postDelayed(this, SamplingIntervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastObservedPresent = null
        confirmedPresent = null
        absentSinceMs = null
        ModuleLog.info("native bar state monitor started")
        sample()
        mainHandler.postDelayed(poller, SamplingIntervalMs)
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(poller)
        lastObservedPresent = null
        confirmedPresent = null
        absentSinceMs = null
        ModuleLog.info("native bar state monitor stopped")
    }

    private fun sample() {
        val parent = nativeBar.home.parent as? View
        val parentPresent = parent != null &&
            parent.isAttachedToWindow &&
            parent.isShown &&
            parent.visibility != View.GONE &&
            parent.alpha > MinParentAlpha &&
            parent.width > 0 &&
            parent.height > 0
        val focusedView = nativeBar.home.rootView?.findFocus()
        val inputFocused = focusedView is EditText
        val bottomInputSurface = parent?.let { parentView ->
            findBottomInputSurface(parentView)
        }
        val present = parentPresent && !inputFocused && bottomInputSurface == null
        val now = SystemClock.elapsedRealtime()

        if (present != lastObservedPresent) {
            lastObservedPresent = present
            if (present) {
                val absentSince = absentSinceMs
                absentSinceMs = null
                confirmedPresent = true
                ModuleLog.info {
                    "native bar observed present=true " +
                        "transientAbsentMs=${absentSince?.let { now - it } ?: 0} " +
                        "parent=${describe(parent)} " +
                        "focus=${describeFocus(focusedView)} " +
                        "bottomInputSurface=${describeInputSurface(bottomInputSurface)}"
                }
                onPresentChanged(present, true)
            } else {
                absentSinceMs = now
                ModuleLog.info {
                    "native bar observed present=false transient " +
                        "parent=${describe(parent)} " +
                        "focus=${describeFocus(focusedView)} " +
                        "bottomInputSurface=${describeInputSurface(bottomInputSurface)}"
                }
                if (confirmedPresent != false) onPresentChanged(present, false)
            }
            return
        }

        if (!present && confirmedPresent != false) {
            val absentSince = absentSinceMs ?: now.also { absentSinceMs = it }
            if (now - absentSince >= HideDebounceMs) {
                confirmedPresent = false
                absentSinceMs = null
                ModuleLog.info {
                    "native bar present=false stableAbsentMs=${now - absentSince} " +
                        "parent=${describe(parent)} " +
                        "focus=${describeFocus(focusedView)} " +
                        "bottomInputSurface=${describeInputSurface(bottomInputSurface)}"
                }
                onPresentChanged(present, true)
            }
        }
    }

    private fun findBottomInputSurface(parent: View): View? {
        val parentLocation = IntArray(2)
        parent.getLocationInWindow(parentLocation)
        val region = Rect(
            parentLocation[0] - RegionPaddingPx,
            parentLocation[1] - RegionPaddingPx,
            parentLocation[0] + parent.width + RegionPaddingPx,
            parentLocation[1] + parent.height + RegionPaddingPx,
        )
        var result: View? = null
        collectViews(parent.rootView) { view ->
            if (result != null) return@collectViews
            if (!view.isShown || !view.isAttachedToWindow) return@collectViews
            if (view.width <= 0 || view.height <= 0) return@collectViews

            val location = IntArray(2)
            view.getLocationInWindow(location)
            val bounds = Rect(
                location[0],
                location[1],
                location[0] + view.width,
                location[1] + view.height,
            )
            if (Rect.intersects(region, bounds) &&
                (view.onCheckIsTextEditor() || isBottomVoiceInput(view, parent))
            ) {
                result = view
            }
        }
        return result
    }

    private fun isBottomVoiceInput(view: View, parent: View): Boolean {
        if (view.width < parent.width * MinVoiceInputWidthRatio) return false
        if (view.height < parent.height * MinVoiceInputHeightRatio) return false
        if (view.id != View.NO_ID) {
            val resourceName = runCatching {
                view.context.resources.getResourceEntryName(view.id)
            }.getOrNull()
            if (resourceName == VoiceInputResourceName) return true
        }

        val text = (view as? TextView)?.text?.toString().orEmpty()
        val description = view.contentDescription?.toString().orEmpty()
        return listOf(text, description).any(::isVoiceInputLabel)
    }

    private fun isVoiceInputLabel(value: String): Boolean {
        val normalized = value.replace(Regex("\\s+"), "")
        return normalized == "按住说话" ||
            normalized == "松开说话" ||
            normalized == "松开发送"
    }

    private fun collectViews(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectViews(view.getChildAt(index), action)
            }
        }
    }

    private fun describeInputSurface(view: View?): String {
        if (view == null) return "none"
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return "${view.javaClass.name}@${System.identityHashCode(view).toString(16)} " +
            "window=$location[0],$location[1] size=${view.width}x${view.height}"
    }

    private fun describeFocus(view: View?): String {
        if (view == null) return "none"
        return "${view.javaClass.name}@${System.identityHashCode(view).toString(16)} " +
            "editable=${view is EditText} shown=${view.isShown} size=${view.width}x${view.height}"
    }

    private fun describe(view: View?): String {
        if (view == null) return "none"
        return "${view.javaClass.simpleName}@${System.identityHashCode(view).toString(16)} " +
            "vis=${visibilityName(view.visibility)} shown=${view.isShown} " +
            "attached=${view.isAttachedToWindow} alpha=${view.alpha} size=${view.width}x${view.height}"
    }

    private fun visibilityName(value: Int): String = when (value) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> value.toString()
    }

    private companion object {
        const val SamplingIntervalMs = 100L
        const val HideDebounceMs = 1_200L
        const val MinParentAlpha = 0.05f
        const val RegionPaddingPx = 96
        const val VoiceInputResourceName = "microphone_btn_new"
        const val MinVoiceInputWidthRatio = 0.45f
        const val MinVoiceInputHeightRatio = 0.5f
    }
}
