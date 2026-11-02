package com.appvalence.umay.survey

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
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Keeps the app alive while a survey records (see [SurveyService]); a no-op in tests. */
interface SurveyForeground {
    fun recordingStarted()

    fun recordingStopped()
}

class AndroidSurveyForeground @Inject constructor(@param:ApplicationContext private val context: Context) : SurveyForeground {
    override fun recordingStarted() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, SurveyService::class.java)) }
            .onFailure { Log.e(TAG, "Could not start the survey service", it) }
    }

    override fun recordingStopped() {
        context.stopService(Intent(context, SurveyService::class.java))
    }

    private companion object {
        const val TAG = "SurveyForeground"
    }
}

/**
 * Keeps the app in the foreground while a survey records, so switching to
 * another app for a moment (a call, a message) doesn't let Android end it and
 * lose the walk; the draft on disk (see [SurveyDrafts]) covers the rest. Shows
 * an ongoing notification with what's been recorded and a stop action. Like
 * the navigation service, it's of the "location" type: BLE scanning is
 * location access, and its permissions are granted before a survey can start.
 *
 * The camera isn't kept running in the background; Android allows that only on
 * screen. Footsteps carry the walk until the survey screen is back.
 */
@AndroidEntryPoint
class SurveyService : Service() {
    @Inject lateinit var recorder: SurveyRecorder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stopping the recording calls back into recordingStopped(), which ends this service.
            if (recorder.state.value.isRecording) recorder.stop() else stopSelf()
            return START_NOT_STICKY
        }
        if (!recorder.state.value.isRecording) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(recorder.state.value), foregroundType)
        }
        if (started.isFailure) {
            // The recording still runs while the app is on screen; the draft keeps it safe.
            Log.e(TAG, "Could not enter the foreground", started.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }

        notificationJob?.cancel()
        notificationJob = scope.launch {
            recorder.state
                .map { it.taggedWaypoints.size to it.currentFloor }
                .distinctUntilChanged()
                .collect { updateNotification(recorder.state.value) }
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(state: SurveyState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.survey_notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.survey_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(state: SurveyState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SurveyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = getString(
            R.string.survey_notification_text,
            getString(R.string.common_floor, state.currentFloor),
            resources.getQuantityString(R.plurals.count_named_places, state.taggedWaypoints.size, state.taggedWaypoints.size),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle(getString(R.string.survey_notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.survey_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val TAG = "SurveyService"
        const val CHANNEL_ID = "survey"

        // Distinct from the navigation service's, so the two never replace each other.
        const val NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.appvalence.umay.survey.STOP"
    }
}
