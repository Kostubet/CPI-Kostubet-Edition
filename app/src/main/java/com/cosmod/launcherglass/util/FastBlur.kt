package com.cosmod.launcherglass.util

import android.graphics.Bitmap

object FastBlur {
    private const val ALPHA_MASK = 0xff000000.toInt()

    fun blur(src: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return src
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        stackBlur(pixels, width, height, radius)

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    internal fun stackBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius < 1 || width < 1 || height < 1) return
        val transposed = IntArray(width * height)
        val divisor = (radius + 1) * (radius + 1)
        // Round the fixed-point reciprocal up so the weighted 8-bit sum
        // still produces the same floor as integer division.
        val reciprocal = ((1L shl 55) + divisor - 1) / divisor
        blurAndTranspose(pixels, transposed, height, width, radius, reciprocal)
        blurAndTranspose(transposed, pixels, width, height, radius, reciprocal)
    }

    private fun blurAndTranspose(
        source: IntArray,
        target: IntArray,
        lines: Int,
        length: Int,
        radius: Int,
        reciprocal: Long
    ) {
        val last = length - 1
        val leftWeight = (radius + 1) * (radius + 2) / 2
        for (line in 0 until lines) {
            val base = line * length
            val first = source[base]
            var sum = redBlue(first) * leftWeight
            var greenSum = green(first) * leftWeight
            var outgoing = redBlue(first) * (radius + 1)
            var greenOutgoing = green(first) * (radius + 1)
            var incoming = 0L
            var greenIncoming = 0
            for (i in 1..radius) {
                val pixel = source[base + minOf(i, last)]
                val weight = radius + 1 - i
                val channels = redBlue(pixel)
                val g = green(pixel)
                sum += channels * weight
                greenSum += g * weight
                incoming += channels
                greenIncoming += g
            }

            var destination = line
            for (i in 0 until last) {
                target[destination] = (source[base + i] and ALPHA_MASK) or rgb(sum, greenSum, reciprocal)
                val next = source[base + i + 1]
                val entering = source[base + minOf(i + radius + 1, last)]
                val leaving = source[base + maxOf(i - radius, 0)]
                val nextChannels = redBlue(next)
                val enteringChannels = redBlue(entering)
                val nextGreen = green(next)
                val enteringGreen = green(entering)
                // The triangular kernel loses one unit of weight on the
                // left of its center and gains one on the right at each step.
                sum = sum - outgoing + incoming + enteringChannels
                greenSum = greenSum - greenOutgoing + greenIncoming + enteringGreen
                outgoing = outgoing - redBlue(leaving) + nextChannels
                greenOutgoing = greenOutgoing - green(leaving) + nextGreen
                incoming += enteringChannels - nextChannels
                greenIncoming += enteringGreen - nextGreen
                destination += lines
            }
            target[destination] = (source[base + last] and ALPHA_MASK) or rgb(sum, greenSum, reciprocal)
        }
    }

    // The independent 32-bit lanes fit the full supported weighted sum.
    private fun redBlue(pixel: Int) = ((pixel and 0xff0000).toLong() shl 16) or (pixel and 255).toLong()
    private fun green(pixel: Int) = (pixel ushr 8) and 255

    private fun rgb(channels: Long, greenSum: Int, reciprocal: Long): Int {
        val red = (((channels ushr 32) * reciprocal) ushr 55).toInt()
        val green = ((greenSum * reciprocal) ushr 55).toInt()
        val blue = (((channels and 0xffffffffL) * reciprocal) ushr 55).toInt()
        return (red shl 16) or (green shl 8) or blue
    }
}
