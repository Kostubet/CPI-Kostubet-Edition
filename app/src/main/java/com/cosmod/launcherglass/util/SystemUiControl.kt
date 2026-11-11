package com.cosmod.launcherglass.util

/** Only apps signed with the module's key may request this action. */
object SystemUiControl {
    const val ACTION=com.cosmod.launcherglass.BuildConfig.APPLICATION_ID+".RESTART_SYSTEM_UI"
    const val PERMISSION=com.cosmod.launcherglass.BuildConfig.APPLICATION_ID+".permission.RESTART_SYSTEM_UI"
    const val ACCEPTED=1
}
