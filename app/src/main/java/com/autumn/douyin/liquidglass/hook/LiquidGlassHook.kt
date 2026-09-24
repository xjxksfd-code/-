package com.autumn.douyin.liquidglass.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.SurfaceControl
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.AbsSeekBar
import com.autumn.douyin.liquidglass.ModuleLog
import com.autumn.douyin.liquidglass.nativebar.NativeBottomBar
import com.autumn.douyin.liquidglass.nativebar.NativeBottomBarStateMonitor
import com.autumn.douyin.liquidglass.nativebar.NativeBottomBarLocator
import com.autumn.douyin.liquidglass.root.CompositeFrameProvider
import com.autumn.douyin.liquidglass.ui.CapturedLayerRegistry
import com.autumn.douyin.liquidglass.ui.DynamicBitmapBackdrop
import com.autumn.douyin.liquidglass.ui.LIQUID_OVERLAY_HORIZONTAL_ALLOWANCE_DP
import com.autumn.douyin.liquidglass.ui.LIQUID_OVERLAY_HEIGHT_DP
import com.autumn.douyin.liquidglass.ui.LIQUID_OVERLAY_MAX_CONTENT_WIDTH_DP
import com.autumn.douyin.liquidglass.ui.LIQUID_OVERLAY_MIN_CONTENT_WIDTH_DP
import com.autumn.douyin.liquidglass.ui.LiquidGlassOverlayView
import com.autumn.douyin.liquidglass.ui.ScreenCaptureExclusion
import com.autumn.douyin.liquidglass.ui.OverlayController
import com.autumn.douyin.liquidglass.ui.BottomAdjacentControlAvoidance
import com.autumn.douyin.liquidglass.settings.ModuleSettings
import com.autumn.douyin.liquidglass.settings.ModuleSettingsBridge
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

class LiquidGlassHook : IXposedHookLoadPackage {
    private val installedActivities =
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val installedOverlays =
        Collections.synchronizedMap(WeakHashMap<Activity, View>())
    private val installedStateMonitors =
        Collections.synchronizedMap(WeakHashMap<Activity, NativeBottomBarStateMonitor>())
    private val installedControllers =
        Collections.synchronizedMap(WeakHashMap<Activity, OverlayController>())
    private val installedSettingsCallbacks =
        Collections.synchronizedMap(WeakHashMap<Activity, (ModuleSettings) -> Unit>())
    @Volatile
    private var applicationContext: Context? = null
    @Volatile
    private var featureHooksInstalled = false
    private val surfaceControlArgumentHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            param.args.forEach { argument ->
                if (argument is SurfaceControl) {
                    CapturedLayerRegistry.register(argument)
                }
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != DOUYIN_PACKAGE || lpparam.processName != DOUYIN_PACKAGE) return

