package com.cosmod.launcherglass.ui

import android.view.MotionEvent
import android.view.VelocityTracker

internal class DialogSwipeVelocity {
    private val tracker=VelocityTracker.obtain()
    private var lastMoveAt=Long.MIN_VALUE
    private var lastSpeed=0f

    fun track(event:MotionEvent,pointer:Int){
        tracker.addMovement(event)
        if(event.actionMasked==MotionEvent.ACTION_MOVE && event.findPointerIndex(pointer)>=0){
            tracker.computeCurrentVelocity(1000)
            lastMoveAt=event.eventTime
            lastSpeed=tracker.getYVelocity(pointer)
        }
    }

    fun atRelease(eventTime:Long,pointer:Int):Float {
        tracker.computeCurrentVelocity(1000)
        val speed=tracker.getYVelocity(pointer)
        // Some firm releases have no movement sample at ACTION_UP.
        return if(lastMoveAt!=Long.MIN_VALUE && eventTime-lastMoveAt<=85L &&
            kotlin.math.abs(speed)<kotlin.math.abs(lastSpeed))lastSpeed else speed
    }

    fun recycle()=tracker.recycle()
}
