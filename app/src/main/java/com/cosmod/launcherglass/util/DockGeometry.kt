package com.cosmod.launcherglass.util
internal object DockGeometry {
    data class Bounds(val left:Int,val top:Int,val right:Int,val bottom:Int)
    fun calculate(left:Int,top:Int,right:Int,bottom:Int,hostWidth:Int,hostHeight:Int,density:Float,
        inset:Int,widthPercent:Int,heightPercent:Int):Bounds? {
        val available=hostWidth-(16*density).toInt()
        if(right<=left || bottom<=top || available<1 || hostHeight<1)return null
        val baseWidth=right-left+((12+2*inset.coerceIn(0,32))*density).toInt()
        val baseline=(bottom-top+(20*density).toInt()).coerceIn(1,hostHeight)
        val width=(baseWidth*widthPercent.coerceIn(55,160)/100).coerceIn(1,available)
        val height=(baseline*heightPercent.coerceIn(55,160)/100).coerceAtLeast(1)
        val margin=(8*density).toInt()
        val x=((left+right)/2-width/2).coerceIn(margin,hostWidth-margin-width)
        val baselineTop=((top+bottom)/2-baseline/2).coerceIn(0,hostHeight-baseline)
        val y=baselineTop+(baseline-height)/2
        return Bounds(x,y,x+width,y+height)
    }
}
