package com.cosmod.launcherglass.ui

import android.content.Context

internal enum class CouiAppearance(val value: Int) {
    ORIGINAL(0),
    MODERN(1);

    companion object {
        const val KEY = "ui_coui_appearance"

        fun read(context: Context): CouiAppearance {
            val preferences = context.getSharedPreferences(SettingsProvider.PREFS_NAME, Context.MODE_PRIVATE)
            return if (preferences.getInt(KEY, ORIGINAL.value) == MODERN.value) MODERN else ORIGINAL
        }
    }
}
