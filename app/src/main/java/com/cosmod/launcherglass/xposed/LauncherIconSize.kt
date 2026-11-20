package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.content.res.TypedArray
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.PathInterpolator
import com.cosmod.launcherglass.ui.DesktopAppearance
import com.cosmod.launcherglass.util.LauncherGridIconScale
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** Keep the native icon bounds and the launch target geometry in agreement. */
internal object LauncherIconSize {
    private data class Size(val native:Int,val applied:Int)
    private val sizes=WeakHashMap<View,Size>()
    private data class Scale(
        val nativeX: Float, val nativeY: Float, val appliedX: Float, val appliedY: Float,
        val nativePivotX: Float, val nativePivotY: Float, val pivotX: Float, val pivotY: Float,
    )
    private val scales = WeakHashMap<View, Scale>()
    private val containers = WeakHashMap<View, Boolean>()
    private val workspaceViews = WeakHashMap<View, Boolean>()
    private val labelAlpha = WeakHashMap<View, Float>()
    private val labelViews = WeakHashMap<View, Boolean>()
    private var scaleWorkspaceIcon: ((View) -> Unit)? = null
    private var updateLabel: ((View) -> Unit)? = null

    private fun scaleAround(
        view: View, ratio: Float, centerX: Float, centerY: Float, nativeScale: Float? = null,
    ) {
        val currentX=view.scaleX
        val currentY=view.scaleY
        val previous = scales[view]
        val nativeX=nativeScale ?: previous?.takeIf {it.appliedX==currentX}?.nativeX ?: currentX
        val nativeY=nativeScale ?: previous?.takeIf {it.appliedY==currentY}?.nativeY ?: currentY
        val x=nativeX*ratio
        val y=nativeY*ratio
        if (x != currentX) view.scaleX = x
        if (y != currentY) view.scaleY = y
        val nativePivotX = previous?.takeIf { view.pivotX == it.pivotX }?.nativePivotX ?: view.pivotX
        val nativePivotY = previous?.takeIf { view.pivotY == it.pivotY }?.nativePivotY ?: view.pivotY
        if (ratio == 1f) {
            if (previous != null) {
                view.pivotX = nativePivotX
                view.pivotY = nativePivotY
                scales.remove(view)
            }
            return
        }
        view.pivotX = centerX
        view.pivotY = centerY
        scales[view] = Scale(nativeX, nativeY, x, y, nativePivotX, nativePivotY, centerX, centerY)
    }
    private var refresh:((View)->Unit)?=null
    private var lastPercent=100
    private var lastLarge = false
    private var lastDockGeometry: List<Int> = emptyList()
    private val large get() = PrefsHelper.enabled && PrefsHelper.desktopLargeIcons
    private val percent get()=if(PrefsHelper.enabled)PrefsHelper.launcherIconSize else 100
    private var largeProgress = 0f
    private var largeMotion: ValueAnimator? = null
    private val enlargedScale get() = (DesktopAppearance.LARGE_ICON_PERCENT / 100f).coerceAtMost(130f / percent)

    private fun updateWorkspaceSizes() {
        for (view in containers.keys.weakSnapshot()) if (view.isAttachedToWindow) runCatching { updateContainer(view) }
        val update = scaleWorkspaceIcon
        if (update != null) for (view in workspaceViews.keys.weakSnapshot()) if (view.isAttachedToWindow) update(view)
        val showLabel = updateLabel
        if (showLabel != null) for (view in labelViews.keys.weakSnapshot()) if (view.isAttachedToWindow) showLabel(view)
    }

