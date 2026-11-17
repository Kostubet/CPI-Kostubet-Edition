package com.cosmod.launcherglass.util

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.FileProvider
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.xposed.IconBitmapProcessor
import com.cosmod.launcherglass.xposed.ProcessedIcon
import java.io.ByteArrayOutputStream
import java.io.File

internal object PreviewIcons {
    const val MAX_SIDE = 256
    data class Icon(val source: Bitmap, val layers: ProcessedIcon)

    fun read(context: Context,packages:List<String> = emptyList()): List<Icon> = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
            ?: return emptyList()
        val directory = File(context.cacheDir, "preview-icons").apply { mkdirs() }
        val file = File.createTempFile("icons-", ".bin", directory)
        try {
            val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".logs", file)
            val permission = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.grantUriPermission(launcher, uri, permission)
            try {
                val request = Intent(HostControl.PREVIEW_ICONS).setPackage(launcher)
                    .putExtra(HostControl.PREVIEW_ICONS_URI, uri.toString())
                    .putStringArrayListExtra(HostControl.PREVIEW_APPS,ArrayList(packages.take(3)))
                if (HostControl.request(context, request, 3200).first != SystemUiControl.ACCEPTED) return emptyList()
                file.inputStream().use(PreviewIconArchive::read).map { icon ->
                    Icon(decode(icon.source), ProcessedIcon(decode(icon.glyph), decode(icon.mask), icon.color))
                }
            } finally {
                context.revokeUriPermission(uri, permission)
            }
        } finally {
            file.delete()
        }
    }.getOrDefault(emptyList())

    fun readInstalled(context:Context,packages:List<String>,limit:Int=3):List<Icon>{
        val manager=context.packageManager
        val launcherIntent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities=manager.queryIntentActivities(launcherIntent,0)
            .distinctBy {it.activityInfo.packageName}
        val lookup=activities.associateBy {it.activityInfo.packageName}
        val preferred=packages.mapNotNull(lookup::get)
        val ordered=(preferred+activities).distinctBy {it.activityInfo.packageName}
        fun load(info:ResolveInfo):Icon?=runCatching {
            val size=144
            val source=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            val drawable=info.loadIcon(manager).mutate()
            drawable.setBounds(0,0,size,size)
            drawable.draw(Canvas(source))
            IconBitmapProcessor.previewLayers(source,100)?.let {Icon(source,it)}
        }.getOrNull()
        return ordered.asSequence().mapNotNull(::load).take(limit).toList()
    }

    fun write(context: Context, uri: Uri, icons: List<Icon>): Boolean = runCatching {
        if (icons.isEmpty()) return false
        val snapshots = icons.take(PreviewIconArchive.MAX_ICONS).map { icon ->
            PreviewIconArchive.Icon(icon.layers.bgColor, png(icon.source),
                png(icon.layers.plateMaskBitmap), png(icon.layers.glyphBitmap))
        }
        context.contentResolver.openOutputStream(uri)?.use { PreviewIconArchive.write(it, snapshots) } ?: return false
        true
    }.getOrDefault(false)

    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..MAX_SIDE && bounds.outHeight in 1..MAX_SIDE)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
    }
}
