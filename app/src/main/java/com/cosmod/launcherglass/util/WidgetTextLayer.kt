package com.cosmod.launcherglass.util

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView

/** Retains actual text glyphs instead of excluding rectangular widget regions. */
internal class WidgetTextLayer {
    private val node=RenderNode("CPI.widgetText")
    private val remove=Paint().apply {blendMode=BlendMode.DST_OUT}
    private val world=Matrix();private val inverse=Matrix();private val local=Matrix()
    private var software:Bitmap?=null
    private var hasText=false
    private val names=java.util.WeakHashMap<ImageView,Pair<Int,String>>()
    private val nativeText=java.util.WeakHashMap<View,Boolean>()
    private val textLayouts=HashMap<Class<*>,Boolean>()
    private fun textNode(view:View):Boolean {
        if(view is TextView)return view.layout!=null
        if(view is ImageView || view is ViewGroup && view.childCount>0)return false
        nativeText[view]?.let {return it}
        // Quick App's text components are custom Views, not Android TextViews.
        // Detect their public accessibility role once, without reading/logging text.
        val layout=textLayouts.getOrPut(view.javaClass){
            generateSequence(view.javaClass as Class<*>?){it.superclass}.takeWhile {it!=View::class.java}
                .any {owner->owner.declaredFields.any {android.text.Layout::class.java.isAssignableFrom(it.type)}}
        }
        val text=layout || runCatching {
            val info=view.createAccessibilityNodeInfo()
            try {info!=null && !info.text.isNullOrEmpty()}finally {info?.recycle()}
        }.getOrDefault(false)
        nativeText[view]=text
        return text
    }
    fun record(host:View,preserveImages:Boolean=false,includeText:Boolean=true,
               imagePredicate:((ImageView)->Boolean)?=null,contentRoot:View=host,
               clipBounds:RectF?=null,outside:Boolean=false,contentMatrix:Matrix?=null,
               excludedRoot:View?=null,preserveUnidentifiedMedia:Boolean=false):Boolean {
        hasText=false;software=null
        if(host.width<=0 || host.height<=0)return false
        world.reset();toGlobal.invoke(if(contentMatrix!=null)contentRoot else host,world)
        if(!world.invert(inverse))return false
        node.setPosition(0,0,host.width,host.height)
        val canvas=node.beginRecording()
        try {
            if(clipBounds!=null){if(outside)canvas.clipOutRect(clipBounds)else canvas.clipRect(clipBounds)}
            fun visit(view:View){
                if(view===excludedRoot || view.visibility!=View.VISIBLE || view.alpha<=0f)return
                fun coordinates(){
                    local.reset();toGlobal.invoke(view,local);local.postConcat(inverse)
                    if(contentMatrix!=null)local.postConcat(contentMatrix)
                    canvas.concat(local)
                    canvas.translate(-view.scrollX.toFloat(),-view.scrollY.toFloat())
                }
                if(includeText && textNode(view)){
                    val save=canvas.save()
                    try {
                        coordinates()
                        // TextView itself handles gravity, compound drawables and baseline.
                        // View.draw() would include its background and foreground decorators.
                        (if(view is TextView)drawText else drawNativeText).invoke(view,canvas);hasText=true
                    }finally {canvas.restoreToCount(save)}
                }
                else if(preserveImages && view is ImageView &&
                    (imagePredicate?.invoke(view) ?: WidgetTintPolicy.logo(view.width,view.height,contentRoot.width,contentRoot.height,
                        names[view]?.takeIf {it.first==view.id}?.second ?: runCatching {
                            view.resources.getResourceEntryName(view.id)
                        }.getOrDefault("").also {names[view]=view.id to it},
                        preserveUnidentifiedMedia && view.drawable is android.graphics.drawable.BitmapDrawable))){
                    val save=canvas.save()
                    try {
                        coordinates()
                        if(view.cropToPadding)canvas.clipRect(view.paddingLeft,view.paddingTop,
                            view.width-view.paddingRight,view.height-view.paddingBottom)
                        canvas.translate(view.paddingLeft.toFloat(),view.paddingTop.toFloat())
                        canvas.concat(view.imageMatrix)
                        view.drawable?.let {it.draw(canvas);hasText=true}
                    }finally {canvas.restoreToCount(save)}
                }
                if(view is ViewGroup)for(index in 0 until view.childCount)visit(view.getChildAt(index))
            }
            visit(contentRoot)
        }finally {node.endRecording()}
        return hasText
    }
    fun cutOut(canvas:Canvas){
        if(!hasText)return
        val save=canvas.saveLayer(null,remove)
        try {drawNode(canvas)}finally {canvas.restoreToCount(save)}
    }
    fun draw(canvas:Canvas,paint:Paint?=null){
        if(!hasText)return
        if(paint==null)drawNode(canvas)else{
            val save=canvas.saveLayer(null,paint)
            try {drawNode(canvas)}finally {canvas.restoreToCount(save)}
        }
    }
    private fun drawNode(canvas:Canvas){
        if(canvas.isHardwareAccelerated){canvas.drawRenderNode(node);return}
        val image=software ?: runCatching {
            val hardware=de.robv.android.xposed.XposedHelpers.callStaticMethod(HardwareRenderer::class.java,
                "createHardwareBitmap",node,node.width,node.height) as Bitmap
            hardware.copy(Bitmap.Config.ARGB_8888,false).also {hardware.recycle();software=it}
        }.getOrNull() ?: return
        canvas.drawBitmap(image,0f,0f,null)
    }
    private companion object {
        val toGlobal=View::class.java.getDeclaredMethod("transformMatrixToGlobal",Matrix::class.java).apply {isAccessible=true}
        val drawText=TextView::class.java.getDeclaredMethod("onDraw",Canvas::class.java).apply {isAccessible=true}
        val drawNativeText=View::class.java.getDeclaredMethod("onDraw",Canvas::class.java).apply {isAccessible=true}
    }
}
