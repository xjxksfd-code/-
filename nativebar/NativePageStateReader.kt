package com.autumn.douyin.liquidglass.nativebar

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.autumn.douyin.liquidglass.ModuleLog

/**
 * 读取抖音「当前实际所在页面」的二级事实来源。
 *
 * 背景：抖音原生底栏按钮的 isSelected 只在「点击底栏 Tab」时被可靠更新，
 * 而在系统返回手势 / 返回键 / Fragment 回退 / 页面内跳转等场景下，
 * isSelected 常常停留在旧值，导致液态玻璃底栏与真实页面不同步。
 *
 * 本读取器不依赖任何单一属性，而是综合多个运行时信号来推断当前页面下标：
 *  1) 主页面容器（ViewPager/FrameLayout）中，哪一个「顶部页签指示」处于选中态；
 *  2) 页面根容器上携带的 Fragment/页面类名、contentDescription、view tag 等标识；
 *  3) 可见的顶部主标题文本（首页 / 朋友 / 消息 / 我）。
 *
 * 所有策略都通过遍历不可见（INVISIBLE）的原生底栏之外的真实内容视图实现，
 * 因此不会受到「模块把底栏按钮设为 INVISIBLE」的影响。
 *
 * 返回值为推断出的 Tab 下标（顺序与 NativeBottomBar.tabs 一致：
 * 0=首页, 1=朋友, 2=消息, 3=我）；无法可靠推断时返回 null，调用方应保持现状。
 */
class NativePageStateReader(
    private val nativeBar: NativeBottomBar,
) {
    /** 抖音主页面（底层内容区）中，用于承载四个 Tab 页面的容器根视图。 */
    private fun contentRoot(): ViewGroup? {
        // 底栏所在行的父容器，其兄弟/父级通常就是内容区；从根视图反查更稳妥。
        val root = nativeBar.home.rootView as? ViewGroup ?: return null
        return root
    }

    /**
     * 采样一次当前页面状态。
     * @return 推断出的 Tab 下标，无法判断时为 null。
     */
    fun sample(): Int? {
        val root = contentRoot() ?: return null

        // 策略 1：直接寻找「可见的、带选中态」的顶部页签。
        inferFromVisibleTabIndicators(root)?.let { return it }

        // 策略 2：从页面容器的类名 / Fragment 标识推断。
        // （抖音不同版本实现差异较大，作为兜底，只在能明确匹配时才返回。）
        inferFromPageIdentity(root)?.let { return it }

        return null
    }

    /**
     * 策略 1：抖音主界面顶部（或页面容器）通常会有一个与底部 Tab 对应的
     * 「页签指示器」，其 isSelected 会随当前真实页面变化更新。
     *
     * 这里只采信「位于内容区、可见、且 isSelected==true」的候选，
     * 且要求它自身或最近的可点击祖先携带与 Tab 对应的文本标识。
     */
    private fun inferFromVisibleTabIndicators(root: ViewGroup): Int? {
        var matched: Int? = null
        collectViews(root) { view ->
            if (matched != null) return@collectViews
            if (!view.isShown || !view.isAttachedToWindow) return@collectViews
            if (!view.isSelected) return@collectViews
            // 跳过底栏自身（底栏按钮已被设为 INVISIBLE，正常不会被采信，
            // 但防御性再判一次，避免把底栏的状态当成页面状态回读）。
            if (isDescendantOfNativeBar(view)) return@collectViews

            val label = resolveTabLabel(view) ?: return@collectViews
            matched = labelToIndex(label)
        }
        return matched
    }

    /**
     * 从视图自身或其最近的、携带文本语义的祖先中，解析出 Tab 文本标识。
     */
    private fun resolveTabLabel(view: View): String? {
        var current: View? = view
        var depth = 0
        while (current != null && depth < MaxLabelSearchDepth) {
            (current as? TextView)?.text?.toString()?.trim()?.let { text ->
                if (text.isNotEmpty() && isKnownTabLabel(text)) return text
            }
            current.contentDescription?.toString()?.trim()?.let { description ->
                if (description.isNotEmpty()) {
                    val normalized = normalize(description)
                    if (isKnownTabLabel(normalized)) return normalized
                }
            }
            current = current.parent as? View
            depth++
        }
        return null
    }

    /**
     * 策略 2：从页面容器的标识（类名 / tag / contentDescription）推断。
     * 抖音主页面容器一般形如 XxxMainContainerView，内部 Fragment 类名也带有
     * MainFragment / HomeFragment / MessageFragment / ProfileFragment 等关键字。
     */
    private fun inferFromPageIdentity(root: ViewGroup): Int? {
        var matched: Int? = null
        collectViews(root) { view ->
            if (matched != null) return@collectViews
            if (!view.isShown || !view.isAttachedToWindow) return@collectViews
            if (view.width <= 0 || view.height <= 0) return@collectViews
            if (isDescendantOfNativeBar(view)) return@collectViews

            val identity = buildString {
                append(view.javaClass.name)
                view.tag?.let { append(' ').append(it.toString()) }
                view.contentDescription?.toString()?.let { append(' ').append(it) }
            }
            tabIndexFromIdentity(identity)?.let { matched = it }
        }
        return matched
    }

    private fun isDescendantOfNativeBar(view: View): Boolean =
        nativeBar.all.any { candidate -> view === candidate || isAncestor(candidate, view) }

    private fun isAncestor(ancestor: View, view: View): Boolean {
        var current: View? = view.parent as? View
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun labelToIndex(label: String): Int? = when (normalize(label)) {
        "首页", "主页", "推荐", "关注", "home" -> 0
        "朋友", "朋友页", "friends" -> 1
        "消息", "消息页", "message", "messages" -> 2
        "我", "我的", "个人主页", "profile", "me", "mine" -> 3
        else -> null
    }

    private fun tabIndexFromIdentity(identity: String): Int? {
        val normalized = identity.lowercase()
        // 注意匹配顺序：先更具体的「消息/朋友」，避免子串互相误伤。
        return when {
            normalized.contains("message") && normalized.contains("fragment") -> 2
            normalized.contains("friend") && normalized.contains("fragment") -> 1
            normalized.contains("profile") && normalized.contains("fragment") -> 3
            normalized.contains("mine") && normalized.contains("fragment") -> 3
            normalized.contains("home") && normalized.contains("fragment") -> 0
            normalized.contains("feed") && normalized.contains("fragment") -> 0
            else -> null
        }
    }

    private fun isKnownTabLabel(value: String): Boolean = labelToIndex(value) != null

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), "").lowercase()

    private fun collectViews(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                view.getChildAt(index)?.let { collectViews(it, action) }
            }
        }
    }

    private companion object {
        const val MaxLabelSearchDepth = 4
    }
}
