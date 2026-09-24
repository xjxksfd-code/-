package com.autumn.douyin.liquidglass.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import com.autumn.douyin.liquidglass.ModuleLog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import kotlin.math.max
import kotlin.math.roundToInt

class DynamicBitmapBackdrop : Backdrop {
    var frame by mutableStateOf<Bitmap?>(null, neverEqualPolicy())
        private set
    var captureRect by mutableStateOf(Rect())
        private set
    var overlayOrigin by mutableStateOf(IntOffset.Zero)
        private set
    var overlayScreenOrigin by mutableStateOf(IntOffset.Zero)
        private set
    var mainWindowOrigin by mutableStateOf(IntOffset.Zero)
        private set
    var surfaceFrame by mutableStateOf<Bitmap?>(null, neverEqualPolicy())
        private set
    var surfaceLayerBounds by mutableStateOf(Rect())
        private set
    var compositeFrame by mutableStateOf<Bitmap?>(null, neverEqualPolicy())
        private set
    var compositeCaptureRect by mutableStateOf(Rect())
        private set
    var compositeFrameTimestamp by mutableStateOf(0L)
        private set

    private var onCaptureRegionChanged: (() -> Unit)? = null
    private var hasLoggedBackdropGeometry = false
    private var hasLoggedSourceMapping = false
    private var hasLoggedSurfaceMapping = false
    private var hasLoggedCompositeMapping = false
    private var lastLoggedCompositeFrameTimestamp = 0L

    fun setOnCaptureRegionChangedListener(listener: () -> Unit) {
        onCaptureRegionChanged = listener
    }

    fun updateFrame(bitmap: Bitmap?) {
        frame = bitmap
    }

    fun updateSurfaceFrame(bitmap: Bitmap?, layerBounds: Rect) {
        surfaceFrame = bitmap
        surfaceLayerBounds = layerBounds
    }

    fun updateCompositeFrame(bitmap: Bitmap?, screenRect: Rect, frameTimestamp: Long) {
        compositeFrame = bitmap
        compositeCaptureRect = screenRect
        compositeFrameTimestamp = frameTimestamp
    }

    fun clearCompositeFrame() {
        compositeFrame = null
        compositeCaptureRect = Rect()
        compositeFrameTimestamp = 0L
    }

    fun updateCaptureRegion(
        localRegion: ComposeRect,
        capturePaddingPx: Float,
        overlayView: View,
        mainWindowView: View,
    ) {
        val overlayPosition = IntArray(2)
        val mainWindowPosition = IntArray(2)
        overlayView.getLocationOnScreen(overlayPosition)
        mainWindowView.getLocationOnScreen(mainWindowPosition)

        val originX = overlayPosition[0] - mainWindowPosition[0]
        val originY = overlayPosition[1] - mainWindowPosition[1]
        overlayScreenOrigin = IntOffset(overlayPosition[0], overlayPosition[1])
        mainWindowOrigin = IntOffset(mainWindowPosition[0], mainWindowPosition[1])
        val nextRect = Rect(
            (originX + localRegion.left - capturePaddingPx).roundToInt(),
            (originY + localRegion.top - capturePaddingPx).roundToInt(),
            (originX + localRegion.right + capturePaddingPx).roundToInt(),
            (originY + localRegion.bottom + capturePaddingPx).roundToInt(),
        )
        val nextOrigin = IntOffset(originX, originY)
        val mainWindowBounds = Rect(0, 0, mainWindowView.width, mainWindowView.height)
        if (!nextRect.intersect(mainWindowBounds)) {
            overlayOrigin = nextOrigin
            return
        }
        if (captureRect == nextRect && overlayOrigin == nextOrigin) return

        if (overlayView.translationY == 0f) {
            ModuleLog.info {
                "backdrop geometry: local=$localRegion, paddingPx=$capturePaddingPx, " +
                    "overlayOrigin=${overlayPosition[0]},${overlayPosition[1]}, " +
                    "mainOrigin=${mainWindowPosition[0]},${mainWindowPosition[1]}, capture=$nextRect"
            }
        }

        captureRect = nextRect
        overlayOrigin = nextOrigin
        hasLoggedBackdropGeometry = false
        hasLoggedSurfaceMapping = false
        onCaptureRegionChanged?.invoke()
    }

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        val compositeBitmap = compositeFrame
        val compositeRect = compositeCaptureRect
        if (
            compositeBitmap != null &&
            !compositeBitmap.isRecycled &&
            !compositeRect.isEmpty
        ) {
            val rootPosition = coordinates?.positionInRoot() ?: Offset.Zero
            val shouldLogMapping = !hasLoggedCompositeMapping
            hasLoggedCompositeMapping = true
            val frameTimestamp = compositeFrameTimestamp
            val shouldLogLatency = frameTimestamp > 0L && (
                lastLoggedCompositeFrameTimestamp == 0L ||
                    frameTimestamp - lastLoggedCompositeFrameTimestamp >= LatencyLogIntervalMs
                )
            drawCompositeFrame(
                bitmap = compositeBitmap,
                captureRect = compositeRect,
                overlayScreenOrigin = overlayScreenOrigin,
                rootPosition = rootPosition,
                density = density,
                downscaleFactor = downscaleFactor,
                shouldLogMapping = shouldLogMapping,
                frameTimestamp = compositeFrameTimestamp,
                shouldLogLatency = shouldLogLatency,
            )
            if (shouldLogLatency) {
                lastLoggedCompositeFrameTimestamp = frameTimestamp
            }
            return
        }

