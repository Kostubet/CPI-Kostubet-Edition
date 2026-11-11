package com.cosmod.launcherglass.ui

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.widget.NestedScrollView
import kotlin.math.abs

/** Uses only the part of a vertical swipe that the native scroll view could not consume. */
internal class ColorosScrollView(context:Context,private val animated:Boolean):NestedScrollView(context) {
    private val density=resources.displayMetrics.density
    private val limit=30f*density
    private val resistance=2.25f
    private val dragStiffness=280f
    private val releaseStiffness=108f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val displacement=OnextSpring(0f,.12f){position->
        getChildAt(0)?.let {child->
            if(child.translationY!=position){
                child.translationY=position
                onPullChanged?.invoke()
            }
        }
    }
    var onPullChanged:(()->Unit)?=null
    private var downX=0f
    private var downY=0f
    private var lastY=0f
    private var pull=0f
    private var pullTarget=0f
    private var verticalDrag:Boolean?=null
    private var flingDirection=0
    private var lastFlingFrame=0L
    private var flingSpeed=0f
    private var flingExpires=0L

    init {if(animated)overScrollMode=View.OVER_SCROLL_NEVER}

    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(!animated)return super.dispatchTouchEvent(event)
        val before=scrollY
        if(event.actionMasked==MotionEvent.ACTION_DOWN){
            downX=event.rawX;downY=event.rawY;lastY=downY
            verticalDrag=null
            val p=(displacement.value/limit).coerceIn(-.95f,.95f)
            pull=p*limit*resistance/(1f-abs(p))
            pullTarget=displacement.value
            displacement.snap(pullTarget)
            flingDirection=0
        }
        val handled=super.dispatchTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_MOVE->{
                val y=event.rawY
                val delta=y-lastY
                lastY=y
                val dx=abs(event.rawX-downX)
                val dy=abs(y-downY)
                if(verticalDrag==null && maxOf(dx,dy)>slop)verticalDrag=dy>dx
                if(verticalDrag==true){
                    val remaining=delta+(scrollY-before)
                    pull=when {
                        pull>0f && delta<0f->(pull+delta).coerceAtLeast(0f)
                        pull<0f && delta>0f->(pull+delta).coerceAtMost(0f)
                        remaining>0f && !canScrollVertically(-1)->(pull+remaining).coerceAtLeast(0f)
                        remaining<0f && !canScrollVertically(1)->(pull+remaining).coerceAtMost(0f)
                        else->0f
                    }.coerceIn(-limit*12f,limit*12f)
                    val target=limit*pull/(limit*resistance+abs(pull))
                    if(abs(target-pullTarget)>.12f){
                        pullTarget=target
                        displacement.to(target,dragStiffness,1f)
                    }
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{
                pull=0f
                verticalDrag=if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN)false else null
                if(pullTarget!=0f || displacement.value!=0f || displacement.velocity!=0f){
                    val velocity=displacement.velocity.coerceIn(-170f*density,170f*density)
                    displacement.to(0f,releaseStiffness,1f,velocity)
                }
                pullTarget=0f
            }
        }
        return handled
    }

    override fun fling(velocityY:Int){
        if(animated){
            val direction=velocityY.compareTo(0)
            flingDirection=if(direction!=0 && canScrollVertically(direction))direction else 0
            flingSpeed=abs(velocityY.toFloat())
            lastFlingFrame=SystemClock.uptimeMillis()
            flingExpires=lastFlingFrame+2500L
        }
        super.fling(velocityY)
    }

    override fun computeScroll(){
        val before=scrollY
        super.computeScroll()
        val direction=flingDirection
        if(direction==0)return
        val now=SystemClock.uptimeMillis()
        if(now>=flingExpires){flingDirection=0;return}
        val distance=abs(scrollY-before)
        val elapsed=now-lastFlingFrame
        if(canScrollVertically(direction)){
            if(distance>0){
                flingSpeed=distance*1000f/elapsed.coerceIn(6L,42L)
                lastFlingFrame=now
            }
            return
        }
        flingDirection=0
        // The final native frame is clamped at the boundary. Use the last
        // full frame to carry momentum into the overscroll spring.
        val speed=maxOf(distance*1000f/elapsed.coerceIn(6L,42L),flingSpeed)
        if(elapsed<64L && speed>42f*density){
            val velocityLimit=340f*density
            val incoming=speed/resistance
            val velocity=velocityLimit*incoming/(velocityLimit+incoming)
            displacement.to(0f,releaseStiffness,1f,-direction*velocity)
        }
    }

    override fun onDetachedFromWindow(){
        flingDirection=0
        displacement.snap(0f)
        super.onDetachedFromWindow()
    }
}
