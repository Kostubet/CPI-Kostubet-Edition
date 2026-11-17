package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class DockToneMatrixTest {
    @Test fun defaultsDoNotChangeAnyChannel(){
        assertArrayEquals(floatArrayOf(1f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f),DockToneMatrix.values(100,0),.00001f)
    }
    @Test fun darknessReachesBlackWithoutChangingAlpha(){
        val matrix=DockToneMatrix.values(200,100)
        assertTrue(matrix.take(15).all {kotlin.math.abs(it)<.00001f})
        assertEquals(1f,matrix[18],0f)
    }
    @Test fun grayscaleConservesNeutralBrightness(){
        val matrix=DockToneMatrix.values(0,0)
        for(row in 0..2)assertEquals(1f,matrix[row*5]+matrix[row*5+1]+matrix[row*5+2],.00001f)
        assertEquals(matrix[0],matrix[5],0f)
    }
}