        val bitmap = frame
        if (bitmap == null || bitmap.isRecycled || captureRect.isEmpty) {
            drawRect(Color.White.copy(alpha = 0.04f))
            return
        }

        val rootPosition = coordinates?.positionInRoot() ?: Offset.Zero
        if (!hasLoggedBackdropGeometry) {
            hasLoggedBackdropGeometry = true
            val effectPadding = (density as? BackdropEffectScope)?.padding ?: 0f
            ModuleLog.info {
                "backdrop draw geometry: drawSize=${size.width}x${size.height}, " +
                    "layoutSize=${coordinates?.size}, density=${density.density}, " +
                    "effectPadding=$effectPadding, downscale=$downscaleFactor, capture=$captureRect"
            }
        }
        val globalX = overlayOrigin.x + rootPosition.x
        val globalY = overlayOrigin.y + rootPosition.y
        val effectPadding = (density as? BackdropEffectScope)?.padding ?: 0f
        val downscale = downscaleFactor.coerceAtLeast(1)
        val captureWidth = captureRect.width().toFloat()
        val captureHeight = captureRect.height().toFloat()
        val sourceX = globalX - effectPadding
        val sourceY = globalY - effectPadding
        val sourceWidth = size.width * downscale
        val sourceHeight = size.height * downscale
        val visibleLeft = max(sourceX, captureRect.left.toFloat())
        val visibleTop = max(sourceY, captureRect.top.toFloat())
        val visibleRight = max(
            visibleLeft,
            minOf(sourceX + sourceWidth, captureRect.right.toFloat()),
        )
        val visibleBottom = max(
            visibleTop,
            minOf(sourceY + sourceHeight, captureRect.bottom.toFloat()),
        )
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) {
            drawRect(Color.White.copy(alpha = 0.04f))
            return
        }

        val bitmapScaleX = bitmap.width / captureWidth
        val bitmapScaleY = bitmap.height / captureHeight
        val srcLeft = ((visibleLeft - captureRect.left) * bitmapScaleX).roundToInt()
        val srcTop = ((visibleTop - captureRect.top) * bitmapScaleY).roundToInt()
        val srcWidth = ((visibleRight - visibleLeft) * bitmapScaleX).roundToInt()
        val srcHeight = ((visibleBottom - visibleTop) * bitmapScaleY).roundToInt()
        val sourceOffset = IntOffset(
            srcLeft.coerceIn(0, max(0, bitmap.width - 1)),
            srcTop.coerceIn(0, max(0, bitmap.height - 1)),
        )
        val sourceSize = IntSize(
            srcWidth.coerceIn(1, (bitmap.width - sourceOffset.x).coerceAtLeast(1)),
            srcHeight.coerceIn(1, (bitmap.height - sourceOffset.y).coerceAtLeast(1)),
        )
        val destinationOffset = IntOffset(
            ((visibleLeft - sourceX) / downscale).roundToInt(),
            ((visibleTop - sourceY) / downscale).roundToInt(),
        )
        val destinationSize = IntSize(
            ((visibleRight - visibleLeft) / downscale).roundToInt().coerceAtLeast(1),
            ((visibleBottom - visibleTop) / downscale).roundToInt().coerceAtLeast(1),
        )

        if (!hasLoggedSourceMapping) {
            hasLoggedSourceMapping = true
            ModuleLog.info {
                "backdrop source mapping: root=$rootPosition, global=$globalX,$globalY, " +
                    "effectPadding=$effectPadding, downscale=$downscale, " +
                    "source=$sourceX,${sourceY.toInt()} ${sourceWidth.toInt()}x${sourceHeight.toInt()}, " +
                    "visible=$visibleLeft,$visibleTop ${visibleRight.toInt()}x${visibleBottom.toInt()}, " +
                    "bitmapSource=$sourceOffset $sourceSize, destination=$destinationOffset $destinationSize"
            }
        }

        val callerPadding = if (downscale > 1) {
            (effectPadding / downscale).toInt().toFloat()
        } else {
            effectPadding
        }
        translate(-callerPadding, -callerPadding) {
            drawImage(
                image = bitmap.asImageBitmap(),
                srcOffset = sourceOffset,
                srcSize = sourceSize,
                dstOffset = destinationOffset,
                dstSize = destinationSize,
            )

            val videoBitmap = surfaceFrame
            val videoBounds = surfaceLayerBounds
            if (
                videoBitmap != null &&
                !videoBitmap.isRecycled &&
                !videoBounds.isEmpty
            ) {
                val videoLeft = max(visibleLeft, videoBounds.left.toFloat())
                val videoTop = max(visibleTop, videoBounds.top.toFloat())
                val videoRight = max(
                    videoLeft,
                    minOf(visibleRight, videoBounds.right.toFloat()),
                )
                val videoBottom = max(
                    videoTop,
                    minOf(visibleBottom, videoBounds.bottom.toFloat()),
                )
                if (videoRight > videoLeft && videoBottom > videoTop) {
                    val videoScaleX = videoBitmap.width / videoBounds.width().toFloat()
                    val videoScaleY = videoBitmap.height / videoBounds.height().toFloat()
                    val videoSourceOffset = IntOffset(
                        ((videoLeft - videoBounds.left) * videoScaleX).roundToInt()
                            .coerceIn(0, max(0, videoBitmap.width - 1)),
                        ((videoTop - videoBounds.top) * videoScaleY).roundToInt()
                            .coerceIn(0, max(0, videoBitmap.height - 1)),
                    )
                    val videoSourceSize = IntSize(
                        ((videoRight - videoLeft) * videoScaleX).roundToInt()
                            .coerceIn(1, videoBitmap.width - videoSourceOffset.x),
                        ((videoBottom - videoTop) * videoScaleY).roundToInt()
                            .coerceIn(1, videoBitmap.height - videoSourceOffset.y),
                    )
                    val videoDestinationOffset = IntOffset(
                        ((videoLeft - sourceX) / downscale).roundToInt(),
                        ((videoTop - sourceY) / downscale).roundToInt(),
                    )
                    val videoDestinationSize = IntSize(
                        ((videoRight - videoLeft) / downscale).roundToInt().coerceAtLeast(1),
                        ((videoBottom - videoTop) / downscale).roundToInt().coerceAtLeast(1),
                    )
                    if (!hasLoggedSurfaceMapping) {
                        hasLoggedSurfaceMapping = true
                        ModuleLog.info {
                            "surface layer mapping: bounds=$videoBounds, visible=" +
                                "$videoLeft,$videoTop ${videoRight.toInt()}x${videoBottom.toInt()}, " +
                                "bitmap=${videoBitmap.width}x${videoBitmap.height}, " +
                                "source=$videoSourceOffset $videoSourceSize, " +
                                "destination=$videoDestinationOffset $videoDestinationSize"
                        }
                    }
                    drawImage(
                        image = videoBitmap.asImageBitmap(),
                        srcOffset = videoSourceOffset,
                        srcSize = videoSourceSize,
                        dstOffset = videoDestinationOffset,
                        dstSize = videoDestinationSize,
                    )
                }
            }
        }
    }
}

