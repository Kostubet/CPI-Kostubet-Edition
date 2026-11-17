package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class EuclideanDistanceTest {
    @Test fun diagonalDistanceHasNoEightDirectionBias(){
        val map=FloatArray(7*7){1e12f};map[0]=0f
        EuclideanDistance.transform(map,7,7)
        assertEquals(25f,map[4*7+3],.0001f)
        assertEquals(13f,map[3*7+2],.0001f)
    }
    @Test fun weightedTransformMatchesExhaustiveNearestSeed(){
        val random=java.util.Random(43)
        repeat(20){
            val w=3+random.nextInt(12);val h=2+random.nextInt(10)
            val seeds=FloatArray(w*h){if(random.nextInt(4)==0)random.nextFloat()*2f else 1e6f}
            seeds[0]=.16f
            val result=seeds.copyOf();EuclideanDistance.transform(result,w,h)
            for(y in 0 until h)for(x in 0 until w){
                var expected=Float.POSITIVE_INFINITY
                for(sy in 0 until h)for(sx in 0 until w){
                    val dx=x-sx;val dy=y-sy
                    expected=minOf(expected,seeds[sy*w+sx]+dx*dx+dy*dy)
                }
                assertEquals(expected,result[y*w+x],.0001f)
            }
        }
    }
    @Test fun onePixelAndLineKeepTheirSeedCosts(){
        val pixel=floatArrayOf(.25f);EuclideanDistance.transform(pixel,1,1);assertEquals(.25f,pixel[0],0f)
        val line=floatArrayOf(0f,1e6f,1e6f);EuclideanDistance.transform(line,1,3)
        assertArrayEquals(floatArrayOf(0f,1f,4f),line,0f)
    }
}
