package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method
import java.util.ArrayDeque

/** Inspects already constructed views. Never observes or intercepts class loading. */
internal object LiveAlertTargets {
    // LoadedApk has not assigned mApplication during Application.attach yet.
    // The attached Application itself already delegates to a valid base context.
    fun bootstrapContext(application:android.app.Application):android.content.Context=application
    const val CARD="com.oplus.systemui.plugins.seedling.card.ui.view.CardBackgroundView"
    const val CAPSULE="com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView"
    const val EXPANDED_CARD="com.oplus.systemui.plugins.seedling.card.ui.view.CardView"
    fun drawMethod(cls:Class<*>):Method? {
        val name=when(cls.name){CARD->"draw";else->return null}
        return cls.declaredMethods.firstOrNull {it.name==name && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))}
    }
    fun visit(root:View,found:(Class<*>)->Unit)=visitViews(root){found(it.javaClass)}
    fun visitViews(root:View,found:(View)->Unit){
        val pending=ArrayDeque<View>();pending.add(root)
        var remaining=1024
        while(pending.isNotEmpty() && remaining-->0){
            val view=pending.removeFirst();val cls=view.javaClass
            if(cls.name==CARD)found(view)
            if(view is ViewGroup)for(i in 0 until view.childCount)pending.addLast(view.getChildAt(i))
        }
    }
}