private const val LatencyLogIntervalMs = 5_000L

private fun DrawScope.drawCompositeFrame(
    bitmap: Bitmap,
    captureRect: Rect,
    overlayScreenOrigin: IntOffset,
    rootPosition: Offset,
    density: Density,
    downscaleFactor: Int,
    shouldLogMapping: Boolean,
    frameTimestamp: Long,
    shouldLogLatency: Boolean,
) {
    if (shouldLogLatency) {
        ModuleLog.info {
            "composite draw latency: captureToDrawMs=" +
                (SystemClock.uptimeMillis() - frameTimestamp)
        }
    }

    val globalX = overlayScreenOrigin.x + rootPosition.x
    val globalY = overlayScreenOrigin.y + rootPosition.y
    val effectPadding = (density as? BackdropEffectScope)?.padding ?: 0f
    val downscale = downscaleFactor.coerceAtLeast(1)
    val captureWidth = captureRect.width().toFloat()
    val captureHeight = captureRect.height().toFloat()
    val sourceX = globalX - effectPadding
    val sourceY = globalY - effectPadding
    val sourceWidth = size.width * downscale
    val sourceHeight = size.height * downscale
    val visibleLeft = max(sourceX, captureRect.left.toFloat())
    val visibleTop = max(sourceY, captureRect.top.toFloat())
    val visibleRight = max(
        visibleLeft,
        minOf(sourceX + sourceWidth, captureRect.right.toFloat()),
    )
    val visibleBottom = max(
        visibleTop,
        minOf(sourceY + sourceHeight, captureRect.bottom.toFloat()),
    )
    if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) {
        drawRect(Color.White.copy(alpha = 0.04f))
        return
    }

    val bitmapScaleX = bitmap.width / captureWidth
    val bitmapScaleY = bitmap.height / captureHeight
    val sourceOffset = IntOffset(
        ((visibleLeft - captureRect.left) * bitmapScaleX).roundToInt()
            .coerceIn(0, max(0, bitmap.width - 1)),
        ((visibleTop - captureRect.top) * bitmapScaleY).roundToInt()
            .coerceIn(0, max(0, bitmap.height - 1)),
    )
    val sourceSize = IntSize(
        ((visibleRight - visibleLeft) * bitmapScaleX).roundToInt()
            .coerceIn(1, (bitmap.width - sourceOffset.x).coerceAtLeast(1)),
        ((visibleBottom - visibleTop) * bitmapScaleY).roundToInt()
            .coerceIn(1, (bitmap.height - sourceOffset.y).coerceAtLeast(1)),
    )
    val destinationOffset = IntOffset(
        ((visibleLeft - sourceX) / downscale).roundToInt(),
        ((visibleTop - sourceY) / downscale).roundToInt(),
    )
    val destinationSize = IntSize(
        ((visibleRight - visibleLeft) / downscale).roundToInt().coerceAtLeast(1),
        ((visibleBottom - visibleTop) / downscale).roundToInt().coerceAtLeast(1),
    )
    val callerPadding = if (downscale > 1) {
        (effectPadding / downscale).toInt().toFloat()
    } else {
        effectPadding
    }

    if (shouldLogMapping) {
        ModuleLog.info {
            "composite source mapping: root=$rootPosition, global=$globalX,$globalY, " +
                "effectPadding=$effectPadding, downscale=$downscale, capture=$captureRect, " +
                "bitmap=${bitmap.width}x${bitmap.height}, source=$sourceOffset $sourceSize, " +
                "destination=$destinationOffset $destinationSize"
        }
    }

    translate(-callerPadding, -callerPadding) {
        drawImage(
            image = bitmap.asImageBitmap(),
            srcOffset = sourceOffset,
            srcSize = sourceSize,
            dstOffset = destinationOffset,
            dstSize = destinationSize,
        )
    }
}
