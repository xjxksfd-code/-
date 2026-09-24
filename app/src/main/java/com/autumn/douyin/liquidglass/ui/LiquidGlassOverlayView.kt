package com.autumn.douyin.liquidglass.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.autumn.douyin.liquidglass.component.FloatingBottomBar
import com.autumn.douyin.liquidglass.component.FloatingBottomBarItem
import com.autumn.douyin.liquidglass.component.FloatingGlassStyle
import com.autumn.douyin.liquidglass.component.drawFloatingGlassBackdrop
import com.autumn.douyin.liquidglass.component.rememberFloatingGlassStyle
import com.autumn.douyin.liquidglass.ModuleLog
import com.autumn.douyin.liquidglass.root.CompositeFrameProvider
import com.autumn.douyin.liquidglass.settings.ModuleSettings
import com.autumn.douyin.liquidglass.theme.DemoMiuixTheme
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.LocalContentColor
import kotlin.math.roundToInt

internal const val LIQUID_OVERLAY_HEIGHT_DP = 96f
internal const val LIQUID_OVERLAY_MAX_CONTENT_WIDTH_DP = 384f
internal const val LIQUID_OVERLAY_MIN_CONTENT_WIDTH_DP = 300f
internal const val LIQUID_OVERLAY_HORIZONTAL_ALLOWANCE_DP = 32f
internal const val LIQUID_OVERLAY_EDGE_CONTENT_INSET_DP = 16f
private const val Android13CaptureResumeDelayMillis = 1500L

