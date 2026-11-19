package com.cosmod.launcherglass.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager.LayoutParams
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import com.cosmod.launcherglass.ui.onextbox.ColorOsPopupMaterialView
import com.cosmod.launcherglass.ui.onextbox.applyOplusPopupBlur
import com.cosmod.launcherglass.ui.onextbox.colorOsPopupFallback
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Keeps the material, backdrop and anchor on one timeline in both directions. */
internal class CouiAnchoredDialog(
    context:Context,
    content:View,
    dark:Boolean,
    width:Int,
    private val anchor:View?,
    private val animated:Boolean,
    actions:CouiPreviewActions?=null,
    holdTouch:Boolean=false,
    private val swipeHeader:Boolean=false,
    private val backdrop:(Float)->Unit,
) : ComponentDialog(context) {
    private val density=context.resources.displayMetrics.density
    private val card=Panel(context,content,dark)
    private val actionPanel=actions
    private val group=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL
        clipChildren=false
        clipToPadding=false
        addView(card,LinearLayout.LayoutParams(-1,-2))
        actionPanel?.let {
            addView(it,LinearLayout.LayoutParams((width*.66f).roundToInt(),-2).apply {
                gravity=Gravity.CENTER_HORIZONTAL
                topMargin=(9*density).roundToInt()
            })
        }
    }
    private val shade=View(context)
    private var animator:ValueAnimator?=null
    private var closing=false
    private var finished=false
    private var progress=0f
    private var backdropProgress=0f
    private var opacity=0f
    private var springStrain=0f
    private var anchorFrame:PreviewCardMotion.Frame?=null
    private var contextFrame:ContextMenuMotion.Frame?=null
    private var drawnContextFrame:ContextMenuMotion.Frame?=null
    private var menuProgress=0f
    private var startY=0f
    private var groupOffset=0f
    private var keyboardOffset=0f
    private var keyboardFallback:ValueAnimator?=null
    private var dragTarget=0f
    private var sidePerVertical=0f
    private val drag=OnextSpring(0f,.4f*density){pose(progress,backdropProgress,opacity,springStrain)}
    private data class SwipeRelease(val offset:Float,val velocity:Float,val fingerVelocity:Float)
    private var swipeRelease:SwipeRelease?=null
    private var swipeOffset=0f
    private var sourceTouchPending=holdTouch
    private data class HeaderTouch(val id:Int,val x:Float,val y:Float,val velocity:DialogSwipeVelocity,var dragging:Boolean=false)
    private var headerTouch:HeaderTouch?=null
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop
    private val holdFlags=LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE

    init {
        shade.setBackgroundColor(if(dark)0x27000000 else 0x24000000)
        shade.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val root=FrameLayout(context).apply {
            clipChildren=false
            clipToPadding=false
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener {cancel()}
            addView(shade,FrameLayout.LayoutParams(-1,-1))
            addView(group,FrameLayout.LayoutParams(width,-2,Gravity.CENTER))
        }
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(LayoutParams.FLAG_DIM_BEHIND)
            // The gesture started in the Activity. Leave that window in
            // control of its pointer until the finger is released.
            if(holdTouch)addFlags(holdFlags)
            setWindowAnimations(0)
        }
        val initial=if(animated)0f else 1f
        if(!animated)(anchor as? CouiPreference)?.resetPreviewLift()
        menuProgress=initial
        pose(initial,initial,initial)
    }

    override fun show(){
        super.show()
        window?.setLayout(-1,-1)
        if(animated)group.doOnPreDraw {
            if(closing || finished)return@doOnPreDraw
            resolveAnchor()
            if(card.hasReplica){
                if(actionPanel!=null)contextFrame=ContextMenuMotion.opening(0f)
                else anchorFrame=PreviewCardMotion.openingFrame(0f)
            }
            pose(0f,0f,0f)
            animateTo(1f){}
        }
    }

    private fun resolveAnchor(){
        val source=anchor?.takeIf {it.isShown && it.width>0 && it.height>0}
        if(source==null){
            startY=42f*density
            return
        }
        val from=IntArray(2)
        val to=IntArray(2)
        source.getLocationOnScreen(from)
        card.getLocationOnScreen(to)
        val x=from[0]-to[0]+(group.pivotX*(1f-group.scaleX)).roundToInt()
        val y=from[1]-to[1]+(group.translationY+group.pivotY*(1f-group.scaleY)).roundToInt()
        val viewportHeight=(group.parent as View).height.toFloat()
        val rowWidth=(source.width*source.scaleX).roundToInt()
        val rowHeight=(source.height*source.scaleY).roundToInt()
        val rowCenter=group.top+card.top+y+rowHeight*.5f
        if(!card.setAnchor(source,Rect(x,y,x+rowWidth,y+rowHeight),
            PreviewCardMotion.rowDirection(rowCenter,viewportHeight)))startY=42f*density
    }

    private fun pose(value:Float,background:Float,visible:Float,strain:Float=0f){
        progress=value
        contextFrame?.let {frame->
            val offset=if(closing)0f else drag.value
            val drawn=if(closing || offset==0f) frame else ContextMenuMotion.dragging(
                frame,offset*sidePerVertical,offset,card.height.toFloat())
            drawnContextFrame=drawn
            groupOffset=0f
            group.translationY=keyboardOffset
            card.presentContext(drawn)
            actionPanel?.apply {
                translationY=card.menuOffset-8f*density*(1f-drawn.menu)
                renderReveal(drawn.menu)
            }
            shade.alpha=drawn.backdrop
            backdrop(drawn.backdrop)
            return
        }
        backdropProgress=background
        opacity=visible
        springStrain=strain
        val dragOffset=if(swipeRelease!=null)swipeOffset else drag.value
        val dragProgress=PreviewCardMotion.pullProgress(dragOffset,card.height.toFloat())
        val anchored=anchorFrame!=null
        groupOffset=startY*(1f-value)+if(anchored)0f else dragOffset
        group.translationY=groupOffset+keyboardOffset
        val scale=1f-.33f*dragProgress
        group.scaleX=if(anchored)1f else scale
        group.scaleY=group.scaleX
        card.present(visible,strain,anchorFrame,dragOffset,scale)
        actionPanel?.apply {
            val underCard=menuProgress
            val held=1f-smooth((abs(dragOffset)-6f*density)/(48f*density))
            renderReveal(underCard*held*visible)
            translationY=card.menuOffset-9f*density*(1f-underCard)
            scaleX=if(anchored)scale else 1f
            scaleY=scaleX
        }
        val backdropDrag=swipeRelease?.let {PreviewCardMotion.pullProgress(it.offset,card.height.toFloat())} ?: dragProgress
        val amount=background*(1f-.45f*backdropDrag)
        shade.alpha=amount
        backdrop(amount)
    }

    @Suppress("DEPRECATION")
    fun animateWithKeyboard(){
        window?.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if(!animated)return
        val root=group.parent as? View ?: return
        var activeIme:WindowInsetsAnimationCompat?=null
        var startTop=0f
        var startOffset=0f
        fun offset(value:Float){
            keyboardOffset=value
            group.translationY=groupOffset+value
        }
        ViewCompat.setWindowInsetsAnimationCallback(root,object:WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE){
            override fun onPrepare(animation:WindowInsetsAnimationCompat){
                if(animation.typeMask and WindowInsetsCompat.Type.ime()==0 || !group.isLaidOut)return
                keyboardFallback?.cancel()
                activeIme=animation
                startTop=group.top+keyboardOffset
            }

            override fun onStart(animation:WindowInsetsAnimationCompat,bounds:WindowInsetsAnimationCompat.BoundsCompat):
                WindowInsetsAnimationCompat.BoundsCompat {
                if(activeIme===animation){
                    startOffset=startTop-group.top
                    offset(startOffset)
                }
                return bounds
            }

            override fun onProgress(insets:WindowInsetsCompat,runningAnimations:List<WindowInsetsAnimationCompat>):WindowInsetsCompat {
                val animation=activeIme
                if(animation!=null && animation in runningAnimations)offset(startOffset*(1f-animation.interpolatedFraction))
                return insets
            }

            override fun onEnd(animation:WindowInsetsAnimationCompat){
                if(activeIme===animation){
                    activeIme=null
                    offset(0f)
                }
            }
        })
        var height=root.height
        group.addOnLayoutChangeListener {_,_,top,_,_,_,oldTop,_,_->
            val next=root.height
            val resized=height>0 && next!=height
            height=next
            if(!resized || top==oldTop)return@addOnLayoutChangeListener
            card.rebaseAnchor(oldTop-top)
            if(activeIme==null && !finished){
                keyboardFallback?.cancel()
                offset(keyboardOffset+oldTop-top)
                keyboardFallback=ValueAnimator.ofFloat(keyboardOffset,0f).apply {
                    duration=270L
                    interpolator=PathInterpolator(.24f,.66f,.26f,1f)
                    addUpdateListener {offset(it.animatedValue as Float)}
                    start()
                }
            }
        }
    }

    fun dragFromAnchor(distance:Float,horizontal:Float=0f){
        if(closing || finished)return
        val target=if(distance>=0f)distance else distance/(1f-distance/(180f*density))
        if(contextFrame!=null && abs(target)>6f*density){
            sidePerVertical=(horizontal*.37f/target).coerceIn(-.20f,.20f)
        }
        if(abs(target-dragTarget)<.4f*density)return
        dragTarget=target
        if(animated)drag.to(target,740f,.97f) else drag.snap(target)
    }

    fun releaseAnchor(commit:Boolean,fingerVelocity:Float=0f){
        if(closing || finished)return
        if(sourceTouchPending){
            sourceTouchPending=false
            window?.clearFlags(holdFlags)
        }
        val threshold=(card.height*.14f).coerceIn(48f*density,88f*density)
        if(commit && dragTarget>=threshold){
            swipeOffset=drag.value
            swipeRelease=SwipeRelease(swipeOffset,drag.velocity.coerceIn(-2400f*density,2400f*density),fingerVelocity)
            close(cancelled=true)
            return
        }
        dragTarget=0f
        if(animated && drag.value!=0f)drag.to(0f,250f,.89f) else drag.snap(0f)
    }

    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        val action=event.actionMasked
        if(action==MotionEvent.ACTION_DOWN){
            clearHeaderTouch()
            headerTouch=if(swipeHeader && !closing && progress>.9f && isHeaderTouch(event.rawX,event.rawY))
                HeaderTouch(event.getPointerId(0),event.rawX,event.rawY,DialogSwipeVelocity()) else null
        }
        val touch=headerTouch ?: return super.dispatchTouchEvent(event)
        touch.velocity.track(event,touch.id)
        val index=event.findPointerIndex(touch.id)
        val distance=if(index>=0)event.getRawY(index)-touch.y else 0f
        if(action==MotionEvent.ACTION_MOVE && index>=0 && !touch.dragging){
            val horizontal=abs(event.getRawX(index)-touch.x)
            if(abs(distance)>touchSlop && abs(distance)>horizontal){
                touch.dragging=true
                val cancel=MotionEvent.obtain(event)
                cancel.action=MotionEvent.ACTION_CANCEL
                try {super.dispatchTouchEvent(cancel)} finally {cancel.recycle()}
            } else if(horizontal>touchSlop){
                clearHeaderTouch()
                return super.dispatchTouchEvent(event)
            }
        }
        val ended=index<0 || action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL ||
            (action==MotionEvent.ACTION_POINTER_UP && event.actionIndex==index)
        if(!touch.dragging){
            if(ended)clearHeaderTouch()
            return super.dispatchTouchEvent(event)
        }
        if(index>=0 && action!=MotionEvent.ACTION_CANCEL)dragFromAnchor(distance,event.getRawX(index)-touch.x)
        if(ended){
            val speed=touch.velocity.atRelease(event.eventTime,touch.id)
            clearHeaderTouch()
            releaseAnchor(action!=MotionEvent.ACTION_CANCEL && index>=0,speed)
        }
        return true
    }

    private fun clearHeaderTouch(){
        headerTouch?.velocity?.recycle()
        headerTouch=null
    }

    private fun isHeaderTouch(x:Float,y:Float):Boolean {
        val location=IntArray(2)
        val surface=card.content
        surface.getLocationOnScreen(location)
        val localX=(x-location[0])/(group.scaleX*surface.scaleX)
        val localY=(y-location[1])/(group.scaleY*surface.scaleY)
        if(localY !in 0f..(54f*density))return false
        val inset=if(actionPanel!=null)14f*density else 62f*density
        return localX>=inset && localX<=card.width-inset
    }

    private fun animateTo(target:Float,end:()->Unit){
        animator?.cancel()
        contextFrame?.let {
            animateContextMenu(target==1f,drawnContextFrame ?: it,end)
            return
        }
        val initial=progress
        val initialBackdrop=backdropProgress
        val initialOpacity=opacity
        val initialStrain=springStrain
        val initialMenu=menuProgress
        val distance=maxOf(abs(target-initial),abs(target-initialBackdrop),abs(target-initialOpacity))
        if(!animated || distance<.001f){
            menuProgress=target
            pose(target,target,target)
            end()
            return
        }
        val opening=target==1f
        val release=swipeRelease
        val startFrame=anchorFrame
        val anchored=startFrame!=null
        val swiped=anchored && !opening && release!=null
        val landing=if(anchored && !opening)PreviewCardMotion.LANDING else 1f
        val baseDuration=when {
            anchored && opening -> PreviewCardMotion.OPEN_DURATION_MS
            swiped -> PreviewCardMotion.SWIPE_CLOSE_DURATION_MS
            anchored -> PreviewCardMotion.CLOSE_DURATION_MS
            opening -> 485
            release!=null -> 415
            else -> 430
        }
        val speedDp=abs(release?.fingerVelocity ?: 0f)/density
        val speedUp=if(anchored && !opening)(speedDp/2400f).coerceIn(0f,1f)*.08f else 0f
        val travelDuration=(baseDuration*(1f-speedUp)*distance.coerceIn(.55f,1f)).roundToInt()
        val swipeEnergy=(abs(release?.velocity ?: 0f)/(960f*density)).coerceIn(0f,1f)
        val closingTravel=if(startFrame!=null && !opening)card.landingTravel()*startFrame.position else 0f
        val overrun=if(startFrame!=null && !opening && startFrame.position>.10f)
            density*PreviewCardMotion.overrunDp((release?.fingerVelocity ?: 0f)/density)
        else 0f
        val rowHeight=card.rowHeight()
        val rowTravel=rowHeight*.95f+overrun*.68f
        val rowOverrun=minOf(rowHeight*.19f,overrun*.52f+4f*density)
        val departureDistance=(card.height*.63f).coerceAtLeast(24f*density)+overrun
        animator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=travelDuration.toLong()
            interpolator=LinearInterpolator()
            addUpdateListener {
                val time=it.animatedValue as Float
                val frame=when {
                    startFrame==null -> null
                    opening -> PreviewCardMotion.openingFrame(time)
                    swiped -> PreviewCardMotion.swipeFrame(time,startFrame,rowTravel,rowOverrun)
                    else -> PreviewCardMotion.closingFrame(time,startFrame,closingTravel,overrun)
                }
                anchorFrame=frame
                val value=when {
                    frame!=null -> frame.position
                    opening -> settle(time)
                    else -> initial*(1f-smooth((time-.07f)/.93f))
                }
                val fade=if(swiped)InterfaceMotion.glide((time-.23f)/.73f)
                    else InterfaceMotion.glide(time/(if(anchored && !opening).88f else .87f))
                val alphaCurve=when {
                    opening -> smooth((time-.035f)/.75f)
                    else -> InterfaceMotion.glide((time-.13f)/(landing-.13f))
                }
                val strain=if(anchored)0f else initialStrain*(1f-smooth(time/.34f))+
                    elasticStrain(time,opening,swipeEnergy)
                val menu=PreviewCardMotion.menuReveal(time,opening)
                menuProgress=if(opening)menu else initialMenu*menu
                if(release!=null){
                    swipeOffset=if(swiped)PreviewCardMotion.swipeDeparture(time,release.offset,
                        departureDistance,release.velocity*.001f,travelDuration)
                    else PreviewCardMotion.releasedOffset((time/landing).coerceAtMost(1f),
                        release.offset,release.velocity*(landing*travelDuration/1000f))
                }
                pose(value,initialBackdrop+(target-initialBackdrop)*fade,
                    frame?.cardAlpha ?: initialOpacity+(target-initialOpacity)*alphaCurve,strain)
            }
            addListener(object:AnimatorListenerAdapter(){
                private var canceled=false
                override fun onAnimationCancel(animation:Animator){canceled=true}
                override fun onAnimationEnd(animation:Animator){
                    if(canceled)return
                    animator=null
                    swipeOffset=0f
                    menuProgress=target
                    pose(target,target,target)
                    end()
                }
            })
            start()
        }
    }

    private fun animateContextMenu(opening:Boolean,start:ContextMenuMotion.Frame,end:()->Unit){
        val release=swipeRelease
        val durationMs=when {
            opening->ContextMenuMotion.OPEN_MS
            release!=null->ContextMenuMotion.SWIPE_CLOSE_MS
            else->ContextMenuMotion.CLOSE_MS
        }
        val travel=card.landingTravel(start)
        val overrun=ContextMenuMotion.overrun(travel,density,release?.fingerVelocity ?: 0f)
        val approachSeconds=durationMs*PreviewCardMotion.LANDING/1000f
        val momentum=((release?.velocity ?: 0f)*approachSeconds)
            .coerceIn(-card.height*.55f,card.height*.55f)
        animator=ValueAnimator.ofFloat(0f,1f).apply {
            interpolator=LinearInterpolator()
            duration=durationMs.toLong()
            addUpdateListener {
                val time=it.animatedValue as Float
                contextFrame=if(opening)ContextMenuMotion.opening(time)
                    else ContextMenuMotion.closing(time,start,travel,overrun,momentum)
                pose(if(opening)time else 1f-time,0f,0f)
            }
            addListener(object:AnimatorListenerAdapter(){
                private var cancelled=false
                override fun onAnimationCancel(animation:Animator){cancelled=true}
                override fun onAnimationEnd(animation:Animator){
                    if(cancelled)return
                    animator=null
                    end()
                }
            })
            start()
        }
    }

    private fun settle(time:Float):Float {
        val phase=time*6.4f
        val remaining=(1f+phase)*exp(-phase)
        return 1f-remaining*(1f-smooth((time-.66f)/.34f))
    }

    private fun elasticStrain(time:Float,opening:Boolean,swipeEnergy:Float):Float {
        fun impulse(start:Float,end:Float):Float {
            val phase=((time-start)/(end-start)).coerceIn(0f,1f)
            if(phase==0f || phase==1f)return 0f
            return sin(Math.PI.toFloat()*phase)*exp(-.9f*phase)*
                smooth(phase/.25f)*smooth((1f-phase)/.27f)
        }
        return if(opening).108f*impulse(.40f,.98f)
        else (.074f+.025f*swipeEnergy)*impulse(0f,.44f)-.049f*impulse(.36f,.90f)
    }

    private fun smooth(value:Float):Float {
        val t=value.coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }

    override fun dismiss(){
        if(finished)super.dismiss() else close()
    }
    override fun cancel(){close(cancelled=true)}
    fun close(after:()->Unit={})=close(cancelled=false,after=after)

    private fun close(cancelled:Boolean,after:()->Unit={}){
        if(closing || finished)return
        drawnContextFrame?.let {contextFrame=it}
        closing=true
        clearHeaderTouch()
        drag.snap(drag.value)
        animateTo(0f){
            finished=true
            keyboardFallback?.cancel()
            card.restoreAnchor()
            (anchor as? CouiPreference)?.resetPreviewLift()
            if(cancelled)super.cancel() else super.dismiss()
            after()
        }
    }

    fun dispose(){
        animator?.cancel()
        keyboardFallback?.cancel()
        clearHeaderTouch()
        contextFrame=null
        drawnContextFrame=null
        drag.snap(0f)
        finished=true
        pose(0f,0f,0f)
        card.restoreAnchor()
        (anchor as? CouiPreference)?.resetPreviewLift()
        super.dismiss()
    }

    private class Panel(context:Context,val content:View,private val dark:Boolean):FrameLayout(context) {
        private val density=resources.displayMetrics.density
        private val legacyMaterial = if (CouiAppearance.read(context) == CouiAppearance.ORIGINAL)
            ColorOsPopupMaterialView(context, dark) else null
        private val material: View = legacyMaterial ?: CouiPopupBackground(context, dark)
        private val shade=View(context).apply {
            background=CouiCardEdgeShade(dark,density)
            outlineProvider=material.outlineProvider
            clipToOutline=true
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val replica=RowReplica(context)
        private var anchorBounds:Rect?=null
        private var sourceRow:View?=null
        private var sourceAlpha=1f
        private var sourceScale=1f
        private var rowScale=1f
        private var strain=0f
        var rowDirection=1f
            private set
        private var frame:PreviewCardMotion.Frame?=null
        private var contextMotion:ContextMenuMotion.Frame?=null
        private val contentClip=Rect()
        private var contentClipped=false
        private var dragOffset=0f
        private var dragScale=1f
        var menuOffset=0f
            private set
        val hasReplica get()=sourceRow!=null

        init {
            val contentHeight=content.layoutParams?.height ?: -2
            addView(material,FrameLayout.LayoutParams(-1,-1))
            addView(shade,FrameLayout.LayoutParams(-1,-1))
            addView(content,FrameLayout.LayoutParams(-1,contentHeight))
            addView(replica,FrameLayout.LayoutParams(1,1))
            isClickable=true
            clipChildren=false
            clipToPadding=false
        }

        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
            measureChild(content,widthMeasureSpec,heightMeasureSpec)
            setMeasuredDimension(content.measuredWidth,content.measuredHeight)
            val fixedWidth=MeasureSpec.makeMeasureSpec(measuredWidth,MeasureSpec.EXACTLY)
            val fixedHeight=MeasureSpec.makeMeasureSpec(measuredHeight,MeasureSpec.EXACTLY)
            material.measure(fixedWidth,fixedHeight)
            shade.measure(fixedWidth,fixedHeight)
        }

        override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int){
            content.layout(0,0,width,height)
            material.layout(0,0,width,height)
            shade.layout(0,0,width,height)
            anchorBounds?.let {replica.layout(it.left,it.top,it.right,it.bottom)}
            updateBounds()
        }

        fun setAnchor(anchor:View,bounds:Rect,direction:Float):Boolean{
            if(!replica.capture(anchor))return false
            anchorBounds=bounds
            rowDirection=direction
            replica.layout(bounds.left,bounds.top,bounds.right,bounds.bottom)
            sourceRow=anchor
            sourceAlpha=anchor.alpha
            sourceScale=anchor.scaleX
            anchor.alpha=0f
            updateBounds()
            return true
        }

        fun restoreAnchor(){
            sourceRow?.alpha=sourceAlpha
            sourceRow=null
        }

        fun rebaseAnchor(offsetY:Int){
            val bounds=anchorBounds ?: return
            bounds.offset(0,offsetY)
            replica.layout(bounds.left,bounds.top,bounds.right,bounds.bottom)
            updateBounds()
        }

        fun landingTravel():Float{
            val bounds=anchorBounds ?: return 0f
            return hypot(bounds.exactCenterX()-width*.5f,bounds.exactCenterY()-height*.5f)
        }

        fun rowHeight():Float=sourceRow?.height?.toFloat() ?: 0f

        fun landingTravel(frame:ContextMenuMotion.Frame):Float {
            val row=anchorBounds ?: return 0f
            return hypot((width*.5f-row.exactCenterX())*frame.position+frame.offsetX,
                (height*.5f-row.exactCenterY())*frame.position+frame.offsetY)
        }

        fun presentContext(next:ContextMenuMotion.Frame){
            contextMotion=next
            renderMaterial(next.materialAlpha)
            shade.alpha=next.materialAlpha
            content.alpha=next.contentAlpha
            replica.alpha=sourceAlpha*next.rowAlpha
            updateBounds()
        }

        fun present(opacity:Float,strain:Float,frame:PreviewCardMotion.Frame?,dragOffset:Float,dragScale:Float){
            contextMotion=null
            if(contentClipped){
                content.clipBounds=null
                contentClipped=false
            }
            this.strain=strain
            this.frame=frame
            this.dragOffset=dragOffset
            this.dragScale=dragScale
            renderMaterial(opacity)
            shade.alpha=opacity
            content.alpha=opacity
            if(frame!=null){
                replica.alpha=sourceAlpha*frame.rowAlpha
                val lifted=sourceScale+(1.045f-sourceScale)*frame.position
                rowScale=lifted+(1f-lifted)*frame.rowSettling
                replica.pivotX=0f
                replica.pivotY=0f
                replica.scaleX=rowScale
                replica.scaleY=rowScale
            }
            updateBounds()
        }

        private fun renderMaterial(opacity: Float) {
            legacyMaterial?.updateTransition(opacity, 1f, 1f)
                ?: (material as CouiPopupBackground).renderProgress(opacity)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            val original = legacyMaterial ?: return
            original.background = ColorDrawable(colorOsPopupFallback(dark))
            original.syncTransitionAlpha()
            if (couiMaterialEnabled(context)) original.doOnPreDraw {
                applyOplusPopupBlur(original, dark, density)
            }
        }

        private fun updateBounds(){
            if(width<=0 || height<=0)return
            val start=anchorBounds
            contextMotion?.let {motion->
                if(start!=null)drawMorphedBounds(start,motion)
                return
            }
            val motion=frame
            var centerX=width*.5f
            var centerY=height*.5f
            var scaleX=1f-.54f*strain
            var scaleY=1f+strain
            if(start!=null && motion!=null){
                val travelX=start.exactCenterX()-centerX
                val travelY=start.exactCenterY()-centerY
                val travel=hypot(travelX,travelY)
                val beyond=motion.overrun-motion.lift*122f*density
                centerX+=travelX*(1f-motion.position)+if(travel>=1f)beyond*travelX/travel else 0f
                centerY+=travelY*(1f-motion.position)+if(travel>=1f)beyond*travelY/travel else beyond*rowDirection
                centerY+=dragOffset
                scaleX=motion.scale*dragScale
                scaleY=scaleX
            }
            val left=centerX-width*scaleX*.5f
            val top=centerY-height*scaleY*.5f
            transform(material,left,top,scaleX,scaleY)
            transform(shade,left,top,scaleX,scaleY)
            menuOffset=centerY+height*scaleY*.5f-height
            if(motion!=null)transform(content,left,top,scaleX,scaleY)
            val source=sourceRow
            if(start!=null && source!=null && motion!=null){
                val rowCenterX=start.exactCenterX()+(centerX-start.exactCenterX())*motion.rowFollowsCard
                val rowCenterY=start.exactCenterY()+(centerY-start.exactCenterY())*motion.rowFollowsCard+motion.rowOffsetY
                replica.translationX=rowCenterX-source.width*rowScale*.5f-start.left
                replica.translationY=rowCenterY-source.height*rowScale*.5f-start.top
            }
        }

        private fun drawMorphedBounds(start:Rect,motion:ContextMenuMotion.Frame){
            val source=sourceRow ?: return
            val morph=motion.shapeProgress
            val lift=1f+(sourceScale-1f)*motion.rowLift
            val rowWidth=source.width*lift
            val rowHeight=source.height*lift
            val restX=start.exactCenterX()+(source.width*.5f-source.pivotX)*(1f-sourceScale)
            val restY=start.exactCenterY()+(source.height*.5f-source.pivotY)*(1f-sourceScale)
            val rowX=restX+(start.exactCenterX()-restX)*motion.rowLift
            val rowY=restY+(start.exactCenterY()-restY)*motion.rowLift
            val centerX=rowX+(width*.5f-rowX)*motion.position+motion.offsetX
            val centerY=rowY+(height*.5f-rowY)*motion.position+motion.offsetY
            val surfaceWidth=(rowWidth+(width-rowWidth)*morph)*motion.scale
            val surfaceHeight=(rowHeight+(height-rowHeight)*morph)*motion.scale
            val left=centerX-surfaceWidth*.5f
            val top=centerY-surfaceHeight*.5f
            val bottom=centerY+surfaceHeight*.5f
            val pixelWidth=surfaceWidth.roundToInt().coerceAtLeast(1)
            val pixelHeight=surfaceHeight.roundToInt().coerceAtLeast(1)
            material.layout(0,0,pixelWidth,pixelHeight)
            shade.layout(0,0,pixelWidth,pixelHeight)
            transform(material,left,top,1f,1f)
            transform(shade,left,top,1f,1f)
            menuOffset=bottom-height
            val contentScale=(surfaceWidth/width).coerceAtLeast(.01f)
            transform(content,left,top,contentScale,contentScale)
            val clipHeight=(surfaceHeight/contentScale).roundToInt().coerceIn(0,height)
            if(clipHeight==height){
                if(contentClipped)content.clipBounds=null
                contentClipped=false
            }else if(!contentClipped || contentClip.bottom!=clipHeight || contentClip.right!=width){
                contentClip.set(0,0,width,clipHeight)
                content.clipBounds=contentClip
                contentClipped=true
            }
            rowScale=minOf(rowWidth,surfaceWidth)/source.width
            val titleCenter=25f*density
            val replicaCenterY=top+(rowHeight*.5f+(titleCenter-rowHeight*.5f)*morph)*motion.scale
            transform(replica,centerX-source.width*rowScale*.5f-start.left,
                replicaCenterY-source.height*rowScale*.5f-start.top,rowScale,rowScale)
        }

        private fun transform(view:View,x:Float,y:Float,sx:Float,sy:Float){
            view.pivotX=0f
            view.pivotY=0f
            view.translationX=x
            view.translationY=y
            view.scaleX=sx
            view.scaleY=sy
        }

    }

    private class RowReplica(context:Context):View(context) {
        private var snapshot:RenderNode?=null

        init {
            alpha=0f
            setWillNotDraw(false)
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun capture(source:View):Boolean {
            if(!source.isHardwareAccelerated)return false
            snapshot=runCatching {
                RenderNode("CPI dialog anchor").apply {
                    setPosition(0,0,source.width,source.height)
                    val canvas=beginRecording()
                    try { source.draw(canvas) } finally { endRecording() }
                }
            }.getOrNull()
            invalidate()
            return snapshot?.hasDisplayList()==true
        }

        override fun onDraw(canvas:Canvas){
            if(canvas.isHardwareAccelerated)snapshot?.let(canvas::drawRenderNode)
        }

        override fun onDetachedFromWindow(){
            snapshot?.discardDisplayList()
            snapshot=null
            super.onDetachedFromWindow()
        }
    }
}

