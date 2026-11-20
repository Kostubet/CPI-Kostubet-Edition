package com.cosmod.launcherglass.xposed

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.FrameLayout
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import org.xmlpull.v1.XmlPullParser
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** Contract verified against OplusLauncher 17.3.12 on the OnePlus 13 IN. */
internal object DesktopAppearanceToolbar {
    private const val TOOLBAR = "com.android.launcher.togglebar.views.ToggleStateToolbar"
    private const val BUTTON = "com.android.launcher.views.PressFeedbackButton"
    private val wallpaperShades = WeakHashMap<Activity, WeakReference<View>>()

    fun updateWallpaperShade() {
        for ((host, shade) in wallpaperShades.toMap()) {
            if (!host.isDestroyed) setWallpaperShade(host, shade.get())
        }
    }

    private fun setWallpaperShade(host: Activity, existing: View?) {
        if (existing != null) {
            existing.visibility = if (PrefsHelper.desktopDimWallpaper) View.VISIBLE else View.GONE
            return
        }
        wallpaperShades[host] = WeakReference(null)
        if (!PrefsHelper.desktopDimWallpaper) return
        val id = host.resources.getIdentifier("workspace", "id", host.packageName)
        val workspace = host.findViewById<View>(id) ?: return
        val layer = workspace.parent as? FrameLayout ?: return
        if (layer.id != host.resources.getIdentifier("drag_layer", "id", host.packageName)) return
        val shade = View(host).apply {
            setBackgroundColor(0x38000000)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        layer.addView(shade, layer.indexOfChild(workspace), FrameLayout.LayoutParams(-1, -1))
        wallpaperShades[host] = WeakReference(shade)
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val toolbar = loader.loadClass(TOOLBAR)
            val button = loader.loadClass(BUTTON).getConstructor(Context::class.java, AttributeSet::class.java)
            val sheet = DesktopAppearancePanel.contract(loader)
            ModuleHooks.hookMethod(toolbar.getDeclaredMethod("onFinishInflate"), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    runCatching {
                        bind(param.thisObject as ViewGroup, { context, attrs -> button.newInstance(context, attrs) as TextView }, sheet)
                    }.onFailure { report(it) }
                }
            })
            GlassLogger.i("DesktopAppearance", "ColorOS edit toolbar contract installed")
        }.onFailure { report(it) }
    }

    private fun bind(toolbar: ViewGroup, makeButton: (Context, AttributeSet) -> TextView, sheet: DesktopAppearancePanel.Contract) {
        val group = XposedHelpers.callMethod(toolbar, "getCancelButton") as? TextView ?: return
        val done = XposedHelpers.callMethod(toolbar, "getDoneButton") as? TextView ?: return
        val parent = done.parent as? ViewGroup ?: return
        if (group.parent !== parent || group.javaClass.name != BUTTON || done.javaClass.name != BUTTON) return
        val context = toolbar.context
        val resources = context.resources
        val groupText = resources.getIdentifier("edite_model_tidyup", "string", context.packageName)
        val doneText = resources.getIdentifier("toggle_bar_complete", "string", context.packageName)
        val layout = resources.getIdentifier("toggle_state_tool_bar", "layout", context.packageName)
        if (groupText == 0 || doneText == 0 || layout == 0) return
        // Use the actual button style from the matching resource. Creating the
        // class without attributes selects an unrelated stock button variant.
        val copy = resources.getLayout(layout).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == BUTTON &&
                    parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "id", 0) == done.id) {
                    return@use makeButton(context, parser)
                }
            }
            null
        } ?: return
        val params = done.layoutParams.javaClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) as ViewGroup.MarginLayoutParams
        XposedHelpers.setIntField(params, "startToEnd", group.id)
        XposedHelpers.setIntField(params, "endToStart", done.id)
        XposedHelpers.setIntField(params, "topToTop", 0)
        XposedHelpers.setIntField(params, "bottomToBottom", 0)
        params.marginStart = dp(context, 6)
        params.marginEnd = dp(context, 6)

        val title = context.createPackageContext(BuildConfig.APPLICATION_ID, 0).getString(R.string.desktop_appearance_title)
        copy.apply {
            id = View.generateViewId()
            text = title
            textSize = 14f
            typeface = done.typeface
            gravity = done.gravity
            includeFontPadding = done.includeFontPadding
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPaddingRelative(dp(context, 12), done.paddingTop, dp(context, 12), done.paddingBottom)
            minimumHeight = done.minHeight
            visibility = View.INVISIBLE
        }
        var dialog: Dialog? = null
        val listener = ViewTreeObserver.OnPreDrawListener {
            val available = PrefsHelper.enabled && group.visibility == View.VISIBLE &&
                done.visibility == View.VISIBLE && group.text.toString() == resources.getString(groupText) &&
                done.text.toString() == resources.getString(doneText)
            val visibility = if (available) View.VISIBLE else View.INVISIBLE
            if (copy.visibility != visibility) copy.visibility = visibility
            val alpha = if (available) minOf(group.alpha, done.alpha) else 0f
            if (copy.alpha != alpha) copy.alpha = alpha
            if (copy.currentTextColor != done.currentTextColor) copy.setTextColor(done.textColors)
            if (copy.isEnabled != (alpha > .6f)) copy.isEnabled = alpha > .6f
            true
        }
        copy.setOnClickListener {
            if (!PrefsHelper.enabled || dialog?.isShowing == true) return@setOnClickListener
            val host = activity(context) ?: return@setOnClickListener
            if (host.isFinishing || host.isDestroyed) return@setOnClickListener
            runCatching {
                dialog = DesktopAppearancePanel.open(host, sheet)
                if (dialog?.isShowing == true) done.performClick()
            }.onFailure { report(it) }
        }
        copy.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.viewTreeObserver.addOnPreDrawListener(listener)
                view.post {
                    if (view.isAttachedToWindow) activity(view.context)?.let { host ->
                        setWallpaperShade(host, wallpaperShades[host]?.get())
                    }
                }
            }
            override fun onViewDetachedFromWindow(view: View) {
                view.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        })
        parent.addView(copy, parent.indexOfChild(done), params)
        GlassLogger.i("DesktopAppearance", "Added appearance action to the stock edit toolbar")
    }

    private tailrec fun activity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> activity(context.baseContext)
        else -> null
    }

    internal fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private fun report(error: Throwable) = GlassLogger.limited("DesktopAppearance", "toolbar") {
        "Optional appearance UI unavailable: ${error.message ?: error.javaClass.simpleName}"
    }
}
