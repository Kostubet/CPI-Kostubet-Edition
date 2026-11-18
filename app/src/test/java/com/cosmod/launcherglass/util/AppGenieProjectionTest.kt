package com.cosmod.launcherglass.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGenieProjectionTest {
    @Test
    fun projectedIconKeepsItsAnchorEvenWithAnOffsetCrop() {
        val deformation = AppWindowDeformation(.14f, .08f, .72f, 1.16f, .38f, .57f)
        val matrix = FloatArray(16)
        assertTrue(deformation.writeCenteredMatrix(matrix, 48f, 67f, 136f, 98f))
        val x = 116f
        val y = 116f
        val w = matrix[13] * y + matrix[15]
        assertEquals(x, (x + matrix[1] * y + matrix[3]) / w, .001f)
        assertEquals(y, (matrix[5] * y + matrix[7]) / w, .001f)
        assertNotEquals(0f, matrix[13], .0001f)
    }
}
