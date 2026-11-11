package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.*
import java.util.WeakHashMap

/** Draws behind hotseat children without replacing OEM drawables or intercepting touch. */
internal object DockMaterial {
    private class Surface(view:ViewGroup) : android.view.ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        val owner=java.lang.ref.WeakReference(view)
        private var host:java.lang.ref.WeakReference<ViewGroup>?=null
        private var branch:java.lang.ref.WeakReference<View>?=null
        val matrix=Matrix()
        private var unclippedNode:RenderNode?=null
        private var originalClip=true

        fun useNativeLayer(view:View):Boolean {
            val node=renderNode(view) ?: return false
            if(unclippedNode!==node){
                restoreClip()
                originalClip=node.clipToBounds
                unclippedNode=node
            }
            node.setClipToBounds(false)
            return true
        }

        fun restoreClip(){
            unclippedNode?.let {it.setClipToBounds(originalClip)}
            unclippedNode=null
        }

        fun hostView()=host?.get()
        fun branchView()=branch?.get()

        fun placeIn(parent:ViewGroup?,child:View?){
            if(hostView()===parent && branchView()===child)return
            branchView()?.let {if(hosted[it]===this)hosted.remove(it)}
            hostView()?.postInvalidateOnAnimation()
            host=parent?.let {java.lang.ref.WeakReference(it)}
            branch=child?.let {java.lang.ref.WeakReference(it)}
            if(parent!=null && child!=null){
                hosted[child]=this
                parent.postInvalidateOnAnimation()
            }
        }
        val identity=ColorDrawable(Color.TRANSPARENT)
        val bounds=Rect()
        var dirty=true
        var drawing=false
        var style:GlassStyle?=null

