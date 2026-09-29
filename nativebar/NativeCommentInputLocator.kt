package com.autumn.douyin.liquidglass.nativebar

import android.view.View
import android.view.ViewGroup

/**
 * 评论输入栏定位器：语义优先（EditText 类名）、几何兜底，不依赖抖音混淆类名。
 *
 * 产物是“承载输入框背景的 ViewGroup”，便于在它的内容区最底层插入玻璃绘制层。
 * 定位约束里特意加了两道保险：
 * 1) 只认贴近面板底部的候选，避免把列表里的评论卡片当成输入栏；
 * 2) 走全窗口兜底时强制要求候选子树里存在 EditText，避免命中底部导航栏。
 */
object NativeCommentInputLocator {
    private const val MaxDepth = 22
    private const val MaxParentWalk = 5
    private const val MinHostHeightDp = 20f
    private const val MaxHostHeightDp = 140f
    private const val PanelBottomBandRatio = 0.45f
    private const val RootBottomBandRatio = 0.70f
    private const val PanelMinWidthRatio = 0.40f
    private const val RootMinWidthRatio = 0.55f
    private const val PanelBottomToleranceDp = 36f

    /** 在评论面板内部查找输入栏宿主。 */
    fun find(panel: ViewGroup): ViewGroup? =
        locate(panel, PanelBottomBandRatio, PanelMinWidthRatio, requireTextAnchor = false)

    /** 面板内部找不到时的兜底：在整棵窗口树里找，但必须命中 EditText。 */
    fun findInRoot(root: ViewGroup): ViewGroup? =
        locate(root, RootBottomBandRatio, RootMinWidthRatio, requireTextAnchor = true)

    private fun locate(
        scope: ViewGroup,
        bandRatio: Float,
        minWidthRatio: Float,
        requireTextAnchor: Boolean,
    ): ViewGroup? {
        if (scope.width <= 0 || scope.height <= 0) return null
        val density = scope.resources.displayMetrics.density
        val minWidth = scope.width * minWidthRatio
        val bandTop = scopeTop(scope) + (scope.height * bandRatio).toInt()
        val scopeBottom = scopeTop(scope) + scope.height
        val bottomTolerance = (PanelBottomToleranceDp * density).toInt()

        val anchor = findTextAnchor(scope, bandTop, MinHostHeightDp * density)
            ?: if (requireTextAnchor) null else findCapsuleAnchor(
                scope = scope,
                bandTop = bandTop,
                scopeBottom = scopeBottom,
                bottomTolerance = bottomTolerance,
                minWidth = minWidth,
                minHeight = MinHostHeightDp * density,
                maxHeight = MaxHostHeightDp * density,
            )
            ?: return null

        val host = resolveHost(scope, anchor, minWidth, MinHostHeightDp * density, MaxHostHeightDp * density)
            ?: return null
        if (host === scope) return null
        if (host.width < minWidth) return null
        if (host.height < MinHostHeightDp * density || host.height > MaxHostHeightDp * density) return null
        if (!host.isShown) return null
        return host
    }

    /** 找面板底部区域里的 EditText，取最靠下的那个。 */
    private fun findTextAnchor(scope: ViewGroup, bandTop: Int, minHeight: Float): View? {
        var best: View? = null
        var bestBottom = Int.MIN_VALUE
        val location = IntArray(2)
        walk(scope, 0) { view ->
            if (view === scope || !view.isShown) return@walk
            val name = view.javaClass.name.lowercase()
            if (!name.contains("edittext") && !name.contains("edit_text")) return@walk
            if (view.height < minHeight) return@walk
            view.getLocationInWindow(location)
            if (location[1] < bandTop) return@walk
            val bottom = location[1] + view.height
            if (bottom > bestBottom) {
                bestBottom = bottom
                best = view
            }
        }
        return best
    }

    /** 兜底：面板底部、贴近面板下沿、带背景的宽扁视图。 */
    private fun findCapsuleAnchor(
        scope: ViewGroup,
        bandTop: Int,
        scopeBottom: Int,
        bottomTolerance: Int,
        minWidth: Float,
        minHeight: Float,
        maxHeight: Float,
    ): View? {
        var best: View? = null
        var bestScore = Long.MIN_VALUE
        val location = IntArray(2)
        walk(scope, 0) { view ->
            if (view === scope || !view.isShown) return@walk
            if (view.width < minWidth) return@walk
            if (view.height < minHeight || view.height > maxHeight) return@walk
            if (view.background == null) return@walk
            view.getLocationInWindow(location)
            if (location[1] < bandTop) return@walk
            val bottom = location[1] + view.height
            if (bottom < scopeBottom - bottomTolerance) return@walk
            val area = view.width.toLong() * view.height.toLong()
            val score = area * 1000L + bottom
            if (score > bestScore) {
                bestScore = score
                best = view
            }
        }
        return best
    }

    /**
     * 从锚点向上回溯，得到“承载背景的容器”。
     * 优先取链上最外层带背景的 ViewGroup；都没有背景时退化为最外层尺寸合法的 ViewGroup。
     */
    private fun resolveHost(
        scope: ViewGroup,
        anchor: View,
        minWidth: Float,
        minHeight: Float,
        maxHeight: Float,
    ): ViewGroup? {
        var current: View = anchor
        var outermost: ViewGroup? = anchor as? ViewGroup
        var withBackground: ViewGroup? =
            if (anchor is ViewGroup && anchor.background != null) anchor else null
        var steps = 0
        while (steps < MaxParentWalk) {
            val parent = current.parent
            if (parent !is ViewGroup || parent === scope) break
            if (!parent.isShown) break
            if (parent.width < minWidth) break
            if (parent.height < minHeight || parent.height > maxHeight) break
            outermost = parent
            if (parent.background != null) withBackground = parent
            current = parent
            steps += 1
        }
        return withBackground ?: outermost ?: (anchor.parent as? ViewGroup)
    }

    private fun scopeTop(scope: ViewGroup): Int {
        val location = IntArray(2)
        scope.getLocationInWindow(location)
        return location[1]
    }

    private fun walk(view: View, depth: Int, action: (View) -> Unit) {
        if (depth > MaxDepth) return
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                walk(view.getChildAt(index), depth + 1, action)
            }
        }
    }
}
