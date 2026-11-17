package com.cosmod.launcherglass.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.AtomicFile
import java.io.File

/** Local preview photo only. Never changes a gallery widget or the original image. */
internal object PreviewPhotoStore {
    private fun file(context:Context)=File(context.filesDir,"widget-preview.webp")
    fun read(context:Context):Bitmap?=runCatching {BitmapFactory.decodeFile(file(context).path)}.getOrNull()
    fun replace(context:Context,uri:Uri):Bitmap {
        val image=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)){decoder,info,_->
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            val scale=minOf(1f,1024f/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
        }
        val atomic=AtomicFile(file(context));val stream=atomic.startWrite()
        try {check(image.compress(Bitmap.CompressFormat.WEBP_LOSSY,95,stream));atomic.finishWrite(stream)}
        catch(error:Throwable){atomic.failWrite(stream);image.recycle();throw error}
        return image
    }
}
