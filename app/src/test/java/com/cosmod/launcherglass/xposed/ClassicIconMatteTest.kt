package com.cosmod.launcherglass.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClassicIconMatteTest {
    @Test
    fun backingDoesNotFollowLogoAtTheEdgesOfARow() {
        val width = 80
        val pixels = IntArray(width * width) { index ->
            val x = index % width
            val y = index / width
            if (x in 4..54 && y in 31..43) 0xffebe5df.toInt()
            else 0xff000000.toInt() or ((34 + x / 4 + y / 3) * 0x010101)
        }
        val result = ClassicIconMatte.extract(pixels, width, ClassicIconMatte.bounds(pixels, width)!!)!!
        assertEquals(255, result[37 * width + 30] ushr 24)
        assertEquals(0, result[37 * width + 64] ushr 24)
        assertEquals(0, result[29 * width + 30] ushr 24)
    }

    @Test
    fun denselyTexturedTileKeepsItsArtwork() {
        val width = 80
        val pixels = IntArray(width * width) { i ->
            when (i % 3) {
                0 -> 0xfff04825.toInt()
                1 -> 0xff154bd8.toInt()
                else -> 0xff28d649.toInt()
            }
        }
        // A dense tile with no isolated logo must not generate a silhouette.
        assertNull(ClassicIconMatte.extract(pixels, width, ClassicIconMatte.bounds(pixels, width)!!))
        assertNotNull(ClassicIconMatte.bounds(pixels, width))
    }
}
