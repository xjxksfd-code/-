package com.autumn.douyin.liquidglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * 覆盖在评论区面板顶层的玻璃装饰层：圆角 + 顶部高光 + 渐隐描边。
 * 透明背景、不拦截触摸、不参与无障碍树。
 */
class CommentGlassFrameView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val cornerRadius = 18f * density
    private val borderWidth = 1f * density
    private val path = Path()
    private val bounds = RectF()
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        val highlightBottom = height * 0.55f
        highlightPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            highlightBottom,
            Color.argb(26, 255, 255, 255),
            Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        bounds.set(0f, 0f, width, height)
        path.reset()
        path.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(0f, 0f, width, highlightBottom, highlightPaint)
        canvas.restore()

        borderPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height,
            Color.argb(64, 255, 255, 255),
            Color.argb(20, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        val inset = borderWidth / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        path.reset()
        path.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.drawPath(path, borderPaint)
    }
}
