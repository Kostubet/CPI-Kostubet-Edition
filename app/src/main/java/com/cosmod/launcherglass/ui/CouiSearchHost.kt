package com.cosmod.launcherglass.ui
import android.content.Context
import android.widget.EditText
import android.view.View
import android.view.ViewTreeObserver
import android.text.Editable
import android.text.TextWatcher
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.cosmod.launcherglass.ui.onextbox.ColorOsSearchSurface
/** Input binding only; the search capsule's material and touch motion are upstream. */
internal class CouiSearchHost(context:Context,dark:Boolean,accent:Int,surface:Int,editorRow:View,private val input:EditText):
    CouiControl(context,dark,accent) {
    private var focused by mutableStateOf(input.hasFocus())
    private var empty by mutableStateOf(input.text.isEmpty())
    private val focusListener=ViewTreeObserver.OnGlobalFocusChangeListener {_,_->focused=input.hasFocus()}
    private val watcher=object:TextWatcher {
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){empty=s.isNullOrEmpty()}
        override fun afterTextChanged(s:Editable?){}
    }
    init {
        composition.layoutParams=LayoutParams(-1,-1)
        content {
            ColorOsSearchSurface(Modifier.fillMaxSize(),Color(surface),focused,empty) {
                AndroidView(factory={editorRow},modifier=Modifier.fillMaxSize())
            }
        }
    }
    override fun onAttachedToWindow(){
        super.onAttachedToWindow()
        focused=input.hasFocus();empty=input.text.isEmpty()
        input.addTextChangedListener(watcher)
        input.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    }
    override fun onDetachedFromWindow(){
        input.removeTextChangedListener(watcher)
        if(input.viewTreeObserver.isAlive)input.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        super.onDetachedFromWindow()
    }
}
