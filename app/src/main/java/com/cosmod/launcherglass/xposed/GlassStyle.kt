package com.cosmod.launcherglass.xposed

import android.graphics.ColorMatrixColorFilter

internal object GlassSurface {
    const val FOLDERS=1
    const val WIDGETS=2
    const val DOCK=3
    const val BANNERS=4
    const val LIVE=5
    const val PAGE_INDICATOR=12
}

data class GlassStyle(val liquid:Boolean,val width:Int,val distortion:Int,
    val radius:Int,val opacity:Int,val filter:ColorMatrixColorFilter?,val folder:Boolean=false,val plateColor:Int?=null,val extendedLens:Boolean=false,val neutral:Boolean=false,val rimOpacity:Int?=null,val volumeOpacity:Int?=null,val surfaceScope:Int=0,val dispersion:Int=0){
    companion object {
        fun icons()=GlassStyle(PrefsHelper.liquidGlass,PrefsHelper.liquidWidth,PrefsHelper.liquidDistortion,
            PrefsHelper.blurRadius,PrefsHelper.bgOpacity,PrefsHelper.blurColorFilter,plateColor=PrefsHelper.iconPlateColor(),dispersion=PrefsHelper.iconDispersion)
    }
}
