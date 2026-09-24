package com.autumn.douyin.liquidglass.nativebar

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

data class NativeBottomBar(
    val home: View,
    val friends: View,
    val plus: View,
    val messages: View,
    val profile: View,
) {
    val tabs: List<View> = listOf(home, friends, messages, profile)
    val all: List<View> = listOf(home, friends, plus, messages, profile)

    /** 原生 Tab 当前选中的下标；没有任何 Tab 处于选中态（如过渡期）时为 null。 */
    val selectedIndexOrNull: Int?
        get() = tabs.indexOfFirst { it.isSelected }.takeIf { it >= 0 }

    val selectedIndex: Int
        get() = selectedIndexOrNull ?: 0

    fun clickTab(index: Int): Boolean = tabs.getOrNull(index)?.performClick() == true

    fun clickPlus(): Boolean = plus.performClick()

    fun longClickPlus(): Boolean = plus.performLongClick()

    fun suppressOriginalUi() {
        all.forEach { view ->
            view.visibility = View.INVISIBLE
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    fun describe(): String = all.joinToString(separator = ", ") { view ->
        val resourceName = view.id
            .takeIf { it != View.NO_ID }
            ?.let { id -> runCatching { view.resources.getResourceName(id) }.getOrNull() }
        "${view.javaClass.simpleName}(${resourceName ?: "no-id"}, ${view.width}x${view.height})"
    }

    fun boundsInWindow(): Rect? {
        if (all.any { !it.isAttachedToWindow || it.width <= 0 || it.height <= 0 }) return null

        val location = IntArray(2)
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        all.forEach { view ->
            view.getLocationInWindow(location)
            left = minOf(left, location[0])
            top = minOf(top, location[1])
            right = maxOf(right, location[0] + view.width)
            bottom = maxOf(bottom, location[1] + view.height)
        }
        if (left >= right || top >= bottom) return null
        return Rect(left, top, right, bottom)
    }
}

object NativeBottomBarLocator {
    fun find(root: ViewGroup): NativeBottomBar? {
        findByGeometry(root)?.let { return it }
        return findByLabels(root)
    }

    private fun findByGeometry(root: ViewGroup): NativeBottomBar? {
        if (root.width <= 0 || root.height <= 0) return null

        val location = IntArray(2)
        val candidates = mutableListOf<Candidate>()
        collectViews(root) { view ->
            if (!view.isShown || !view.isClickable || !view.isEnabled) return@collectViews
            val widthRatio = view.width.toFloat() / root.width.toFloat()
            if (widthRatio !in 0.12f..0.28f) return@collectViews
            if (view.height <= 0 || view.height > root.height / 3) return@collectViews

            view.getLocationInWindow(location)
            val bottom = location[1] + view.height
            if (bottom < root.height - (root.height * 0.04f)) return@collectViews
            candidates += Candidate(view, location[0], location[1])
        }

        if (candidates.size < 5) return null

        val bottomRow = candidates
            .groupBy { it.top }
            .maxByOrNull { group -> group.value.sumOf { candidate -> candidate.view.width } }
            ?.value
            ?.sortedBy { it.left }
            ?.take(5)
            ?: return null

        if (bottomRow.size < 5) return null
        return NativeBottomBar(
            home = bottomRow[0].view,
            friends = bottomRow[1].view,
            plus = bottomRow[2].view,
            messages = bottomRow[3].view,
            profile = bottomRow[4].view,
        )
    }

    private fun findByLabels(root: ViewGroup): NativeBottomBar? {
        val home = findClickableByLabel(root, "首页") ?: return null
        val friends = findClickableByLabel(root, "朋友") ?: return null
        val messages = findClickableByLabel(root, "消息") ?: return null
        val profile = findClickableByLabel(root, "我") ?: return null
        val plus = findClickableByDescription(root, "拍") ?: return null
        return NativeBottomBar(home, friends, plus, messages, profile)
    }

    private fun collectViews(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectViews(view.getChildAt(index), action)
            }
        }
    }

    private fun findClickableByLabel(root: ViewGroup, label: String): View? =
        findFirst(root) { view ->
            if (view !is TextView) return@findFirst false
            view.text?.toString() == label || view.contentDescription?.toString()?.startsWith(label) == true
        }?.closestClickable(root)

    private fun findClickableByDescription(root: ViewGroup, keyword: String): View? =
        findFirst(root) { view ->
            view.contentDescription?.toString()?.contains(keyword) == true
        }?.closestClickable(root)

    private fun findFirst(root: ViewGroup, predicate: (View) -> Boolean): View? {
        var result: View? = null
        collectViews(root) { view ->
            if (result == null && predicate(view)) result = view
        }
        return result
    }

    private fun View.closestClickable(root: ViewGroup): View? {
        var current: View = this
        while (current !== root) {
            if (current.isClickable) return current
            current = current.parent as? View ?: return null
        }
        return null
    }

    private data class Candidate(
        val view: View,
        val left: Int,
        val top: Int,
    )
}