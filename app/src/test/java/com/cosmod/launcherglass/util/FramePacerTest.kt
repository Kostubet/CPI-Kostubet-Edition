package com.cosmod.launcherglass.util
import com.cosmod.launcherglass.xposed.SampleFrameClock
import org.junit.Assert.*
import org.junit.Test
class FramePacerTest {
    @Test fun displayRatesAreNotHalvedByMillisecondRounding(){
        for(display in listOf(24,30,48,60,90,120,165))for(limit in listOf(30,60,120)){
            val p=FramePacer();val frames=(0 until display*3).count {p.ready(it*1_000_000_000L/display,limit)}
            assertTrue("display=$display limit=$limit frames=$frames",kotlin.math.abs(minOf(display,limit)*3-frames)<=1)
        }
    }
    @Test fun pauseDoesNotQueueCatchupWork(){
        val p=FramePacer();assertTrue(p.ready(1L,60));assertFalse(p.ready(2L,60))
        assertTrue(p.ready(5_000_000_000L,60));assertFalse(p.ready(5_000_000_001L,60))
        p.reset();assertTrue(p.ready(5_000_000_001L,60))
    }
    @Test fun optionalHighRefreshUsesEvenDisplayIntervals(){
        for((limit,steps) in listOf(30 to 6,45 to 4,60 to 3,120 to 2)){
            val pacer=FramePacer()
            val selected=(0 until 330).filter {frame->
                pacer.ready(frame*1_000_000_000L/165,limit,165f)
            }
            assertEquals((0 until 330 step steps).toList(),selected)
        }
    }
    @Test fun captureClockContinuesAt120HzAndResetsAfterClosing() {
        val clock = SampleFrameClock()
        val start = 1_000_000_000L
        clock.record(start, 120)
        for (frame in 1..120) {
            val time = start + frame * 1_000_000_000L / 120
            assertTrue(clock.remainingNs(time - 2_000_000L, 120) > 0)
            assertEquals(0L, clock.remainingNs(time, 120))
            clock.record(time, 120)
        }
        val lastFrame = start + 1_000_000_000L
        assertTrue(clock.remainingNs(lastFrame, 120) > 0)
        clock.reset()
        assertEquals(0L, clock.remainingNs(lastFrame, 120))
    }
    @Test fun switchingHighRefreshPolicyClearsTheOldDeadline(){
        val pacer=FramePacer()
        assertTrue(pacer.ready(1_000_000L,60))
        assertTrue(pacer.ready(2_000_000L,60,165f))
        assertTrue(pacer.ready(3_000_000L,60,120f))
    }
}