        init {
            view.addOnAttachStateChangeListener(this)
            view.viewTreeObserver.addOnGlobalLayoutListener(this)
        }
        override fun onGlobalLayout(){
            dirty=true
            if(enabled() && com.cosmod.launcherglass.util.LiveBlurEngine.isUnlockMotion)
                owner.get()?.let {view->safely {refreshGeometry(view,this)}}
        }
        override fun onViewAttachedToWindow(view:View) = Unit
        override fun onViewDetachedFromWindow(view:View){
            view.viewTreeObserver.removeOnGlobalLayoutListener(this)
            view.removeOnAttachStateChangeListener(this)
            restoreClip()
            placeIn(null,null)
            surfaces.remove(view)
        }
        fun refresh(){
            dirty=true
            style=null
            if(!enabled()){
                restoreClip()
                placeIn(null,null)
            }
            owner.get()?.postInvalidateOnAnimation()
            hostView()?.postInvalidateOnAnimation()
        }
    }

    private val surfaces=WeakHashMap<View,Surface>()
    private val hosted=WeakHashMap<View,Surface>()
    private data class UnlockTuning(val speed:Float=1f,val softness:Float=0f,val travel:Float=1f,
        val impulse:Float=0f,val blurDp:Int=0)
    @Volatile private var unlock=UnlockTuning()
    private var unlockBlur:android.animation.ValueAnimator?=null
    private val nativeAlpha=WeakHashMap<View,Float>()
    private val nativeTranslation=WeakHashMap<View,Float>()
    private val renderNodeField by lazy {
        runCatching {View::class.java.getDeclaredField("mRenderNode").apply {isAccessible=true}}.getOrNull()
    }

    private fun renderNode(view:View)=runCatching {renderNodeField?.get(view) as? RenderNode}.getOrNull()
    private fun renderAlpha(view:View):Float=renderNode(view)?.alpha ?: view.alpha

    fun updateUnlock(prefs:android.os.Bundle){
        val key=com.cosmod.launcherglass.ui.LockscreenSettings
        unlock=if(prefs.getBoolean(key.UNLOCK_ENABLED)){
            UnlockTuning(prefs.getInt(key.UNLOCK_SPEED,100).coerceIn(key.MIN_UNLOCK_SPEED,key.MAX_UNLOCK_SPEED)*.01f,
                prefs.getInt(key.UNLOCK_SMOOTHNESS,0).coerceIn(0,100)*.01f,
                prefs.getInt(key.UNLOCK_TRAVEL,100).coerceIn(0,150)*.01f,
                prefs.getInt(key.UNLOCK_IMPULSE,0).coerceIn(0,100)*.01f,
                prefs.getInt(key.UNLOCK_BLUR,0).coerceIn(0,key.MAX_UNLOCK_BLUR))
        }else UnlockTuning()
        if(unlock.blurDp==0)stopUnlockBlur()
    }

    fun stopUnlockBlur(){
        unlockBlur?.cancel()
    }

    private fun addUnlockBlur(animation:android.animation.Animator,views:List<View>){
        val tuning=unlock
        if(!PrefsHelper.enabled || tuning.blurDp==0 || views.isEmpty() ||
            !android.animation.ValueAnimator.areAnimatorsEnabled())return
        animation.addListener(object:android.animation.AnimatorListenerAdapter(){
            private var blur:android.animation.ValueAnimator?=null
            override fun onAnimationStart(animation:android.animation.Animator){
                stopUnlockBlur()
                val targets=views.filter {it.isAttachedToWindow && it.isHardwareAccelerated}
                if(targets.isEmpty())return
                val density=targets.first().resources.displayMetrics.density
                val levels=12
                val padding=kotlin.math.ceil(tuning.blurDp*density*3f).toInt()
                val effects=arrayOfNulls<RenderEffect>(levels+1)
                var current=-1
                fun apply(step:Int){
                    if(step==current)return
                    current=step
                    val effect=if(step==0)null else effects[step] ?: run {
                        val radius=(tuning.blurDp*density*step/levels).coerceAtLeast(.1f)
                        RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.DECAL).also {effects[step]=it}
                    }
                    UnlockElementBlur.apply(targets,effect,padding)
                }
                val animator=android.animation.ValueAnimator.ofFloat(1f,0f).apply {
                    duration=(360f/tuning.speed).toLong()
                    interpolator=android.view.animation.PathInterpolator(.25f,.1f,.35f,1f)
                    addUpdateListener {apply(kotlin.math.round((it.animatedValue as Float)*levels).toInt().coerceIn(0,levels))}
                    addListener(object:android.animation.AnimatorListenerAdapter(){
                        override fun onAnimationEnd(animation:android.animation.Animator){
                            apply(0)
                            if(unlockBlur===animation)unlockBlur=null
                        }
                    })
                }
                blur=animator
                unlockBlur=animator
                apply(levels)
                animator.start()
            }
            override fun onAnimationEnd(animation:android.animation.Animator){blur?.cancel();blur=null}
        })
    }

    private val pendingUnlockVelocity=ThreadLocal<Float>()
    private fun hookUnlockVelocity(loader:ClassLoader):Boolean=runCatching {
        val spring=XposedHelpers.findClass(
            "com.coui.appcompat.animation.dynamicanimation.COUIDynamicAnimation",loader)
        val float=Float::class.javaPrimitiveType!!
        val startValue=spring.getDeclaredMethod("m",float)
        val startVelocity=spring.getDeclaredMethod("n",float).apply {isAccessible=true}
        ModuleHooks.hookMethod(startValue,object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                val velocity=pendingUnlockVelocity.get() ?: return
                if(!param.hasThrowable())runCatching {startVelocity.invoke(param.thisObject,velocity)}
            }
        })
        true
    }.getOrDefault(false)

    private fun hookUnlockHelper(type:Class<*>,loader:ClassLoader){
        val impulseSupported=hookUnlockVelocity(loader)
        UnlockElementBlur.install(loader)
        type.declaredMethods.firstOrNull {it.name=="d" && it.parameterCount==7 &&
            it.returnType.name=="com.android.launcher3.stack.view.edit.StackEditAnimationWrapper"}?.let {method->
            ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    if(!PrefsHelper.enabled)return
                    val tuning=unlock
                    val bounce=param.args[4] as? Float ?: return
                    val period=param.args[5] as? Float ?: return
                    val holder=param.args[1]?.javaClass?.simpleName.orEmpty()
                    val translation=holder.contains("TranslationYHolder")
                    val motion=translation || holder.contains("ItemPropertyHolder")
                    val speed=tuning.speed*(if(motion)1f+.35f*tuning.impulse else 1f)
                    if(speed!=1f && period>=0f && period.isFinite()){
                        param.args[5]=((if(period==0f)1f else period)/speed).coerceIn(.12f,2f)
                    }
                    if(tuning.softness>0f && bounce in 0f..1f)param.args[4]=bounce*(1f-tuning.softness)
                    if(motion){
                        val initial=param.args[2] as? Float ?: return
                        val end=param.args[3] as? Float ?: return
                        if(!initial.isFinite() || !end.isFinite())return
                        val travel=(initial-end)*(if(translation)tuning.travel else 1f)
                        if(translation && tuning.travel!=1f)param.args[2]=end+travel
                        if(impulseSupported && tuning.impulse>0f && kotlin.math.abs(travel)>.1f){
                            val density=(param.args[0] as? View)?.resources?.displayMetrics?.density ?: 1f
                            val kick=(kotlin.math.abs(travel)*8f).coerceAtMost(if(translation)680f*density else 8f)*tuning.impulse
                            // Argument 6 is the OEM's minimum visible change. The
                            // velocity belongs to the newly constructed spring.
                            param.setObjectExtra("cpiUnlockImpulse",true)
                            pendingUnlockVelocity.set(-kotlin.math.sign(travel)*kick)
                        }
                    }
                }
                override fun afterHookedMethod(param:MethodHookParam){
                    if(param.getObjectExtra("cpiUnlockImpulse")==true)pendingUnlockVelocity.remove()
                }
            })
        }
        for((holder,alpha) in listOf("getAsyncAlphaHolder" to true,"getAsyncTranslationYHolder" to false)){
            val name=type.name+"$"+holder+"$"+"1"
            val cls=XposedHelpers.findClassIfExists(name,loader) ?: continue
            val views=cls.declaredFields.firstOrNull {it.type.componentType==View::class.java}?.apply {isAccessible=true} ?: continue
            val set=cls.getDeclaredMethod("setValue",Float::class.javaPrimitiveType)
            ModuleHooks.hookMethod(set,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    if(param.hasThrowable() || !enabled())return
                    val value=(param.args[0] as? Float)?.takeIf {it.isFinite()} ?: return
                    val children=views.get(param.thisObject) as? Array<*> ?: return
                    val dock=children.firstOrNull {it is View && it.javaClass.simpleName.endsWith("Hotseat")} as? View ?: return
                    fun changed(){
                        val values=if(alpha)nativeAlpha else nativeTranslation
                        values[dock]=value
                        surfaces[dock]?.hostView()?.invalidate()
                    }
                    if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())changed()else dock.post {changed()}
                }
            })
        }
    }
    private var failureLogged=false
    private val resourceNames=HashMap<Int,String>()
    private val dockIds=setOf("hotseat","hotseat_layout","dockbar","dock_bar")
    fun onSettingsChanged(){surfaces.values.toList().forEach(Surface::refresh)}
    private val dockClasses=WeakHashMap<Class<*>,Boolean>()
    private fun candidate(view:View):Boolean {
        if(dockClasses.getOrPut(view.javaClass) {
            generateSequence(view.javaClass as Class<*>?){it.superclass}.any {it.name in names}
        })return true
        if(view.id==View.NO_ID)return false
        val id=resourceNames.getOrPut(view.id){try {view.resources.getResourceEntryName(view.id)}catch(_:Exception){""}}
        return id in dockIds
    }
    private fun isDock(view:View):Boolean {
        if(!candidate(view))return false
        var parent=view.parent
        while(parent is View){if(candidate(parent))return false;parent=(parent as View).parent}
        return true
    }
    private val names=listOf("com.android.launcher3.Hotseat","com.android.launcher3.OplusHotseat",
        "com.oplus.launcher.Hotseat","com.android.launcher.Hotseat")

    // The hotseat itself can include the search bar/navigation padding. Prefer its icon container.
    private fun iconContainer(view:ViewGroup):ViewGroup? {
        if(view.javaClass.simpleName.contains("ShortcutAndWidgetContainer"))return view
        for(i in 0 until view.childCount){
            val child=view.getChildAt(i) as? ViewGroup ?: continue
            iconContainer(child)?.let {return it}
        }
        return null
    }
    private fun footprint(view:ViewGroup,union:Rect) {
        union.setEmpty()
        fun include(child:View){
            // Launcher cells include empty gutters and label space. Enclose the visible
            // top drawable instead, otherwise a four-icon dock spans the whole screen.
            val drawable=(child as? android.widget.TextView)?.compoundDrawables?.getOrNull(1)
            val size=drawable?.bounds
            val rect=if(size!=null && size.width()>0 && size.height()>0){
                val left=(child.width-size.width())/2
                Rect(left,child.paddingTop,left+size.width(),child.paddingTop+size.height())
            }else Rect(child.paddingLeft,child.paddingTop,child.width-child.paddingRight,child.height-child.paddingBottom)
            view.offsetDescendantRectToMyCoords(child,rect)
            union.union(rect)
        }
        fun collect(parent:ViewGroup,depth:Int){
            if(depth>12)return
            for(i in 0 until parent.childCount){
                val child=parent.getChildAt(i)
                if(child.visibility!=View.VISIBLE || child.width<=0 || child.height<=0)continue
                val icon=generateSequence(child.javaClass as Class<*>?){it.superclass}.any {
                    it.simpleName.endsWith("BubbleTextView") || it.simpleName.endsWith("FolderIcon")
                }
                if(icon)include(child)else if(child is ViewGroup)collect(child,depth+1)
            }
        }
        val container=iconContainer(view)
        if(container!=null){
            for(i in 0 until container.childCount){
                val child=container.getChildAt(i)
                if(child.visibility==View.VISIBLE && child.width>0 && child.height>0)include(child)
            }
        }else collect(view,0)
        if(union.isEmpty)return
        val density=view.resources.displayMetrics.density
        val availableWidth=view.width-(16*density).toInt()
        if(availableWidth<1 || view.height<1){union.setEmpty();return}
        val baseWidth=union.width()+((12+2*PrefsHelper.dockInset)*density).toInt()
        val baseHeight=union.height()+(20*density).toInt()
        val width=(baseWidth*PrefsHelper.dockWidth/100).coerceIn(1,availableWidth)
        val baseline=baseHeight.coerceIn(1,view.height)
        val height=(baseline*PrefsHelper.dockHeight/100).coerceAtLeast(1)
        val margin=(8*density).toInt()
        val left=(union.centerX()-width/2).coerceIn(margin,view.width-margin-width)
        val baselineTop=(union.centerY()-baseline/2).coerceIn(0,view.height-baseline)
        val top=baselineTop+(baseline-height)/2
        union.set(left,top,left+width,top+height)
    }

    private fun refreshGeometry(view:ViewGroup,state:Surface){
        if(!state.dirty)return
        footprint(view,state.bounds)
        state.dirty=false
        if(state.bounds.top<0 || state.bounds.bottom>view.height){
            // Keep the plate in the native RenderNode so RenderThread alpha
            // and translation are identical to the hotseat during folder transitions.
            if(state.useNativeLayer(view)){
                state.placeIn(null,null)
                return
            }
            // Older builds can fall back to the nearest layer that fits both edges.
            // If the plate reaches the window edge, let the window clip it.
            val relative=Rect()
            var branch:View=view
            var parent=branch.parent as? ViewGroup
            var host=parent
            while(parent!=null){
                host=parent
                relative.set(state.bounds)
                parent.offsetDescendantRectToMyCoords(view,relative)
                if(relative.top>=0 && relative.bottom<=parent.height)break
                val enclosing=parent.parent as? ViewGroup ?: break
                branch=parent
                parent=enclosing
            }
            if(host!=null){
                state.placeIn(host,branch)
                return
            }
        }
        state.restoreClip()
        state.placeIn(null,null)
    }

    private fun enabled()=PrefsHelper.enabled && PrefsHelper.blurEnabled && PrefsHelper.dockStyle!=0

    private fun draw(canvas:Canvas,view:ViewGroup,state:Surface,alpha:Int=255){
        val bounds=state.bounds
        if(bounds.isEmpty || alpha<=0)return
        val radius=minOf(bounds.width(),bounds.height())*PrefsHelper.dockCorner/100f
        val style=state.style ?: GlassStyle(PrefsHelper.dockStyle==2,PrefsHelper.dockLiquidWidth,
            PrefsHelper.dockLiquidDistortion,PrefsHelper.dockBlur,0,null,
            plateColor=Color.WHITE,extendedLens=true,neutral=true,rimOpacity=0,volumeOpacity=0,
            surfaceScope=GlassSurface.DOCK,dispersion=PrefsHelper.dockDispersion).also {state.style=it}
        SurfaceMaterial.draw(canvas,bounds,view,state.identity,radius,decorate=true,style=style,alpha=alpha)
    }

    private inline fun safely(action:()->Unit){
        try {action()}catch(t:Throwable){
            if(!failureLogged){failureLogged=true;GlassLogger.e("DockMaterial","Dock material failed",t)}
        }
    }

    fun install(loader:ClassLoader){
        val methods=HashSet<java.lang.reflect.Method>()
        val hook=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? ViewGroup ?: return
                val state=surfaces[view] ?: run {
                    if(!isDock(view) || !view.isAttachedToWindow)return
                    Surface(view).also {surfaces[view]=it}
                }
                if(state.drawing || !enabled())return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                safely {
                    refreshGeometry(view,state)
                    if(state.bounds.isEmpty || state.hostView()!=null)return@safely
                    state.drawing=true
                    param.setObjectExtra("cpiDockDrawing",state)
                    draw(canvas,view,state)
                }
            }
            override fun afterHookedMethod(param:MethodHookParam){
                (param.getObjectExtra("cpiDockDrawing") as? Surface)?.drawing=false
            }
        }
        runCatching {
            val unlockHelper=XposedHelpers.findClassIfExists("com.android.keyguardservice.UnlockAnimHelper",loader)
            unlockHelper?.declaredMethods
                ?.firstOrNull {it.name=="e" && it.parameterCount==3 &&
                    it.returnType.name=="com.android.launcher3.stack.view.edit.StackEditAnimationSet"}?.let {method->
                    ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                        override fun beforeHookedMethod(param:MethodHookParam){
                            nativeAlpha.clear()
                            nativeTranslation.clear()
                            com.cosmod.launcherglass.util.LiveBlurEngine.onUnlockMotionStarted()
                        }
                        override fun afterHookedMethod(param:MethodHookParam){
                            if(param.hasThrowable() || unlock.blurDp==0)return
                            val animation=param.result as? android.animation.Animator ?: return
                            addUnlockBlur(animation,param.args.filterIsInstance<View>().distinct())
                        }
                    })
                }
            if(unlockHelper!=null)hookUnlockHelper(unlockHelper,loader)
        }.onFailure {GlassLogger.limited("DockMaterial","unlockHook"){"Native unlock hook unavailable: "+it}}
        for(name in names){
            var cls:Class<*>?=XposedHelpers.findClassIfExists(name,loader)
            while(cls!=null){
                val method=cls.declaredMethods.firstOrNull {it.name=="dispatchDraw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))}
                if(method!=null){if(methods.add(method))ModuleHooks.hookMethod(method,hook);break}
                cls=cls.superclass
            }
        }
        // OEM revisions sometimes replace Hotseat with a generic container bearing the
        // same resource ID. This process-local fallback filters before any drawing.
        val fallback=ViewGroup::class.java.getDeclaredMethod("dispatchDraw",Canvas::class.java)
        if(methods.add(fallback))ModuleHooks.hookMethod(fallback,hook)
        val drawChild=ViewGroup::class.java.getDeclaredMethod("drawChild",Canvas::class.java,View::class.java,Long::class.javaPrimitiveType)
        ModuleHooks.hookMethod(drawChild,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val branch=param.args[1] as? View ?: return
                val state=hosted[branch] ?: return
                if(!enabled())return
                val host=param.thisObject as? ViewGroup ?: return
                if(state.hostView()!==host)return
                val view=state.owner.get()?.takeIf {it.isShown} ?: return
                safely {
                    refreshGeometry(view,state)
                    if(state.hostView()!==host)return@safely
                    val canvas=param.args[0] as Canvas
                    var opacity=1f
                    var node:View?=view
                    while(node!=null && node!==host){
                        opacity*=if(node===view && com.cosmod.launcherglass.util.LiveBlurEngine.isUnlockMotion)
                            nativeAlpha[view]?.coerceIn(0f,1f) ?: renderAlpha(node) else renderAlpha(node)
                        node=node.parent as? View
                    }
                    if(node!==host)return@safely
                    val save=canvas.save()
                    try {
                        state.matrix.reset()
                        view.transformMatrixToGlobal(state.matrix)
                        host.transformMatrixToLocal(state.matrix)
                        if(com.cosmod.launcherglass.util.LiveBlurEngine.isUnlockMotion){
                            nativeTranslation[view]?.let {state.matrix.postTranslate(0f,it-view.translationY)}
                        }
                        canvas.concat(state.matrix)
                        draw(canvas,view,state,(opacity*255).toInt())
                    }finally{canvas.restoreToCount(save)}
                }
            }
        })
        GlassLogger.i("DockMaterial","Installed ${methods.size} hotseat draw hooks")
    }
}
