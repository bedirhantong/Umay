package com.appvalence.umay

import android.app.Application
import com.appvalence.umay.diagnostics.CrashLog

class UmayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
