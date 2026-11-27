package com.cosmod.launcherglass.util

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class FastBlurTest {
    @Test fun packedBlurPreservesTheIntegerKernelAndOriginalAlpha() {
        for ((width, height, radius) in listOf(Triple(40, 41, 1), Triple(9, 3, 3),
            Triple(1, 7, 8), Triple(11, 1, 65), Triple(7, 5, 110), Triple(3, 2, 0))) {
            val random = Random(17)
            val source = IntArray(width * height) { random.nextInt() }
            val expected = referencePass(referencePass(source, width, height, radius, vertical = false),
                width, height, radius, vertical = true)
            val actual = source.copyOf()
            FastBlur.stackBlur(actual, width, height, radius)
            assertArrayEquals("${width}x${height}, radius=$radius", expected, actual)
        }
    }

    @Test fun rollingChannelsDoNotCarryIntoEachOther() {
        val width = 17
        val height = 8
        val radius = 6
        val source = IntArray(width * height) { index ->
            val color = when (index % 4) {
                0 -> 0xff0000
                1 -> 0x00ff00
                2 -> 0x0000ff
                else -> 0xffffff
            }
            ((index * 19 and 255) shl 24) or color
        }
        val expected = referencePass(referencePass(source, width, height, radius, vertical = false),
            width, height, radius, vertical = true)
        FastBlur.stackBlur(source, width, height, radius)
        assertArrayEquals(expected, source)
    }

    private fun referencePass(pixels: IntArray, width: Int, height: Int, radius: Int, vertical: Boolean): IntArray {
        val divisor = (radius + 1) * (radius + 1)
        return IntArray(pixels.size) { index ->
            val x = index % width
            val y = index / width
            var pixel = pixels[index] and 0xff000000.toInt()
            for (shift in listOf(0, 8, 16)) {
                var sum = 0
                for (offset in -radius..radius) {
                    val sampleX = if (vertical) x else (x + offset).coerceIn(0, width - 1)
                    val sampleY = if (vertical) (y + offset).coerceIn(0, height - 1) else y
                    val value = (pixels[sampleY * width + sampleX] ushr shift) and 255
                    sum += value * (radius + 1 - abs(offset))
                }
                pixel = pixel or ((sum / divisor) shl shift)
            }
            pixel
        }
    }
}
