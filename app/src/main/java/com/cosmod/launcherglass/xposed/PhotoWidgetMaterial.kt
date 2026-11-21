package com.cosmod.launcherglass.xposed

import android.appwidget.AppWidgetHostView
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.*
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Wallpaper glass below remote photo content; contour above; no volume. */
internal object PhotoWidgetMaterial {
    private val previewCapture=ThreadLocal<Boolean>()
    internal val capturingPreview get()=previewCapture.get()==true

    internal fun snapshotPreview(kind:com.cosmod.launcherglass.util.WidgetPreviewKind):com.cosmod.launcherglass.ui.NativePreviewScenes.Scene? {
        check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
        val candidates=linkedSetOf<View>().apply {addAll(hosts.keys.weakSnapshot())}
        val queue=java.util.ArrayDeque<View>()
        IconGlassHook.registeredViews().mapNotNull {it.get()?.rootView}.distinct().forEach(queue::add)
        var visited=0
        while(queue.isNotEmpty() && visited++<6000){
            val view=queue.removeFirst()
            if(view is AppWidgetHostView || isCard(view))candidates.add(view)
            if(view is ViewGroup)for(i in 0 until view.childCount)queue.add(view.getChildAt(i))
        }
        val recognized=linkedSetOf<com.cosmod.launcherglass.util.WidgetPreviewKind>()
        for(host in candidates){
            if(!host.isAttachedToWindow || host.width<=0 || host.height<=0)continue
            val native=content(host)
            val captions=StringBuilder().append(host.contentDescription?.toString().orEmpty())
            // TitleCard's name can be a cached view outside its active smart subtree.
            for(method in listOf("getCardName","getLauncherCardName")){
                val label=NativeIconAccess.call(host,method) ?: continue
                captions.append(' ').append(NativeIconAccess.call(label,"getText"))
            }
            val metadata=buildString {
                append(host.javaClass.name);append(' ');append(native.javaClass.name);append(' ');append(native.context.packageName)
                val components=java.util.ArrayDeque<View>().apply {add(host)}
                var inspected=0
                while(components.isNotEmpty() && inspected++<64){
                    val component=components.removeFirst()
                    if(component.id!=View.NO_ID)runCatching {component.resources.getResourceEntryName(component.id)}
                        .getOrNull()?.let {append(' ');append(it)}
                    if(component !is android.widget.TextView){append(' ');append(component.javaClass.name)}
                    else {
                        var parent:View?=component
                        while(parent!=null && parent!==native && parent!==host)parent=parent.parent as? View
                        if(native===host || parent!==native)captions.append(' ').append(component.text.take(160))
                    }
                    if(component is ViewGroup)for(i in 0 until component.childCount)components.add(component.getChildAt(i))
                }
                if(host is AppWidgetHostView)append(host.appWidgetInfo?.provider?.flattenToString())
                val info=NativeIconAccess.call(host,"getItemInfo") ?: host.tag
                if(info!=null){
                    append(' ');append(NativeIconAccess.call(info,"getTargetComponent"))
                    captions.append(' ').append(NativeIconAccess.field(info,"title"))
                    for(name in listOf("mProviderName","providerName","mPackageName","packageName","mCardName","cardName")){
                        append(' ');append(NativeIconAccess.field(info,name))
                    }
                }
            }
            if(host is AppWidgetHostView){
                val pkg=host.appWidgetInfo?.provider?.packageName ?: continue
                val flags=runCatching {host.context.packageManager.getApplicationInfo(pkg,0).flags}.getOrDefault(0)
                if(flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)==0)continue
            }
            val itemInfo = NativeIconAccess.call(host, "getItemInfo") ?: host.tag
            val spanX = (itemInfo?.let { NativeIconAccess.call(it, "getSpanX") } as? Number)?.toInt()
            val spanY = (itemInfo?.let { NativeIconAccess.call(it, "getSpanY") } as? Number)?.toInt()
            val cardBounds = NativeIconAccess.call(host, "getCardBounds") as? Rect
            val wide = when {
                spanX != null && spanY != null && spanX != spanY -> spanX > spanY
                cardBounds != null && !cardBounds.isEmpty -> cardBounds.width() > cardBounds.height() * 1.35f
                else -> host.width > host.height * 1.35f
            }
            val match=com.cosmod.launcherglass.util.WidgetPreviewKind.classify(metadata,wide,
                widgetType(host).photo,captions.toString())
            if(match==null)GlassLogger.limited("WidgetPreview","unclassified-${NativeIconAccess.call(host,"getCardType")}"){
                "Unclassified card=${NativeIconAccess.call(host,"getCardType")}, content=${native.javaClass.name}, meta=${metadata.take(1000)}"
            }
            if(match!=null)recognized.add(match)
            if(match!=kind)continue
            GlassLogger.limited("WidgetPreview","capture-${kind.name}"){
                "Capture ${kind.name}: ${host.javaClass.simpleName} ${native.width}x${native.height}"
            }
            fun invalidateTree(view:View){
                view.invalidate()
                if(view is ViewGroup)for(i in 0 until view.childCount)invalidateTree(view.getChildAt(i))
            }
            val scene=runCatching {
                previewCapture.set(true);invalidateTree(native)
                fun capture(draw:(Canvas)->Unit):Bitmap {
                    val scale=minOf(1f,560f/maxOf(native.width,native.height))
                    val node=RenderNode("CPI.nativeWidgetPreview").apply {
                        setPosition(0,0,(native.width*scale).toInt().coerceAtLeast(1),(native.height*scale).toInt().coerceAtLeast(1))
                    }
                    try {
                        val canvas=node.beginRecording()
                        try {canvas.scale(scale,scale);draw(canvas)}finally {node.endRecording()}
                        val hardware=XposedHelpers.callStaticMethod(HardwareRenderer::class.java,"createHardwareBitmap",node,node.width,node.height) as Bitmap
                        return try { requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888,false)) }
                        finally { hardware.recycle() }
                    }finally {node.discardDisplayList()}
                }
                val content=capture(native::draw)
                val layer=com.cosmod.launcherglass.util.WidgetTextLayer()
                val text=if (layer.record(native)) capture { layer.draw(it) } else null
                val logos=if (layer.record(native,preserveImages=true,includeText=false)) capture { layer.draw(it) } else null
                com.cosmod.launcherglass.ui.NativePreviewScenes.Scene(content,text,logos)
            }.onFailure {error->GlassLogger.limited("WidgetPreview",kind.name){"Native capture unavailable: $error"}}.getOrNull()
            previewCapture.remove();invalidateTree(native)
            if(scene!=null)return scene
        }
        GlassLogger.limited("WidgetPreview","missing-${kind.name}"){
            "No ${kind.name} source: hosts=${candidates.size}, recognized=${recognized.joinToString()}"
        }
        return null
    }

    private val identities=WeakHashMap<View,ColorDrawable>()
    private val hosts=WeakHashMap<View,Boolean>()
    private data class WidgetReturn(var startAfter: Long, var native: Boolean, var firstDrawAt: Long = -1L)
    private val returns=java.util.Collections.synchronizedMap(WeakHashMap<View,WidgetReturn>())
    private val transitionDraw=ThreadLocal<Boolean>()
    @Volatile private var launchHost: WeakReference<View>? = null
    private const val NATIVE_RETURN_WAIT_MS = 550L
    private const val HOME_RETURN_WAIT_MS = 90L
    private const val RETURN_FADE_MS = 420f
    fun onAppLaunched(source: View?) {
        val host = transitionHost(source)?.takeIf { styled(it, PrefsHelper.widgets) }
        if (host == null && source == null) return
        launchHost = host?.let(::WeakReference)
        if (host != null && returns.remove(host) != null) host.postInvalidateOnAnimation()
    }

    fun onHomeReturned() {
        val host = launchHost?.get()
        launchHost = null
        if (host != null) armReturn(host, HOME_RETURN_WAIT_MS, existing = false, native = false)
        onBackdropChanged()
    }

    private fun armReturn(source: View?, delay: Long, existing: Boolean, native: Boolean) {
        val host = transitionHost(source) ?: return
        if (!styled(host, PrefsHelper.widgets) || !android.animation.ValueAnimator.areAnimatorsEnabled()) return
        val time = SystemClock.uptimeMillis() + delay
        synchronized(returns) {
            val state = returns[host]
            if (state == null) {
                if (existing) return
                returns[host] = WidgetReturn(time, native)
            } else if (native && state.firstDrawAt < 0L) {
                state.native = true
                state.startAfter = if (delay == 0L) minOf(state.startAfter, time)
                    else maxOf(state.startAfter, time)
            }
        }
        if (native && delay == 0L && launchHost?.get() === host) launchHost = null
        host.postInvalidateOnAnimation()
    }

    private fun returnProgress(view:View,canvas:Canvas):Float {
        if(transitionDraw.get()==true || !canvas.isHardwareAccelerated)return 1f
        val visible=view.isShown && view.windowVisibility==View.VISIBLE && view.alpha>.01f
        val focused = view.hasWindowFocus()
        val now=SystemClock.uptimeMillis()
        var needsFrame = false
        val progress = synchronized(returns) {
            val state=returns[view] ?: return 1f
            if (!visible || !state.native && !focused) 0f else {
                needsFrame = true
                if (now < state.startAfter) return@synchronized 0f
                if(state.firstDrawAt<0L)state.firstDrawAt=now
                ((now-state.firstDrawAt)/RETURN_FADE_MS).coerceIn(0f,1f).also {
                    if(it>=1f)returns.remove(view)
                }
            }
        }
        if(needsFrame && progress<1f)view.postInvalidateOnAnimation()
        return progress*progress*(3f-2f*progress)
    }
    fun onBackdropChanged(){
        for(view in hosts.keys.weakSnapshot())if(hosts[view]==true)view.postInvalidateOnAnimation()
    }
    fun onLauncherFocused() {
        synchronized(returns) {
            for ((view, state) in returns) {
                if (!state.native && state.firstDrawAt < 0L && view.hasWindowFocus())
                    view.postInvalidateOnAnimation()
            }
        }
    }
    fun onSettingsChanged(){
        PhotoFade.clear()
        hosts.keys.weakSnapshot().forEach {host->content(host).invalidate();host.postInvalidateOnAnimation()}
        if(!PrefsHelper.enabled || !PrefsHelper.widgetGlassEnabled){
            launchHost = null
            returns.clear()
            for((root,listener) in roots.strongSnapshot())if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnPreDrawListener(listener)
            roots.clear();stamps.clear()
        }
    }
    private fun style(settings:WidgetMaterialSettings)=GlassStyle(
        settings.liquid,settings.width,settings.distortion,settings.radius,settings.glassOpacity,settings.tintFilter,
        plateColor=Color.WHITE,extendedLens=true,neutral=true,surfaceScope=GlassSurface.WIDGETS,dispersion=settings.dispersion
    )
    private val logged=HashSet<String>()
    private data class WidgetType(val photo:Boolean=false,val clock:Boolean=false,val search:Boolean=false)
    private data class PhotoHost(val info:java.lang.ref.WeakReference<Any>,val type:WidgetType)
    private val photoHosts=WeakHashMap<View,PhotoHost>()
    private val cardClasses=HashMap<Class<*>,Boolean>()
    private val contentPaint=ThreadLocal.withInitial {Paint()}
    private val elementLayers=WeakHashMap<View,com.cosmod.launcherglass.util.WidgetTextLayer>()
    private val captionLayers=WeakHashMap<View,com.cosmod.launcherglass.util.WidgetTextLayer>()
    private val tintPaint=Paint()
    private fun isCard(view:View)=cardClasses.getOrPut(view.javaClass){
        generateSequence(view.javaClass as Class<*>?){it.superclass}.any {it.name=="com.android.launcher3.card.LauncherCardView"}
    }
    fun transitionHost(source:View?):View? {
        var view=source
        repeat(8) {
            val candidate=view ?: return null
            if(candidate is AppWidgetHostView || isCard(candidate) ||
                candidate.javaClass.name.startsWith("com.android.launcher3.inspirationclock."))return candidate
            view=candidate.parent as? View
        }
        return null
    }
    private val drawMethods=HashSet<java.lang.reflect.Method>()
    private val drawing=ThreadLocal<MutableSet<View>>()
    private val roots=WeakHashMap<View,android.view.ViewTreeObserver.OnPreDrawListener>()
    private val stamps=WeakHashMap<View,Int>()
    private fun content(view:View):View {
        val child=if(view is AppWidgetHostView)view.getChildAt(0)
            else runCatching { NativeIconAccess.call(view, "getSmartView") as? View }.getOrNull()
        // Global Gallery can draw mSnapshot before its smart subtree is laid out.
        return child?.takeIf {it.width>0 && it.height>0} ?: view
    }
    private fun transform(host: View, child: View, matrix: Matrix = Matrix()) = matrix.apply {
        reset()
        if(child!==host){setTranslate((child.left-host.scrollX).toFloat(),(child.top-host.scrollY).toFloat());preConcat(child.matrix)}
        if(host !is AppWidgetHostView)try {
            // Match clipSmoothRoundCorner's mask transform, including the OEM
            // press/hover spring, which is not necessarily a View scale property.
            val bounds = NativeIconAccess.call(host, "getCardBounds") as? Rect ?: return@apply
            reset()
            if(child!==host)setTranslate(bounds.left.toFloat(),bounds.top.toFloat())
            if(NativeIconAccess.call(host,"hasHoverFlag")==true && child!==host){
                val manager=NativeIconAccess.call(host,"getFocusFrameAnimManager")
                if(manager!=null)postConcat(XposedHelpers.callMethod(manager,"getHoverBitmapMatrix",RectF(bounds)) as Matrix)
            }
        }catch(_:Throwable){}
    }
    private fun watch(view:View){
        IconGlassHook.registerMaterialView(view)
        if(view is AppWidgetHostView)return
        val root=view.rootView
        if(roots.containsKey(root))return
        val weak=java.lang.ref.WeakReference(root)
        val matrix = Matrix()
        val values = FloatArray(9)
        val listener=android.view.ViewTreeObserver.OnPreDrawListener {
            val current=weak.get()
            if(current!=null && PrefsHelper.enabled && PrefsHelper.widgetGlassEnabled)for(host in hosts.keys.weakSnapshot()){
                if(hosts[host]!=true || host is AppWidgetHostView || host.rootView!==current || !host.isShown)continue
                val child=content(host)
                transform(host, child, matrix).getValues(values)
                val stamp=31*(31*values.contentHashCode()+child.width)+child.height
                if(stamps.put(host,stamp)!=stamp)host.invalidate()
            }
            true
        }
        roots[root]=listener;root.viewTreeObserver.addOnPreDrawListener(listener)
    }
    private fun widgetType(view:View):WidgetType {
        if(view is AppWidgetHostView){
            val info=view.appWidgetInfo ?: return WidgetType()
            photoHosts[view]?.takeIf {it.info.get()===info}?.let {return it.type}
            val provider=info.provider
            val type=WidgetType(PhotoProviders.supports(provider?.packageName,provider?.className,null,false),
                PhotoProviders.isClock(provider?.packageName,provider?.className),
                com.cosmod.launcherglass.util.SearchWidgetPolicy.googleSearch(provider?.packageName,provider?.className))
            photoHosts[view]=PhotoHost(java.lang.ref.WeakReference(info),type)
            return type
        }
        val info=view.tag
        if(info!=null)photoHosts[view]?.takeIf {it.info.get()===info}?.let {return it.type}
        val resolved=NativeIconAccess.call(view,"getItemInfo") ?: info
        val provider=try {resolved?.let {XposedHelpers.getObjectField(it,"mProviderName")} as? android.content.ComponentName}catch(_:Throwable){null}
            ?: try {resolved?.let {XposedHelpers.callMethod(it,"getTargetComponent")} as? android.content.ComponentName}catch(_:Throwable){null}
            ?: try {resolved?.let {XposedHelpers.getObjectField(it,"providerName")} as? android.content.ComponentName}catch(_:Throwable){null}
        val cardType = runCatching { NativeIconAccess.call(view, "getCardType") as? Int }.getOrNull()
        val type=WidgetType(PhotoProviders.supports(provider?.packageName,provider?.className,cardType,true),
            PhotoProviders.isClock(provider?.packageName,provider?.className) ||
                content(view).javaClass.simpleName.contains("clock",ignoreCase=true))
        if(info!=null)photoHosts[view]=PhotoHost(java.lang.ref.WeakReference(info),type)
        return type
    }

    private fun styled(view:View,settings:WidgetMaterialSettings):Boolean {
        if(!PrefsHelper.enabled || !settings.enabled || (view !is AppWidgetHostView && !isCard(view)))return false
        return styled(widgetType(view), settings)
    }

    private fun styled(type: WidgetType, settings: WidgetMaterialSettings): Boolean =
        !type.clock && (settings.allWidgets || type.photo)

    private fun installTransitionDraws(loader:ClassLoader){
        val namespace="com.android.launcher3.dragndrop"
        val card=XposedHelpers.findClassIfExists("$namespace.LauncherCardDrawable",loader)
        if(card!=null){
            ModuleHooks.hookAllMethods(card,"draw",object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    if(NativeIconAccess.field(param.thisObject,"mOnlyDrawSmartView")!=true)return
                    val host=NativeIconAccess.field(param.thisObject,"mCardView") as? View ?: return
                    if(!styled(host,PrefsHelper.widgets) || returns.containsKey(host))return
                    val child=NativeIconAccess.call(host,"getSmartView") as? View ?: return
                    val crop=NativeIconAccess.call(host,"getCardBounds") as? Rect ?: return
                    drawTransitionHost(param,host,crop,child.width,child.height)
                }
            })
            ModuleHooks.hookAllMethods(card,"drawCardView",object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    if(param.args.getOrNull(2)!=true)return
                    val host=NativeIconAccess.field(param.thisObject,"mCardView") as? View ?: return
                    if(!styled(host,PrefsHelper.widgets) || returns.containsKey(host))return
                    if(NativeIconAccess.call(host,"getSmartView") !is View)return
                    // The alternate OEM branch draws the full card with its title hidden.
                    // Drawing only getSmartView() bypasses the host's glass compositor.
                    param.args[2]=false
                }
            })
        }
        val widget=XposedHelpers.findClassIfExists("$namespace.LauncherWidgetDrawable",loader)
        if(widget!=null)ModuleHooks.hookAllMethods(widget,"drawCardView",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val host=NativeIconAccess.field(param.thisObject,"mCustomLauncherAppWidgetHostView") as? AppWidgetHostView ?: return
                if(!styled(host,PrefsHelper.widgets) || returns.containsKey(host))return
                val child=NativeIconAccess.call(host,"getWidgetView") as? View ?: return
                val crop=Rect(0,0,child.width,child.height)
                if(crop.isEmpty || child===host)return
                // Preserve the OEM preview's child-sized crop and scale.
                try {host.offsetDescendantRectToMyCoords(child,crop)}catch(_:IllegalArgumentException){return}
                drawTransitionHost(param,host,crop)
            }
        })
        val group=XposedHelpers.findClassIfExists("$namespace.LauncherUSCardDrawable",loader)
        if(group!=null)ModuleHooks.hookAllMethods(group,"drawCardView",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(NativeIconAccess.field(param.thisObject,"isSeedlingCard")!=true)return
                val host=NativeIconAccess.call(param.thisObject,"getCardView") as? View ?: return
                if(!styled(host,PrefsHelper.widgets) || returns.containsKey(host))return
                val bitmap=(NativeIconAccess.call(param.thisObject,"getCardViewScreenShot") as? Bitmap)
                    ?.takeUnless {it.isRecycled}
                val crop=(NativeIconAccess.call(host,"getCardBounds") as? Rect)
                    ?.takeUnless {it.isEmpty} ?: Rect(0,0,host.width,host.height)
                // The seedling bitmap is the provider's unstyled screenshot.
                // Keep its destination size while drawing the styled card.
                drawTransitionHost(param,host,crop,bitmap?.width ?: crop.width(),bitmap?.height ?: crop.height())
            }
        })
    }

    private fun drawTransitionHost(param:XC_MethodHook.MethodHookParam,host:View,crop:Rect,
        width:Int=crop.width(),height:Int=crop.height()){
        if(crop.isEmpty || width<=0 || height<=0 || host.width<=0 || host.height<=0)return
        val canvas=param.args.firstOrNull() as? Canvas ?: return
        val scale=(param.args.getOrNull(1) as? Number)?.toFloat() ?: 1f
        if(!scale.isFinite() || scale<=0f)return
        val save=canvas.save()
        try {
            canvas.scale(scale*width/crop.width(),scale*height/crop.height())
            canvas.translate(-crop.left.toFloat(),-crop.top.toFloat())
            canvas.clipRect(crop)
            val previous=transitionDraw.get()
            transitionDraw.set(true)
            try {host.draw(canvas)}finally {
                if(previous==null)transitionDraw.remove() else transitionDraw.set(previous)
            }
            param.result=null
        }catch(t:Throwable){
            GlassLogger.limited("PhotoWidget","transitionDraw") {"Widget transition draw failed: $t"}
        }finally{canvas.restoreToCount(save)}
    }

    fun isStyledHost(view:View)=hosts[view]==true

    private fun guardLegacyCardTracking(loader: ClassLoader) {
        if (android.os.Build.VERSION.SDK_INT != android.os.Build.VERSION_CODES.BAKLAVA) return
        val tracker = XposedHelpers.findClassIfExists("com.nearme.instant.xcard.track.DcsTracker", loader) ?: return
        val init = runCatching {
            tracker.getDeclaredMethod("initForApp", android.content.Context::class.java, Long::class.javaPrimitiveType,
                Map::class.java)
        }.getOrNull()?.takeIf { it.returnType == Boolean::class.javaPrimitiveType } ?: return
        ModuleHooks.hookMethod(init, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val error = param.throwable as? NoSuchMethodError ?: return
                val description = error.message.orEmpty()
                if (!description.contains("getInstance(Ljava/lang/String;)Lcom/oplus/statistics/OplusTrack;")) return
                // This caller propagates a Boolean indicating whether tracking initialized.
                param.result = false
                GlassLogger.limited("PhotoWidget", "legacyCardTracking") {
                    "Skipped incompatible ColorOS 16 card tracking initialization"
                }
            }
        })
    }

    fun install(loader:ClassLoader){
        guardLegacyCardTracking(loader)
        val transition="com.android.launcher3.anim.OplusLauncherAppTransitionHelper"
        for(name in listOf(transition,"${transition}V2")){
            val type=XposedHelpers.findClassIfExists(name,loader) ?: continue
            for(method in type.declaredMethods){
                if(method.name!="startAppCloseWindowAnim" && method.name!="startAppLaunchWindowAnim")continue
                val sourceIndex=method.parameterTypes.indexOfFirst {View::class.java.isAssignableFrom(it)}
                if(sourceIndex<0)continue
                val closing = method.name == "startAppCloseWindowAnim"
                ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam) {
                        if (closing) armReturn(p.args[sourceIndex] as? View, NATIVE_RETURN_WAIT_MS, existing = false, native = true)
                    }
                    override fun afterHookedMethod(p:MethodHookParam) {
                        if (!closing && !p.hasThrowable()) onAppLaunched(p.args[sourceIndex] as? View)
                    }
                })
            }
        }
        XposedHelpers.findClassIfExists("$transition\$startAppCloseWindowAnim\$12",loader)?.let {callback->
            ModuleHooks.hookAllMethods(callback,"onAnimationEnd",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    armReturn(NativeIconAccess.field(p.thisObject,"\$appIconView") as? View, 0L, existing = false, native = true)
                }
            })
        }
        // Independent of optional live SurfaceControl streaming. getClickView is
        // the OEM source, including widget children whose host was hidden.
        for(name in listOf("com.android.launcher3.anim.iconsurfacemanager.surface.IconSurfaceRecord",
            "com.android.launcher3.anim.iconsurfacemanager.IconSurfaceRecord")){
            val record=XposedHelpers.findClassIfExists(name,loader) ?: continue
            ModuleHooks.hookAllMethods(record,"showOriginalIcon",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    val source=NativeIconAccess.call(p.thisObject,"getClickView") as? View ?: return
                    armReturn(source, 0L, existing = true, native = true)
                }
            })
        }
        XposedHelpers.findClassIfExists("com.android.launcher3.uioverrides.QuickstepInteractionHandler", loader)?.let { handler ->
            ModuleHooks.hookAllMethods(handler, "onInteraction", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.hasThrowable() || p.result != true) return
                    val intent = p.args.getOrNull(1) as? android.app.PendingIntent ?: return
                    if (intent.isActivity) onAppLaunched(p.args.firstOrNull() as? View)
                }
            })
        }
        val contentHook=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                if(capturingPreview)return
                if(!PrefsHelper.enabled)return
                if(view !is AppWidgetHostView && !isCard(view))return
                val settings=PrefsHelper.widgets
                val type = if (settings.enabled) widgetType(view) else null
                val enabled = type != null && styled(type, settings)
                hosts[view]=enabled
                if(!enabled)return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                if(canvas.quickReject(0f,0f,view.width.toFloat(),view.height.toFloat()))return
                val child=content(view)
                if(child.width<=0 || child.height<=0)return
                if(logged.add("host:"+view.javaClass.name))GlassLogger.i("PhotoWidget","Registered host: "+view.javaClass.name)
                val active=drawing.get() ?: HashSet<View>().also {drawing.set(it)}
                // OEM cards may wrap an AppWidgetHostView. Style that subtree once;
                // an inner restoration would otherwise be tinted again by the outer card.
                var ancestor=view.parent as? View
                while(ancestor!=null){if(ancestor in active)return;ancestor=ancestor.parent as? View}
                if(!active.add(view))return
                param.setObjectExtra("photoActive",true)
                watch(view)
                val search = type.search
                val bounds=if(search)GoogleSearchWidget.bounds(child) ?: Rect(0,0,child.width,child.height)
                    else if(child!==view)Rect(0,0,child.width,child.height) else {
                    val native=NativeIconAccess.call(view,"getCardContentBounds") as? Rect
                    native?.takeIf {!it.isEmpty && it.left>=0 && it.top>=0 && it.right<=view.width && it.bottom<=view.height}
                        ?.let(::Rect) ?: Rect(view.paddingLeft,view.paddingTop,view.width-view.paddingRight,view.height-view.paddingBottom)
                }
                var radius=minOf(bounds.width(),bounds.height())*.08f
                val outline=Outline()
                try {child.outlineProvider?.getOutline(child,outline);if(outline.radius>0f)radius=outline.radius}catch(_:Throwable){}
                // Use the exact radius AND weight used by createClipSmoothRoundBitmap,
                // rather than the differently shaped drag-preview outline.
                val customFrame = settings.customFrame
                val nativeCard = view !is AppWidgetHostView && !customFrame && !search
                val legacyCard = nativeCard && android.os.Build.VERSION.SDK_INT == 36
                if (legacyCard) {
                    radius = runCatching { (NativeIconAccess.call(view, "getRadius") as? Number)?.toFloat() }
                        .getOrNull()?.takeIf { it > 0f } ?: radius
                }
                var nativePath = if (!nativeCard || legacyCard) null else try {
                    val info=XposedHelpers.callMethod(view,"getItemInfo")
                    val rightAngle=try {info!=null && XposedHelpers.getBooleanField(info,"mIsUseRightAngleCard")}catch(_:Throwable){false}
                    if(rightAngle)Path().apply {addRect(RectF(bounds),Path.Direction.CCW)} else {
                    val pair=XposedHelpers.callMethod(view,"getCardRadiusAndWeight",view)!!
                    radius=(XposedHelpers.callMethod(pair,"a") as Number).toFloat()
                    val weight=(XposedHelpers.callMethod(pair,"b") as Number).toFloat()
                    Path().also {path ->
                        val manager=XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager",loader)
                        XposedHelpers.callStaticMethod(manager,"createSmoothPath",path,RectF(bounds),radius,weight,Path.Direction.CCW)
                    }
                    }
                }catch(_:Throwable){null}
                var nativeMask = if (!nativeCard || legacyCard) null else try {
                    val info=XposedHelpers.callMethod(view,"getItemInfo")
                    val rightAngle=try {info!=null && XposedHelpers.getBooleanField(info,"mIsUseRightAngleCard")}catch(_:Throwable){false}
                    if(rightAngle)null else {
                        val stored=XposedHelpers.getObjectField(view,"mBitmap") as? Bitmap
                        val cardBounds=XposedHelpers.callMethod(view,"getCardBounds") as Rect
                        stored?.takeIf { !it.isRecycled && it.width == cardBounds.width() + 2 &&
                            it.height == cardBounds.height() + 2 && it.width > 2 && it.height > 2 }
                    }
                }catch(_:Throwable){null}
                if(search){
                    // The Google background is an ImageView with a rounded drawable;
                    // the enclosing RemoteViews container has a rectangular outline.
                    radius=minOf(bounds.width(),bounds.height())*.5f
                    nativePath=Path().apply {addRoundRect(RectF(bounds),radius,radius,Path.Direction.CW)}
                }
                if(customFrame){
                    radius=minOf(bounds.width(),bounds.height())*settings.corner/100f
                    nativePath=Path().apply {addRoundRect(RectF(bounds),radius,radius,Path.Direction.CW)}
                    nativeMask=null
                }
                val identity=identities.getOrPut(child){ColorDrawable(Color.TRANSPARENT)}
                try {
                    val style=style(settings)
                    val matrix=transform(view,child)
                    val progress=returnProgress(view,canvas)
                    val glassAlpha=kotlin.math.round(255f*progress).toInt()
                    val save=canvas.save()
                    try {
                        if(glassAlpha>0){
                            canvas.concat(matrix)
                            SurfaceMaterial.draw(canvas,bounds,child,identity,radius,nativePath,decorate=false,
                                alpha=glassAlpha,style=style,nativeMask=nativeMask)
                        }
                    }finally {canvas.restoreToCount(save)}
                    val paint=contentPaint.get()!!.apply {
                        alpha=kotlin.math.round(255f-(255f-settings.opacity*2.55f)*progress).toInt().coerceIn(0,255)
                        colorFilter=null
                    }
                    param.setObjectExtra("photoReturnProgress",progress)
                    // Enclose the COMPLETE OEM compositor: live children, cached
                    // Picture, preload image and mSnapshot. Treating only the
                    // smart subtree leaves an opaque snapshot above the glass.
                    val contentSave=canvas.save()
                    param.setObjectExtra("photoSave",contentSave)
                    if(search){
                        val rounded=Path(nativePath!!).apply {transform(matrix)}
                        canvas.clipPath(rounded)
                    }else if(customFrame){
                        // Cut only the photo's corners; keep OEM titles outside its footprint.
                        val footprint=Path().apply {addRect(RectF(bounds),Path.Direction.CW);transform(matrix)}
                        val rounded=Path(nativePath!!).apply {transform(matrix)}
                        val clip=Path().apply {
                            fillType=Path.FillType.EVEN_ODD
                            addRect(RectF(canvas.clipBounds),Path.Direction.CW)
                            addPath(footprint);addPath(rounded)
                        }
                        canvas.clipPath(clip)
                    }
                    val contentTint=if(!settings.tint || progress<=0f)null else if(progress>=1f)settings.tintFilter
                        else com.cosmod.launcherglass.util.widgetTintFilter(settings.hue,settings.saturation,settings.lightness,
                            (100-(100-settings.tintTransparency)*progress).toInt(),settings.brightness)
                    val isolated=paint.alpha!=255 || contentTint!=null || settings.hasFade ||
                        settings.shade>0 || WidgetElementColors.enabled
                    // A separate tint layer is only needed when the photo fade
                    // has to be applied after tinting the content.
                    val useSeparateTint = contentTint != null && settings.hasFade
                    if(isolated){
                        if (!useSeparateTint) paint.colorFilter = contentTint
                        val footprint=RectF(bounds).apply {matrix.mapRect(this)}
                        val layerBounds=RectF(footprint).apply {union(0f,0f,view.width.toFloat(),view.height.toFloat())}
                        canvas.saveLayer(layerBounds,paint)
                        if(!WidgetElementColors.followsBackground){
                            val elements=elementLayers.getOrPut(view){com.cosmod.launcherglass.util.WidgetTextLayer()}
                            if(runCatching {elements.record(view,preserveImages=true,contentRoot=child,clipBounds=footprint,
                                    contentMatrix=matrix,
                                    preserveUnidentifiedMedia=android.os.Build.VERSION.SDK_INT == 36,
                                    imagePredicate=if(search)({GoogleSearchWidget.control(it,child)})else null)}.getOrDefault(false))
                                param.setObjectExtra("widgetElementLayer",elements)
                        }
                        // The launcher's caption is outside the widget and keeps its native appearance.
                        val caption=captionLayers.getOrPut(view){com.cosmod.launcherglass.util.WidgetTextLayer()}
                        if(runCatching {caption.record(view,clipBounds=footprint,outside=true,
                                excludedRoot=child.takeIf {it!==view})}.getOrDefault(false))
                            param.setObjectExtra("widgetCaptionLayer",caption)
                    }
                    param.setObjectExtra("widgetContentIsolated",isolated)
                    if(useSeparateTint){
                        tintPaint.colorFilter=contentTint
                        param.setObjectExtra("widgetTintSave",canvas.saveLayer(null,tintPaint))
                    }
                    param.setObjectExtra("widgetProfile",settings)
                    param.setObjectExtra("photoBounds",bounds);param.setObjectExtra("photoRadius",radius);param.setObjectExtra("photoPath",nativePath)
                    param.setObjectExtra("photoMask",nativeMask)
                    param.setObjectExtra("photoTransform",matrix)
                }catch(t:Throwable){
                    (param.getObjectExtra("photoSave") as? Int)?.let {saved ->
                        try {canvas.restoreToCount(saved)}catch(_:Throwable){}
                        param.setObjectExtra("photoSave",null)
                    }
                    GlassLogger.e("PhotoWidget","Photo material failed",t)
                }
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.getObjectExtra("photoActive")!=true)return
                drawing.get()?.remove(param.thisObject as? View)
                val save=param.getObjectExtra("photoSave") as? Int ?: return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                try {
                    val bounds=param.getObjectExtra("photoBounds") as Rect
                    val settings=param.getObjectExtra("widgetProfile") as WidgetMaterialSettings
                    val progress=param.getObjectExtra("photoReturnProgress") as? Float ?: 1f
                    val elements=param.getObjectExtra("widgetElementLayer") as? com.cosmod.launcherglass.util.WidgetTextLayer
                    val caption=param.getObjectExtra("widgetCaptionLayer") as? com.cosmod.launcherglass.util.WidgetTextLayer
                    if(param.getObjectExtra("widgetContentIsolated")==true){elements?.cutOut(canvas);caption?.cutOut(canvas)}
                    (param.getObjectExtra("widgetTintSave") as? Int)?.let(canvas::restoreToCount)
                    // Fade only the isolated widget content, above the glass.
                    try {if(settings.hasFade){
                        val maskSave=canvas.save()
                        try {
                            canvas.concat(param.getObjectExtra("photoTransform") as Matrix)
                            PhotoFade.apply(canvas,bounds,settings.fadeHeight,kotlin.math.round(settings.fade*progress).toInt(),settings.fadeSmoothness)
                        }finally{canvas.restoreToCount(maskSave)}
                    }}finally{canvas.restoreToCount(save)}
                    if(progress<=0f){caption?.draw(canvas);elements?.draw(canvas,WidgetElementColors.paint);return}
                    val radius=param.getObjectExtra("photoRadius") as Float
                    val outer=canvas.save()
                    try {
                        canvas.concat(param.getObjectExtra("photoTransform") as Matrix)
                        if(progress<1f)canvas.saveLayerAlpha(RectF(bounds),kotlin.math.round(255f*progress).toInt())
                        if(settings.customFrame)
                            GlassFrame.draw(canvas,bounds,radius,settings.shade,
                                0,settings.rim,param.getObjectExtra("photoPath") as? Path)
                        else SurfaceMaterial.contour(canvas,bounds,radius,param.getObjectExtra("photoPath") as? Path,
                            param.getObjectExtra("photoMask") as? Bitmap,settings.rim)
                        SurfaceMaterial.edge(canvas,bounds,radius,param.getObjectExtra("photoPath") as? Path,
                            param.getObjectExtra("photoMask") as? Bitmap,param.thisObject as View,settings.liquid)
                    }finally{canvas.restoreToCount(outer)}
                    // Internal glyphs share one color profile; the launcher caption stays native.
                    caption?.draw(canvas)
                    elements?.draw(canvas,WidgetElementColors.paint)
                }catch(t:Throwable){
                    runCatching {canvas.restoreToCount(save)}
                    runCatching {(param.getObjectExtra("widgetCaptionLayer") as? com.cosmod.launcherglass.util.WidgetTextLayer)?.draw(canvas)}
                    runCatching {(param.getObjectExtra("widgetElementLayer") as? com.cosmod.launcherglass.util.WidgetTextLayer)?.draw(canvas,WidgetElementColors.paint)}
                    GlassLogger.e("PhotoWidget","Photo contour failed",t)
                }
            }
        }
        for(name in listOf("android.appwidget.AppWidgetHostView","com.android.launcher3.card.groupcard.GroupCardView","com.android.launcher3.card.TitleCardView","com.android.launcher3.card.CommonCardView","com.android.launcher3.card.EngineCardView","com.android.launcher3.card.LauncherCardView","com.android.launcher3.widget.LauncherAppWidgetHostView","com.android.launcher3.widget.CustomLauncherAppWidgetHostView")){
                var cls:Class<*>?=XposedHelpers.findClassIfExists(name,loader)
                while(cls!=null){
                    val method=cls.declaredMethods.firstOrNull {it.name=="dispatchDraw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))}
                    if(method!=null){
                        if(method.declaringClass==ViewGroup::class.java)break
                        if(drawMethods.add(method))ModuleHooks.hookMethod(method,contentHook)
                        break
                    }
                    cls=cls.superclass
                }
        }
        installTransitionDraws(loader)
    }
}
