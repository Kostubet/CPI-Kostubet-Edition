package com.cosmod.launcherglass.xposed

import android.annotation.SuppressLint
import android.content.Context
import com.cosmod.launcherglass.BuildConfig

internal class DesktopAppearanceResources(host: Context) {
    private val module = host.createPackageContext(BuildConfig.APPLICATION_ID, 0)

    fun text(name: String) = module.getString(id("desktop_appearance_$name", "string"))

    fun icon(name: String) = module.getDrawable(id(name, "drawable"))

    // LSPosed can keep an old module loaded while Android resolves resources from the
    // updated APK. Resolve each resource by name in the same table we use to read it.
    @SuppressLint("DiscouragedApi")
    private fun id(name: String, type: String) =
        module.resources.getIdentifier(name, type, module.packageName)
}
