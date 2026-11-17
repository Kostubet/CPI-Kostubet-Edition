package com.cosmod.launcherglass.ui
import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.*
import kotlin.math.roundToInt

/** Adapter only: track, thumb, haptics and horizontal gestures are COUI's HSV components. */
internal class ColorBand(context: Context, val component: Int, dark: Boolean = true, accent: Int = 0xff1684ff.toInt()) :
    CouiControl(context,dark,accent) {
    private var hue by mutableStateOf(0f)
    private var saturation by mutableStateOf(0f)
    private var brightness by mutableStateOf(1f)
    var monochrome by mutableStateOf(false)
    var onChange: ((Int,Boolean)->Unit)?=null
    private var initial=0
    private var adjusting=false
    private var pending:Int?=null
    private val maximum get()=if(component==0)360 else 100
    private val value get()=when(component){0->hue.roundToInt();1->(saturation*100).roundToInt();else->(brightness*100).roundToInt()}
    init {
        content {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)
                .graphicsLayer { alpha=if(controlEnabled)1f else .38f }) {
                val change:(Float)->Unit={ next ->
                    if(controlEnabled){
                        val n=(next*maximum).roundToInt().coerceIn(0,maximum)
                        pending=n
                        onChange?.invoke(n,false)
                        if(!adjusting) {onChange?.invoke(n,true);pending=null}
                    }
                }
                when(component){
                    0->HsvHueSlider(hue,change)
                    1->HsvSaturationSlider(hue,saturation,change)
                    else->HsvValueSlider(hue,if(monochrome)0f else saturation,brightness,change)
                }
            }
        }
    }
    fun setColor(h:Int,s:Int,v:Int){hue=h.coerceIn(0,360).toFloat();saturation=s.coerceIn(0,100)/100f;brightness=v.coerceIn(0,100)/100f}
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){initial=value;adjusting=true;pending=null}
        val handled=super.dispatchTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_UP->{adjusting=false;pending?.let {onChange?.invoke(it,true)};pending=null}
            MotionEvent.ACTION_CANCEL->{adjusting=false;pending=null;onChange?.invoke(initial,false)}
        }
        return handled
    }
}
