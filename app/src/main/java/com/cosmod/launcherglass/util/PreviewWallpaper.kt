package com.cosmod.launcherglass.util

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.cosmod.launcherglass.BuildConfig
import java.io.File
import kotlin.math.roundToInt

/** Transfers only a bounded wallpaper image through a temporary URI granted to the current launcher. */
internal object PreviewWallpaper {
    private const val MAX_SIDE = 900

    fun read(context: Context): Bitmap? {
        val manager = WallpaperManager.getInstance(context)
        return if (runCatching { manager.wallpaperInfo != null }.getOrDefault(false)) {
            readLive(context) ?: runCatching {
                val thumbnail = manager.wallpaperInfo?.loadThumbnail(context.packageManager) ?: return@runCatching null
                val width = thumbnail.intrinsicWidth.coerceAtLeast(1)
                val height = thumbnail.intrinsicHeight.coerceAtLeast(1)
                val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(width, height))
                Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    thumbnail.setBounds(0, 0, it.width, it.height)
                    thumbnail.draw(android.graphics.Canvas(it))
                }
            }.getOrNull()
        } else {
            readStatic(manager) ?: readFromLauncher(context)
        }
    }

    fun isLive(context: Context): Boolean = runCatching {
        WallpaperManager.getInstance(context).wallpaperInfo != null
    }.getOrDefault(false)

    fun readLive(context: Context): Bitmap? = readFromLauncher(context, live = true)

    // Wallpaper access depends on the injected host. Callers fall back to the launcher snapshot.
    @android.annotation.SuppressLint("MissingPermission")
    private fun readStatic(manager: WallpaperManager): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use {
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds)
        }
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) / MAX_SIDE).coerceAtLeast(1))
        }
        manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use {
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)?.let(::bounded)
        }
    }.getOrNull()

    private fun readFromLauncher(context: Context, live: Boolean = false): Bitmap? = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName ?: return null
        val directory = File(context.cacheDir, "preview-wallpaper").apply { mkdirs() }
        val file = File.createTempFile("wallpaper-", ".jpg", directory)
        try {
            val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".logs", file)
            val permission = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.grantUriPermission(launcher, uri, permission)
            try {
                val request = Intent(HostControl.PREVIEW_WALLPAPER).setPackage(launcher)
                    .putExtra(HostControl.PREVIEW_URI, uri.toString())
                    .putExtra(HostControl.PREVIEW_WALLPAPER_LIVE, live)
                if (HostControl.request(context, request, 3200).first == SystemUiControl.ACCEPTED) {
                    BitmapFactory.decodeFile(file.path)
                } else null
            } finally {
                context.revokeUriPermission(uri, permission)
            }
        } finally {
            file.delete()
        }
    }.getOrNull()

    fun writeFromLauncher(context: Context, uri: Uri, live: Boolean): Boolean = runCatching {
        val frame = WallpaperHelper.readSystemSnapshot(context, fresh = live, maxSide = MAX_SIDE)
            ?: if (live) null else readStatic(WallpaperManager.getInstance(context))
        if (frame == null) return false
        val software = if (frame.config == Bitmap.Config.HARDWARE) frame.copy(Bitmap.Config.ARGB_8888, false) else frame
        val preview = bounded(software)
        try {
            context.contentResolver.openOutputStream(uri)?.use {
                preview.compress(Bitmap.CompressFormat.JPEG, 88, it)
            } ?: false
        } finally {
            if (preview !== software) preview.recycle()
            if (software !== frame) software.recycle()
            frame.recycle()
        }
    }.getOrDefault(false)

    private fun bounded(bitmap: Bitmap): Bitmap {
        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }
}
