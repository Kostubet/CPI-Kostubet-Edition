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
        val rect=Rect(native?.bounds ?: Rect())
        if(rect.isEmpty || !rect.intersect(0,0,view.width,view.height))rect.set(0,0,view.width,view.height)
        val nativeOutline=Outline()
        native?.getOutline(nativeOutline)
        val outlined=Rect()
        val nativeRound=nativeOutline.getRect(outlined) && !outlined.isEmpty && nativeOutline.radius>0f
        val source=path(native)?.takeUnless {it.isEmpty}
        if(source!=null){
            if(shape.source!==source || shape.generation!=generation(source) || shape.rect!=rect){
                shape.source=source;shape.generation=generation(source);shape.rect.set(rect)
                shape.path.set(source)
                shape.path.computeBounds(shape.bounds,true)
                // BackgroundBlurDrawable's path is already in view pixels.
                // Stretching it to the full view introduces a second edge
                // during the card-to-pill morph and on inset cards.
                if(!shape.bounds.isEmpty &&
                    shape.bounds.left>=-.5f && shape.bounds.top>=-.5f &&
                    shape.bounds.right<=rect.width()+.5f && shape.bounds.bottom<=rect.height()+.5f &&
                    !RectF(rect).contains(shape.bounds)){
                    shape.path.offset(rect.left.toFloat(),rect.top.toFloat())
                    shape.bounds.offset(rect.left.toFloat(),rect.top.toFloat())
                }
                shape.radius=if(nativeRound)nativeOutline.radius else minOf(rect.width(),rect.height())*.28f
                shape.nativePath=true;shape.revision++
            }
        }else {
            var radius=minOf(rect.width(),rect.height())*.25f
            if(nativeRound){
                rect.set(outlined)
                radius=nativeOutline.radius
            }else{
                val outline=Outline()
                view.outlineProvider?.getOutline(view,outline)
                if(outline.getRect(outlined) && !outlined.isEmpty && outline.radius>0f){
                    rect.set(outlined)
                    radius=outline.radius
                }
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
