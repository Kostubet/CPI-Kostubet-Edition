package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.reflect.Field
import java.util.WeakHashMap

/** Copies the OEM's actual smooth path, rather than approximating it with a circle. */
internal object AlertContour {
    class Shape {
        val path=Path();val bounds=RectF()
        var radius=0f;var revision=0;var nativePath=false
        internal var source:Path?=null;internal var generation=-1
        internal var drawable:Drawable?=null;internal val rect=Rect()
    }
    private val shapes=WeakHashMap<View,Shape>()
    private class Geometry {
        val rect=Rect()
        val outlined=Rect()
        val outline=Outline()

        inline fun readRoundRect(draw: (Outline) -> Unit): Boolean {
            outline.setEmpty()
            draw(outline)
            return outline.getRect(outlined) && !outlined.isEmpty && outline.radius>0f
        }
    }
    private val geometry=ThreadLocal.withInitial { Geometry() }
    private val fields=HashMap<Class<*>,Field?>()
    private fun path(drawable:Drawable?):Path? {
        if(drawable==null)return null
        val cls=drawable.javaClass
        val field=if(fields.containsKey(cls))fields[cls] else {
            var type:Class<*>?=cls;var found:Field?=null
            while(type!=null && found==null){
                found=type.declaredFields.firstOrNull {(it.name=="mRectPath" || it.name=="mPath") && it.type==Path::class.java}
                type=type.superclass
            }
            found?.isAccessible=true;fields[cls]=found;found
        }
        return try {field?.get(drawable) as? Path}catch(_:Exception){null}
    }
    private val generationMethod by lazy {try {Path::class.java.getMethod("getGenerationId")}catch(_:Exception){null}}
    private fun generation(path:Path):Int=if(android.os.Build.VERSION.SDK_INT>=34)path.generationId else try {(generationMethod?.invoke(path) as? Int) ?: 0}catch(_:Exception){0}
    fun resolve(view:View,native:Drawable?):Shape {
        val shape=shapes.getOrPut(view){Shape()}
        val scratch=geometry.get()!!
        val rect=scratch.rect
        if(native!=null)rect.set(native.bounds)else rect.setEmpty()
        if(rect.isEmpty || !rect.intersect(0,0,view.width,view.height))rect.set(0,0,view.width,view.height)
        val source=path(native)?.takeUnless {it.isEmpty}
        if(source!=null){
            val generation=generation(source)
            if(shape.source!==source || shape.generation!=generation || shape.rect!=rect){
                shape.source=source;shape.generation=generation;shape.rect.set(rect)
                shape.path.set(source)
                shape.path.computeBounds(shape.bounds,true)
                // BackgroundBlurDrawable's path is already in view pixels.
                // Stretching it to the full view introduces a second edge
                // during the card-to-pill morph and on inset cards.
                if(!shape.bounds.isEmpty &&
                    shape.bounds.left>=-.5f && shape.bounds.top>=-.5f &&
                    shape.bounds.right<=rect.width()+.5f && shape.bounds.bottom<=rect.height()+.5f &&
                    !(rect.left<=shape.bounds.left && rect.top<=shape.bounds.top &&
                        rect.right>=shape.bounds.right && rect.bottom>=shape.bounds.bottom)){
                    shape.path.offset(rect.left.toFloat(),rect.top.toFloat())
                    shape.bounds.offset(rect.left.toFloat(),rect.top.toFloat())
                }
                shape.radius=if(scratch.readRoundRect {native?.getOutline(it)})scratch.outline.radius
                    else minOf(rect.width(),rect.height())*.28f
                shape.nativePath=true;shape.revision++
            }
        }else {
            var radius=minOf(rect.width(),rect.height())*.25f
            if(scratch.readRoundRect {native?.getOutline(it)} ||
                scratch.readRoundRect {view.outlineProvider?.getOutline(view,it)}){
                rect.set(scratch.outlined)
                radius=scratch.outline.radius
            }
            if(shape.source!=null || shape.drawable!==native || shape.rect!=rect || shape.radius!=radius){
                shape.source=null;shape.drawable=native;shape.rect.set(rect);shape.bounds.set(rect);shape.radius=radius
                shape.path.reset();shape.path.addRoundRect(shape.bounds,radius,radius,Path.Direction.CW)
                shape.nativePath=false;shape.revision++
            }
        }
        return shape
    }
}
