package com.laradars.app

import android.app.Application
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        @Suppress("DEPRECATION")
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        // OpenStreetMap's tile policy asks every app to identify itself.
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
    }
}
