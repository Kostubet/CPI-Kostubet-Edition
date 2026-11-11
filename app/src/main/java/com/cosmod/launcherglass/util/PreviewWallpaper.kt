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
            readFromLauncher(context) ?: readStatic(manager)
        } else {
            readStatic(manager) ?: readFromLauncher(context)
        }
    }

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

    private fun readFromLauncher(context: Context): Bitmap? = runCatching {
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

    fun writeFromLauncher(context: Context, uri: Uri): Boolean = runCatching {
        val bitmap = WallpaperHelper.readSystemSnapshot(context)
            ?: readStatic(WallpaperManager.getInstance(context))
            ?: return false
        val preview = bounded(bitmap)
        context.contentResolver.openOutputStream(uri)?.use {
            preview.compress(Bitmap.CompressFormat.JPEG, 91, it)
        } ?: false
    }.getOrDefault(false)

    private fun bounded(bitmap: Bitmap): Bitmap {
        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }
}
