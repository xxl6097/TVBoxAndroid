package com.hong.tvbox.ui

import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * TV 遥控器焦点导航助手。
 *
 * 问题：首页纵向滚动（ScrollView）+ 横向滚动条（HorizontalScrollView）结构下，
 * 按方向键时只有页面滚动，焦点框（黄色高亮）没有出现在卡片上。
 *
 * 根因：HorizontalScrollView 拿到焦点后只会横向滚动自身，不会把焦点传给子卡片。
 *
 * 修复方案：
 * 1. 给 HorizontalScrollView 设置自定义焦点导航处理
 * 2. 当用户按方向键时，优先把焦点传给首个/下一个可聚焦的卡片
 * 3. 卡片拿到焦点后自动显示黄色高亮 + 视觉反馈
 * 4. 只有在卡片沿着横向无法移动时才向上传导给纵向滚动条
 */
object FocusNavigationHelper {

    /**
     * 给 HorizontalScrollView 启用遥控器焦点导航。
     *
     * 原理：
     * - 首次聚焦时（或当前没有焦点子View时）向第一个子卡片传焦点
     * - 按左/右键时调用 FocusFinder 找下一个子View
     * - 按上/下键时跳出滚动条，让 ScrollView 处理纵向导航
     *
     * @param scrollView HorizontalScrollView 实例
     * @param row        滚动条内的行容器（通常是 LinearLayout）
     */
    fun enableFocusNavigation(scrollView: HorizontalScrollView, row: ViewGroup) {
        // 禁用 scrollView 自身的可聚焦性，转交给其内部卡片
        scrollView.isFocusable = false
        scrollView.isFocusableInTouchMode = false
        scrollView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        // 首次进入时，把焦点传给第一个卡片
        scrollView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && !hasChildWithFocus(row)) {
                val first = findFirstFocusableChild(row)
                first?.requestFocus()
            }
        }

        // 拦截 Key 事件，实现自定义焦点导航
        scrollView.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 左右方向键：在卡片间水平移动
                    val focused = scrollView.findFocus() ?: return@setOnKeyListener false
                    val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        View.FOCUS_LEFT
                    } else {
                        View.FOCUS_RIGHT
                    }

                    // FocusFinder 会自动寻找下一个可聚焦的 View
                    val next = FocusFinder.getInstance().findNextFocus(scrollView, focused, direction)
                    if (next != null && next != focused && isChildOf(next, row)) {
                        next.requestFocus()
                        // 自动滚动以保证卡片可见
                        scrollView.post {
                            scrollView.smoothScrollTo(
                                (next as? View)?.left ?: 0,
                                0
                            )
                        }
                        return@setOnKeyListener true
                    }
                    return@setOnKeyListener false
                }

                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // 上下方向键：跳出横向滚动，交给纵向滚动处理
                    // 先清除 HorizontalScrollView 的焦点，让 ScrollView 接管
                    val parent = scrollView.parent as? ViewGroup ?: return@setOnKeyListener false
                    val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        View.FOCUS_UP
                    } else {
                        View.FOCUS_DOWN
                    }
                    val next = FocusFinder.getInstance().findNextFocus(parent, scrollView, direction)
                    next?.requestFocus()
                    return@setOnKeyListener true
                }

                else -> return@setOnKeyListener false
            }
        }
    }

    /**
     * 检查 ViewGroup 中是否已有 View 获得焦点
     */
    private fun hasChildWithFocus(group: ViewGroup): Boolean {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.hasFocus()) return true
            if (child is ViewGroup && hasChildWithFocus(child)) return true
        }
        return false
    }

    /**
     * 查找第一个可聚焦的子 View（深度优先）
     */
    private fun findFirstFocusableChild(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child.isFocusable) return child
            if (child is ViewGroup) {
                findFirstFocusableChild(child)?.let { return it }
            }
        }
        return null
    }

    /**
     * 检查 target 是否是 parent 的后代
     */
    private fun isChildOf(target: View, parent: ViewGroup): Boolean {
        var current: View? = target
        while (current != null) {
            if (current == parent) return true
            current = current.parent as? View
        }
        return false
    }

    /**
     * 增强型 ScrollView：支持焦点跟踪
     *
     * 问题：纵向 ScrollView 在接收到上/下方向键时，可能只会滚动页面，
     * 不会把焦点传给合适的卡片。
     *
     * 修复：给 ScrollView 设置 onKey 监听，确保焦点正确跟踪到子 View。
     */
    fun enhanceScrollViewFocusTracking(scrollView: ScrollView) {
        scrollView.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val focused = scrollView.findFocus()
                    if (focused == null || focused == scrollView) {
                        // ScrollView 本身有焦点，需要传递给第一个子卡片
                        val first = findFirstFocusableChild(scrollView)
                        first?.requestFocus()
                        return@setOnKeyListener true
                    }
                    // 已有焦点的卡片，让 FocusFinder 处理
                    return@setOnKeyListener false
                }

                else -> return@setOnKeyListener false
            }
        }
    }
}
