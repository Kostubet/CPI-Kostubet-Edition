// SPDX-License-Identifier: GPL-3.0-only
// View port of ONextBox 17.0.0.100 motion by MiToverG422 and contributors.
// Source and modifications are recorded in THIRD_PARTY_NOTICES.md.
package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.sign

internal class OnextSpring(initial:Float,private val threshold:Float=.0005f,private val update:(Float)->Unit) {
    var value=initial
        private set
    var velocity=0f
        private set
    private var animation:SpringAnimation?=null

    private fun newAnimation()=SpringAnimation(FloatValueHolder(value)).apply {
        minimumVisibleChange=threshold
        spring=SpringForce(value)
        addUpdateListener {_,position,speed -> value=position;velocity=speed;update(position)}
    }

    fun to(target:Float,stiffness:Float,damping:Float,startVelocity:Float?=null) {
        if(!ValueAnimator.areAnimatorsEnabled()){snap(target);return}
        // DynamicAnimation 1.0 keeps a pending retarget across cancel().
        // Start a fresh spring when releasing with an explicit velocity.
        if(startVelocity!=null){
            animation?.cancel()
            animation=null
        }
        val running=animation ?: newAnimation().also {animation=it}
        if(!running.isRunning){
            running.setStartValue(value)
            running.setStartVelocity(startVelocity ?: 0f)
        }
        running.spring.stiffness=stiffness
        running.spring.dampingRatio=damping
        running.animateToFinalPosition(target)
    }

    fun snap(position:Float) {
        animation?.cancel()
        animation=null
        value=position;velocity=0f
        update(position)
    }
}

/** View port of ColorOsDialogButtonMotion and ColorOsTopBarMotion. */
internal class OnextButtonMotion(private val view:View,private val compact:Boolean,private val stretches:Boolean=compact) {
    private val density=view.resources.displayMetrics.density
    private val slop=ViewConfiguration.get(view.context).scaledTouchSlop
    private val maxStretch=28f*density
    private val maxDeform=150f*density
    var material:View?=null
    var onMaterialMoved:(()->Unit)?=null
    private var glassFeedback=false
    private val dragRatio get()=if(glassFeedback).22f else .05f
    private val shiftX=OnextSpring(0f,.5f){invalidatePose()}
    private val shiftY=OnextSpring(0f,.5f){invalidatePose()}
    private val stretchX=OnextSpring(1f){invalidatePose()}
    private val stretchY=OnextSpring(1f){invalidatePose()}
    private val press=OnextSpring(1f,.0001f){invalidatePose()}
    private val mask=OnextSpring(0f,.0001f){view.invalidate()}
    private var tracker:VelocityTracker?=null
    private var downX=0f
    private var downY=0f
    private var baseX=0f
    private var baseY=0f
    private var dragging=false
    private val shade=Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds=RectF()
    private val spotlight=OnextSpotlight(view,
        view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==
            android.content.res.Configuration.UI_MODE_NIGHT_YES)

    fun shareGlass(onMove:()->Unit){
        material=view
        onMaterialMoved=onMove
        glassFeedback=true
    }

    private fun invalidatePose(){
        material?.let {
            it.translationX=shiftX.value;it.translationY=shiftY.value
            it.scaleX=press.value*stretchX.value;it.scaleY=press.value*stretchY.value
            onMaterialMoved?.invoke()
        }
        view.invalidate()
    }

