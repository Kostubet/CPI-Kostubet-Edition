package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class DockGeometryTest {
    private fun bounds(height:Int,width:Int=100)=DockGeometry.calculate(80,40,320,100,400,180,1f,6,width,height)!!
    @Test fun heightAbove100GrowsEquallyAroundItsBaseline(){
        val normal=bounds(100);val large=bounds(150)
        assertTrue(large.bottom-large.top>normal.bottom-normal.top)
        assertEquals(normal.top-large.top,large.bottom-normal.bottom)
    }
    @Test fun narrowerAndWiderPlatesStayInsideHostWidth(){
        for(percent in 55..160){val b=bounds(100,percent);assertTrue(b.left>=8);assertTrue(b.right<=392)}
    }
    @Test fun heightDoesNotShiftIconCenter(){
        val normal=bounds(100)
        for(percent in 55..160){val b=bounds(percent);assertTrue(kotlin.math.abs(normal.top+normal.bottom-b.top-b.bottom)<=1)}
    }
    @Test fun smallOrEmptyHostsHaveNoPlate(){
        assertNull(DockGeometry.calculate(0,0,10,10,8,30,1f,0,100,100))
        assertNull(DockGeometry.calculate(10,10,10,20,400,30,1f,0,100,100))
    }
}
