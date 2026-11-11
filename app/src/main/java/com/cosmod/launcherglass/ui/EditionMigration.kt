package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/** One-time, read-only copy from the old edition; each app then owns its settings. */
internal object EditionMigration {
    fun copy(context:Context,prefs:SharedPreferences){
        if(prefs.contains("edition_migrated"))return
        if(prefs.all.isNotEmpty()){prefs.edit().putBoolean("edition_migrated",true).commit();return}
        try {
            val data=context.contentResolver.call(Uri.parse("content://com.cosmod.launcherglass.settings/settings"),"getAll",null,null)
            if(data!=null){
                val editor=prefs.edit()
                for((key,default) in SettingsDefaults.portable()){
                    if(!data.containsKey(key))continue
                    if(key.startsWith("folder_") && key!=FolderSettingsKeys.OWN && !data.getBoolean(FolderSettingsKeys.OWN,false))continue
                    when(default){is Boolean->editor.putBoolean(key,data.getBoolean(key));is Int->editor.putInt(key,data.getInt(key))}
                }
                for(key in listOf(SettingsProvider.KEY_MD3_UI,SettingsProvider.KEY_BOOT_RESTART,SettingsProvider.KEY_BOOT_RESTART_TOAST)){
                    if(data.containsKey(key))editor.putBoolean(key,data.getBoolean(key))
                }
                // The source is already migrated; do not replace its tuned dock/photo defaults.
                editor.putBoolean("island_view_discovery_v2",true).putBoolean("neutral_dock_revision_1",true)
                    .putBoolean("icon_dock_revision_1",true).putInt("defaults_revision",38)
                    .putBoolean("tone_group_migrated",true).putInt("blur_brightness_revision",1)
                    .putInt("outline_sliders_revision",1).putBoolean("edition_migrated",true).commit()
                return
            }
        }catch(_:Exception){/* A fresh edition can also be installed without the original. */}
        prefs.edit().putBoolean("edition_migrated",true).commit()
    }
}
