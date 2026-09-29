package com.autumn.douyin.liquidglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View

/**
 * 评论输入栏的液态玻璃背板。
 *
 * 借鉴点（只取思路，代码完全独立重写，不复制任何第三方源码）：
 * - 用圆角矩形 SDF 在边缘一圈同时做“折射带 + 顶部高光 + 底部内阴影”，制造镜片厚度感；
 * - 底色由本 View 自己绘制，不做任何下层像素采样。
 *
 * 注入场景下拿不到宿主合成帧，因此这里刻意不依赖 RenderEffect / Haze / skipScreenshot：
 * AGSL RuntimeShader 只作为“程序化绘制”的画笔使用，输入只有尺寸与几何参数。
 * 着色器不可用时回退到纯 Canvas 渐变，保证任何设备上都有可看的玻璃质感。
 */
class CommentInputGlassView(context: Context) : View(context) {

    /** 圆角半径（px）。<= 0 表示自动取高度的一半（胶囊形）。 */
    var cornerRadiusPx: Float = -1f

    private val density = resources.displayMetrics.density
    private val bounds = RectF()
    private val path = Path()
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val runtimeShader: RuntimeShader? = runCatching { RuntimeShader(SHADER) }.getOrNull()

    private val tintRed = 0.70f
    private val tintGreen = 0.77f
    private val tintBlue = 0.88f
    private val tintAlpha = 0.30f
    private val refractRatio = 0.85f
    private val topHighlight = 0.22f
    private val innerShadow = 0.30f

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
        val maxRadius = minOf(width, height) * 0.5f
        val radius = if (cornerRadiusPx > 0f) minOf(cornerRadiusPx, maxRadius) else maxRadius
        if (!drawWithShader(canvas, width, height, radius)) {
            drawFallback(canvas, width, height, radius)
        }
        drawEdgeBorder(canvas, width, height, radius)
    }

    private fun drawWithShader(canvas: Canvas, width: Float, height: Float, radius: Float): Boolean {
        val shader = runtimeShader ?: return false
        return runCatching {
            shader.setFloatUniform("resolution", floatArrayOf(width, height))
            shader.setFloatUniform("corner_radius", radius)
            shader.setFloatUniform("edge_thickness", maxOf(radius * 0.7f, 12f * density))
            shader.setFloatUniform("refract_ratio", refractRatio)
            shader.setFloatUniform("tint_rgb", floatArrayOf(tintRed, tintGreen, tintBlue))
            shader.setFloatUniform("tint_alpha", tintAlpha)
            shader.setFloatUniform("top_highlight", topHighlight)
            shader.setFloatUniform("inner_shadow", innerShadow)
            shaderPaint.shader = shader
            canvas.drawRect(0f, 0f, width, height, shaderPaint)
        }.isSuccess
    }

    private fun drawFallback(canvas: Canvas, width: Float, height: Float, radius: Float) {
        bounds.set(0f, 0f, width, height)
        path.reset()
        path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        fallbackPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height,
            Color.argb(104, 178, 196, 224),
            Color.argb(56, 118, 130, 156),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, fallbackPaint)
        val highlightBottom = height * 0.5f
        fallbackPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            highlightBottom,
            Color.argb(46, 255, 255, 255),
            Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(0f, 0f, width, highlightBottom, fallbackPaint)
        canvas.restore()
        fallbackPaint.shader = null
    }

    private fun drawEdgeBorder(canvas: Canvas, width: Float, height: Float, radius: Float) {
        val inset = borderPaint.strokeWidth / 2f
        val innerRadius = maxOf(radius - inset, 0f)
        bounds.set(inset, inset, width - inset, height - inset)
        path.reset()
        path.addRoundRect(bounds, innerRadius, innerRadius, Path.Direction.CW)
        borderPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height,
            Color.argb(92, 255, 255, 255),
            Color.argb(26, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, borderPaint)
    }

    private companion object {
        /** 圆角矩形 SDF + 边缘折射/高光/内阴影。只依赖几何参数，不采样背景。 */
        const val SHADER = """
uniform float2 resolution;
uniform float corner_radius;
uniform float edge_thickness;
uniform float refract_ratio;
uniform float3 tint_rgb;
uniform float tint_alpha;
uniform float top_highlight;
uniform float inner_shadow;

half4 main(float2 coord) {
    float w = max(resolution.x, 1.0);
    float h = max(resolution.y, 1.0);
    float2 halfSize = float2(w * 0.5, h * 0.5);
    float2 p = float2(coord.x - halfSize.x, coord.y - halfSize.y);
    float r = min(corner_radius, min(halfSize.x, halfSize.y));
    float2 q = float2(abs(p.x) - (halfSize.x - r), abs(p.y) - (halfSize.y - r));
    float2 outside = float2(max(q.x, 0.0), max(q.y, 0.0));
    float sd = length(outside) + min(max(q.x, q.y), 0.0) - r;
    float mask = 1.0 - step(0.5, sd);

    float et = max(edge_thickness, 1.0);
    float inner01 = clamp(-sd / et, 0.0, 1.0);
    float band = 1.0 - inner01;
    float bottomBias = clamp(p.y / halfSize.y * 0.5 + 0.5, 0.0, 1.0);
    float topBias = clamp(1.0 - coord.y / h, 0.0, 1.0);

    float lensRim = band * band * refract_ratio;
    float shadow = band * inner_shadow * (0.35 + 0.65 * bottomBias);
    float topGlow = pow(topBias, 5.0);

    float3 rgb = float3(
        clamp(tint_rgb.x + lensRim * 0.30 + topGlow * top_highlight - shadow * 0.20, 0.0, 1.0),
        clamp(tint_rgb.y + lensRim * 0.30 + topGlow * top_highlight - shadow * 0.20, 0.0, 1.0),
        clamp(tint_rgb.z + lensRim * 0.32 + topGlow * top_highlight - shadow * 0.16, 0.0, 1.0)
    );
    float a = clamp(tint_alpha + lensRim * 0.40 + topGlow * 0.12, 0.0, 0.92) * mask;
    return half4(half(rgb.x * a), half(rgb.y * a), half(rgb.z * a), half(a));
}
"""
    }
}
