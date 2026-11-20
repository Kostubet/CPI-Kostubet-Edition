package com.cosmod.launcherglass.xposed

import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorMatrix
import android.util.LruCache

internal object GlassSurface {
    const val FOLDERS=1
    const val WIDGETS=2
    const val DOCK=3
    const val BANNERS=4
    const val LIVE=5
    const val PAGE_INDICATOR=12
    const val VOLUME=13
    const val LOCKSCREEN=14
}

internal object BackdropBrightness {
    private val filters=LruCache<Pair<ColorMatrixColorFilter?,Int>,ColorMatrixColorFilter>(24)

    fun filter(original:ColorMatrixColorFilter?,brightness:Int):ColorMatrixColorFilter? {
        if(brightness==100)return original
        val key=original to brightness
        filters.get(key)?.let {return it}
        val matrix=ColorMatrix()
        original?.getColorMatrix(matrix)
        val gain=brightness*.01f
        matrix.postConcat(ColorMatrix().apply {setScale(gain,gain,gain,1f)})
        return ColorMatrixColorFilter(matrix).also {filters.put(key,it)}
    }
}

data class GlassStyle(val liquid:Boolean,val width:Int,val distortion:Int,
    val radius:Int,val opacity:Int,val filter:ColorMatrixColorFilter?,val folder:Boolean=false,val plateColor:Int?=null,val extendedLens:Boolean=false,val neutral:Boolean=false,val rimOpacity:Int?=null,val volumeOpacity:Int?=null,val surfaceScope:Int=0,val dispersion:Int=0,
    val newEngine:Boolean=KyantGlassEngine.enabled(surfaceScope),
    val engineOptics:com.cosmod.launcherglass.ui.GlassEngineSettings.Optics=KyantGlassEngine.optics(surfaceScope),
    val folderBackdrop:Boolean=false,val largeFolderBackdrop:Boolean=false){
    val backdropFilter get()=BackdropBrightness.filter(filter,engineOptics.backdropBrightness)

    companion object {
        fun icons() = GlassStyle(PrefsHelper.liquidGlass, PrefsHelper.liquidWidth, PrefsHelper.liquidDistortion,
            PrefsHelper.blurRadius, PrefsHelper.bgOpacity, PrefsHelper.blurColorFilter,
            plateColor = PrefsHelper.iconPlateColor(), dispersion = PrefsHelper.iconDispersion)
    }
}
