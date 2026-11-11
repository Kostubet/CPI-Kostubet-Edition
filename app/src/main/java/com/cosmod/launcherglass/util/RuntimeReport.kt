package com.cosmod.launcherglass.util

import android.content.Context
import android.os.Bundle
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.ui.SettingsProvider

/** Records the actual injected version, which can differ from the updated app. */
object RuntimeReport {
    fun loaded(context:Context,role:String){
        try {context.contentResolver.call(SettingsProvider.CONTENT_URI,SettingsProvider.METHOD_REPORT_RUNTIME,role,
            Bundle().apply {putString("version",BuildConfig.VERSION_NAME)})}
        catch(_:Exception){/* Settings remain usable before the provider is available. */}
    }
}
