package com.autumn.douyin.liquidglass.nativebar

import android.view.View
import android.view.ViewGroup

/**
 * 评论区半屏面板定位器：语义优先、几何兜底。
 * 不使用抖音混淆类名，避免版本升级后失效。
 */
object NativeCommentPanelLocator {
    private const val MaxSemanticDepth = 20
    private const val MaxGeometryDepth = 12
    private const val MinAreaRatio = 0.05f
    private const val MinWidthRatio = 0.90f
    private const val MinHeightRatio = 0.38f
    private const val MaxHeightRatio = 0.85f
    private const val BottomToleranceRatio = 0.04f
    private const val BackgroundBonus = 1_000_000_000_000L

    private val PositiveHints = listOf(
        "comment_container",
        "comment_root",
        "comment_panel",
        "comment_list",
        "comment_layout",
        "comment_sheet",
        "comment_dialog",
        "comment_page",
    )

    private val NegativeHints = listOf(
        "avatar",
        "icon",
        "emoji",
        "like",
        "share",
        "digg",
        "sticker",
    )

    fun find(root: ViewGroup): ViewGroup? {
        if (root.width <= 0 || root.height <= 0) return null
        return findBySemantics(root) ?: findByGeometry(root)
    }

    private fun findBySemantics(root: ViewGroup): ViewGroup? {
        val rootArea = root.width.toLong() * root.height.toLong()
        val threshold = (rootArea * MinAreaRatio).toLong()
        var best: ViewGroup? = null
        var bestArea = 0L
        walk(root, 0, MaxSemanticDepth) { view ->
            if (view !is ViewGroup || !view.isShown) return@walk
            val key = resourceKey(view)
            if (key.isEmpty()) return@walk
            if (NegativeHints.any { key.contains(it) }) return@walk
            if (PositiveHints.none { key.contains(it) }) return@walk
            val area = view.width.toLong() * view.height.toLong()
            if (area >= threshold && area > bestArea) {
                bestArea = area
                best = view
            }
        }
        return best
    }

    private fun findByGeometry(root: ViewGroup): ViewGroup? {
        val rootWidth = root.width.toFloat()
        val rootHeight = root.height.toFloat()
        val rootLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        val rootBottom = rootLocation[1] + root.height
        val minWidth = rootWidth * MinWidthRatio
        val minHeight = rootHeight * MinHeightRatio
        val maxHeight = rootHeight * MaxHeightRatio
        val bottomTolerance = rootHeight * BottomToleranceRatio
        val location = IntArray(2)
        var best: ViewGroup? = null
        var bestScore = -1L
        walk(root, 0, MaxGeometryDepth) { view ->
            if (view !is ViewGroup || !view.isShown) return@walk
            val width = view.width.toFloat()
            val height = view.height.toFloat()
            if (width < minWidth || height < minHeight || height > maxHeight) return@walk
            view.getLocationInWindow(location)
            val bottom = location[1] + view.height
            if (bottom < rootBottom - bottomTolerance) return@walk
            val area = view.width.toLong() * view.height.toLong()
            val score = area + if (view.background != null) BackgroundBonus else 0L
            if (score > bestScore) {
                bestScore = score
                best = view
            }
        }
        return best
    }

    private fun walk(view: View, depth: Int, maxDepth: Int, action: (View) -> Unit) {
        if (depth > maxDepth) return
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                walk(view.getChildAt(index), depth + 1, maxDepth, action)
            }
        }
    }

    private fun resourceKey(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return ""
        return runCatching { view.resources.getResourceEntryName(id) }
            .getOrDefault("")
            .lowercase()
    }
}
