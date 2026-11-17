package com.cosmod.launcherglass.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

internal object PreviewCardMotion {
    const val OPEN_DURATION_MS = 335
    const val CLOSE_DURATION_MS = 345
    const val SWIPE_CLOSE_DURATION_MS = 595
    const val LANDING = .62f
    const val OVERRUN_PEAK = .34f

    data class Frame(
        val position: Float,
        val scale: Float,
        val cardAlpha: Float,
        val rowAlpha: Float,
        val rowSettling: Float = 0f,
        val overrun: Float = 0f,
        val lift: Float = 0f,
        val rowFollowsCard: Float = 0f,
        val rowOffsetY: Float = 0f,
    )

    fun openingFrame(time:Float):Frame {
        val position=openingProgress(time)
        val bounce=openingStrain(time)
        return Frame(position,.90f+.10f*position+bounce,
            InterfaceMotion.glide((time-.035f)/.60f),1f-InterfaceMotion.glide((time-.06f)/.48f),lift=bounce)
    }

    fun closingFrame(time:Float,start:Frame,travel:Float,overrun:Float):Frame {
        val cardFade=InterfaceMotion.glide((time-.34f)/.41f)
        val rowFade=InterfaceMotion.glide((time-.39f)/.23f)
        return Frame(
            position=start.position*closingProgress(time,travel,overrun),
            scale=start.scale*(1f-.24f*InterfaceMotion.glide(time/.71f)),
            cardAlpha=start.cardAlpha*(1f-cardFade),
            rowAlpha=start.rowAlpha+(1f-start.rowAlpha)*rowFade,
            rowSettling=rowSettling(time),
            overrun=closingOverrun(time,travel,overrun),
            lift=start.lift*(1f-InterfaceMotion.glide(time/.36f)),
            rowFollowsCard=InterfaceMotion.glide(time/.38f),
        )
    }

    fun swipeFrame(time:Float,start:Frame,rowTravel:Float,rowOverrun:Float):Frame {
        val returnPhase=((time-.47f)/.53f).coerceIn(0f,1f)
        return Frame(
            position=start.position,
            scale=start.scale,
            cardAlpha=start.cardAlpha*(1f-InterfaceMotion.glide((time-.10f)/.37f)),
            rowAlpha=start.rowAlpha*(1f-InterfaceMotion.glide(time/.16f))+
                InterfaceMotion.glide((returnPhase-.025f)/.28f),
            rowSettling=InterfaceMotion.glide(returnPhase),
            rowOffsetY=(rowTravel*closingProgress(returnPhase,rowTravel,rowOverrun)-
                closingOverrun(returnPhase,rowTravel,rowOverrun))*InterfaceMotion.glide(time/.18f),
            lift=start.lift,
        )
    }

    fun swipeDeparture(time:Float,offset:Float,distance:Float,velocityPxPerMs:Float,durationMs:Int):Float {
        val departureTime=.56f
        val slope=(velocityPxPerMs*durationMs*departureTime/distance).coerceIn(0f,1.25f)
        return offset+distance*InterfaceMotion.glide(time/departureTime,slope)
    }

    fun openingProgress(time:Float):Float {
        return InterfaceMotion.glide(time/.88f)
    }

    fun openingStrain(time:Float):Float =
        .015f*InterfaceMotion.glide((time-.47f)/.27f)*(1f-InterfaceMotion.glide((time-.74f)/.26f))

    fun menuReveal(time:Float,opening:Boolean):Float =
        if(opening)InterfaceMotion.glide((time-.34f)/.52f)
        else 1f-InterfaceMotion.glide(time/.28f)

    fun overrunDp(speedDp:Float):Float =
        7f+29f*(1f-exp(-abs(speedDp).coerceAtMost(3600f)/1190f))

    fun rowSettling(time:Float):Float = InterfaceMotion.glide((time-.60f)/.40f)

    fun rowDirection(rowCenter:Float,viewportHeight:Float):Float =
        if(rowCenter>=viewportHeight*.5f)1f else -1f

    fun pullProgress(offset:Float,height:Float):Float {
        val cardHeight=height.coerceAtLeast(1f)
        val onset=cardHeight*.025f
        val distance=hypot(offset,onset)-onset
        val t=(distance/(cardHeight*.58f)).coerceIn(0f,1f)
        return t*(2f-t)
    }

    fun releasedOffset(phase:Float,offset:Float,momentum:Float):Float =
        offset*(1f-InterfaceMotion.glide(phase))+momentum*InterfaceMotion.velocityBasis(phase)

    fun closingProgress(time:Float,travel:Float,overrun:Float):Float {
        if(time>=LANDING)return 0f
        val phase=time/LANDING
        val endSlope=if(travel>0f)landingVelocity(travel,overrun)*LANDING/travel else 0f
        return 1f-InterfaceMotion.glide(phase)+endSlope*InterfaceMotion.velocityBasis(1f-phase)
    }

    fun closingOverrun(time:Float,travel:Float,overrun:Float):Float {
        if(time<=LANDING || overrun<=0f)return 0f
        val phase=((time-LANDING)/(1f-LANDING)).coerceIn(0f,1f)
        if(phase>=OVERRUN_PEAK)return overrun*(1f-InterfaceMotion.glide((phase-OVERRUN_PEAK)/(1f-OVERRUN_PEAK)))
        val approach=phase/OVERRUN_PEAK
        val startVelocity=landingVelocity(travel,overrun)*(1f-LANDING)*OVERRUN_PEAK
        return overrun*InterfaceMotion.glide(approach,startVelocity/overrun)
    }

    // Match velocity and acceleration across the row's original position.
    private fun landingVelocity(travel:Float,overrun:Float):Float =
        minOf(travel*1.4f/LANDING,overrun*1.3f/((1f-LANDING)*OVERRUN_PEAK))

}