internal class CouiCardEdgeShade(dark:Boolean,density:Float):Drawable(){
    private val fade=32f*density
    private val matrix=Matrix()
    private val shader=LinearGradient(0f,0f,0f,1f,
        intArrayOf(if(dark)0x26000000 else 0x16000000,Color.TRANSPARENT,Color.TRANSPARENT,
            if(dark)0x38000000 else 0x20000000),
        floatArrayOf(0f,.135f,.83f,1f),Shader.TileMode.CLAMP)
    private val paint=Paint().apply {shader=this@CouiCardEdgeShade.shader}

    override fun draw(canvas:Canvas){
        val span=bounds.height().toFloat()
        if(span<=0f)return
        val saved=canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        val width=bounds.width().toFloat()
        val start=minOf(fade,span*.135f)
        val end=minOf(fade,span*.17f)
        matrix.setScale(1f,start/.135f)
        shader.setLocalMatrix(matrix)
        canvas.drawRect(0f,0f,width,start,paint)
        val scale=end/.17f
        matrix.setScale(1f,scale)
        matrix.postTranslate(0f,span-scale)
        shader.setLocalMatrix(matrix)
        canvas.drawRect(0f,span-end,width,span,paint)
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha:Int){
        paint.alpha=alpha
        invalidateSelf()
    }
    override fun setColorFilter(colorFilter:ColorFilter?){
        paint.colorFilter=colorFilter
        invalidateSelf()
    }
    @Deprecated("Drawable alpha is provided by the paints")
    override fun getOpacity()=PixelFormat.TRANSLUCENT
}
