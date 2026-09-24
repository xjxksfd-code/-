package com.autumn.douyin.liquidglass.ui

import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.SeekBar
import com.autumn.douyin.liquidglass.ModuleLog
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Keeps Douyin's feed overlay above the glass bar by changing its own bottom
 * layout boundary. Individual controls keep their native relative layout.
 */
object BottomAdjacentControlAvoidance {
    private const val ApplyIntervalMs = 100L
    private const val ScanIntervalMs = 1_000L
    private const val LayoutScanIntervalMs = 100L
    private const val ContainerRetentionMs = 2_500L
    private const val NegativeLogIntervalMs = 5_000L
    private const val FallbackGlassContentBottomInsetDp = 28f
    private const val ClearanceDp = 4f
    private const val MaxBottomInsetPx = 220
    private const val MaxGuideHeightPx = 220
    private const val MinGlassContentTopRootFraction = 0.60f
    private const val MaxGlassContentTopRootFraction = 1.05f
    private const val FeedOverlayContainerId = "ja"
    private const val ProgressOverlayContainerId = "xw_"
    private val progressNames = setOf("cez", "v5j", "progress_bar")

    private val runners = WeakHashMap<View, Runnable>()
    private val containers = WeakHashMap<View, MutableSet<View>>()
    private val originalPaddings = WeakHashMap<View, Int>()
    private val contentLines = WeakHashMap<View, Int>()
    private val lastScanTimes = WeakHashMap<View, Long>()
    private val glassContentTops = WeakHashMap<View, Float>()
    private val lastSeenTimes = WeakHashMap<View, Long>()
    private val layoutDirty = WeakHashMap<View, Boolean>()
    private val layoutListeners = WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()
    private val preDrawListeners = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()
    private val lastProgressMoveLogTimes = WeakHashMap<View, Long>()
    private val lastNoTargetLogTimes = WeakHashMap<View, Long>()
    private val lastNoInsetLogTimes = WeakHashMap<View, Long>()

    private data class FeedContainerCandidate(
        val view: View,
        val contentLine: Int,
    )

