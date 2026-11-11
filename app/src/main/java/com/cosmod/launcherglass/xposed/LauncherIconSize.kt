package com.cosmod.launcherglass.xposed

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.view.View
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

    private fun scaleContainer(view:View,ratio:Float=percent/100f){
        val currentX=view.scaleX
        val currentY=view.scaleY
        val previous=containers[view]
        val nativeX=if(previous!=null && previous.appliedX==currentX)previous.nativeX else currentX
        val nativeY=if(previous!=null && previous.appliedY==currentY)previous.nativeY else currentY
        val x=nativeX*ratio
        val y=nativeY*ratio
        if(x!=currentX)view.scaleX=x
        if(y!=currentY)view.scaleY=y
        if(previous==null || previous.appliedX!=x || previous.appliedY!=y || previous.nativeX!=nativeX || previous.nativeY!=nativeY)
            containers[view]=Scale(nativeX,nativeY,x,y)
    }
    private var refresh:((View)->Unit)?=null
    private var lastPercent=100
    private val percent get()=if(PrefsHelper.enabled)PrefsHelper.launcherIconSize else 100

    fun onSettingsChanged(){
        if(percent==lastPercent)return
        lastPercent=percent
        for(view in containers.keys.weakSnapshot())if(view.isAttachedToWindow)updateContainer(view)
        val apply=refresh ?: return
        for(view in synchronized(sizes){sizes.keys.weakSnapshot()}){
            if(view.isAttachedToWindow)apply(view)
        }
    }

    private const val SHORTCUT="com.android.launcher3.viewcontainer.ShortcutContainer"
    private const val GRID="com.android.launcher3.viewcontainer.GridRecyclerView"
    private const val FLEX_FOLDER="com.android.launcher3.folder.FlexibleFolderIcon"

    private fun updateContainer(view:View){
        val group=view as? ViewGroup
        val name=view.javaClass.name
        if(name==SHORTCUT && group!=null){
            val content=(NativeIconAccess.call(view,"getHoverScaleView") as? View)?.takeIf {it!==view}
                ?: (0 until group.childCount).firstNotNullOfOrNull {index->group.getChildAt(index).takeIf {it.javaClass.name==GRID}}
            if(content!=null){
                scaleContainer(view,1f)
                scaleContainer(content)
                return
            }
        }
        val span=if(name==FLEX_FOLDER || name.endsWith("FolderIcon"))NativeIconAccess.call(view,"getSpanRange") as? Rect else null
        val item=if(span==null && name.endsWith("FolderIcon"))NativeIconAccess.call(view,"getItemInfo") ?: view.tag else null
        val large=span?.let {it.width()>1 && it.height()>1} ?: (item!=null &&
            ((NativeIconAccess.field(item,"spanX") as? Int ?: 1)>1) &&
            ((NativeIconAccess.field(item,"spanY") as? Int ?: 1)>1))
        scaleContainer(view,if(large)1f else percent/100f)
    }

    fun install(loader:ClassLoader){
        val targets=listOf("com.android.launcher3.folder.FolderIcon",FLEX_FOLDER,SHORTCUT).mapNotNull {XposedHelpers.findClassIfExists(it,loader)}
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
        targets.firstOrNull {it.name==SHORTCUT}?.declaredMethods?.filter {
            it.name=="updateItemIconLayout" && it.parameterCount==2
        }?.forEach {ModuleHooks.hookMethod(it,resize)}
        val type=XposedHelpers.findClassIfExists("com.android.launcher3.BubbleTextView",loader) ?: return
        runCatching {
            val display=type.getDeclaredField("mDisplay").apply {isAccessible=true}
            val size=type.getDeclaredField("mIconSize").apply {isAccessible=true}
            val icon=type.getDeclaredField("mIcon").apply {isAccessible=true}
            val workspace=XposedHelpers.getStaticIntField(type,"DISPLAY_WORKSPACE")
            val drawer=XposedHelpers.getStaticIntField(type,"DISPLAY_ALL_APPS")
            val update=type.getDeclaredMethod("updateIconBounds",Drawable::class.java)
            fun apply(view:View,nativeOverride:Int?=null){
                val surface=display.getInt(view)
                if(surface!=workspace && surface!=drawer)return
                val observed=size.getInt(view)
                if(observed<=0)return
                synchronized(sizes){
                    val previous=sizes[view]
                    val native=nativeOverride ?: previous?.takeIf {it.applied==observed}?.native ?: observed
                    val adjusted=(native*percent+50)/100
                    if(adjusted!=observed)size.setInt(view,adjusted)
                    if(previous==null || previous.native!=native || previous.applied!=adjusted)sizes[view]=Size(native,adjusted)
                }
            }
            ModuleHooks.hookAllConstructors(type,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    if(!param.hasThrowable())apply(param.thisObject as View)
                }
            })
            ModuleHooks.hookMethod(update,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){apply(param.thisObject as View)}
            })
            val oplus=XposedHelpers.findClassIfExists("com.android.launcher3.OplusBubbleTextView",loader)
            oplus?.declaredMethods?.firstOrNull {
                it.name=="setIconSize" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }?.let {method->
                ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                    override fun afterHookedMethod(param:MethodHookParam){
                        if(!param.hasThrowable())apply(param.thisObject as View,param.args[0] as Int)
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