    fun listen(event:MotionEvent):Boolean {
        if(!view.isEnabled || !ValueAnimator.areAnimatorsEnabled()){
            if(tracker!=null)dispose()
            return false
        }
        if(event.actionMasked==MotionEvent.ACTION_DOWN){
            resetTracker()
            tracker=VelocityTracker.obtain()
            downX=event.rawX;downY=event.rawY
            baseX=inverseRubber(shiftX.value);baseY=inverseRubber(shiftY.value)
            shiftX.snap(shiftX.value);shiftY.snap(shiftY.value)
            stretchX.snap(stretchX.value);stretchY.snap(stretchY.value)
            dragging=false
            press.to(if(glassFeedback).94f else if(compact).88858957f else if(view.width<72f*density).92f else .96f,
                if(compact)631.655f else 438.65f,1f)
            mask.to(1f,438.649f,1f)
            spotlight.down(event.x,event.y)
        }
        val tracking=tracker ?: return false
        tracking.addMovement(event)
        when(event.actionMasked){
            MotionEvent.ACTION_MOVE->{
                spotlight.move(event.x,event.y)
                val dx=event.rawX-downX
                val dy=event.rawY-downY
                if(!dragging && dx*dx+dy*dy>slop*slop){
                    dragging=true
                    val cancel=MotionEvent.obtain(event)
                    cancel.action=MotionEvent.ACTION_CANCEL
                    view.onTouchEvent(cancel)
                    cancel.recycle()
                    if(stretches)view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if(stretches)move(dx+baseX,dy+baseY)
            }
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                val commit=event.actionMasked==MotionEvent.ACTION_UP
                val swallow=dragging || event.actionMasked==MotionEvent.ACTION_POINTER_DOWN
                if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN){
                    val cancel=MotionEvent.obtain(event)
                    cancel.action=MotionEvent.ACTION_CANCEL
                    view.onTouchEvent(cancel)
                    cancel.recycle()
                }
                tracking.computeCurrentVelocity(1000)
                release(if(commit)tracking.xVelocity else 0f,if(commit)tracking.yVelocity else 0f)
                spotlight.release()
                if(stretches)view.parent?.requestDisallowInterceptTouchEvent(false)
                resetTracker()
                return swallow
            }
        }
        return dragging
    }

    private fun move(x:Float,y:Float) {
        if(abs(x)<5f && abs(y)<5f)return
        val stiffness=39.47f/(.15f*.15f)
        shiftX.to(rubber(x,dragRatio,maxStretch),stiffness,.925f)
        shiftY.to(rubber(y,dragRatio,maxStretch),stiffness,.925f)
        val maxScale=maximumScale()
        val xScale=1f+abs(rubber(x,.55f,maxDeform))/maxDeform*(maxScale-1f)
        val yScale=1f+abs(rubber(y,.55f,maxDeform))/maxDeform*(maxScale-1f)
        val total=abs(x)+abs(y)
        val stretch=(ln(xScale)*abs(x)/total-ln(yScale)*abs(y)/total)*.71f
        stretchX.to(exp(stretch),stiffness,.925f)
        stretchY.to(exp(-stretch),stiffness,.925f)
    }

    private fun release(x:Float,y:Float) {
        val areaDp=view.width.toFloat()*view.height/(density*density)
        val area=((areaDp-56f*56f)/(100f*100f-56f*56f)).coerceIn(0f,1f)
        val response=.39f+.055f*releaseEase.getInterpolation(area)
        val stiffness=39.47f/(response*response)
        fun axis(spring:OnextSpring,rest:Float,velocity:Float){
            val initial=(velocity*.3f/maxStretch*abs(spring.value-rest)).coerceIn(-1000f,1000f)
            spring.to(rest,stiffness,1f-max(.83f,abs(initial)/1000f)*.5f,initial)
        }
        axis(shiftX,0f,x);axis(shiftY,0f,y)
        axis(stretchX,1f,x);axis(stretchY,1f,y)
        press.to(1f,157.914f,.4f)
        mask.to(0f,438.649f,1f,0f)
    }

    fun beginDraw(canvas:Canvas):Int {
        val state=canvas.save()
        if(material==null){
            canvas.translate(shiftX.value,shiftY.value)
            canvas.scale(press.value*stretchX.value,press.value*stretchY.value,view.width*.5f,view.height*.5f)
        }
        return state
    }

    fun endDraw(canvas:Canvas,state:Int) {
        val inset=if(compact && material==null)3f*density else 0f
        bounds.set(inset,inset,view.width-inset,view.height-inset)
        val radius=min(bounds.width(),bounds.height())*.5f
        if(mask.value>.002f){
            shade.color=Color.WHITE
            shade.alpha=((if(glassFeedback)7 else 23)*mask.value.coerceIn(0f,1f)).roundToInt()
            canvas.drawRoundRect(bounds,radius,radius,shade)
        }
        spotlight.draw(canvas,bounds,radius,if(glassFeedback).34f else 1f)
        canvas.restoreToCount(state)
    }

    fun dispose() {
        resetTracker()
        shiftX.snap(0f);shiftY.snap(0f)
        stretchX.snap(1f);stretchY.snap(1f);press.snap(1f);mask.snap(0f)
        spotlight.cancel()
    }

    private fun resetTracker(){tracker?.recycle();tracker=null}
    private fun maximumScale():Float {
        val width=view.width.toFloat()
        val height=view.height.toFloat()
        val area=width*height
        val shortSide=min(width,height)
        if(shortSide<=0f || area<=38416f)return 1.2f
        val attenuation=min(.35f*ln(area/38416f)+.015f*(max(width,height)/shortSide-1f),1f)
        return max(.17f*(1f-attenuation)+1.03f,1.03f)
    }
    private fun rubber(value:Float,ratio:Float,limit:Float)=
        sign(value)*(1f-1f/(abs(value)*ratio/limit+1f))*limit
    private fun inverseRubber(value:Float):Float {
        val distance=abs(value).coerceAtMost(maxStretch-.001f)
        return sign(value)*distance*maxStretch/(dragRatio*(maxStretch-distance))
    }

    private companion object {
        val releaseEase=PathInterpolator(.55026454f,0f,.8915344f,.18783069f)
    }
}

