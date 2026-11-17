package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class CatalogSearchGeometryTest {
    @Test fun restoredSearchWaitsForItsNarrowerLayout(){
        assertEquals(54f,CatalogSearchGeometry.topWithoutOverlap(6.5f,54f,-46,6),.001f)
        assertEquals(6.5f,CatalogSearchGeometry.topWithoutOverlap(6.5f,54f,12,6),.001f)
        assertEquals(64f,CatalogSearchGeometry.topWithoutOverlap(64f,54f,-46,6),.001f)
    }
    @Test fun restUsesFullWidth(){assertEquals(0f,CatalogSearchGeometry.reservedWidth(0f),0f)}
    @Test fun pinnedSearchAndButtonHaveTheSameCenter(){
        assertEquals(6f+46f/2f,CatalogSearchGeometry.top(1f,64f,1f)+45f/2f,.001f)
        assertEquals(58f,CatalogSearchGeometry.reservedWidth(1f),.001f)
    }
    @Test fun searchClearsTheButtonBeforeTheirVerticalBoundsIntersect(){
        for(i in 0..1000){val p=i/1000f
            if(CatalogSearchGeometry.top(p,64f,1f)<52f)
                assertEquals(58f,CatalogSearchGeometry.reservedWidth(p),.001f)
        }
    }
    @Test fun widthChangesContinuously(){
        var previous=0f
        for(i in 0..1000){val value=CatalogSearchGeometry.reservedWidth(i/1000f);assertTrue(value>=previous);assertTrue(value-previous<.5f);previous=value}
    }
    @Test fun flingReboundNarrowsFurtherWithoutCoveringTheButton(){
        assertTrue(CatalogSearchGeometry.reservedWidth(1.04f)>58f)
        assertTrue(CatalogSearchGeometry.reservedWidth(1.04f)<=72f)
        assertEquals(58f,CatalogSearchGeometry.reservedWidth(1f),.001f)
    }
    @Test fun overshootIsBoundedAndNeverExpandsPastTheScreen(){
        assertEquals(72f,CatalogSearchGeometry.reservedWidth(5f),.001f)
        assertEquals(0f,CatalogSearchGeometry.reservedWidth(-.1f),.001f)
    }
}
