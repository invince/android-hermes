package com.nous.hermes.phone

import android.app.Application
import android.content.Context

class HermesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
    }

    companion object {
        lateinit var instance: HermesApp
            private set
        lateinit var prefs: android.content.SharedPreferences
            private set
    }
}