        installHook("Application.attach") {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as? Context ?: return
                        applicationContext = context
                        ModuleSettingsBridge.start(context, ::onSettingsChanged)
                        onSettingsChanged(ModuleSettingsBridge.current)
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install Application.attach", it) }

        installHook("Instrumentation.callActivityOnCreate") {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callActivityOnCreate",
                Activity::class.java,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.args[0] as? Activity ?: return
                        if (!isMainDouyinActivity(activity)) return
                        installOverlay(activity)
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install Instrumentation hook", it) }

        installHook("Activity.onResume") {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!isMainDouyinActivity(activity)) return
                        installOverlay(activity)
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install Activity.onResume hook", it) }

        installHook("Activity.onDestroy") {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onDestroy",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!isMainDouyinActivity(activity)) return
                        removeOverlay(activity)
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install Activity.onDestroy hook", it) }

        installHook("ActivityThread.performLaunchActivity") {
            val activityThreadClass = XposedHelpers.findClass(
                "android.app.ActivityThread",
                lpparam.classLoader,
            )
            val recordClass = XposedHelpers.findClass(
                "android.app.ActivityThread\$ActivityClientRecord",
                lpparam.classLoader,
            )
            XposedHelpers.findAndHookMethod(
                activityThreadClass,
                "performLaunchActivity",
                recordClass,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.result as? Activity ?: return
                        if (!isMainDouyinActivity(activity)) return
                        ModuleLog.info { "activity launch observed: ${activity.javaClass.name}" }
                        installOverlay(activity)
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install ActivityThread hook", it) }

        installHook("SplashActivity.onResume") {
            val splashClass = XposedHelpers.findClass(
                "com.ss.android.ugc.aweme.splash.SplashActivity",
                lpparam.classLoader,
            )
            XposedHelpers.findAndHookMethod(
                splashClass,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        ModuleLog.info("splash resume observed")
                        installOverlay(activity)
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install SplashActivity.onResume hook", it) }

        if (LegacySurfaceControlTrackingEnabled) installHook("SurfaceControl.Builder.build") {
            val builderClass = XposedHelpers.findClass(
                "android.view.SurfaceControl\$Builder",
                lpparam.classLoader,
            )
            XposedBridge.hookAllMethods(
                builderClass,
                "setParent",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val builder = param.thisObject ?: return
                        val parent = param.args.filterIsInstance<SurfaceControl>().firstOrNull()
                        CapturedLayerRegistry.recordBuilderParent(builder, parent)
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                builderClass,
                "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val control = param.result as? SurfaceControl ?: return
                        val parent = CapturedLayerRegistry.consumeBuilderParent(param.thisObject)
                        CapturedLayerRegistry.register(control, parent)
                        val description = control.toString()
                        if (description.contains("ttPlayer", ignoreCase = true)) {
                            ModuleLog.info { "captured player surfacecontrol: $description" }
                        }
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install SurfaceControl.Builder.build hook", it) }

        if (LegacySurfaceControlTrackingEnabled) installHook("SurfaceControl.Transaction.setBuffer") {
            val transactionClass = XposedHelpers.findClass(
                "android.view.SurfaceControl\$Transaction",
                lpparam.classLoader,
            )
            XposedBridge.hookAllMethods(
                transactionClass,
                "setBuffer",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val control = param.args.firstOrNull { it is SurfaceControl } as? SurfaceControl
                        CapturedLayerRegistry.noteBuffer(control)
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install SurfaceControl.Transaction.setBuffer hook", it) }

        if (LegacySurfaceControlTrackingEnabled) installHook("SurfaceControl.Transaction.reparent") {
            XposedHelpers.findAndHookMethod(
                SurfaceControl.Transaction::class.java,
                "reparent",
                SurfaceControl::class.java,
                SurfaceControl::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        CapturedLayerRegistry.recordReparent(
                            param.args.getOrNull(0) as? SurfaceControl,
                            param.args.getOrNull(1) as? SurfaceControl,
                        )
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install SurfaceControl.Transaction.reparent hook", it) }

        if (LegacySurfaceControlTrackingEnabled) installHook("SurfaceControl.Transaction.setVisibility") {
            XposedHelpers.findAndHookMethod(
                SurfaceControl.Transaction::class.java,
                "setVisibility",
                SurfaceControl::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val control = param.args.getOrNull(0) as? SurfaceControl
                        val visible = param.args.getOrNull(1) as? Boolean ?: true
                        CapturedLayerRegistry.recordVisibility(control, visible)
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install SurfaceControl.Transaction.setVisibility hook", it) }

        if (LegacySurfaceControlTrackingEnabled) installHook("SurfaceControl.Transaction.show/hide") {
            val transactionClass = SurfaceControl.Transaction::class.java
            XposedBridge.hookAllMethods(
                transactionClass,
                "show",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.args.filterIsInstance<SurfaceControl>().forEach {
                            CapturedLayerRegistry.recordVisibility(it, true)
                        }
                    }
                },
            )
            XposedBridge.hookAllMethods(
                transactionClass,
                "hide",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.args.filterIsInstance<SurfaceControl>().forEach {
                            CapturedLayerRegistry.recordVisibility(it, false)
                        }
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install SurfaceControl.Transaction.show/hide hook", it) }
    }

    private inline fun installHook(
        name: String,
        block: () -> Unit,
    ): Result<Unit> {
        ModuleLog.info { "installing hook $name" }
        return runCatching(block).onSuccess {
            ModuleLog.info { "installed hook $name" }
        }
    }

    private fun installProgressTouchDiagnostics() {
        installHook("AbsSeekBar.dispatchTouchEvent") {
            XposedBridge.hookAllMethods(
                AbsSeekBar::class.java,
                "dispatchTouchEvent",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? AbsSeekBar ?: return
                        val event = param.args.firstOrNull() as? MotionEvent ?: return
                        if (!BottomAdjacentControlAvoidance.shouldLogProgressTouch(view)) return
                        BottomAdjacentControlAvoidance.logProgressDispatch(
                            view = view,
                            event = event,
                            handled = param.result as? Boolean ?: false,
                        )
                    }
                },
            )
        }.onFailure { ModuleLog.error("failed to install progress touch diagnostics", it) }
    }

    private fun onSettingsChanged(settings: ModuleSettings) {
        ModuleLog.setDiagnosticsEnabled(settings.diagnosticLoggingEnabled)
        val context = applicationContext
        if (settings.diagnosticLoggingEnabled && context != null) {
            ModuleLog.install(context)
        }
        if (settings.glassBarEnabled) {
            installFeatureHooksOnce()
        }
    }

    private fun installFeatureHooksOnce() {
        if (featureHooksInstalled) return
        featureHooksInstalled = true
        val classLoader = applicationContext?.classLoader ?: return
        if (!LegacySurfaceControlTrackingEnabled) {
            ModuleLog.info("legacy surfacecontrol tracking disabled")
        }
        TransparentBarIntegration.install(classLoader)
        installProgressTouchDiagnostics()
    }

    private fun isMainDouyinActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return name == MAIN_ACTIVITY ||
            name == "com.ss.android.ugc.aweme.main.MainActivity" ||
            name.endsWith(".splash.SplashActivity")
    }

    private fun logRuntimeSummary(activity: Activity) {
        val settings = ModuleSettingsBridge.current
        val moduleInfo = runCatching {
            activity.packageManager.getPackageInfo(MODULE_PACKAGE, 0)
        }.getOrNull()
        val douyinInfo = runCatching {
            activity.packageManager.getPackageInfo(DOUYIN_PACKAGE, 0)
        }.getOrNull()
        val metrics = activity.resources.displayMetrics
        ModuleLog.info {
            "runtime summary module=${moduleInfo?.versionName}" +
                "/${moduleInfo?.longVersionCode} android=${Build.VERSION.SDK_INT} " +
                "device=${Build.BRAND}/${Build.MODEL} " +
                "display=${metrics.widthPixels}x${metrics.heightPixels} " +
                "density=${metrics.density} douyin=${douyinInfo?.versionName}" +
                "/${douyinInfo?.longVersionCode} settings=$settings"
        }
    }

    private fun installOverlay(activity: Activity) {
        if (!ModuleSettingsBridge.current.glassBarEnabled) return
        synchronized(installedActivities) {
            if (!installedActivities.add(activity)) return
        }

        ModuleLog.install(activity)
        logRuntimeSummary(activity)
        ModuleLog.info { "load package hook ready, activity=${activity.javaClass.name}" }

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: activity.window.decorView as? ViewGroup
            ?: return

        content.post {
            if (tryInstallOverlay(activity, content)) return@post
                content.postDelayed({
                    if (!tryInstallOverlay(activity, content)) {
                        markInstallFailed(activity)
                        ModuleLog.error("bottom bar was not found after retry")
                    }
            }, 600)
        }
    }

    private fun tryInstallOverlay(activity: Activity, content: ViewGroup): Boolean {
        return runCatching {
            val nativeBar = NativeBottomBarLocator.find(content)
                ?: return false

            ModuleLog.info { "native bottom bar found: ${nativeBar.describe()}" }
            val overlayGeometry = calculateOverlayWindowGeometry(activity, nativeBar)
            val controller = OverlayController(nativeBar)
            val dynamicBackdrop = DynamicBitmapBackdrop()
            val compositeFrameProvider = CompositeFrameProvider(
                context = activity.applicationContext,
                backdrop = dynamicBackdrop,
            )
            val overlay = LiquidGlassOverlayView(
                context = activity,
                controller = controller,
                backdrop = dynamicBackdrop,
                compositeFrameProvider = compositeFrameProvider,
                mainWindowView = activity.window.decorView,
                initialSettings = ModuleSettingsBridge.current,
                expandContentToWindow = overlayGeometry.edgeToEdge,
            )
            val stateMonitor = NativeBottomBarStateMonitor(nativeBar) { present, stable ->
                overlay.setNativeBarPresent(present, stable)
            }
            controller.start()
            val settingsCallback: (ModuleSettings) -> Unit = { settings ->
                overlay.updateFeatureSettings(settings)
            }
            ModuleSettingsBridge.addListener(settingsCallback)
            installedSettingsCallbacks.putIfAbsent(activity, settingsCallback)
            installedOverlays.putIfAbsent(activity, overlay)
            installedStateMonitors.putIfAbsent(activity, stateMonitor)
            installedControllers.putIfAbsent(activity, controller)
            nativeBar.suppressOriginalUi()
            overlay.startLifecycle()
            activity.windowManager.addView(
                overlay,
                createOverlayLayoutParams(activity, overlayGeometry),
            )
            ScreenCaptureExclusion.request(overlay)
            stateMonitor.start()
            ModuleLog.info("liquid glass overlay installed in app subwindow")
            true
        }.getOrElse { throwable ->
            ModuleLog.error("failed to install overlay", throwable)
            markInstallFailed(activity)
            false
        }
    }

    private fun markInstallFailed(activity: Activity) {
        synchronized(installedActivities) {
            installedActivities.remove(activity)
        }
    }

    private fun removeOverlay(activity: Activity) {
        installedSettingsCallbacks.remove(activity)?.let(ModuleSettingsBridge::removeListener)
        installedStateMonitors.remove(activity)?.stop()
        installedControllers.remove(activity)?.stop()
        val overlay = installedOverlays.remove(activity) ?: return
        runCatching {
            activity.windowManager.removeView(overlay)
        }.onFailure { throwable ->
            ModuleLog.error("failed to remove overlay while activity destroyed", throwable)
        }
    }

    private companion object {
        const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        const val MODULE_PACKAGE = "com.autumn.douyin.liquidglass"
        const val MAIN_ACTIVITY = "com.ss.android.ugc.aweme.splash.SplashActivity"
        const val LegacySurfaceControlTrackingEnabled = false
    }
}

private data class OverlayWindowGeometry(
    val width: Int,
    val x: Int,
    val edgeToEdge: Boolean,
)

private fun createOverlayLayoutParams(
    activity: Activity,
    geometry: OverlayWindowGeometry,
): WindowManager.LayoutParams {
    return WindowManager.LayoutParams(
        geometry.width,
        (LIQUID_OVERLAY_HEIGHT_DP * activity.resources.displayMetrics.density).roundToInt(),
        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        x = geometry.x
        setTitle("DouyinLiquidGlassOverlay")
        token = activity.window.decorView.windowToken
        windowAnimations = 0
    }
}

private fun calculateOverlayWindowGeometry(
    activity: Activity,
    nativeBar: NativeBottomBar,
): OverlayWindowGeometry {
    val density = activity.resources.displayMetrics.density
    val parentWidth = activity.window.decorView.width
        .takeIf { it > 0 }
        ?: activity.resources.displayMetrics.widthPixels
    val nativeBounds = nativeBar.boundsInWindow()
    if (parentWidth <= 0 || nativeBounds == null) {
        return OverlayWindowGeometry(
            width = calculateFallbackOverlayWindowWidth(parentWidth, density),
            x = 0,
            edgeToEdge = false,
        )
    }

    val sideAllowancePx =
        (LIQUID_OVERLAY_HORIZONTAL_ALLOWANCE_DP / 2f * density).roundToInt()
    val minWindowWidthPx = (
        LIQUID_OVERLAY_MIN_CONTENT_WIDTH_DP +
            LIQUID_OVERLAY_HORIZONTAL_ALLOWANCE_DP
        ) * density

    var left = (nativeBounds.left - sideAllowancePx).coerceIn(0, parentWidth)
    var right = (nativeBounds.right + sideAllowancePx).coerceIn(0, parentWidth)
    if (right - left < minWindowWidthPx) {
        val extra = (minWindowWidthPx.roundToInt() - (right - left)) / 2f
        left = (left - extra).roundToInt().coerceIn(0, parentWidth)
        right = (right + extra).roundToInt().coerceIn(left, parentWidth)
    }

    val width = right - left
    val centerOffset = ((left + right) / 2f - parentWidth / 2f).roundToInt()
    val edgeToEdge = width >= parentWidth - sideAllowancePx
    ModuleLog.info {
        "liquid overlay geometry native=$nativeBounds target=[left=$left,right=$right] " +
            "width=$width x=$centerOffset edgeToEdge=$edgeToEdge parentWidth=$parentWidth"
    }
    return OverlayWindowGeometry(width, centerOffset, edgeToEdge)
}

private fun calculateFallbackOverlayWindowWidth(screenWidthPx: Int, density: Float): Int {
    val screenWidthDp = screenWidthPx / density
    val maxWindowWidthDp = LIQUID_OVERLAY_MAX_CONTENT_WIDTH_DP +
        LIQUID_OVERLAY_HORIZONTAL_ALLOWANCE_DP
    val minWindowWidthDp = LIQUID_OVERLAY_MIN_CONTENT_WIDTH_DP +
        LIQUID_OVERLAY_HORIZONTAL_ALLOWANCE_DP
    val windowWidthDp = minOf(
        maxWindowWidthDp,
        maxOf(minWindowWidthDp, screenWidthDp - LIQUID_OVERLAY_HORIZONTAL_ALLOWANCE_DP),
    )
    return (windowWidthDp * density).roundToInt()
}
