package com.cosmod.launcherglass.xposed

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import androidx.core.graphics.ColorUtils
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Parameter as P
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Profile
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Adjusts the existing blur transaction and scrim. No independent panel-sized layer. */
internal class ShadeNativeBlur(context:Context,loader:ClassLoader,
    private val profile:()->Profile?,private val adjustRadius:()->Boolean) {
    private val keyguard=context.getSystemService(KeyguardManager::class.java)
    private var control=WeakReference<View>(null)
    private var host=WeakReference<View>(null)
    private val visibleBounds=Rect()

    fun observe(view:View) {
        if(control.get()===view)return
        control=WeakReference(view)
        host=WeakReference(view.rootView)
    }

    private fun active():Profile? {
        val profile=profile() ?: return null
        val view=control.get() ?: return null
        if(!view.isShown || view.alpha<=.001f || !view.getGlobalVisibleRect(visibleBounds) ||
            keyguard?.isKeyguardLocked!=false)return null
        return profile
    }

    init {
        runCatching {installBlur(loader)}.onFailure {
            GlassLogger.e("ShadeGlass","Stock shade blur contract unavailable",it)
        }
        runCatching {installScrim(loader)}.onFailure {
            GlassLogger.e("ShadeGlass","Stock shade scrim contract unavailable",it)
        }
    }

    private fun installBlur(loader:ClassLoader) {
        val owner=loader.loadClass("com.android.systemui.statusbar.BlurUtils")
        val root=Class.forName("android.view.ViewRootImpl")
        val getView=root.getMethod("getView")
        val maxRadius=owner.getMethod("getMaxBlurRadius")
        val method=owner.getMethod("applyBlur",root,Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,Float::class.javaPrimitiveType)
        XposedBridge.hookMethod(method,object:XC_MethodHook() {
            override fun beforeHookedMethod(p:MethodHookParam) {
                if(!adjustRadius() || (p.args[1] as Int)<=0)return
                val current=active() ?: return
                val rootImpl=p.args[0] ?: return
                runCatching {
                    val window=host.get() ?: return
                    if(getView.invoke(rootImpl)!==window)return
                    val stockMax=(maxRadius.invoke(p.thisObject) as Float).coerceAtLeast(1f)
                    val amount=((p.args[1] as Int)/stockMax).coerceIn(0f,1f)
                    p.args[1]=(amount*current[P.RADIUS]*current.fraction(P.BLUR_OPACITY)*window.resources.displayMetrics.density).roundToInt()
                }
            }
        })
    }

    private fun installScrim(loader:ClassLoader) {
        val owner=loader.loadClass("com.android.systemui.statusbar.phone.ScrimController")
        val state=owner.getDeclaredField("mState").apply {isAccessible=true}
        val behind=owner.getDeclaredField("mScrimBehind").apply {isAccessible=true}
        val method=owner.getDeclaredMethod("updateScrimColor",View::class.java,
            Int::class.javaPrimitiveType,Float::class.javaPrimitiveType)
        XposedBridge.hookMethod(method,object:XC_MethodHook() {
            override fun beforeHookedMethod(p:MethodHookParam) {
                val current=active() ?: return
                val opacity=p.args[2] as Float
                if(opacity<=0f || host.get()==null)return
                runCatching {
                    val controller=p.thisObject
                    if((state.get(controller) as? Enum<*>)?.name!="UNLOCKED" || behind.get(controller)!==p.args[0])return
                    val scrim=p.args[0] as View
                    if(scrim.rootView!==host.get())return
                    val brightness=current.fraction(P.BRIGHTNESS)-1f
                    val stockTint=p.args[1] as Int
                    p.args[1]=ColorUtils.blendARGB(stockTint,if(brightness<0)Color.BLACK else Color.WHITE,
                        kotlin.math.abs(brightness)*.84f)
                    p.args[2]=(opacity*(.65f+1.6f*current.fraction(P.OPACITY))).coerceIn(0f,1f)
                }
            }
        })
    }
}