internal class OnextImageButton(context:Context,animated:Boolean):AppCompatImageButton(context) {
    private val motion=if(animated)OnextButtonMotion(this,true)else null
    init {motion?.let {m->setOnTouchListener {_,event->m.listen(event)}}}
    override fun draw(canvas:Canvas){
        val m=motion ?: return super.draw(canvas)
        val state=m.beginDraw(canvas)
        super.draw(canvas)
        m.endDraw(canvas,state)
    }
    override fun onWindowFocusChanged(hasWindowFocus:Boolean){
        super.onWindowFocusChanged(hasWindowFocus)
        if(!hasWindowFocus)motion?.dispose()
    }
    override fun onDetachedFromWindow(){motion?.dispose();super.onDetachedFromWindow()}
}

internal class OnextTextButton(context:Context,animated:Boolean,stretchy:Boolean=false):AppCompatButton(context) {
    private val motion=if(animated)OnextButtonMotion(this,false,stretchy)else null
    init {motion?.let {m->setOnTouchListener {_,event->m.listen(event)}}}
    fun shareGlass(onMove:()->Unit){motion?.shareGlass(onMove)}
    override fun draw(canvas:Canvas){
        val m=motion ?: return super.draw(canvas)
        val state=m.beginDraw(canvas)
        super.draw(canvas)
        m.endDraw(canvas,state)
    }
    override fun onDetachedFromWindow(){motion?.dispose();super.onDetachedFromWindow()}
}

internal data class OnextBarRelease(
    val x:Float,val y:Float,val scaleX:Float,val scaleY:Float,val press:Float,
    val vx:Float,val vy:Float
)

