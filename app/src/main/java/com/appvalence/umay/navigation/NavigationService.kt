package com.appvalence.umay.navigation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.appvalence.umay.MainActivity
import com.appvalence.umay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground for as long as a [NavigationSession] runs —
 * that's what lets Android keep delivering step/heading sensor events,
 * (filtered) BLE scan results and speech with the screen off or another app
 * open. It holds no navigation logic itself: it shows an ongoing
 * notification mirroring the current instruction, with a stop action, and
 * that's all. The session starts and stops it (see [NavigationSessions]).
 *
 * Declared with the "location" foreground-service type: BLE beacon scanning
 * *is* location access, and the permissions it needs are already granted
 * before any session can start.
 */
class NavigationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The session stopping calls back into stop(), which ends this service.
            NavigationSessions.active.value?.stop("stopped_from_notification") ?: stopSelf()
            return START_NOT_STICKY
        }
        val session = NavigationSessions.active.value
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(session.ui.value), foregroundType)
        }
        if (started.isFailure) {
            // E.g. a permission revoked between the screen's check and here. The session still
            // runs while the app is visible; it just won't survive the screen turning off.
            Log.e(TAG, "Could not enter the foreground", started.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }

        notificationJob?.cancel()
        notificationJob = scope.launch {
            session.ui
                .map { it.instruction to it.phase }
                .distinctUntilChanged()
                .collect { updateNotification(session.ui.value) }
        }
        return START_NOT_STICKY
    }

    // Without notification permission (Android 13+, declined) the service still runs and keeps
    // navigation alive with the screen off — there's just no visible notification to refresh.
    private fun updateNotification(ui: NavigationSessionUi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(ui))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(ui: NavigationSessionUi): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (ui.phase == NavigationPhase.ARRIVED) getString(R.string.nav_status_arrived) else getString(R.string.notification_title)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle(title)
            .setContentText(ui.instruction)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ui.instruction))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "NavigationService"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.appvalence.umay.navigation.STOP"

        internal fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java)) }
                .onFailure { Log.e(TAG, "Could not start the navigation service", it) }
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
