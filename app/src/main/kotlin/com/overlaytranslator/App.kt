package com.overlaytranslator

import android.app.Application
import com.google.android.material.color.DynamicColors

/** Applies Material You (dynamic) colors to every activity when the device supports it. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
