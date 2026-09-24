package com.autumn.douyin.liquidglass.hook

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.autumn.douyin.liquidglass.ModuleLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extends Douyin content under the bottom-bar area without changing native tab bounds.
 */
object TransparentBarIntegration {
    private const val BottomSpaceClass =
        "com.ss.android.ugc.aweme.feed.ui.bottom.BottomSpace"
    private const val BottomSpaceId = "bottom_space"
    private const val TabLayerClass = "X.0sR5"
    private const val BackgroundId = "b_t"
    private const val MessageContainerId = "ptn"
    private const val MessageFragmentSuffix = "MessagesFragment3"

    private val loggedVisibility = AtomicBoolean(false)
    private val loggedClear = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        ModuleLog.info("transparent bar integration loading")
        hookBottomSpace(classLoader)
        hookTabLayer(classLoader)
        hookMessageFragment(classLoader)
        hookBackgroundSetters()
        hookFallbackApply()
    }

    private fun hookBottomSpace(classLoader: ClassLoader) {
        runCatching {
            val bottomSpace = XposedHelpers.findClass(BottomSpaceClass, classLoader)
            XposedHelpers.findAndHookMethod(
                bottomSpace,
                "setVisibility",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = View.GONE
                        if (loggedVisibility.compareAndSet(false, true)) {
                            ModuleLog.info("transparent bar forced BottomSpace GONE")
                        }
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                bottomSpace,
                "LIZIZ",
                Int::class.javaPrimitiveType,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = View.GONE
                    }
                },
            )
            ModuleLog.info("transparent bar BottomSpace hooks installed")
        }.onFailure {
            ModuleLog.error("transparent bar BottomSpace hooks failed", it)
        }
    }

    private fun hookTabLayer(classLoader: ClassLoader) {
        runCatching {
            val tabLayer = XposedHelpers.findClass(TabLayerClass, classLoader)
            val clearAfterUpdate = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    clearTabLayer(view)
                }
            }
            XposedHelpers.findAndHookMethod(
                tabLayer,
                "LIZIZ",
                Int::class.javaObjectType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                clearAfterUpdate,
            )
            XposedHelpers.findAndHookMethod(
                tabLayer,
                "LJI",
                Boolean::class.javaPrimitiveType,
                clearAfterUpdate,
            )
            XposedHelpers.findAndHookMethod(
                tabLayer,
                "LJIIIZ",
                clearAfterUpdate,
            )
            ModuleLog.info("transparent bar tab-layer hooks installed")
        }.onFailure {
            ModuleLog.error("transparent bar tab-layer hooks failed", it)
        }
    }

    private fun hookMessageFragment(classLoader: ClassLoader) {
        runCatching {
            val fragmentClass = XposedHelpers.findClass(
                "androidx.fragment.app.Fragment",
                classLoader,
            )
            XposedHelpers.findAndHookMethod(
                fragmentClass,
                "onViewCreated",
                View::class.java,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.args[0] as? View ?: return
                        if (isMessagesFragment(param.thisObject)) scheduleApply(view)
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                fragmentClass,
                "onHiddenChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == true) return
                        val fragment = param.thisObject ?: return
                        if (!isMessagesFragment(fragment)) return
                        fragmentView(fragment)?.let(::scheduleApply)
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                fragmentClass,
                "performResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fragment = param.thisObject ?: return
                        if (!isMessagesFragment(fragment)) return
                        fragmentView(fragment)?.let(::scheduleApply)
                    }
                },
            )
            ModuleLog.info("transparent bar message-fragment hooks installed")
        }.onFailure {
            ModuleLog.error("transparent bar message-fragment hooks failed", it)
        }
    }

    private fun hookBackgroundSetters() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setBackgroundColor",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (!isTabLayer(view)) return
                        view.background = null
                        param.result = null
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setBackgroundResource",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (!isTabLayer(view)) return
                        view.background = null
                        param.result = null
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setBackgroundDrawable",
                Drawable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isBackgroundTarget(param.thisObject)) param.args[0] = null
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setBackground",
                Drawable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isBackgroundTarget(param.thisObject)) param.args[0] = null
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                ImageView::class.java,
                "setImageDrawable",
                Drawable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isBackgroundTarget(param.thisObject)) param.args[0] = null
                    }
                },
            )
            ModuleLog.info("transparent bar background-setter hooks installed")
        }.onFailure {
            ModuleLog.error("transparent bar background-setter hooks failed", it)
        }
    }

    private fun hookFallbackApply() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        scheduleApply(activity.window.decorView)
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] != true) return
                        val activity = param.thisObject as? Activity ?: return
                        scheduleApply(activity.window.decorView)
                    }
                },
            )
            ModuleLog.info("transparent bar fallback hooks installed")
        }.onFailure {
            ModuleLog.error("transparent bar fallback hooks failed", it)
        }
    }

    private fun scheduleApply(root: View) {
        root.postDelayed({ applyFallback(root) }, FirstApplyDelayMs)
        root.postDelayed({ applyFallback(root) }, SecondApplyDelayMs)
    }

    private fun applyFallback(view: View) {
        val resourceName = resourceName(view)
        when {
            resourceName == BottomSpaceId &&
                view.javaClass.name == BottomSpaceClass &&
                view.visibility != View.GONE -> {
                view.visibility = View.GONE
                ModuleLog.info("transparent bar fallback set BottomSpace GONE")
            }

            view.javaClass.name == TabLayerClass -> clearTabLayer(view)

            view is ImageView && resourceName == BackgroundId -> clearImageView(view)

            resourceName == MessageContainerId -> extendMessageContent(view)
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                view.getChildAt(index)?.let(::applyFallback)
            }
        }
    }

    private fun clearTabLayer(view: View) {
        if (view.background != null) {
            view.background = null
            if (loggedClear.compareAndSet(false, true)) {
                ModuleLog.info("transparent bar cleared tab-layer background")
            }
        }
        clearBackgroundChild(view)
    }

    private fun extendMessageContent(view: View) {
        var changed = view.paddingBottom != 0
        val oldBottomPadding = view.paddingBottom
        var oldContentHeight = 0
        var newContentHeight = 0

        if (oldBottomPadding != 0) {
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                0,
            )
        }

        val group = view as? ViewGroup
        if (group != null && group.childCount >= 2) {
            val statusBar = group.getChildAt(0)
            val content = group.getChildAt(1)
            if (resourceName(statusBar) == "status_bar") {
                val targetHeight = view.height - statusBar.height
                if (targetHeight > 0 && content.height != targetHeight) {
                    oldContentHeight = content.height
                    newContentHeight = targetHeight
                    content.layoutParams.height = targetHeight
                    content.layoutParams = content.layoutParams
                    changed = true
                }
            }
        }

        if (changed) {
            view.requestLayout()
            ModuleLog.info {
                "transparent bar extended message content: " +
                    "oldBottomPadding=$oldBottomPadding " +
                    "contentHeight=$oldContentHeight->$newContentHeight " +
                    "bounds=${view.width}x${view.height}"
            }
        }
    }

    private fun clearBackgroundChild(view: View) {
        if (view is ImageView && resourceName(view) == BackgroundId) {
            clearImageView(view)
            return
        }
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            group.getChildAt(index)?.let(::clearBackgroundChild)
        }
    }

    private fun clearImageView(view: ImageView) {
        if (view.background != null) view.background = null
        if (view.drawable != null) view.setImageDrawable(null)
    }

    private fun isTabLayer(target: Any?): Boolean =
        target is View && target.javaClass.name == TabLayerClass

    private fun isMessagesFragment(target: Any?): Boolean =
        target != null && target.javaClass.name.endsWith(MessageFragmentSuffix)

    private fun fragmentView(fragment: Any): View? =
        runCatching {
            fragment.javaClass.getMethod("getView").invoke(fragment) as? View
        }.getOrNull()

    private fun isBackgroundTarget(target: Any?): Boolean {
        val view = target as? View ?: return false
        return isTabLayer(view) ||
            view is ImageView && resourceName(view) == BackgroundId
    }

    private fun resourceName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return ""
        return runCatching {
            view.resources.getResourceEntryName(id)
        }.getOrDefault("")
    }

    private const val FirstApplyDelayMs = 500L
    private const val SecondApplyDelayMs = 2_000L
}
