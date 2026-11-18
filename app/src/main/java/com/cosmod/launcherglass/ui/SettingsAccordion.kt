package com.cosmod.launcherglass.ui
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import io.github.suqi8.coui.kmp.basic.ExpandableGroupContent

/** Connects a View-backed settings group to COUI's expansion layout. */
internal class SettingsAccordion(private val body:View,initiallyOpen:Boolean):FrameLayout(body.context){
    private var open by mutableStateOf(initiallyOpen)
    private var animate by mutableStateOf(true)
    init {
        clipChildren=false;clipToPadding=false
        addView(ComposeView(context).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val bodyContent: @Composable () -> Unit = {
                    AndroidView(factory = {
                        (body.parent as? ViewGroup)?.removeView(body)
                        body
                    }, modifier = Modifier.fillMaxWidth())
                }
                if (animate) ExpandableGroupContent(expanded = open) { bodyContent() }
                else if (open) bodyContent()
            }
        },LayoutParams(-1,-2))
    }
    fun setOpen(next:Boolean,animate:Boolean){this.animate=animate;open=next}
}
