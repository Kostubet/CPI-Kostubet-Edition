package com.cosmod.launcherglass.ui

import kotlin.math.abs
import kotlin.math.exp

/** A preview's center and size share the same progress from the source row. */
internal object ContextMenuMotion {
    const val OPEN_MS = 365
    const val CLOSE_MS = 470
    const val SWIPE_CLOSE_MS = 490

    data class Frame(
        val position: Float = 1f,
        val scale: Float = 1f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val backdrop: Float = 1f,
        val menu: Float = 1f,
        val rowLift: Float = 0f,
    ) {
        val shapeProgress: Float
            get() {
                val progress = position.coerceIn(0f, 1f)
                val approach = (progress / .14f).coerceAtMost(1f)
                return progress * approach * (2f - approach)
            }
        val materialAlpha get() = InterfaceMotion.glide(shapeProgress / .56f)
        val contentAlpha get() = InterfaceMotion.glide((shapeProgress - .17f) / .57f)
        val rowAlpha get() = 1f - InterfaceMotion.glide((shapeProgress - .18f) / .60f)
    }

    fun opening(time: Float): Frame {
        val position = InterfaceMotion.glide(time / .87f)
        val expansion = InterfaceMotion.glide((time - .50f) / .24f)
        val settle = InterfaceMotion.glide((time - .74f) / .26f)
        return Frame(
            position = position,
            scale = 1f + .023f * expansion * (1f - settle),
            backdrop = InterfaceMotion.glide((time - .04f) / .80f),
            menu = InterfaceMotion.glide((time - .46f) / .51f),
            rowLift = 1f - InterfaceMotion.glide(time / .58f),
        )
    }

    fun dragging(frame: Frame, x: Float, y: Float, height: Float): Frame {
        val response = frame.shapeProgress
        val pull = PreviewCardMotion.pullProgress(y, height)
        return frame.copy(
            offsetX = frame.offsetX + x * response,
            offsetY = frame.offsetY + y * response,
            scale = frame.scale * (1f - .27f * pull * response),
            menu = frame.menu * (1f - .88f * pull),
        )
    }

    fun closing(time: Float, start: Frame, travel: Float, overrun: Float, momentum: Float): Frame {
        if (time <= 0f) return start
        val distance = travel.coerceAtLeast(1f)
        val returnFraction = PreviewCardMotion.closingProgress(time, distance, overrun)
        val springBack = PreviewCardMotion.closingOverrun(time, distance, overrun) / distance
        val center = returnFraction - springBack
        val approach = (time / PreviewCardMotion.LANDING).coerceIn(0f, 1f)
        return Frame(
            position = start.position * center,
            offsetX = start.offsetX * center,
            offsetY = start.offsetY * center + momentum * InterfaceMotion.velocityBasis(approach),
            scale = 1f + (start.scale - 1f) * returnFraction,
            backdrop = start.backdrop * (1f - InterfaceMotion.glide((time - .09f) / .78f)),
            menu = start.menu * (1f - InterfaceMotion.glide(time / .42f)),
            rowLift = start.rowLift * (1f - InterfaceMotion.glide(approach)),
        )
    }

    fun overrun(distance: Float, density: Float, swipeSpeed: Float): Float {
        val speedDp = abs(swipeSpeed) / density
        val bounce = (8f + 19f * (1f - exp(-speedDp / 1250f))) * density
        return bounce.coerceAtMost(distance * .20f)
    }
}