    private data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun start(root: View) {
        if (!root.isAttachedToWindow) {
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    root.removeOnAttachStateChangeListener(this)
                    startLocked(view)
                }

                override fun onViewDetachedFromWindow(view: View) = Unit
            })
            return
        }
        startLocked(root)
    }

    fun stop(root: View) {
        val runner = synchronized(runners) { runners.remove(root) }
        runner?.let(root::removeCallbacks)
        val observer = root.viewTreeObserver
        synchronized(layoutListeners) { layoutListeners.remove(root) }?.let {
            observer.removeOnGlobalLayoutListener(it)
        }
        synchronized(preDrawListeners) { preDrawListeners.remove(root) }?.let {
            observer.removeOnPreDrawListener(it)
        }
        val targets = synchronized(containers) { containers.remove(root) }
        targets?.forEach(::restoreContainer)
        synchronized(lastScanTimes) { lastScanTimes.remove(root) }
        synchronized(glassContentTops) { glassContentTops.remove(root) }
        synchronized(lastSeenTimes) { lastSeenTimes.remove(root) }
        synchronized(layoutDirty) { layoutDirty.remove(root) }
        synchronized(lastNoTargetLogTimes) { lastNoTargetLogTimes.remove(root) }
        synchronized(lastNoInsetLogTimes) { lastNoInsetLogTimes.remove(root) }
    }

    fun updateGlassContentTop(root: View, topOnScreen: Float) {
        if (!root.isAttachedToWindow || root.height <= 0 || !topOnScreen.isFinite()) {
            ModuleLog.info {
                "feed layout ignored unavailable glass geometry: " +
                    "topOnScreen=$topOnScreen rootHeight=${root.height}"
            }
            return
        }
        val rootPosition = screenLocation(root)
        val topInRoot = topOnScreen - rootPosition[1]
        val minimumTop = root.height * MinGlassContentTopRootFraction
        val maximumTop = root.height * MaxGlassContentTopRootFraction
        if (topInRoot !in minimumTop..maximumTop) {
            ModuleLog.info {
                "feed layout ignored invalid glass geometry: " +
                    "topInRoot=${topInRoot.roundToInt()}px " +
                    "validRange=${minimumTop.roundToInt()}-${maximumTop.roundToInt()}px"
            }
            return
        }
        synchronized(glassContentTops) { glassContentTops[root] = topInRoot }
    }

    private fun startLocked(root: View) {
        synchronized(runners) {
            if (runners.containsKey(root)) return
            val runner = object : Runnable {
                override fun run() {
                    if (!root.isAttachedToWindow) {
                        stop(root)
                        return
                    }
                    apply(root)
                    root.postDelayed(this, ApplyIntervalMs)
                }
            }
            runners[root] = runner
            root.post(runner)
        }
        installLayoutBridge(root)
        ModuleLog.info("feed layout avoidance started")
    }

    private fun installLayoutBridge(root: View) {
        synchronized(layoutListeners) {
            if (layoutListeners.containsKey(root)) return
        }

        val observer = root.viewTreeObserver
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            synchronized(layoutDirty) { layoutDirty[root] = true }
        }
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            val now = System.currentTimeMillis()
            val dirty = synchronized(layoutDirty) { layoutDirty[root] ?: false }
            val lastScan = synchronized(lastScanTimes) { lastScanTimes[root] ?: 0L }
            if (!dirty || now - lastScan < LayoutScanIntervalMs) return@OnPreDrawListener true

            synchronized(layoutDirty) { layoutDirty.remove(root) }
            synchronized(lastScanTimes) { lastScanTimes[root] = now }
            refreshContainers(root)
            val targets = synchronized(containers) { containers[root] }.orEmpty()
            val paddingsBefore = targets.associateWith { it.paddingBottom }
            apply(root, allowScan = false)
            val changed = targets.any { it.paddingBottom != paddingsBefore[it] }
            if (changed) {
                ModuleLog.info("feed layout pre-draw clearance held")
            }
            !changed
        }

        observer.addOnGlobalLayoutListener(layoutListener)
        observer.addOnPreDrawListener(preDrawListener)
        synchronized(layoutListeners) { layoutListeners[root] = layoutListener }
        synchronized(preDrawListeners) { preDrawListeners[root] = preDrawListener }
    }

    private fun apply(root: View, allowScan: Boolean = true) {
        val now = System.currentTimeMillis()
        val shouldScan = allowScan && synchronized(lastScanTimes) {
            val last = lastScanTimes[root] ?: 0L
            now - last >= ScanIntervalMs
        }
        if (shouldScan) {
            synchronized(lastScanTimes) { lastScanTimes[root] = now }
            refreshContainers(root)
        }

        val targets = synchronized(containers) { containers[root] } ?: return
        if (targets.isEmpty()) logNoTarget(root, 0)
        val clearanceTop = glassContentTopPx(root) -
            ClearanceDp * root.resources.displayMetrics.density
        targets.forEach { container ->
            if (!container.isAttachedToWindow || !container.isShown) return@forEach
            val contentLine = synchronized(contentLines) { contentLines[container] } ?: return@forEach
            val originalPadding = synchronized(originalPaddings) { originalPaddings[container] }
                ?: return@forEach
            val requiredInset = (contentLine - clearanceTop)
                .roundToInt()
                .coerceIn(0, MaxBottomInsetPx)
            val targetPadding = originalPadding + requiredInset
            if (requiredInset == 0 && container.paddingBottom == originalPadding) {
                logNoInset(container, contentLine, clearanceTop)
                return@forEach
            }
            if (container.paddingBottom == targetPadding) return@forEach

            container.setPadding(
                container.paddingLeft,
                container.paddingTop,
                container.paddingRight,
                targetPadding,
            )
            ModuleLog.info {
                "feed layout clearance: container=${describe(container)} " +
                    "contentLine=${contentLine}px clearanceTop=${clearanceTop.roundToInt()}px " +
                    "paddingBottom=$originalPadding->$targetPadding"
            }
        }
    }

    private fun refreshContainers(root: View) {
        val now = System.currentTimeMillis()
        val current = synchronized(containers) { containers[root] }
        val replacement = findContainers(root)
        val retained = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

        replacement.forEach { container ->
            synchronized(lastSeenTimes) { lastSeenTimes[container] = now }
            retained.add(container)
        }

        current?.forEach { container ->
            if (container in replacement) return@forEach
            val lastSeen = synchronized(lastSeenTimes) { lastSeenTimes[container] } ?: now
            val withinTransition = container.isAttachedToWindow &&
                now - lastSeen <= ContainerRetentionMs
            if (withinTransition) {
                retained.add(container)
            } else {
                restoreContainer(container)
            }
        }

        retained.forEach { container ->
            val originalPadding = synchronized(originalPaddings) {
                originalPaddings.getOrPut(container) { container.paddingBottom }
            }
            if (synchronized(contentLines) { contentLines.containsKey(container) }) return@forEach

            // Keep the pre-clearance line stable; remeasuring after padding is
            // applied would interpret the moved controls as the original bounds.
            val line = measureContentLine(container, root)
            if (line <= 0) return@forEach
            val resolvedLine = resolveContentLine(
                container = container,
                root = root,
                originalPadding = originalPadding,
                measuredLine = line,
            )
            synchronized(contentLines) { contentLines[container] = resolvedLine }
            ModuleLog.info {
                "feed layout target: container=${describe(container)} " +
                    "contentLine=${resolvedLine}px measuredLine=${line}px " +
                    "originalPaddingBottom=${originalPadding}px"
            }
        }
        synchronized(containers) { containers[root] = retained }
        if (retained.none { resourceName(it) == FeedOverlayContainerId }) {
            logNoTarget(root, retained.size)
        }
    }

    private fun logNoTarget(root: View, retainedCount: Int) {
        val now = System.currentTimeMillis()
        val shouldLog = synchronized(lastNoTargetLogTimes) {
            val last = lastNoTargetLogTimes[root] ?: 0L
            if (now - last >= NegativeLogIntervalMs) {
                lastNoTargetLogTimes[root] = now
                true
            } else {
                false
            }
        }
        if (!shouldLog) return
        ModuleLog.info {
            "feed layout target not found retained=$retainedCount " +
                "fallbackClearanceTop=${glassContentTopPx(root).roundToInt()}px"
        }
    }

    private fun logNoInset(container: View, contentLine: Int, clearanceTop: Float) {
        val now = System.currentTimeMillis()
        val shouldLog = synchronized(lastNoInsetLogTimes) {
            val last = lastNoInsetLogTimes[container] ?: 0L
            if (now - last >= NegativeLogIntervalMs) {
                lastNoInsetLogTimes[container] = now
                true
            } else {
                false
            }
        }
        if (!shouldLog) return
        ModuleLog.info {
            "feed layout clearance not required container=${describe(container)} " +
                "contentLine=${contentLine}px clearanceTop=${clearanceTop.roundToInt()}px"
        }
    }

    private fun resolveContentLine(
        container: View,
        root: View,
        originalPadding: Int,
        measuredLine: Int,
    ): Int {
        if (resourceName(container) != FeedOverlayContainerId || originalPadding <= 0) {
            return measuredLine
        }

        // Normalize ja's original bottom padding to the window. The container
        // itself can sit slightly lower during Douyin page transitions, while
        // this boundary remains stable across feed items.
        val parentBoundary = root.height - originalPadding
        return if (parentBoundary > 0 && parentBoundary < root.height) {
            parentBoundary
        } else {
            measuredLine
        }
    }

    private fun findContainers(root: View): MutableSet<View> {
        val explicit = mutableListOf<View>()
        val progressLayers = mutableListOf<View>()
        val progressViews = mutableListOf<View>()
        collectViews(root) { view ->
            when (resourceName(view)) {
                FeedOverlayContainerId -> explicit.add(view)
                in progressNames -> progressViews.add(view)
            }
            if (view is SeekBar) progressViews.add(view)
        }

        val result = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        selectFeedContainer(explicit, root)?.let(result::add)

        progressViews.forEach { progress ->
            findProgressLayer(progress)?.let { layer ->
                if (layer !== root && measureContentLine(layer, root) > 0) {
                    progressLayers.add(layer)
                }
            }
        }
        progressLayers.forEach(result::add)
        return result
    }

    private fun selectFeedContainer(candidates: List<View>, root: View): View? {
        val clearanceTop = glassContentTopPx(root) -
            ClearanceDp * root.resources.displayMetrics.density
        return candidates
            .asSequence()
            .filter { isContainerCandidate(it, root) }
            .map { candidate ->
                FeedContainerCandidate(
                    view = candidate,
                    contentLine = measureContentLine(candidate, root),
                )
            }
            .filter { candidate ->
                candidate.contentLine > clearanceTop &&
                    candidate.contentLine < root.height
            }
            .minByOrNull { it.contentLine }
            ?.view
    }

    fun shouldLogProgressTouch(view: View): Boolean {
        val name = resourceName(view)
        return name == "v5j" || name == "progress_bar" || view is SeekBar
    }

    fun logProgressDispatch(view: View, event: MotionEvent, handled: Boolean) {
        if (!ModuleLog.isEnabled) return
        val now = System.currentTimeMillis()
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val last = synchronized(lastProgressMoveLogTimes) {
                lastProgressMoveLogTimes[view] ?: 0L
            }
            if (now - last < 500L) return
        }
        synchronized(lastProgressMoveLogTimes) { lastProgressMoveLogTimes[view] = now }

        val position = screenLocation(view)
        val layer = findProgressLayer(view)
        ModuleLog.info {
            "progress dispatch: action=${touchAction(event.actionMasked)} " +
                "handled=$handled " +
                "local=${event.x.roundToInt()},${event.y.roundToInt()} " +
                "raw=${event.rawX.roundToInt()},${event.rawY.roundToInt()} " +
                "view=${describe(view)} bounds=[${position[0]},${position[1]}]" +
                "[${position[0] + view.width},${position[1] + view.height}] " +
                "enabled=${view.isEnabled} clickable=${view.isClickable} " +
                "layer=${layer?.let(::describe) ?: "none"} " +
                "layerPadding=${layer?.paddingBottom ?: -1}"
        }
    }

    private fun touchAction(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> action.toString()
    }

    private fun findProgressLayer(progress: View): View? {
        var current = progress.parent as? View?
        while (current != null) {
            if (resourceName(current) == ProgressOverlayContainerId) return current
            current = current.parent as? View?
        }
        return findFullSizeParent(progress)
    }

    private fun findFullSizeParent(view: View): View? {
        val root = view.rootView ?: return null
        var current = view.parent as? View?
        while (current != null) {
            if (current.width >= root.width * 0.8f &&
                current.height >= root.height * 0.5f
            ) {
                return current
            }
            current = current.parent as? View?
        }
        return null
    }

    private fun viewDepth(view: View): Int {
        var depth = 0
        var current = view.parent as? View?
        while (current != null) {
            depth++
            current = current.parent as? View?
        }
        return depth
    }

    private fun isContainerCandidate(view: View, root: View): Boolean {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return false
        if (view === root || view.parent == null) return false
        return view.width >= root.width * 0.8f && view.height >= root.height * 0.5f
    }

    private fun measureContentLine(container: View, root: View): Int {
        val rootPosition = screenLocation(root)
        var line = 0
        collectViews(container) { view ->
            if (view === container || view.width <= 0 || view.height <= 0 || !view.isShown) {
                return@collectViews
            }
            val bounds = layoutBoundsInRoot(view, rootPosition)
            val name = resourceName(view)
            val explicitProgress = name in progressNames || view is SeekBar
            val compactControl = bounds.height in 1..MaxGuideHeightPx
            val narrowControl = bounds.width < root.width * 0.95f
            if (!compactControl || (!explicitProgress && !narrowControl)) return@collectViews
            if (bounds.left < 0 || bounds.right > root.width) return@collectViews
            if (bounds.top < 0 || bounds.bottom > root.height) return@collectViews
            if (bounds.bottom > line) line = bounds.bottom
        }
        return line
    }

    private fun restoreContainer(container: View) {
        val original = synchronized(originalPaddings) { originalPaddings.remove(container) }
        synchronized(contentLines) { contentLines.remove(container) }
        if (original != null && container.paddingBottom != original) {
            container.setPadding(
                container.paddingLeft,
                container.paddingTop,
                container.paddingRight,
                original,
            )
            ModuleLog.info {
                "feed layout restored: container=${describe(container)} " +
                    "paddingBottom=$original"
            }
        }
    }

    private fun glassContentTopPx(root: View): Float {
        val measuredTop = synchronized(glassContentTops) { glassContentTops[root] }
        if (measuredTop != null && measuredTop > 0f) return measuredTop
        val inset = FallbackGlassContentBottomInsetDp * root.resources.displayMetrics.density
        return root.height - inset
    }

    private fun layoutBoundsInRoot(view: View, rootPosition: IntArray): Bounds {
        val position = screenLocation(view)
        val left = position[0] - rootPosition[0]
        val top = (position[1] - rootPosition[1] - view.translationY).roundToInt()
        return Bounds(left, top, left + view.width, top + view.height)
    }

    private fun screenLocation(view: View): IntArray {
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        return position
    }

    private fun collectViews(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                view.getChildAt(index)?.let { collectViews(it, action) }
            }
        }
    }

    private fun resourceName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return ""
        return runCatching {
            view.resources.getResourceEntryName(id)
        }.getOrDefault("")
    }

    private fun describe(view: View): String =
        "id=${resourceName(view)} class=${view.javaClass.name} size=${view.width}x${view.height}"
}