    fun onSettingsChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post(::onSettingsChanged)
            return
        }
        if (percent != lastPercent) {
            lastPercent = percent
            refresh?.let { apply ->
                for (view in synchronized(sizes) { sizes.keys.weakSnapshot() }) if (view.isAttachedToWindow) apply(view)
            }
            updateWorkspaceSizes()
        }
        val dockGeometry = listOf(PrefsHelper.dockWidth, PrefsHelper.dockHeight, PrefsHelper.dockInset, PrefsHelper.dockCorner)
        if (dockGeometry != lastDockGeometry) {
            lastDockGeometry = dockGeometry
            if (large && large == lastLarge) updateWorkspaceSizes()
        }
        if (large == lastLarge) return
        lastLarge = large
        largeMotion?.cancel()
        val target = if (large) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled() || (workspaceViews.isEmpty() && containers.isEmpty())) {
            largeProgress = target
            updateWorkspaceSizes()
            return
        }
        largeMotion = ValueAnimator.ofFloat(largeProgress, target).apply {
            duration = 460L
            interpolator = PathInterpolator(.30f, 0f, .28f, 1f)
            addUpdateListener {
                largeProgress = it.animatedValue as Float
                updateWorkspaceSizes()
            }
            start()
        }
    }

    fun onNativeIconReturned(view: View) {
        if (!large && largeProgress == 0f && percent <= 100) return
        if (view in containers) runCatching { updateContainer(view) }
        else scaleWorkspaceIcon?.invoke(view)
    }

    private const val SHORTCUT="com.android.launcher3.viewcontainer.ShortcutContainer"
    private const val FOLDER="com.android.launcher3.folder.FolderIcon"
    private const val FLEX_FOLDER="com.android.launcher3.folder.FlexibleFolderIcon"
    private var folderTypes:List<Class<*>> = emptyList()
    private var shortcutType: Class<*>? = null

    private fun hasScaledContainer(view: View, targets: List<Class<*>>): Boolean =
        generateSequence(view.parent as? View) { it.parent as? View }
            .any { parent -> targets.any { it.isInstance(parent) } }

    private fun updateContainer(view: View, nativeScale: Float? = null) {
        containers[view] = true
        val isShortcut = shortcutType?.isInstance(view) == true
        val isLargeFolder = if (!isShortcut && folderTypes.any { it.isInstance(view) }) {
            val item = NativeIconAccess.call(view, "getItemInfo") ?: view.tag
            val width = item?.let { NativeIconAccess.field(it, "spanX") as? Int }
            val height = item?.let { NativeIconAccess.field(it, "spanY") as? Int }
            if (width != null && height != null) width > 1 && height > 1
            else NativeIconAccess.call(view, "isBigFolderIcon") == true ||
                (NativeIconAccess.call(view, "getSpanRange") as? Rect)?.let { it.width() > 1 && it.height() > 1 } == true
        } else false
        val child = if (isShortcut) NativeIconAccess.call(view, "getMRecyclerView") as? View else null
        val plate = child ?: view
        if (plate.width == 0 || plate.height == 0) return
        // The native resize frame owns the plate's pivot and scale until resetState.
        if (NativeIconAccess.call(view, "isResizeFrameActive") == true) return
        val bounds = (NativeIconAccess.call(view, if (isShortcut) "getBackgroundBounds" else "getItemIconBounds") as? Rect)
            ?.takeUnless { it.isEmpty }
        val centerX = bounds?.exactCenterX()?.minus(child?.left ?: 0) ?: plate.width / 2f
        val centerY = bounds?.exactCenterY()?.minus(child?.top ?: 0) ?: plate.height / 2f
        val label = NativeIconAccess.call(view, if (isShortcut) "getMContainerName" else "getFolderName") as? View
        if (label != null && labelViews.put(label, true) == null) updateLabel?.invoke(label)
        val normal = if (isLargeFolder) 1f else percent / 100f
        val start = fitCell(view, bounds, normal, nativeRatio = 1f)
        val end = if (isLargeFolder || largeProgress == 0f) start else
            fitCell(view, bounds, normal * enlargedScale, nativeRatio = 1f)
        val ratio = LauncherGridIconScale.interpolate(start, end, largeProgress)
        scaleAround(plate, ratio, centerX, centerY, nativeScale)
    }

    private fun fitCell(view: View, bounds: Rect?, requested: Float, nativeRatio: Float): Float {
        if (requested <= nativeRatio || bounds == null || bounds.isEmpty || view.width == 0 || view.height == 0) {
            return requested
        }
        val gap = 4f * view.resources.displayMetrics.density
        return LauncherGridIconScale.fit(requested, nativeRatio, view.width, view.height,
            bounds.exactCenterX(), bounds.exactCenterY(), bounds.width(), bounds.height(), gap)
    }

    fun install(loader:ClassLoader){
        val targets=listOf(FOLDER,FLEX_FOLDER,SHORTCUT).mapNotNull {XposedHelpers.findClassIfExists(it,loader)}
        folderTypes=targets.filter {it.name!=SHORTCUT}
        shortcutType = targets.firstOrNull { it.name == SHORTCUT }
        val methods=targets.mapNotNull {type->
            generateSequence(type) {it.superclass}.firstNotNullOfOrNull {owner->
                owner.declaredMethods.firstOrNull {it.name=="onLayout" && it.parameterCount==5}
            }
        }.toSet()
        val resize=object:XC_MethodHook(){
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.method.name != "notifyFrameAttachedToWindow" || param.args[0] != true) return
                val view = param.thisObject as View
                val plate = if (shortcutType?.isInstance(view) == true)
                    NativeIconAccess.call(view, "getMRecyclerView") as? View else view
                if (plate != null && plate in scales) scaleAround(plate, 1f, plate.pivotX, plate.pivotY)
            }

            override fun afterHookedMethod(param:MethodHookParam){
                if(param.hasThrowable())return
                val view=param.thisObject as? View ?: return
                if(targets.any {it.isInstance(view)})runCatching {updateContainer(view)}
            }
        }
        for(method in methods)ModuleHooks.hookMethod(method,resize)
        targets.flatMap { target -> target.declaredMethods.filter {
            (it.name == "resetState" && it.parameterCount == 0) ||
                (it.name == "notifyFrameAttachedToWindow" &&
                    it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)))
        } }.distinct().forEach { ModuleHooks.hookMethod(it, resize) }
        targets.firstOrNull {it.name==FOLDER}?.declaredMethods?.firstOrNull {
            it.name=="setReorderBounceScale" && it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
        }?.let {method->
            ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    if(!param.hasThrowable())runCatching {
                        updateContainer(param.thisObject as View,param.args[0] as Float)
                    }
                }
            })
        }
        val type=XposedHelpers.findClassIfExists("com.android.launcher3.BubbleTextView",loader) ?: return
        runCatching {
            val display=type.getDeclaredField("mDisplay").apply {isAccessible=true}
            val size=type.getDeclaredField("mIconSize").apply {isAccessible=true}
            val icon=type.getDeclaredField("mIcon").apply {isAccessible=true}
            val workspace=XposedHelpers.getStaticIntField(type,"DISPLAY_WORKSPACE")
            val drawer=XposedHelpers.getStaticIntField(type,"DISPLAY_ALL_APPS")
            val update=type.getDeclaredMethod("updateIconBounds",Drawable::class.java)
            fun adjust(view:View,observed:Int,nativeOverride:Boolean=false):Int{
                val surface=display.getInt(view)
                if((surface!=workspace && surface!=drawer) || observed<=0)return observed
                return synchronized(sizes){
                    val previous=sizes[view]
                    val native=previous?.takeIf {!nativeOverride && it.applied==observed}?.native ?: observed
                    val scale = if (surface == workspace && hasScaledContainer(view, targets)) 100 else percent
                    val adjusted=(native*scale+50)/100
                    if(previous==null || previous.native!=native || previous.applied!=adjusted)sizes[view]=Size(native,adjusted)
                    adjusted
                }
            }
            fun apply(view:View,nativeOverride:Boolean=false){
                val observed=size.getInt(view)
                val adjusted=adjust(view,observed,nativeOverride)
                if(adjusted!=observed)size.setInt(view,adjusted)
            }
            val oplus=XposedHelpers.findClassIfExists("com.android.launcher3.OplusBubbleTextView",loader)
            val textAlpha = type.getDeclaredMethod("setTextAlpha", Float::class.javaPrimitiveType)
            val nativeText = type.getDeclaredField("mTextAlpha").apply { isAccessible = true }
            val labelExtension = runCatching { type.getDeclaredField("mOPlusBtvExtV2").apply { isAccessible = true } }.getOrNull()
            val setLabelEffectAlpha = runCatching {
                labelExtension?.type?.getMethod("setTextAlphaEntryPoint", Float::class.javaPrimitiveType)
            }.getOrNull()
            val refreshShadow = runCatching { type.getMethod("refreshShadowLayer") }.getOrNull()
            val refreshingLabel = ThreadLocal<View>()
            val getIconBounds = type.getDeclaredMethod("getIconBounds", Rect::class.java)
            val iconBounds = Rect()
            fun isWorkspaceIcon(view: View) = display.getInt(view) == workspace || DockMaterial.containingDock(view) != null

            fun resizeWorkspaceIcon(view: View) {
                if (view.width == 0 || view.height == 0 || !isWorkspaceIcon(view) ||
                    hasScaledContainer(view, targets)) return
                iconBounds.setEmpty()
                getIconBounds.invoke(view, iconBounds)
                if (iconBounds.isEmpty) return
                val nativeRatio = 100f / percent
                val start = fitCell(view, iconBounds, 1f, nativeRatio)
                val target = if (largeProgress == 0f) start else fitCell(view, iconBounds, enlargedScale, nativeRatio)
                val dock = if (target > 1f) DockMaterial.containingDock(view) else null
                val end = if (dock == null) target else DockMaterial.limitIconScale(dock, view, iconBounds, target)
                val ratio = LauncherGridIconScale.interpolate(start, end, largeProgress)
                scaleAround(view, ratio, iconBounds.exactCenterX(), iconBounds.exactCenterY())
            }
            scaleWorkspaceIcon = { view -> runCatching { resizeWorkspaceIcon(view) } }
            val floating = XposedHelpers.findClassIfExists("com.android.launcher3.views.FloatingIconView", loader)
            floating?.declaredMethods?.firstOrNull {
                it.name == "getLocationBoundsForView" && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.drop(1) == listOf(View::class.java, Boolean::class.javaPrimitiveType,
                        RectF::class.java, Rect::class.java)
            }?.let { method ->
                ModuleHooks.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable() || param.args[2] != false) return
                        val view = param.args[1] as? View ?: return
                        if (!type.isInstance(view)) return
                        val bounds = param.args[3] as? RectF ?: return
                        if (bounds.isEmpty) return
                        val scale = scales[view] ?: return
                        if (scale.nativeX <= 0f || scale.nativeY <= 0f) return
                        // ColorOS omits View matrices when it computes the closing target.
                        // Reapply only CPI's scale so the flying icon lands at its visible size.
                        val extraX = (scale.appliedX / scale.nativeX - 1f) * bounds.width() / 2f
                        val extraY = (scale.appliedY / scale.nativeY - 1f) * bounds.height() / 2f
                        bounds.inset(-extraX, -extraY)
                    }
                })
            }
            fun trackWorkspaceIcon(view: View) {
                if (!isWorkspaceIcon(view) || hasScaledContainer(view, targets)) return
                labelViews[view] = true
                if (workspaceViews.put(view, true) == null) {
                    view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> scaleWorkspaceIcon?.invoke(v) }
                }
                resizeWorkspaceIcon(view)
                if (largeProgress > 0f) updateLabel?.invoke(view)
            }
            fun refreshLabelEffects(view: View, alpha: Float) {
                labelExtension?.get(view)?.let { setLabelEffectAlpha?.invoke(it, alpha) }
                refreshShadow?.invoke(view)
            }

            fun applyLabel(view: View, rebind: Boolean = false) {
                val current = nativeText.getFloat(view)
                val wanted = labelAlpha.getOrPut(view) { current }
                val alpha = wanted * (1f - largeProgress)
                if (!rebind && current == alpha) return
                val previous = refreshingLabel.get()
                refreshingLabel.set(view)
                try {
                    textAlpha.invoke(view, alpha)
                    if (rebind || alpha == 0f || current == 0f) refreshLabelEffects(view, alpha)
                } finally {
                    refreshingLabel.set(previous)
                }
            }

            ModuleHooks.hookMethod(textAlpha, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    if (refreshingLabel.get() === view) return
                    if (view !in labelViews && (!isWorkspaceIcon(view) || hasScaledContainer(view, targets))) return
                    val wanted = param.args[0] as Float
                    labelViews[view] = true
                    labelAlpha[view] = wanted
                    param.args[0] = wanted * (1f - largeProgress)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    if (!param.hasThrowable() && refreshingLabel.get() !== view && largeProgress > 0f &&
                        param.args[0] == 0f && view in labelViews) {
                        runCatching { refreshLabelEffects(view, 0f) }
                    }
                }
            })
            // ColorOS re-creates the text layer and resets its effects to full opacity.
            type.declaredMethods.firstOrNull { it.name == "setMultiNodeEnable" && it.parameterCount == 3 }
                ?.let { method ->
                    ModuleHooks.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as View
                            if (largeProgress == 0f || view !in labelViews) return
                            param.setObjectExtra("cpiLabelRebind", true)
                            param.setObjectExtra("cpiPreviousLabel", refreshingLabel.get())
                            refreshingLabel.set(view)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.getObjectExtra("cpiLabelRebind") != true) return
                            refreshingLabel.set(param.getObjectExtra("cpiPreviousLabel") as? View)
                            if (!param.hasThrowable()) runCatching { applyLabel(param.thisObject as View, rebind = true) }
                        }
                    })
                }
            updateLabel = { view -> runCatching { applyLabel(view) } }
            // Adjust the stock size after the device profile and any XML override have been resolved.
            val initialize=object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    if(!param.hasThrowable())param.result=adjust(param.thisObject as View,param.result as Int)
                }
            }
            val initializers=listOfNotNull(type,oplus).mapNotNull {owner->
                owner.declaredMethods.firstOrNull {
                    it.name=="getInitIconSize" && it.returnType==Int::class.javaPrimitiveType &&
                        it.parameterTypes.contentEquals(arrayOf(TypedArray::class.java,Int::class.javaPrimitiveType))
                }
            }
            initializers.forEach {ModuleHooks.hookMethod(it,initialize)}
            if(initializers.isEmpty())ModuleHooks.hookAllConstructors(type,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    if(!param.hasThrowable())apply(param.thisObject as View)
                }
            })
            type.declaredMethods.firstOrNull { it.name == "onAttachedToWindow" && it.parameterCount == 0 }
                ?.let { method ->
                    ModuleHooks.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!param.hasThrowable()) runCatching { trackWorkspaceIcon(param.thisObject as View) }
                        }
                    })
                }
            ModuleHooks.hookMethod(update,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){apply(param.thisObject as View)}
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!param.hasThrowable()) runCatching { trackWorkspaceIcon(param.thisObject as View) }
                }
            })
            // This native method resets both View scale axes after setIcon updates its bounds.
            oplus?.declaredMethods?.firstOrNull {
                it.name == "resetViewProperties" && it.parameterTypes.contentEquals(arrayOf(Drawable::class.java))
            }?.let { method ->
                ModuleHooks.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!param.hasThrowable()) scaleWorkspaceIcon?.invoke(param.thisObject as View)
                    }
                })
            }
            oplus?.declaredMethods?.firstOrNull {
                it.name=="setIconSize" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }?.let {method->
                ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = adjust(param.thisObject as View, param.args[0] as Int, nativeOverride = true)
                    }
                })
            }
            refresh={view->
                runCatching {
                    apply(view)
                    val drawable=icon.get(view) as? Drawable
                    if(drawable!=null)XposedHelpers.callMethod(view,"applyCompoundDrawables",drawable)
                    view.requestLayout()
                    view.invalidate()
                }.onFailure {GlassLogger.limited("LauncherIconSize","refresh"){"Native icon size refresh unavailable: $it"}}
            }
        }.onFailure {GlassLogger.e("LauncherIconSize","Launcher icon geometry hooks unavailable",it)}
    }
}
