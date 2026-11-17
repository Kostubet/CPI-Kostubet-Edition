package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Looper
import android.view.View
import com.cosmod.launcherglass.ui.NativePreviewScenes.Scene
import com.cosmod.launcherglass.util.SurfacePreviewKind
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XposedHelpers

/** One explicit capture of the OEM foreground and its real material footprint. */
internal object NativeMaterialPreview {
    private class Capture(val kind:SurfacePreviewKind,val mask:Canvas){val bounds=RectF()}
    private val current=ThreadLocal<Capture>()
    fun active(kind:SurfacePreviewKind)=current.get()?.kind==kind

    /** Record the OEM drawing in the same coordinate space as the mask and crop. */
    fun capture(bounds: RectF, path: Path, foreground: (Canvas) -> Unit): Scene? {
        if (bounds.isEmpty || path.isEmpty) return null
        val scale = minOf(1f, 600f / maxOf(bounds.width(), bounds.height()))
        val width = kotlin.math.ceil(bounds.width() * scale).toInt().coerceAtLeast(1)
        val height = kotlin.math.ceil(bounds.height() * scale).toInt().coerceAtLeast(1)
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        maskCanvas.scale(scale, scale)
        maskCanvas.translate(-bounds.left, -bounds.top)
        maskCanvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val node = RenderNode("CPI.previewForeground").apply { setPosition(0, 0, width, height) }
        try {
            val canvas = node.beginRecording()
            try {
                canvas.scale(scale, scale)
                canvas.translate(-bounds.left, -bounds.top)
                foreground(canvas)
            } finally {
                node.endRecording()
            }
            val hardware = XposedHelpers.callStaticMethod(
                HardwareRenderer::class.java, "createHardwareBitmap", node, width, height
            ) as Bitmap
            val content = try {
                requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
            } finally {
                hardware.recycle()
            }
            return Scene(content, shape = mask)
        } catch (error: Throwable) {
            mask.recycle()
            throw error
        } finally {
            node.discardDisplayList()
        }
    }

    fun captureWithoutMaterial(kind: SurfacePreviewKind, draw: () -> Scene?): Scene? {
        val previous = current.get()
        current.set(Capture(kind, Canvas()))
        return try {
            draw()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
    fun background(kind:SurfacePreviewKind,canvas:Canvas,bounds:RectF,path:Path?=null,radius:Float=0f):Boolean {
        val capture=current.get()?.takeIf {it.kind==kind} ?: return false
        @Suppress("DEPRECATION") val matrix=Matrix().also(canvas::getMatrix)
        capture.bounds.union(RectF(bounds).apply {matrix.mapRect(this)})
        val save=capture.mask.save()
        try {
            capture.mask.concat(matrix)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE}
            if(path!=null)capture.mask.drawPath(path,paint)else capture.mask.drawRoundRect(bounds,radius,radius,paint)
        }finally {capture.mask.restoreToCount(save)}
        return true
    }
    fun snapshot(kind:SurfacePreviewKind):Scene? {
        check(Looper.myLooper()==Looper.getMainLooper())
        if (kind == SurfacePreviewKind.DOCK) return DockMaterial.snapshotPreview()
        if (kind == SurfacePreviewKind.VOLUME) return VolumeGlass.snapshotPreview()
        if (kind.ordinal >= SurfacePreviewKind.BANNER_SMS.ordinal) {
            return NotificationBannerGlass.snapshotPreview(kind.ordinal - SurfacePreviewKind.BANNER_SMS.ordinal)
        }
        val view: View = LiveAlertGlass.previewSource() ?: return null
        if(view.width<=0 || view.height<=0)return null
        val scale=minOf(1f,768f/maxOf(view.width,view.height))
        val w=(view.width*scale).toInt().coerceAtLeast(1);val h=(view.height*scale).toInt().coerceAtLeast(1)
        val node=RenderNode("CPI.nativePreview").apply {setPosition(0,0,w,h)}
        val mask=RenderNode("CPI.nativePreviewShape").apply {setPosition(0,0,w,h)}
        fun invalidateTree(node:View){
            node.invalidate()
            if(node is android.view.ViewGroup)for(i in 0 until node.childCount)invalidateTree(node.getChildAt(i))
        }
        return try {
            val shape=mask.beginRecording()
            val capture=Capture(kind,shape);current.set(capture)
            val canvas=node.beginRecording()
            try {
                invalidateTree(view)
                canvas.scale(scale,scale)
                view.draw(canvas)
            }finally {node.endRecording();mask.endRecording();current.remove();invalidateTree(view)}
            if(capture.bounds.isEmpty)return null
            val clip=Rect(kotlin.math.floor(capture.bounds.left).toInt().coerceIn(0,w-1),
                kotlin.math.floor(capture.bounds.top).toInt().coerceIn(0,h-1),
                kotlin.math.ceil(capture.bounds.right).toInt().coerceIn(1,w),
                kotlin.math.ceil(capture.bounds.bottom).toInt().coerceIn(1,h))
            if(clip.isEmpty)return null
            fun bitmap(record:RenderNode):Bitmap {
                val hardware=XposedHelpers.callStaticMethod(HardwareRenderer::class.java,"createHardwareBitmap",record,w,h) as Bitmap
                val image=try {requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888,false))}finally {hardware.recycle()}
                return Bitmap.createBitmap(image,clip.left,clip.top,clip.width(),clip.height()).also {if(it!==image)image.recycle()}
            }
            val shapeBitmap=bitmap(mask)
            try {Scene(bitmap(node),shape=shapeBitmap)}catch(error:Throwable){shapeBitmap.recycle();throw error}
        }catch(error:Throwable){GlassLogger.limited("NativePreview",kind.name){"OEM component capture unavailable: $error"};null}
        finally {current.remove();node.discardDisplayList();mask.discardDisplayList()}
    }
}
