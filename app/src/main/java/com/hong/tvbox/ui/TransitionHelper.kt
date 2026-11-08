package com.hong.tvbox.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.view.animation.SlideInLeftAnimation
import android.view.animation.SlideOutRightAnimation
import android.view.animation.TranslateAnimation

/**
 * Activity 过渡动画管理器
 * 
 * 提供通用的页面跳转动画（Fade/Slide/Scale）
 * 支持共享元素过渡（Shared Element Transition）
 */
object TransitionHelper {
    
    // ====== 内置动画类 ======
    
    class SlideInLeftAnimation : Animation() {
        override fun onApplyTransformation(interpolatedTime: Float, t: android.view.animation.Transformation?) {
            super.onApplyTransformation(interpolatedTime, t)
        }
    }
    
    class SlideOutRightAnimation : Animation() {
        override fun onApplyTransformation(interpolatedTime: Float, t: android.view.animation.Transformation?) {
            super.onApplyTransformation(interpolatedTime, t)
        }
    }
    
    // ====== Activity 启动动画 ======
    
    /**
     * 淡入淡出过渡
     */
    fun startActivityWithFade(
        from: Activity,
        intent: Intent,
        enterDuration: Long = 300
    ) {
        from.startActivity(intent)
        from.overridePendingTransition(
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
    }
    
    /**
     * 从左滑入过渡
     */
    fun startActivityWithSlideLeft(
        from: Activity,
        intent: Intent
    ) {
        from.startActivity(intent)
        from.overridePendingTransition(
            android.R.anim.slide_in_left,
            android.R.anim.slide_out_right
        )
    }
    
    /**
     * 共享元素过渡（海报/标题跨 Activity 动画）
     */
    fun startActivityWithSharedElement(
        from: Activity,
        intent: Intent,
        sharedView: View,
        sharedName: String = "poster"
    ) {
        val options = ActivityOptions.makeSceneTransitionAnimation(
            from,
            sharedView,
            sharedName
        ).toBundle()
        from.startActivity(intent, options)
    }
    
    /**
     * 多个共享元素过渡
     */
    fun startActivityWithMultipleSharedElements(
        from: Activity,
        intent: Intent,
        vararg pairs: Pair<View, String>
    ) {
        val options = ActivityOptions.makeSceneTransitionAnimation(
            from,
            *pairs.toTypedArray()
        ).toBundle()
        from.startActivity(intent, options)
    }
    
    // ====== View 级动画 ======
    
    /**
     * 淡入动画
     */
    fun fadeIn(view: View, duration: Long = 300): Animation {
        val anim = AlphaAnimation(0f, 1f)
        anim.duration = duration
        anim.interpolator = AccelerateDecelerateInterpolator()
        view.startAnimation(anim)
        return anim
    }
    
    /**
     * 淡出动画
     */
    fun fadeOut(view: View, duration: Long = 300, onEnd: (() -> Unit)? = null): Animation {
        val anim = AlphaAnimation(1f, 0f)
        anim.duration = duration
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                onEnd?.invoke()
            }
            override fun onAnimationRepeat(animation: Animation?) {}
        })
        view.startAnimation(anim)
        return anim
    }
    
    /**
     * 放大动画（焦点获取反馈）
     */
    fun scaleUp(view: View, targetScale: Float = 1.1f, duration: Long = 200): Animation {
        val anim = ScaleAnimation(
            1f, targetScale,
            1f, targetScale,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        anim.duration = duration
        anim.interpolator = AccelerateDecelerateInterpolator()
        view.startAnimation(anim)
        return anim
    }
    
    /**
     * 缩小动画（焦点失去反馈）
     */
    fun scaleDown(view: View, targetScale: Float = 1f, duration: Long = 200): Animation {
        val anim = ScaleAnimation(
            1.1f, targetScale,
            1.1f, targetScale,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        anim.duration = duration
        anim.interpolator = AccelerateDecelerateInterpolator()
        view.startAnimation(anim)
        return anim
    }
    
    /**
     * 滑动进入（从左边）
     */
    fun slideInFromLeft(view: View, distance: Float = 500f, duration: Long = 300): Animation {
        val anim = TranslateAnimation(
            -distance, 0f,
            0f, 0f
        )
        anim.duration = duration
        anim.interpolator = AccelerateDecelerateInterpolator()
        view.startAnimation(anim)
        return anim
    }
    
    /**
     * 滑动退出（向右边）
     */
    fun slideOutToRight(view: View, distance: Float = 500f, duration: Long = 300, onEnd: (() -> Unit)? = null): Animation {
        val anim = TranslateAnimation(
            0f, distance,
            0f, 0f
        )
        anim.duration = duration
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                onEnd?.invoke()
            }
            override fun onAnimationRepeat(animation: Animation?) {}
        })
        view.startAnimation(anim)
        return anim
    }
    
    /**
     * 组合动画：淡入 + 缩放（页面进入）
     */
    fun enterAnimation(view: View, duration: Long = 500): Animation {
        val set = AnimationSet(true)
        
        val fade = AlphaAnimation(0f, 1f)
        val scale = ScaleAnimation(
            0.8f, 1f,
            0.8f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        
        set.addAnimation(fade)
        set.addAnimation(scale)
        set.duration = duration
        set.interpolator = AccelerateDecelerateInterpolator()
        
        view.startAnimation(set)
        return set
    }
    
    /**
     * 组合动画：淡出 + 缩放（页面退出）
     */
    fun exitAnimation(view: View, duration: Long = 500, onEnd: (() -> Unit)? = null): Animation {
        val set = AnimationSet(true)
        
        val fade = AlphaAnimation(1f, 0f)
        val scale = ScaleAnimation(
            1f, 0.8f,
            1f, 0.8f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        
        set.addAnimation(fade)
        set.addAnimation(scale)
        set.duration = duration
        set.interpolator = AccelerateDecelerateInterpolator()
        set.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                onEnd?.invoke()
            }
            override fun onAnimationRepeat(animation: Animation?) {}
        })
        
        view.startAnimation(set)
        return set
    }
}
