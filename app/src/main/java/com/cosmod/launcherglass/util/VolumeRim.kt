package com.cosmod.launcherglass.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.RenderNode
import android.graphics.RenderEffect

internal class VolumeRim {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var height = 0f
    private var strength = -1
    private val node=RenderNode("CPI.glassRim").apply {setClipToBounds(false)}
    private var key=""

    fun draw(canvas: Canvas, bounds: RectF, radius: Float, stroke: Float, percent: Int,softness:Int=0) {
        if (percent <= 0 || bounds.isEmpty) return
        if (height != bounds.height() || strength != percent) {
            height = bounds.height()
            strength = percent
            val colors = intArrayOf(100, 29, 8, 64).map {
                Color.argb((it * percent / 100).coerceAtMost(255), 255, 255, 255)
            }.toIntArray()
            paint.shader = LinearGradient(0f, 0f, 0f, height, colors,
                floatArrayOf(0f, .20f, .73f, 1f), Shader.TileMode.CLAMP)
        }
        paint.strokeWidth = stroke
        val inset = stroke * .5f
        val corner = (radius - inset).coerceAtLeast(0f)
        val saved = canvas.save()
        canvas.translate(bounds.left, bounds.top)
        if(softness>0 && canvas.isHardwareAccelerated){
            val next="${bounds.width()}:$height:$radius:$stroke:$percent:$softness"
            if(key!=next || !node.hasDisplayList()){
                node.setPosition(0,0,bounds.width().toInt().coerceAtLeast(1),height.toInt().coerceAtLeast(1))
                val target=node.beginRecording()
                try {target.drawRoundRect(inset,inset,bounds.width()-inset,height-inset,corner,corner,paint)}finally {node.endRecording()}
                val blur=(stroke*softness*.025f).coerceAtLeast(.01f)
                node.setRenderEffect(RenderEffect.createBlurEffect(blur,blur,Shader.TileMode.CLAMP));key=next
            }
            canvas.drawRenderNode(node)
        }else canvas.drawRoundRect(inset, inset, bounds.width() - inset, height - inset, corner, corner, paint)
        canvas.restoreToCount(saved)
    }
}