/** View port of the standard ColorOs17BottomNavigationBar and its TouchMotion. */
internal class OnextTabBar(context:Context,private val selected:Int,private val count:Int,
    private val dark:Boolean,private val animated:Boolean,
    private val entry:OnextBarRelease?,private val preview:(Int)->Unit,
    private val choose:(Int,OnextBarRelease)->Unit):FrameLayout(context) {
    private val density=resources.displayMetrics.density
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val limit=28f*density
    private val deformLimit=150f*density
    private val shiftX=OnextSpring(0f,.5f){invalidate()}
    private val shiftY=OnextSpring(0f,.5f){invalidate()}
    private val stretchX=OnextSpring(1f){invalidate()}
    private val stretchY=OnextSpring(1f){invalidate()}
    private val press=OnextSpring(1f,.0001f){invalidate()}
    private val spotlight=OnextSpring(0f){invalidate()}
    private val shade=Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightMatrix=android.graphics.Matrix()
    private val light=android.graphics.RadialGradient(0f,0f,1f,
        intArrayOf(Color.WHITE,0x7affffff,0x20ffffff,Color.TRANSPARENT),
        floatArrayOf(0f,.36f,.74f,1f),android.graphics.Shader.TileMode.CLAMP)
    private val fingerX=OnextSpring(0f,.5f){invalidate()}
    private val fingerY=OnextSpring(0f,.5f){invalidate()}
    private var startX=0f
    private var startY=0f
    private var baseX=0f
    private var baseY=0f
    private var currentPreview=selected
    private var tracker:VelocityTracker?=null
    private var dragging=false
    var onMaterialMoved:(()->Unit)?=null
    var drawConnectedGlass:((Canvas)->Unit)?=null
    var placementX=0f
    private val dragRatio get()=if(drawConnectedGlass==null).06f else .10f

    init {clipChildren=false;clipToPadding=false}

    override fun onAttachedToWindow(){
        super.onAttachedToWindow()
        if(animated)entry?.let {pose->
            shiftX.snap(pose.x);shiftY.snap(pose.y)
            stretchX.snap(pose.scaleX);stretchY.snap(pose.scaleY);press.snap(pose.press)
            settle(pose)
        }
    }

    private fun bar()=getChildAt(0)
    private fun indexAt(x:Float,y:Float):Int?{
        val bar=bar() ?: return null
        val left=bar.left+placementX
        if(x<left || x>left+bar.width || y<bar.top || y>bar.bottom)return null
        val local=(x-left)/bar.width.coerceAtLeast(1)
        val index=(local*count).toInt().coerceIn(0,count-1)
        return if(layoutDirection==LAYOUT_DIRECTION_RTL)count-1-index else index
    }

    private fun inverse(offset:Float):Float {
        val distance=abs(offset)
        if(distance>=limit)return limit*sign(offset)
        return limit*distance/(dragRatio*(limit-distance))*sign(offset)
    }

    private fun pressedScale(width:Float,height:Float):Float {
        if(width<=0f || height<=0f)return 1f
        fun interpolate(input:Float):Float {
            val t=input.coerceIn(0f,1f).pow(1f/3f)
            return 3f*t*t-2f*t*t*t
        }
        val area=(width*height).coerceIn(400f,22500f)
        val areaScale=.85f+.12f*interpolate((area-400f)/22100f)
        val ratio=max(width,height)/min(width,height)
        if(ratio<=2f)return areaScale
        val aspectScale=.85f+.15f*interpolate((ratio-2f)/13f)
        return ((areaScale+aspectScale)*.5f).coerceIn(.85f,.97f)
    }

    private fun begin(event:MotionEvent){
        val bar=bar() ?: return
        tracker?.recycle()
        tracker=VelocityTracker.obtain().also {it.addMovement(event)}
        startX=event.x;startY=event.y
        fingerX.snap(startX);fingerY.snap(startY)
        baseX=inverse(shiftX.value);baseY=inverse(shiftY.value)
        currentPreview=selected
        dragging=false
        press.to(pressedScale(bar.width.toFloat(),bar.height.toFloat()),438.649f,1f)
        spotlight.to(1f,438.649f,1f)
    }

    private fun move(event:MotionEvent){
        val bar=bar() ?: return
        fingerX.to(event.x,631.65f,.94f);fingerY.to(event.y,631.65f,.94f)
        indexAt(event.x,event.y)?.let {index->
            if(index!=currentPreview){currentPreview=index;preview(index)}
        }
        val x=baseX+event.x-startX
        val y=baseY+event.y-startY
        if(abs(x)<5f && abs(y)<5f)return
        val stiffness=1754.60f
        shiftX.to(rubber(x,dragRatio,limit),stiffness,.85f)
        shiftY.to(rubber(y,dragRatio,limit),stiffness,.85f)
        val maximum=maximumDeformScale(bar.width.toFloat(),bar.height.toFloat())
        val xScale=1f+rubber(abs(x),.55f,deformLimit)/deformLimit*(maximum-1f)
        val yScale=1f+rubber(abs(y),.55f,deformLimit)/deformLimit*(maximum-1f)
        val sum=abs(x)+abs(y)
        val stretch=ln(xScale)*abs(x)/sum-ln(yScale)*abs(y)/sum
        stretchX.to(exp(stretch),stiffness,.85f)
        stretchY.to(exp(-stretch),stiffness,.85f)
    }

    private fun release(accept:Boolean):OnextBarRelease?{
        val tracker=tracker ?: return null
        tracker.computeCurrentVelocity(1000)
        val vx=if(accept)tracker.xVelocity else 0f
        val vy=if(accept)tracker.yVelocity else 0f
        tracker.recycle()
        this.tracker=null
        val pose=OnextBarRelease(shiftX.value,shiftY.value,stretchX.value,stretchY.value,press.value,vx,vy)
        settle(pose)
        dragging=false
        preview(selected)
        return pose
    }

    private fun settle(pose:OnextBarRelease){
        fun handUp(spring:OnextSpring,rest:Float,velocity:Float){
            val initial=(velocity/1000f*.3f/limit*abs(spring.value-rest)).coerceIn(-1000f,1000f)
            spring.to(rest,194.955f,.35f,initial)
        }
        handUp(shiftX,0f,pose.vx);handUp(shiftY,0f,pose.vy)
        handUp(stretchX,1f,pose.vx);handUp(stretchY,1f,pose.vy)
        press.to(1f,157.914f,.5f)
        spotlight.to(0f,438.649f,1f,0f)
    }

    override fun onInterceptTouchEvent(event:MotionEvent):Boolean{
        if(!animated || !ValueAnimator.areAnimatorsEnabled())return false
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->if(indexAt(event.x,event.y)!=null)begin(event)
            MotionEvent.ACTION_MOVE->if(tracker!=null){
                tracker?.addMovement(event)
                val dx=event.x-startX;val dy=event.y-startY
                if(dx*dx+dy*dy>slop*slop){
                    dragging=true;move(event)
                    return true
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->release(false)
        }
        return false
    }

    override fun onTouchEvent(event:MotionEvent):Boolean{
        if(!dragging)return super.onTouchEvent(event)
        tracker?.addMovement(event)
        when(event.actionMasked){
            MotionEvent.ACTION_MOVE->move(event)
            MotionEvent.ACTION_UP->{
                move(event)
                val index=currentPreview
                val pose=release(true)
                if(index!=selected && pose!=null)choose(index,pose)
            }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->release(false)
        }
        return true
    }

    override fun dispatchDraw(canvas:Canvas){
        val bar=bar() ?: return
        val sx=press.value+stretchX.value-1f
        val sy=press.value+stretchY.value-1f
        val x=shiftX.value+placementX
        if(bar.translationX!=x || bar.translationY!=shiftY.value || bar.scaleX!=sx || bar.scaleY!=sy){
            bar.translationX=x;bar.translationY=shiftY.value
            bar.scaleX=sx;bar.scaleY=sy
            onMaterialMoved?.invoke()
        }
        drawConnectedGlass?.invoke(canvas)
        super.dispatchDraw(canvas)
        if(spotlight.value<=.004f)return
        val save=canvas.save()
        canvas.translate(x,shiftY.value)
        canvas.scale(sx,sy,bar.left+bar.width*.5f,bar.top+bar.height*.5f)
        run {
            val r=bar.height*.5f
            val falloff=bar.width*.60f
            lightMatrix.setScale(falloff,falloff);lightMatrix.postTranslate(fingerX.value,fingerY.value)
            light.setLocalMatrix(lightMatrix)
            shade.color=if(dark)Color.WHITE else Color.BLACK
            shade.alpha=(spotlight.value*(if(dark)73 else 35)).roundToInt()
            shade.shader=light
            canvas.drawRoundRect(bar.left.toFloat(),bar.top.toFloat(),bar.right.toFloat(),bar.bottom.toFloat(),r,r,shade)
            shade.shader=null
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow(){
        tracker?.recycle();tracker=null
        shiftX.snap(0f);shiftY.snap(0f);stretchX.snap(1f);stretchY.snap(1f)
        press.snap(1f);spotlight.snap(0f)
        fingerX.snap(0f);fingerY.snap(0f)
        super.onDetachedFromWindow()
    }
}

private fun rubber(offset:Float,ratio:Float,maxDistance:Float)=
    (1f-1f/(ratio*abs(offset)/maxDistance+1f))*maxDistance*sign(offset)

private fun maximumDeformScale(width:Float,height:Float):Float {
    if(width<=0f || height<=0f)return 1f
    val area=width*height
    if(area<=38416f)return 1.2f
    val ratio=max(width,height)/min(width,height)
    val decay=(.35f*ln(area/38416f)+.015f*(ratio-1f)).coerceAtMost(1f)
    return max(.17f*(1f-decay)+1.03f,1.03f)
}

/** ColorOsPopup's entrance, exit and rubber-band deformation. */
internal class OnextPopupShell(context:Context):FrameLayout(context) {
    var onMaterialMoved:(()->Unit)?=null
    private var afterClose:(()->Unit)?=null
    private val density=resources.displayMetrics.density
    private val shiftLimit=28f*density
    private val deformLimit=150f*density
    private val geometry=OnextSpring(1f,.0001f){applyTransform()}
    private val shiftX=OnextSpring(0f,.15f){applyTransform()}
    private val shiftY=OnextSpring(0f,.15f){applyTransform()}
    private val stretch=OnextSpring(0f){applyTransform()}
    private val sheetOffset=OnextSpring(0f,.2f){applyTransform()}
    private var baseX=0f
    private var baseY=0f
    private val opacity=OnextSpring(1f,.002f){
        alpha=it.coerceIn(0f,1f)
        if(alpha<=.002f && afterClose!=null){
            val action=afterClose
            afterClose=null
            action?.invoke()
        }
    }

    private fun applyTransform(){
        val size=geometry.value.coerceAtLeast(.001f)
        val deformation=exp(stretch.value)
        scaleX=size*deformation
        scaleY=size/deformation
        // Stretch around the centre while the entrance still scales from its anchor.
        translationX=shiftX.value+(width*.5f-pivotX)*(size-scaleX)
        translationY=sheetOffset.value+shiftY.value+(height*.5f-pivotY)*(size-scaleY)
        onMaterialMoved?.invoke()
    }

    fun startDrag(){
        fun inverse(offset:Float):Float {
            val distance=abs(offset).coerceAtMost(shiftLimit-.001f)
            return sign(offset)*distance*shiftLimit/(.05f*(shiftLimit-distance))
        }
        baseX=inverse(shiftX.value);baseY=inverse(shiftY.value)
    }

    fun dragBy(dx:Float,dy:Float){
        val x=baseX+dx
        val y=baseY+dy
        val distance=abs(x)+abs(y)
        val scaleRange=maximumDeformScale(width.toFloat(),height.toFloat())-1f
        val scaleX=1f+rubber(abs(x),.55f,deformLimit)/deformLimit*scaleRange
        val scaleY=1f+rubber(abs(y),.55f,deformLimit)/deformLimit*scaleRange
        val deformation=if(distance==0f)0f else (ln(scaleX)*abs(x)-ln(scaleY)*abs(y))/distance
        shiftX.to(rubber(x,.05f,shiftLimit),1754.22f,.925f)
        shiftY.to(rubber(y,.05f,shiftLimit),1754.22f,.925f)
        stretch.to(deformation,1754.22f,.925f)
    }

    fun releaseDrag(){
        for(spring in listOf(shiftX,shiftY,stretch)){
            if(spring.value!=0f || spring.velocity!=0f)spring.to(0f,215f,.55f,spring.velocity)
            else spring.snap(0f)
        }
    }

    fun open(){
        geometry.snap(.001f);opacity.snap(0f)
        geometry.to(1f,322.27f,.8f)
        opacity.to(1f,322.27f,.8f)
    }
    fun close(after:()->Unit){
        afterClose=after
        geometry.to(0f,438.65f,1f)
        opacity.to(0f,631.65f,1f)
    }
    fun openSheet(){
        pivotX=width*.5f
        pivotY=height.toFloat()
        geometry.snap(.94f)
        sheetOffset.snap(height+12f*density)
        geometry.to(1f,298f,.59f)
        sheetOffset.to(0f,242f,.62f)
    }
    fun closeSheet(after:()->Unit){
        afterClose=after
        sheetOffset.to(68f*density,424f,.9f)
        geometry.to(.96f,355f,.89f)
        opacity.to(0f,631.65f,1f)
    }
    override fun onDetachedFromWindow(){
        afterClose=null;geometry.snap(1f);opacity.snap(1f)
        shiftX.snap(0f);shiftY.snap(0f);stretch.snap(0f);sheetOffset.snap(0f)
        super.onDetachedFromWindow()
    }
}

/** Keeps the gesture in window coordinates even as the menu moves under the finger. */
internal class OnextChoiceOverlay(context:Context,val shell:OnextPopupShell,
    private val options:LinearLayout,private val highlight:View,private val animated:Boolean):FrameLayout(context) {
    private val inverse=Matrix()
    private val point=FloatArray(2)
    private val deadZone=5f*resources.displayMetrics.density
    private var startX=0f
    private var startY=0f
    private var pressedOption:View?=null
    private var highlightedOption:View?=null
    private val highlightY=OnextSpring(0f,.3f){highlight.translationY=it}
    private val highlightAlpha=OnextSpring(0f,.003f){highlight.alpha=it.coerceIn(0f,1f)}
    private var tracking=false
    private var cancelled=false
    private var dragging=false
    private var closing=false
    var spotlight:OnextSpotlightView?=null

    init {
        for(i in 0 until options.childCount){
            options.getChildAt(i).setOnFocusChangeListener {row,focused->
                if(!tracking && !closing){
                    if(focused)moveHighlight(row)
                    else if(highlightedOption===row)moveHighlight(null)
                }
            }
        }
    }

    private fun mapTouch(event:MotionEvent):Boolean {
        point[0]=event.x-shell.left
        point[1]=event.y-shell.top
        if(!shell.matrix.invert(inverse))return false
        inverse.mapPoints(point)
        return point[0]>=0f && point[0]<shell.width && point[1]>=0f && point[1]<shell.height
    }

    private fun updatePressed(inside:Boolean){
        val x=point[0]-options.left
        val y=point[1]-options.top
        val next=if(inside)options.findChildAt(x,y) else null
        if(pressedOption!==next){
            pressedOption?.isPressed=false
            pressedOption=next
            next?.isPressed=true
        }
        moveHighlight(next)
    }

    private fun moveHighlight(row:View?){
        val previous=highlightedOption
        if(previous===row)return
        highlightedOption=row
        if(row!=null){
            val top=(options.top+row.top).toFloat()
            if(animated && highlight.alpha>.002f)highlightY.to(top,438.65f,1f)
            else highlightY.snap(top)
        }
        // Changing rows only moves the same backing view. Opacity is only
        // animated when the finger enters or leaves the option area.
        if(previous==null || row==null){
            val alpha=if(row==null)0f else 1f
            if(animated)highlightAlpha.to(alpha,631.65f,1f)
            else highlightAlpha.snap(alpha)
        }
    }

    private fun LinearLayout.findChildAt(x:Float,y:Float):View? {
        for(i in 0 until childCount){
            val row=getChildAt(i)
            if(x>=row.left && x<row.right && y>=row.top && y<row.bottom)return row
        }
        return null
    }

    private fun release(){
        updatePressed(false)
        spotlight?.light?.release()
        if(dragging)shell.releaseDrag()
        dragging=false
    }

    fun stopInteraction(){
        closing=true
        release()
    }

    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(closing)return true
        if(event.actionMasked==MotionEvent.ACTION_DOWN){
            tracking=mapTouch(event)
            cancelled=false
            if(tracking){
                startX=event.x;startY=event.y
                updatePressed(true)
                spotlight?.light?.down(point[0],point[1])
            }
        }
        if(!tracking)return super.dispatchTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_MOVE->if(!cancelled){
                val dx=event.x-startX
                val dy=event.y-startY
                if(animated && !dragging && (abs(dx)>=deadZone || abs(dy)>=deadZone)){
                    shell.startDrag()
                    dragging=true
                }
                if(dragging)shell.dragBy(dx,dy)
                updatePressed(mapTouch(event))
                spotlight?.light?.move(point[0],point[1])
            }
            MotionEvent.ACTION_POINTER_DOWN->{
                cancelled=true
                release()
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                updatePressed(!cancelled && event.actionMasked==MotionEvent.ACTION_UP && mapTouch(event))
                val picked=pressedOption
                tracking=false
                release()
                picked?.performClick()
            }
        }
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus:Boolean){
        super.onWindowFocusChanged(hasWindowFocus)
        if(!hasWindowFocus){
            cancelled=true
            release()
        }else if(!tracking && !closing)moveHighlight(options.findFocus())
    }

    override fun onDetachedFromWindow(){
        pressedOption?.isPressed=false
        pressedOption=null
        highlightedOption=null
        highlightY.snap(0f)
        highlightAlpha.snap(0f)
        super.onDetachedFromWindow()
    }
}

/** Intercepts a drag on an inactive search field while leaving native text editing intact. */
internal class OnextSearchFrame(context:Context,private val surface:OnextPopupShell,
    private val canStretch:()->Boolean):FrameLayout(context) {
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var startX=0f
    private var startY=0f
    private var tracking=false
    private var dragging=false
    var spotlight:OnextSpotlightView?=null
    var dragExclusion:View?=null
    private val exclusionBounds=Rect()
    private val lightInverse=Matrix()
    private val lightPoint=FloatArray(2)

    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(spotlight!=null || dragExclusion!=null){
            lightPoint[0]=event.x-surface.left
            lightPoint[1]=event.y-surface.top
            if(surface.matrix.invert(lightInverse))lightInverse.mapPoints(lightPoint)
        }
        spotlight?.light?.let {light->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->light.down(lightPoint[0],lightPoint[1])
                MotionEvent.ACTION_MOVE->light.move(lightPoint[0],lightPoint[1])
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->light.release()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                startX=event.rawX;startY=event.rawY
                val overButtons=dragExclusion?.let {view->
                    exclusionBounds.set(0,0,view.width,view.height)
                    surface.offsetDescendantRectToMyCoords(view,exclusionBounds)
                    exclusionBounds.contains(lightPoint[0].toInt(),lightPoint[1].toInt())
                } ?: false
                tracking=canStretch() && !overButtons
            }
            MotionEvent.ACTION_MOVE->if(tracking &&
                (abs(event.rawX-startX)>slop || abs(event.rawY-startY)>slop)){
                dragging=true
                surface.startDrag()
                surface.dragBy(event.rawX-startX,event.rawY-startY)
                return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->tracking=false
        }
        return false
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!dragging)return super.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_MOVE->surface.dragBy(event.rawX-startX,event.rawY-startY)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{
                surface.releaseDrag()
                dragging=false
                tracking=false
            }
        }
        return true
    }
}
