package com.cosmod.launcherglass.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.ui.NativePreviewScenes.Scene
import java.io.ByteArrayOutputStream
import java.io.File

/** A single bounded snapshot, outside Binder's image-size limit; no invented widget fallback. */
internal object NativeWidgetPreviews {
    fun read(context:Context,kind:WidgetPreviewKind):Scene?=read(context,kind.ordinal,HostControl.PREVIEW_WIDGET)
    fun folder(context:Context,kind:FolderPreviewKind):Scene?=read(context,kind.ordinal,HostControl.PREVIEW_FOLDER)
    fun material(context:Context,kind:SurfacePreviewKind):Scene?=
        read(context,kind.ordinal,HostControl.PREVIEW_SURFACE,kind.role)

    private fun read(context:Context,id:Int,action:String,role:String="launcher"):Scene?=runCatching {
        val host=if(role=="systemui")"com.android.systemui" else
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)
                ?.activityInfo?.packageName ?: return null
        val dir=File(context.cacheDir,"preview-widgets").apply {mkdirs()}
        val file=File.createTempFile("widget-",".bin",dir)
        try {
            val uri=FileProvider.getUriForFile(context,BuildConfig.APPLICATION_ID+".logs",file)
            val permission=Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.grantUriPermission(host,uri,permission)
            try {
                val request=Intent(action).setPackage(host)
                    .putExtra(HostControl.PREVIEW_WIDGET_URI,uri.toString()).putExtra(HostControl.PREVIEW_WIDGET_KIND,id)
                if(HostControl.request(context,request,4000).first!=SystemUiControl.ACCEPTED)return null
                val image=file.inputStream().use(PreviewIconArchive::read).single()
                require(image.color==id)
                fun decode(bytes:ByteArray):Bitmap {
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(bounds.outWidth in 1..768 && bounds.outHeight in 1..768)
                    return requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
                }
                if (action == HostControl.PREVIEW_SURFACE && id >= SurfacePreviewKind.BANNER_SMS.ordinal) Scene(decode(image.source))
                else if(action!=HostControl.PREVIEW_WIDGET)Scene(decode(image.source),shape=decode(image.mask))
                else Scene(decode(image.source),decode(image.mask),decode(image.glyph))
            }finally {context.revokeUriPermission(uri,permission)}
        }finally {file.delete()}
    }.getOrNull()

    fun write(context:Context,uri:Uri,id:Int,scene:Scene):Boolean=runCatching {
        fun encode(bitmap:Bitmap?,lossless:Boolean):ByteArray=ByteArrayOutputStream().use {output->
            val image=bitmap ?: Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
            check(image.compress(if(lossless)Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.WEBP_LOSSY,95,output))
            if(bitmap==null)image.recycle()
            output.toByteArray()
        }
        val image=PreviewIconArchive.Icon(id,encode(scene.content,scene.shape!=null),encode(scene.shape ?: scene.text,true),encode(scene.logos,true))
        context.contentResolver.openOutputStream(uri)?.use {PreviewIconArchive.write(it,listOf(image))} ?: return false
        true
    }.getOrDefault(false)
}
