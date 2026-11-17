package com.cosmod.launcherglass.ui
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.cosmod.launcherglass.ui.onextbox.FeatureExpansionMotion
/** View-page bridge to ONextBox's shared feature expansion, without a second height animator. */
internal class SettingsAccordion(private val body:View,initiallyOpen:Boolean):FrameLayout(body.context){
    private var open by mutableStateOf(initiallyOpen)
    private var animate by mutableStateOf(true)
    init {
        clipChildren=false;clipToPadding=false
        addView(ComposeView(context).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AnimatedVisibility(visible=open,
                    enter=if(animate)FeatureExpansionMotion.Enter else EnterTransition.None,
                    exit=if(animate)FeatureExpansionMotion.Exit else ExitTransition.None){
                    AndroidView(factory={
                        (body.parent as? ViewGroup)?.removeView(body)
                        body
                    },modifier=Modifier.fillMaxWidth())
                }
            }
        },LayoutParams(-1,-2))
    }
    fun setOpen(next:Boolean,animate:Boolean){this.animate=animate;open=next}
}
