package com.cosmod.launcherglass.xposed

import android.content.SharedPreferences

/** One interrupted startup is enough to retain native System UI on the next attempt. */
internal object AlertStartupGuard {
    fun begin(store:SharedPreferences,now:Long,version:Int):Boolean {
        val sameBuild=store.getInt("guard_build",0)==version
        if(sameBuild){
            val previous=store.getLong("started",0)
            val interrupted=store.getBoolean("starting",false) && now>=previous && now-previous<120000L
            if(interrupted || store.getBoolean("blocked",false))return false
        }
        // An update gets one clean attempt. A crash in this build still
        // keeps the native System UI on the next startup.
        return store.edit().putInt("guard_build",version).putBoolean("blocked",false)
            .putBoolean("starting",true).putLong("started",now).commit()
    }
    fun clear(store:SharedPreferences)=store.edit().putBoolean("blocked",false).putBoolean("starting",false).commit()
    fun stable(store:SharedPreferences,started:Long){
        if(store.getLong("started",0)==started)store.edit().putBoolean("starting",false).apply()
    }
    fun block(store:SharedPreferences){store.edit().putBoolean("blocked",true).commit()}
}
