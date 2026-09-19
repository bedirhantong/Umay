package com.appvalence.umay

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.appvalence.umay.diagnostics.CrashLog
import com.appvalence.umay.navigation.NavigationSessions
import com.appvalence.umay.storage.BuildingRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class UmayApplication : Application() {
    @Inject lateinit var buildings: BuildingRepository

    @Inject lateinit var sessions: NavigationSessions

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this) {
            sessions.active.value?.let { "${it.buildingMap.id}: ${it.startNodeId} -> ${it.destinationNode.id}" }
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) enableStrictMode()
        // Start reading buildings off the main thread now, so the list is usually
        // ready by the time the first screen asks for it.
        buildings.refresh()
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
