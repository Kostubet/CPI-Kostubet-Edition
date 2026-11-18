package com.cosmod.launcherglass.camera

internal class ZoomFrameWatchdog(openedAt: Long) {
    private var lastFrame = openedAt

    fun acceptFrame(at: Long): Boolean {
        if (at < lastFrame) return false
        lastFrame = at
        return true
    }

    fun remaining(at: Long): Long = (TIMEOUT_MS - (at - lastFrame)).coerceIn(0L, TIMEOUT_MS)

    companion object {
        const val TIMEOUT_MS = 300L
    }
}
