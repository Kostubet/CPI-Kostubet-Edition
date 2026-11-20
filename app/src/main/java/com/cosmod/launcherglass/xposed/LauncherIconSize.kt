package com.cosmod.launcherglass.xposed

import android.content.res.TypedArray
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import com.cosmod.launcherglass.ui.DesktopAppearance
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** Keep the native icon bounds and the launch target geometry in agreement. */
internal object LauncherIconSize {
    private data class Size(val native:Int,val applied:Int)
    private val sizes=WeakHashMap<View,Size>()
    private data class Scale(val nativeX:Float,val nativeY:Float,val appliedX:Float,val appliedY:Float)
    private val containers=WeakHashMap<View,Scale>()

    private fun scaleContainer(view:View,ratio:Float,nativeScale:Float?=null){
        val currentX=view.scaleX
        val currentY=view.scaleY
        val previous=containers[view]
        val nativeX=nativeScale ?: previous?.takeIf {it.appliedX==currentX}?.nativeX ?: currentX
        val nativeY=nativeScale ?: previous?.takeIf {it.appliedY==currentY}?.nativeY ?: currentY
        val x=nativeX*ratio
        val y=nativeY*ratio
        if(x!=currentX)view.scaleX=x
        if(y!=currentY)view.scaleY=y
        if(previous==null || previous.appliedX!=x || previous.appliedY!=y || previous.nativeX!=nativeX || previous.nativeY!=nativeY)
            containers[view]=Scale(nativeX,nativeY,x,y)
    }
    private var refresh:((View)->Unit)?=null
    private var lastPercent=100
    private var lastLarge = false
    private val large get() = PrefsHelper.enabled && PrefsHelper.desktopLargeIcons
    private val percent get()=if(PrefsHelper.enabled)PrefsHelper.launcherIconSize else 100
    private val workspacePercent get() = if (large) (percent * DesktopAppearance.LARGE_ICON_PERCENT / 100).coerceAtMost(130) else percent

    fun onSettingsChanged(){
        if(percent==lastPercent && large==lastLarge)return
        lastPercent=percent
        lastLarge=large
        for(view in containers.keys.weakSnapshot())if(view.isAttachedToWindow)updateContainer(view)
        val apply=refresh ?: return
        for(view in synchronized(sizes){sizes.keys.weakSnapshot()}){
            if(view.isAttachedToWindow)apply(view)
        }
    }

    private const val SHORTCUT="com.android.launcher3.viewcontainer.ShortcutContainer"
    private const val FOLDER="com.android.launcher3.folder.FolderIcon"
    private const val FLEX_FOLDER="com.android.launcher3.folder.FlexibleFolderIcon"
    private var folderTypes:List<Class<*>> = emptyList()

    private fun hasScaledContainer(view: View, targets: List<Class<*>>): Boolean =
        generateSequence(view.parent as? View) { it.parent as? View }
            .any { parent -> targets.any { it.isInstance(parent) } }

    private fun updateContainer(view:View,nativeScale:Float?=null){
        val large = if (folderTypes.any { it.isInstance(view) }) {
            val item = NativeIconAccess.call(view, "getItemInfo") ?: view.tag
            val width = item?.let { NativeIconAccess.field(it, "spanX") as? Int }
            val height = item?.let { NativeIconAccess.field(it, "spanY") as? Int }
            if (width != null && height != null) width > 1 && height > 1
            else NativeIconAccess.call(view, "isBigFolderIcon") == true ||
                (NativeIconAccess.call(view, "getSpanRange") as? Rect)?.let { it.width() > 1 && it.height() > 1 } == true
        } else false
        scaleContainer(view,if(large)1f else workspacePercent/100f,nativeScale)
    }

    fun install(loader:ClassLoader){
        val targets=listOf(FOLDER,FLEX_FOLDER,SHORTCUT).mapNotNull {XposedHelpers.findClassIfExists(it,loader)}
        folderTypes=targets.filter {it.name!=SHORTCUT}
        val methods=targets.mapNotNull {type->
            generateSequence(type) {it.superclass}.firstNotNullOfOrNull {owner->
                owner.declaredMethods.firstOrNull {it.name=="onLayout" && it.parameterCount==5}
            }
        }.toSet()
        val resize=object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.hasThrowable())return
                val view=param.thisObject as? View ?: return
                if(targets.any {it.isInstance(view)})runCatching {updateContainer(view)}
            }
        }
        for(method in methods)ModuleHooks.hookMethod(method,resize)
        targets.flatMap { target -> target.declaredMethods.filter {
            (it.name=="updateItemIconLayout" && it.parameterCount==2) ||
                (target.name==SHORTCUT && it.name=="resetState" && it.parameterCount==0)
        } }.forEach { ModuleHooks.hookMethod(it, resize) }
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
                    val scale = if (surface == drawer) percent
                        else if (hasScaledContainer(view, targets)) 100 else workspacePercent
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
            ModuleHooks.hookMethod(textAlpha, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (large && display.getInt(param.thisObject) == workspace) param.args[0] = 0f
                }
            })
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
            ModuleHooks.hookMethod(update,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){apply(param.thisObject as View)}
            })
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
                    if(display.getInt(view)==workspace) NativeIconAccess.call(view, "setTextVisibility")
                    view.requestLayout()
                    view.invalidate()
                }.onFailure {GlassLogger.limited("LauncherIconSize","refresh"){"Native icon size refresh unavailable: $it"}}
            }
        }.onFailure {GlassLogger.e("LauncherIconSize","Launcher icon geometry hooks unavailable",it)}
    }
}
