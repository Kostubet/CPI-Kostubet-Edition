package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** Effects belong to a separate render node so ColorOS keeps its animated view properties. */
internal object UnlockElementBlur {
    private class Plate {
        val node=RenderNode("CPI.unlockElements")
        var recording=false
        var padding=0
    }
    private val active=WeakHashMap<View,Plate>()
    private var supported=false

    fun apply(views:List<View>,effect:RenderEffect?,padding:Int=0){
        if(!supported)return
        for(view in views){
            if(effect==null){
                if(active.remove(view)==null)continue
            }else{
                if(!view.isAttachedToWindow)continue
                active.getOrPut(view){Plate()}.apply {
                    this.padding=padding
                    node.setClipToBounds(false)
                    node.setRenderEffect(effect)
                }
            }
            view.invalidate()
        }
    }

    fun install(loader:ClassLoader){
        if(supported)return
        val type=XposedHelpers.findClassIfExists("com.android.launcher3.ShortcutAndWidgetContainer",loader) ?: return
        val draw=generateSequence(type) {it.superclass}.firstNotNullOfOrNull {owner->
            owner.declaredMethods.firstOrNull {it.name=="dispatchDraw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))}
        } ?: return
        ModuleHooks.hookMethod(draw,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(active.isEmpty())return
                val view=param.thisObject as? ViewGroup ?: return
                val plate=active[view] ?: return
                val canvas=param.args[0] as Canvas
                if(plate.recording || !canvas.isHardwareAccelerated || view.width<=0 || view.height<=0)return
                if(canvas.clipBounds.isEmpty)return
                val node=plate.node
                val padding=plate.padding
                node.setPosition(-padding,-padding,view.width+padding,view.height+padding)
                val recording=node.beginRecording()
                recording.translate(padding.toFloat(),padding.toFloat())
                plate.recording=true
                param.setObjectExtra("cpiUnlockCanvas",canvas)
                param.setObjectExtra("cpiUnlockPlate",plate)
                param.args[0]=recording
            }

            override fun afterHookedMethod(param:MethodHookParam){
                val canvas=param.getObjectExtra("cpiUnlockCanvas") as? Canvas ?: return
                val plate=param.getObjectExtra("cpiUnlockPlate") as Plate
                try {
                    plate.node.endRecording()
                    if(!param.hasThrowable())canvas.drawRenderNode(plate.node)
                }finally {
                    plate.recording=false
                    param.args[0]=canvas
                }
            }
        })
        supported=true
    }
}
