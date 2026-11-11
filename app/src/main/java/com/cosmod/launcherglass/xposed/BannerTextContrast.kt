package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Retones text only when its heads-up row is painted with the custom backdrop. */
internal object BannerTextContrast {
    private class Style(row:View,val lightText:Boolean):ViewTreeObserver.OnGlobalLayoutListener,
        View.OnAttachStateChangeListener {
        private val rowRef=WeakReference(row)
        private val observer=row.viewTreeObserver
        private val labels=WeakHashMap<TextView,Boolean>()

        init {
            observer.addOnGlobalLayoutListener(this)
            row.addOnAttachStateChangeListener(this)
        }
        override fun onGlobalLayout(){
            rowRef.get()?.let(::scan)
        }
        override fun onViewAttachedToWindow(view:View)=Unit
        override fun onViewDetachedFromWindow(view:View){clear(view)}

        fun scan(view:View){
            if(view is TextView){
                if(labels.put(view,true)==null){
                    active[view]=this
                    view.postInvalidateOnAnimation()
                }
            }else if(view is ViewGroup){
                for(i in 0 until view.childCount)scan(view.getChildAt(i))
            }
        }
        fun dispose(row:View){
            if(observer.isAlive)observer.removeOnGlobalLayoutListener(this)
            row.removeOnAttachStateChangeListener(this)
            for(label in labels.keys.toList())if(active[label]===this){
                active.remove(label)
                label.postInvalidateOnAnimation()
            }
        }
        fun owns(label:TextView):Boolean {
            val row=rowRef.get() ?: return false
            var current:View?=label
            while(current!=null){
                if(current===row)return true
                current=current.parent as? View
            }
            return false
        }
    }

    private val rows=WeakHashMap<View,Style>()
    private val active=WeakHashMap<TextView,Style>()
    private var installed=false

    fun install(){
        if(installed)return
        installed=runCatching {
            val color=XposedHelpers.findField(TextView::class.java,"mCurTextColor")
            val draw=TextView::class.java.getDeclaredMethod("onDraw",Canvas::class.java)
            XposedBridge.hookMethod(draw,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    if(active.isEmpty())return
                    val label=param.thisObject as TextView
                    val style=active[label] ?: return
                    if(!style.owns(label)){active.remove(label);return}
                    val original=color.getInt(label)
                    if(Color.alpha(original)==0)return
                    val brightness=(Color.red(original)*54+Color.green(original)*183+Color.blue(original)*19) shr 8
                    if(style.lightText && brightness>=175 || !style.lightText && brightness<=102)return
                    param.setObjectExtra("cpiBannerTextColor",original)
                    val replacement=if(style.lightText)0x00ffffff else 0x0017181b
                    color.setInt(label,(maxOf(Color.alpha(original),204) shl 24) or replacement)
                }
                override fun afterHookedMethod(param:MethodHookParam){
                    val original=param.getObjectExtra("cpiBannerTextColor") as? Int ?: return
                    color.setInt(param.thisObject,original)
                }
            })
        }.isSuccess
    }

    fun use(row:View,lightText:Boolean){
        if(!installed || rows[row]?.lightText==lightText)return
        clear(row)
        rows[row]=Style(row,lightText).also {it.scan(row)}
    }
    fun has(row:View)=rows.containsKey(row)
    fun clear(row:View){rows.remove(row)?.dispose(row)}
    fun clearAll(){rows.keys.toList().forEach(::clear)}
}
