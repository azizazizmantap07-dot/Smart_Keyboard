package com.smartkeyboard.ime

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.smartkeyboard.ime.utils.PreferencesHelper

class SmartKeyboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Monet / Material You when available (Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this)

        val mode = PreferencesHelper(this).themeMode
        when (mode) {
            PreferencesHelper.THEME_LIGHT ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            PreferencesHelper.THEME_DARK ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
