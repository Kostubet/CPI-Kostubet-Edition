package com.cosmod.launcherglass.ui

import kotlin.math.abs

/** Motion of the existing context preview. Coordinates are relative to its fully open bounds. */
internal object ContextMenuMotion {
    const val OPEN_MS = 290
    const val CLOSE_MS = 860
    const val CARD_CLOSE_MS = 540

    data class Frame(
        val x: Float = 0f,
        val y: Float = 0f,
        val scale: Float = 1f,
        val alpha: Float = 1f,
        val menuY: Float = 0f,
        val menuScale: Float = 1f,
        val menuAlpha: Float = 1f,
        val backdrop: Float = 1f,
        val rowAlpha: Float = 0f,
        val rowY: Float = 0f,
        val rowLift: Float = 0f,
    )

    // Tracked against the settled card at 2.48 s. Scale, center and visibility settle at
    // different times. Unresolved frames at the start of the dissolve interpolate into the trace.
    private val openScale = Curve(0f,.95f, .09f,1.016f, .19f,1.09f, .32f,1.042f,
        .40f,1.027f, .48f,1.015f, .58f,1.002f, .72f,1f, 1f,1f)
    private val openY = Curve(0f,.133f, .10f,.107f, .19f,.060f, .32f,.025f,
        .40f,.014f, .48f,.005f, .59f,-.004f, .78f,-.002f, 1f,0f)
    private val openAlpha = Curve(0f,0f, .06f,.10f, .12f,.36f, .19f,.67f,
        .32f,.79f, .48f,.89f, .71f,.99f, .78f,1f, 1f,1f)
    private val openBackdrop = Curve(0f,0f, .07f,.23f, .19f,.55f, .30f,.81f,
        .49f,.88f, .66f,.98f, .84f,1f, 1f,1f)
    private val openMenuAlpha = Curve(0f,0f, .14f,0f, .24f,.18f, .35f,.66f, .54f,.98f, .66f,1f, 1f,1f)
    private val openMenuOffset = Curve(0f,0f, .16f,.10f, .32f,.56f, .50f,.88f, .67f,1f, 1f,1f)

    // Pan distance divided by card height, measured over the descent beginning at 3.43 s.
    private val pullScale = Curve(0f,1f, .025f,.985f, .061f,.960f, .121f,.925f,
        .184f,.877f, .35f,.777f, .46f,.750f, .88f,.705f, 1.16f,.700f)
    private val closeScale = Curve(0f,0f, .12f,.13f, .27f,.49f, .44f,.87f, .62f,1f, 1f,1f)
    private val closeCardAlpha = Curve(0f,1f, .12f,1f, .25f,.89f, .37f,.57f, .48f,.15f, .56f,0f, 1f,0f)
    private val closeMenuAlpha = Curve(0f,1f, .06f,.85f, .13f,.31f, .21f,0f, 1f,0f)
    // The card is already offscreen while the list underneath is still dimmed.
    private val closeBackdrop = Curve(0f,1f, .22f,1f, .37f,.81f, .47f,.63f, .56f,.44f,
        .65f,.25f, .75f,.14f, .84f,.05f, .94f,.02f, 1f,0f)
    private val returnRow = Curve(0f,.94f, .22f,.82f, .44f,.41f, .64f,-.09f, .75f,-.14f, 1f,0f)

    fun opening(time: Float, anchorSide: Float): Frame {
        if (time >= 1f) return Frame()
        val menu = openMenuOffset.at(time)
        return Frame(
            x = anchorSide * .24f * (1f - InterfaceMotion.glide(time / .58f)),
            y = openY.at(time), scale = openScale.at(time), alpha = openAlpha.at(time),
            menuY = -.11f * (1f - menu), menuScale = .94f + .06f * menu,
            menuAlpha = openMenuAlpha.at(time), backdrop = openBackdrop.at(time),
            rowAlpha = 1f - InterfaceMotion.glide((time - .035f) / .31f),
            rowLift = 1f - InterfaceMotion.glide(time / .35f),
        )
    }

    fun dragging(frame: Frame, x: Float, y: Float, menuTravel: Float): Frame = frame.copy(
        x = frame.x + x, y = frame.y + y, scale = frame.scale * pullScale.at(abs(y)),
        menuY = frame.menuY + menuTravel * .70f,
        menuAlpha = frame.menuAlpha * (1f - InterfaceMotion.glide((abs(y) - .018f) / .22f)),
    )

    fun closing(progress: Float, start: Frame, velocityInCardHeights: Float, rowDirection: Float,
        swipeSpeedDp: Float): Frame {
        if (progress <= 0f) return start
        val time = (progress * CLOSE_MS / CARD_CLOSE_MS).coerceAtMost(1f)
        val leave = .68f
        val distance = maxOf(.91f - start.y, .41f, velocityInCardHeights * leave / 1.25f)
        val slope = (velocityInCardHeights * leave / distance).coerceIn(0f,1.25f)
        val translation = InterfaceMotion.glide(time / leave, slope)
        val scale = start.scale + (minOf(start.scale,.70f) - start.scale) * closeScale.at(time)
        val rowPhase = ((time - .54f) / .46f).coerceIn(0f,1f)
        val rowTravel = 1f + .60f * InterfaceMotion.glide(abs(swipeSpeedDp) / 2200f)
        return Frame(
            x = start.x * (1f + .21f * InterfaceMotion.glide(time / leave)),
            y = start.y + distance * translation, scale = scale, alpha = start.alpha * closeCardAlpha.at(time),
            menuY = start.menuY + .16f * InterfaceMotion.glide(time / .29f),
            menuScale = start.menuScale * (1f - .06f * InterfaceMotion.glide(time / .29f)),
            menuAlpha = start.menuAlpha * closeMenuAlpha.at(time), backdrop = start.backdrop * closeBackdrop.at(progress),
            rowAlpha = start.rowAlpha * (1f - InterfaceMotion.glide(time / .14f)) + InterfaceMotion.glide(rowPhase / .38f),
            rowY = -rowDirection * rowTravel * returnRow.at(rowPhase) * InterfaceMotion.glide((time - .14f) / .22f),
            rowLift = start.rowLift * (1f - InterfaceMotion.glide(time / .14f)),
        )
    }

    /** Slope-limited Hermite interpolation between measured samples. */
    private class Curve(vararg points: Float) {
        private val samples = points
        private val slopes = FloatArray(samples.size / 2).apply {
            for (i in 1 until lastIndex) {
                val k = i * 2
                val left = (samples[k+1]-samples[k-1])/(samples[k]-samples[k-2])
                val right = (samples[k+3]-samples[k+1])/(samples[k+2]-samples[k])
                if (left * right > 0f) this[i] = 2f * left * right / (left + right)
            }
        }

        fun at(time: Float): Float {
            if (time <= samples[0]) return samples[1]
            for (i in 0 until slopes.lastIndex) {
                val k = i * 2
                val end = samples[k + 2]
                if (time > end) continue
                val span = end - samples[k]
                val u = (time - samples[k]) / span
                val uu = u * u
                val uuu = uu * u
                return (2f*uuu-3f*uu+1f)*samples[k+1] + (uuu-2f*uu+u)*span*slopes[i] +
                    (3f*uu-2f*uuu)*samples[k+3] + (uuu-uu)*span*slopes[i+1]
            }
            return samples.last()
        }
    }
}
