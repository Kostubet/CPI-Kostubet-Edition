package com.cosmod.launcherglass.xposed

import android.graphics.*

/** Lightweight finish: a dark upper face and a luminous lower bevel, like the timer card. */
internal object GlassFrame {
    fun dock(canvas:Canvas,bounds:Rect,radius:Float,rim:Int,dp:Float){
        val rect=RectF(bounds)
        val path=Path().apply {addRoundRect(rect,radius,radius,Path.Direction.CW)}
        val save=canvas.save()
        try {
            canvas.clipPath(path)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader=LinearGradient(0f,rect.top,0f,rect.bottom,
                intArrayOf(0x22ffffff,0x08ffffff,0x16ffffff),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
            canvas.drawRect(rect,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.1f*dp
            val alpha=(rim.coerceIn(0,100)*1.5f).toInt()
            paint.shader=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,
                intArrayOf(Color.argb(alpha,255,255,255),Color.argb(alpha/6,255,255,255),Color.argb(alpha/2,255,255,255)),
                floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
            canvas.drawPath(path,paint)
        }finally{canvas.restoreToCount(save)}
    }
    fun draw(canvas:Canvas,bounds:Rect,radius:Float,shade:Int,glow:Int,rim:Int,path:Path?=null) {
        if(bounds.isEmpty)return
        val rect=RectF(bounds)
        val shape=path ?: Path().apply {addRoundRect(rect,radius,radius,Path.Direction.CW)}
        val save=canvas.save()
        try {
            canvas.clipPath(shape)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            fun alpha(percent:Int,scale:Float)=((percent.coerceIn(0,100)/100f)*255*scale).toInt().coerceIn(0,255)
            paint.shader=LinearGradient(0f,rect.top,0f,rect.bottom,
                intArrayOf(Color.argb(alpha(shade,1f),0,0,0),Color.argb(alpha(shade,.62f),0,0,0),Color.TRANSPARENT),
                floatArrayOf(0f,.6f,1f),Shader.TileMode.CLAMP)
            canvas.drawRect(rect,paint)
            paint.shader=LinearGradient(0f,rect.top,0f,rect.bottom,
                intArrayOf(Color.TRANSPARENT,Color.TRANSPARENT,Color.argb(alpha(glow,.45f),222,232,213)),
                floatArrayOf(0f,.65f,1f),Shader.TileMode.CLAMP)
            canvas.drawRect(rect,paint)
            paint.style=Paint.Style.STROKE
            // Stroke is clipped to the inside, keeping it within the photo's mask.
            paint.strokeWidth=(minOf(rect.width(),rect.height())*.012f).coerceIn(1.5f,4f)
            paint.shader=LinearGradient(0f,rect.top,0f,rect.bottom,
                intArrayOf(Color.argb(alpha(rim,.22f),255,255,255),Color.argb(alpha(rim,.12f),255,255,255),Color.argb(alpha(rim,1f),235,244,227)),
                floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP)
            canvas.drawPath(shape,paint)
        }finally{canvas.restoreToCount(save)}
    }
}
