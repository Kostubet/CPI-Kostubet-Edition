package com.cosmod.launcherglass.xposed

import kotlin.math.abs

/** Separates simple launcher plates without projecting the color of a logo across a row. */
internal object ClassicIconMatte {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int, val opaque: Int) {
        fun coversTile(pixelCount: Int) = opaque.toLong() * 100 >= pixelCount.toLong() * 54
    }

    fun bounds(pixels: IntArray, width: Int): Bounds? {
        var left = width
        var right = -1
        var top = pixels.size / width
        var bottom = -1
        var opaque = 0
        for (i in pixels.indices) {
            if (pixels[i] ushr 24 < 160) continue
            val x = i % width
            val y = i / width
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
            opaque++
        }
        return if (opaque == 0) null else Bounds(left, top, right, bottom, opaque)
    }

    fun extract(pixels: IntArray, width: Int, bounds: Bounds): IntArray? {
        val spanX = bounds.right - bounds.left
        val spanY = bounds.bottom - bounds.top
        if (spanX < 12 || spanY < 12) return null

        val insetX = (spanX * .1f).toInt().coerceAtLeast(1)
        val insetY = (spanY * .1f).toInt().coerceAtLeast(1)
        val nearX = bounds.left + insetX
        val farX = bounds.right - insetX
        val nearY = bounds.top + insetY
        val farY = bounds.bottom - insetY
        val sides = Array(4) { IntArray(7) }
        for (index in 0..6) {
            val t = .26f + .08f * index
            val x = bounds.left + (spanX * t).toInt()
            val y = bounds.top + (spanY * t).toInt()
            sides[0][index] = pixels[y * width + nearX]
            sides[1][index] = pixels[y * width + farX]
            sides[2][index] = pixels[nearY * width + x]
            sides[3][index] = pixels[farY * width + x]
        }

        fun fit(shift: Int): FloatArray {
            val side = FloatArray(4)
            for (i in side.indices) {
                val samples = sides[i].filter { it ushr 24 >= 208 }.map { it shr shift and 255 }.sorted()
                if (samples.size < 5) return floatArrayOf()
                side[i] = samples[samples.size / 2].toFloat()
            }
            return floatArrayOf(side.average().toFloat(), (side[1] - side[0]) / (farX - nearX),
                (side[3] - side[2]) / (farY - nearY))
        }
        val red = fit(16)
        if (red.isEmpty()) return null
        val green = fit(8)
        val blue = fit(0)
        val midX = (bounds.left + bounds.right) * .5f
        val midY = (bounds.top + bounds.bottom) * .5f
        val foreground = IntArray(pixels.size)
        var visible = 0
        for (y in bounds.top..bounds.bottom) {
            val dy = y - midY
            val r = red[0] + red[2] * dy
            val g = green[0] + green[2] * dy
            val b = blue[0] + blue[2] * dy
            for (x in bounds.left..bounds.right) {
                val i = y * width + x
                val pixel = pixels[i]
                val alpha = pixel ushr 24
                if (alpha == 0) continue
                val dx = x - midX
                val difference = maxOf(abs((pixel shr 16 and 255) - r - red[1] * dx),
                    abs((pixel shr 8 and 255) - g - green[1] * dx),
                    abs((pixel and 255) - b - blue[1] * dx))
                val mix = ((difference - 29f) / 62f).coerceIn(0f, 1f)
                val opacity = (alpha * mix * mix * (3f - 2f * mix)).toInt()
                if (opacity >= 56) visible++
                foreground[i] = (opacity shl 24) or (pixel and 0xffffff)
            }
        }
        // Photographic icons and multicolored tiles retain their native artwork.
        return foreground.takeIf { visible >= bounds.opaque * .02f && visible <= bounds.opaque * .51f }
    }
}
