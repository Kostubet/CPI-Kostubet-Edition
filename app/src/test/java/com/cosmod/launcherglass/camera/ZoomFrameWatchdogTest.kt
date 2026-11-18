package com.cosmod.launcherglass.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomFrameWatchdogTest {
    @Test fun inputOnlyDoesNotExtendTheDeadline() {
        val watchdog = ZoomFrameWatchdog(1000)
        for (time in 1020L..1280L step 20) assertEquals(1300 - time, watchdog.remaining(time))
        assertEquals(0L, watchdog.remaining(1300))
    }

    @Test fun onlyFreshFramesMoveTheDeadline() {
        val watchdog = ZoomFrameWatchdog(1000)
        assertFalse(watchdog.acceptFrame(990))
        assertTrue(watchdog.acceptFrame(1130))
        assertFalse(watchdog.acceptFrame(1120))
        assertEquals(30L, watchdog.remaining(1400))
        assertEquals(0L, watchdog.remaining(1430))
    }
}
