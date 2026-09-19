package com.appvalence.umay

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.appvalence.umay.diagnostics.CrashLog

class UmayApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) enableStrictMode()
        // Start reading buildings off the main thread now, so the list is usually
        // ready by the time the first screen asks for it.
        graph.buildings.refresh()
    }

    // Debug builds only: every disk or network access on the main thread is logged
    // (logcat tag "StrictMode") with the stack trace that caused it — the kind of
    // work that shows up as a stutter on a slower phone long before anyone
    // notices it on a fast one.
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }
}
