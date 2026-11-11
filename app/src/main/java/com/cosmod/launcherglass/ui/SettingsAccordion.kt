package com.cosmod.launcherglass.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** The viewport expands while its controls keep their full, stable layout. */
internal class SettingsAccordion(private val content:View, initiallyOpen:Boolean):FrameLayout(content.context) {
    private var open=initiallyOpen
    private var fraction=if(open)1f else 0f
    private var animator:ValueAnimator?=null
    private val maxBounce=resources.displayMetrics.density*9f

    init {
        clipChildren=true
        addView(content,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        finishMotion()
    }

    fun setOpen(next:Boolean,animate:Boolean){
        if(next==open && (animate || animator==null))return
        open=next
        cancelMotion()
        if(!animate || !isAttachedToWindow || (parent as? View)?.width==0){
            finishMotion()
            return
        }
        visibility=View.VISIBLE
        importantForAccessibility=if(open)IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val end=if(open)1f else 0f
        val motion=ValueAnimator.ofFloat(fraction,end).apply {
            duration=if(open)390L else 280L
            interpolator=if(open)CherryMotion else CherryEffects
            addUpdateListener {
                fraction=it.animatedValue as Float
                content.alpha=(fraction/.45f).coerceIn(0f,1f)
                requestLayout()
            }
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationEnd(animation:Animator){
                    if(animator!==animation)return
                    animator=null
                    finishMotion()
                }
            })
        }
        animator=motion
        motion.start()
    }

    override fun onMeasure(widthSpec:Int,heightSpec:Int){
        super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        val fullHeight=measuredHeight
        val visibleHeight=(fullHeight*fraction).coerceIn(0f,fullHeight+maxBounce).roundToInt()
        setMeasuredDimension(measuredWidth,resolveSize(visibleHeight,heightSpec))
    }

    override fun dispatchDraw(canvas:android.graphics.Canvas){
        val save=canvas.save()
        try {
            canvas.clipRect(0,0,width,height)
            super.dispatchDraw(canvas)
        }finally {canvas.restoreToCount(save)}
    }

    override fun onDetachedFromWindow(){
        cancelMotion()
        finishMotion()
        super.onDetachedFromWindow()
    }

    private fun cancelMotion(){
        val previous=animator
        animator=null
        previous?.cancel()
    }

    private fun finishMotion(){
        fraction=if(open)1f else 0f
        content.alpha=1f
        visibility=if(open)View.VISIBLE else View.GONE
        importantForAccessibility=if(open)IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        requestLayout()
    }
}