class LiquidGlassOverlayView(
    context: Context,
    private val controller: OverlayController,
    private val backdrop: DynamicBitmapBackdrop,
    private val compositeFrameProvider: CompositeFrameProvider,
    private val mainWindowView: View,
    initialSettings: ModuleSettings,
    private val expandContentToWindow: Boolean,
) : FrameLayout(context), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleOwner = OverlayLifecycleOwner()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val overlayHeightPx =
        (LIQUID_OVERLAY_HEIGHT_DP * resources.displayMetrics.density).roundToInt()
    private val edgeContentInsetPx =
        LIQUID_OVERLAY_EDGE_CONTENT_INSET_DP * resources.displayMetrics.density
    override val viewModelStore: ViewModelStore = ViewModelStore()
    private var lifecycleStarted = false
    private var desiredNativeBarPresent = true
    private var nativeBarStablePresent = true
    private var avoidanceGeometryLocked = false
    private var presenceAnimator: ValueAnimator? = null
    private var lastCaptureRegion: androidx.compose.ui.geometry.Rect? = null
    private var lastCapturePaddingPx = 0f
    private var dynamicBackdropEnabled: Boolean
    private var controlAvoidanceEnabled: Boolean
    private var currentTouchInsideContent = false
    private val delayedBackdropStart = Runnable {
        if (dynamicBackdropEnabled && desiredNativeBarPresent && visibility != View.GONE) {
            compositeFrameProvider.start()
        }
    }

    private val showPresenceInterpolator = PathInterpolator(
        0.22f,
        1.12f,
        0.36f,
        1f,
    )
    private val hidePresenceInterpolator = PathInterpolator(
        0.4f,
        0f,
        0.8f,
        0.36f,
    )

    init {
        dynamicBackdropEnabled = initialSettings.dynamicBackdropEnabled
        controlAvoidanceEnabled = initialSettings.controlAvoidanceEnabled
    }

    override val lifecycle: Lifecycle
        get() = lifecycleOwner.lifecycle

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, overlayHeightPx)
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        savedStateController.performAttach()

        val composeView = ComposeView(context)
        composeView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        composeView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setContent {
            LiquidGlassOverlayContent(
                controller = controller,
                overlayView = this@LiquidGlassOverlayView,
                backdrop = backdrop,
                expandContentToWindow = expandContentToWindow,
            )
        }
        addView(composeView)

        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                ScreenCaptureExclusion.start(view)
                startBackdropIfEligible()
                if (controlAvoidanceEnabled) BottomAdjacentControlAvoidance.start(mainWindowView)
            }

            override fun onViewDetachedFromWindow(view: View) {
                ScreenCaptureExclusion.stop(view)
                stopBackdrop()
                val activity = context as? Activity ?: return
                if (activity.isFinishing || activity.isDestroyed) {
                    destroyLifecycle()
                }
            }
        })
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        ScreenCaptureExclusion.refresh(this)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val contentRegion = lastCaptureRegion
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val horizontalTouchInset = if (expandContentToWindow) edgeContentInsetPx else 0f
                currentTouchInsideContent = contentRegion == null ||
                    event.x >= contentRegion.left - horizontalTouchInset &&
                    event.x <= contentRegion.right + horizontalTouchInset &&
                    event.y >= contentRegion.top && event.y <= contentRegion.bottom
                if (!currentTouchInsideContent) {
                    ModuleLog.info {
                        "glass touch passthrough: local=${event.x.roundToInt()},${event.y.roundToInt()} " +
                            "raw=${event.rawX.roundToInt()},${event.rawY.roundToInt()} " +
                            "contentRegion=$contentRegion"
                    }
                    return false
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!currentTouchInsideContent) return false
                currentTouchInsideContent = false
            }

            else -> {
                if (!currentTouchInsideContent) return false
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ModuleLog.info {
                "glass touch dispatch: local=${event.x.roundToInt()},${event.y.roundToInt()} " +
                    "raw=${event.rawX.roundToInt()},${event.rawY.roundToInt()} " +
                    "contentRegion=$lastCaptureRegion"
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        BottomAdjacentControlAvoidance.stop(mainWindowView)
        val animator = presenceAnimator
        presenceAnimator = null
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    fun updateFeatureSettings(settings: ModuleSettings) {
        val shouldStreamFrames = settings.dynamicBackdropEnabled
        if (shouldStreamFrames != dynamicBackdropEnabled) {
            dynamicBackdropEnabled = shouldStreamFrames
            if (shouldStreamFrames) {
                startBackdropIfEligible()
            } else {
                stopBackdrop()
            }
        }

        val shouldAvoidControls = settings.controlAvoidanceEnabled
        if (shouldAvoidControls != controlAvoidanceEnabled) {
            controlAvoidanceEnabled = shouldAvoidControls
            if (shouldAvoidControls) {
                BottomAdjacentControlAvoidance.start(mainWindowView)
            } else {
                BottomAdjacentControlAvoidance.stop(mainWindowView)
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        ScreenCaptureExclusion.refresh(this)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            if (hasWindowFocus) {
                startBackdropIfEligible()
            } else {
                stopBackdrop()
            }
        }
    }

    fun setNativeBarPresent(present: Boolean, stable: Boolean) {
        if (present) {
            nativeBarStablePresent = true
        } else if (stable) {
            nativeBarStablePresent = false
        }

        if (present == desiredNativeBarPresent) {
            if (!present && stable && visibility == View.GONE) {
                releaseHiddenPresenceBackend()
            }
            return
        }
        desiredNativeBarPresent = present

        if (present) {
            visibility = View.VISIBLE
            startBackdropIfEligible()
            if (controlAvoidanceEnabled) BottomAdjacentControlAvoidance.start(mainWindowView)
            animatePresence(
                targetTranslationY = 0f,
                durationMs = ShowPresenceDurationMs,
                interpolator = showPresenceInterpolator,
            ) {
                unlockAvoidanceGeometry()
            }
            ModuleLog.info("liquid glass overlay presence animation=show")
        } else {
            cancelDelayedBackdropStart()
            if (!stable && !avoidanceGeometryLocked) {
                avoidanceGeometryLocked = true
                ModuleLog.info("feed avoidance geometry locked for transient presence")
            }
            visibility = View.VISIBLE
            animatePresence(
                targetTranslationY = height.toFloat(),
                durationMs = HidePresenceDurationMs,
                interpolator = hidePresenceInterpolator,
            ) {
                visibility = View.GONE
                translationY = height.toFloat()
                if (nativeBarStablePresent) {
                    ModuleLog.info("liquid glass overlay transiently hidden; avoidance held")
                } else {
                    releaseHiddenPresenceBackend()
                }
            }
            ModuleLog.info("liquid glass overlay presence animation=hide")
        }
    }

    private fun animatePresence(
        targetTranslationY: Float,
        durationMs: Long,
        interpolator: TimeInterpolator,
        onEnd: () -> Unit = {},
    ) {
        val previousAnimator = presenceAnimator
        presenceAnimator = null
        previousAnimator?.cancel()

        val startTranslationY = translationY
        if (startTranslationY == targetTranslationY) {
            onEnd()
            return
        }

        val animator = ValueAnimator.ofFloat(startTranslationY, targetTranslationY)
        animator.duration = durationMs
        animator.interpolator = interpolator
        animator.addUpdateListener { animation ->
            translationY = animation.animatedValue as Float
            refreshCaptureRegion()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (presenceAnimator !== animation) return
                presenceAnimator = null
                onEnd()
            }
        })
        presenceAnimator = animator
        animator.start()
    }

    private fun releaseHiddenPresenceBackend() {
        if (dynamicBackdropEnabled) stopBackdrop()
        if (controlAvoidanceEnabled) BottomAdjacentControlAvoidance.stop(mainWindowView)
        ModuleLog.info("liquid glass overlay hidden after animation")
    }

    private fun startBackdropIfEligible() {
        cancelDelayedBackdropStart()
        if (!dynamicBackdropEnabled || !desiredNativeBarPresent || visibility == View.GONE) return

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            ModuleLog.info(
                "composite provider android13 resume delay=${Android13CaptureResumeDelayMillis}ms",
            )
            postDelayed(delayedBackdropStart, Android13CaptureResumeDelayMillis)
        } else {
            compositeFrameProvider.start()
        }
    }

    private fun stopBackdrop() {
        cancelDelayedBackdropStart()
        compositeFrameProvider.stop()
    }

    private fun cancelDelayedBackdropStart() {
        removeCallbacks(delayedBackdropStart)
    }

    private fun unlockAvoidanceGeometry() {
        if (!avoidanceGeometryLocked) return
        avoidanceGeometryLocked = false
        ModuleLog.info("feed avoidance geometry unlocked")
        refreshCaptureRegion()
    }

    fun updateCaptureRegion(
        localRegion: androidx.compose.ui.geometry.Rect,
        capturePaddingPx: Float,
    ) {
        lastCaptureRegion = localRegion
        lastCapturePaddingPx = capturePaddingPx
        val overlayPosition = IntArray(2)
        getLocationOnScreen(overlayPosition)
        if (controlAvoidanceEnabled && !avoidanceGeometryLocked) {
            BottomAdjacentControlAvoidance.updateGlassContentTop(
                root = mainWindowView,
                topOnScreen = overlayPosition[1] + localRegion.top,
            )
        }
        backdrop.updateCaptureRegion(
            localRegion = localRegion,
            capturePaddingPx = capturePaddingPx,
            overlayView = this,
            mainWindowView = mainWindowView,
        )
        compositeFrameProvider.updateCaptureRegion(
            captureRect = backdrop.captureRect,
            mainWindowOrigin = backdrop.mainWindowOrigin,
        )
    }

    private fun refreshCaptureRegion() {
        val localRegion = lastCaptureRegion ?: return
        updateCaptureRegion(
            localRegion = localRegion,
            capturePaddingPx = lastCapturePaddingPx,
        )
    }

    fun startLifecycle() {
        if (lifecycleStarted) return
        lifecycleStarted = true
        savedStateController.performRestore(null)
        lifecycleOwner.dispatchEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.dispatchEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.dispatchEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun destroyLifecycle() {
        if (!lifecycleStarted) return
        lifecycleStarted = false
        lifecycleOwner.dispatchEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner.dispatchEvent(Lifecycle.Event.ON_STOP)
        lifecycleOwner.dispatchEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    private companion object {
        const val ShowPresenceDurationMs = 360L
        const val HidePresenceDurationMs = 280L
    }

    private class OverlayLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry

        fun dispatchEvent(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }
}

private data class DouyinTab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private const val MessageTabIndex = 2

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiquidGlassOverlayContent(
    controller: OverlayController,
    overlayView: LiquidGlassOverlayView,
    backdrop: DynamicBitmapBackdrop,
    expandContentToWindow: Boolean,
) {
    val density = LocalDensity.current
    val tabs = remember {
        listOf(
            DouyinTab("首页", Icons.Rounded.Cottage),
            DouyinTab("朋友", Icons.Rounded.People),
            DouyinTab("消息", Icons.Rounded.ChatBubble),
            DouyinTab("我", Icons.Rounded.Face),
        )
    }
    DemoMiuixTheme(darkTheme = true) {
        val glassStyle = rememberFloatingGlassStyle()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(LIQUID_OVERLAY_HEIGHT_DP.dp),
        ) {
            val plusButtonSize = 64.dp
            val groupGap = 12.dp
            val reservedHorizontalPadding =
                if (expandContentToWindow) {
                    (LIQUID_OVERLAY_EDGE_CONTENT_INSET_DP * 2).dp
                } else {
                    16.dp
                }
            val availableCapsuleWidth =
                maxWidth - plusButtonSize - groupGap - reservedHorizontalPadding
            val capsuleWidth = if (expandContentToWindow) {
                availableCapsuleWidth
            } else {
                minOf(308.dp, availableCapsuleWidth)
            }.coerceAtLeast(224.dp)

            Row(
                modifier = (if (expandContentToWindow) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                })
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 11.dp)
                    .padding(
                        horizontal = if (expandContentToWindow) {
                            LIQUID_OVERLAY_EDGE_CONTENT_INSET_DP.dp
                        } else {
                            0.dp
                        },
                    )
                    .onGloballyPositioned { coordinates ->
                        val capturePaddingPx = with(density) { 28.dp.toPx() }
                        overlayView.updateCaptureRegion(
                            localRegion = coordinates.boundsInRoot(),
                            capturePaddingPx = capturePaddingPx,
                        )
                    },
                horizontalArrangement = Arrangement.spacedBy(groupGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingBottomBar(
                    modifier = Modifier.width(capsuleWidth),
                    selectedIndex = { controller.selectedTab },
                    onSelected = controller::clickTab,
                    backdrop = backdrop,
                    glassStyle = glassStyle,
                    tabsCount = tabs.size,
                    isBlurEnabled = true,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        FloatingBottomBarItem(
                            onClick = { controller.clickTab(index) },
                        ) {
                            if (index == MessageTabIndex) {
                                Box(
                                    modifier = Modifier.size(28.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                    )
                                    if (controller.messageBadgeCount > 0) {
                                        MessageCountBadge(
                                            count = controller.messageBadgeCount,
                                            modifier = Modifier.align(Alignment.TopEnd),
                                        )
                                    }
                                }
                            } else {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                )
                            }
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }

                LiquidPlusButton(
                    backdrop = backdrop,
                    glassStyle = glassStyle,
                    onClick = controller::clickPlus,
                    onLongClick = controller::longClickPlus,
                )
            }
        }
    }
}

@Composable
private fun MessageCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val label = if (count > 99) "99+" else count.toString()
    val width = when {
        count > 99 -> 27.dp
        count > 9 -> 21.dp
        else -> 15.dp
    }
    Box(
        modifier = modifier
            .offset(x = 7.dp, y = 0.dp)
            .size(width = width, height = 15.dp)
            .background(ComposeColor(0xFFFF3B30), CircleShape)
            .border(
                width = 1.dp,
                color = ComposeColor.White.copy(alpha = 0.88f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = ComposeColor.White,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiquidPlusButton(
    backdrop: Backdrop,
    glassStyle: FloatingGlassStyle,
    onClick: () -> Unit,
    onLongClick: () -> Boolean,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        label = "plus-scale",
    )

    Box(
        modifier = Modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .dropShadow(
                shape = CircleShape,
                shadow = Shadow(
                    radius = 10.dp,
                    color = androidx.compose.ui.graphics.Color.Black,
                    alpha = glassStyle.shadowColor.alpha,
                ),
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = { onLongClick() },
            )
            .drawFloatingGlassBackdrop(
                backdrop = backdrop,
                shape = CircleShape,
                style = glassStyle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
        )
    }
}
