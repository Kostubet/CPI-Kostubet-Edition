package com.cosmod.launcherglass.util

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import kotlin.math.roundToInt
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter

/** Immutable snapshot shared by all icon render paths. Constructed on preference changes only. */
data class IconPalette(val custom: Boolean, val background: Int, val foreground: Int, val clear: Boolean = false,
    val tintHue: Int = 0, val tintSaturation: Int = 0, val whiteGlyph: Boolean = false) {
    val filter = PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_IN)
    data class WallpaperTone(val hue:Int,val saturation:Int) {
        fun saturationAt(amount:Int)=(saturation*amount/50f).roundToInt().coerceIn(0,100)
    }

    companion object {
        const val FOLLOW_WALLPAPER="detailed_wallpaper_colors"

        fun wallpaperTone(context:Context):WallpaperTone?=runCatching {
            wallpaperTone(WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
        }.getOrNull()

        fun wallpaperTone(colors:WallpaperColors?):WallpaperTone? {
            colors ?: return null
            val hsv=FloatArray(3)
            var primary:WallpaperTone?=null
            for(color in listOfNotNull(colors.primaryColor,colors.secondaryColor,colors.tertiaryColor)){
                Color.colorToHSV(color.toArgb(),hsv)
                val tone=WallpaperTone(hsv[0].roundToInt(),(hsv[1]*100f).roundToInt())
                if(primary==null)primary=tone
                if(tone.saturation>=12 && hsv[2]>=.16f)return tone
            }
            return primary?.copy(saturation=0)
        }

        fun isSystemDark(context: android.content.Context): Boolean =
            context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        fun from(mode: Int, hue: Int, saturation: Int, brightness: Int, dark: Boolean = true, clear: Boolean = false): IconPalette {
            val h = hue.coerceIn(0, 360).toFloat()
            val s = saturation.coerceIn(0, 100) / 100f
            val v = brightness.coerceIn(0, 100) / 100f
            if (clear) {
                val plate = if (dark) .06f + .52f * v else .44f + .54f * v
                val logo = .65f + .25f * v
                return IconPalette(mode == 1, Color.HSVToColor(floatArrayOf(0f,0f,plate)),
                    if (dark) Color.HSVToColor(floatArrayOf(0f,0f,logo)) else Color.WHITE,
                    mode == 1, whiteGlyph = !dark)
            }
            // Both layers share the selected hue. Let saturation affect both
            // layers directly; brightness supplies the theme-specific separation.
            val background: Int
            val foreground: Int
            if (dark) {
                background = Color.HSVToColor(floatArrayOf(h, s, .06f + .52f * v))
                foreground = Color.HSVToColor(floatArrayOf(h, s * .55f, .25f + .65f * v))
            } else {
                background = Color.HSVToColor(floatArrayOf(h, s, .44f + .54f * v))
                foreground = Color.WHITE
            }
            // Do not counteract these user-controlled tones with automatic
            // contrast correction: it made dark logos bright and light ones dark.
            return IconPalette(mode == 1, background, foreground, false, h.toInt(), saturation.coerceIn(0,100), !dark)
        }
    }
}
