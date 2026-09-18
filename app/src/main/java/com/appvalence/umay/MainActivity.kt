package com.appvalence.umay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.coremodel.ObstacleType
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.perception.CameraXObstacleDetector
import com.appvalence.umay.ui.theme.UmayTheme
import java.util.UUID
import kotlin.math.roundToInt

private const val CRITICAL_DISTANCE_METERS = 1.5
private const val WARNING_DISTANCE_METERS = 3.5
private const val MIN_ANNOUNCEMENT_INTERVAL_MS = 2_500L

private enum class Screen(val label: String) {
    OBSTACLE_WATCH("Engel Algılama"),
    BEACON_SCANNER("Beacon Tarayıcı"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UmayTheme {
                var selectedScreen by remember { mutableStateOf(Screen.OBSTACLE_WATCH) }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        TabRow(selectedTabIndex = selectedScreen.ordinal) {
                            Screen.entries.forEach { screen ->
                                Tab(
                                    selected = selectedScreen == screen,
                                    onClick = { selectedScreen = screen },
                                    text = { Text(screen.label) },
                                )
                            }
                        }
                        when (selectedScreen) {
                            Screen.OBSTACLE_WATCH -> ObstacleWatchScreen(modifier = Modifier.weight(1f))
                            Screen.BEACON_SCANNER -> BeaconScannerScreen(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private data class LatencyStats(
    val lastMs: Long = 0L,
    val worstMs: Long = 0L,
    val sampleCount: Int = 0,
    val exceedsBudget: Boolean = false,
)

@Composable
fun ObstacleWatchScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val feedback = remember { TtsFeedbackController(context) }
    DisposableEffect(Unit) {
        onDispose { feedback.shutdown() }
    }

    var latencyStats by remember { mutableStateOf(LatencyStats()) }
    var nearestObstacle by remember { mutableStateOf<DetectedObstacle?>(null) }
    var lastAnnouncedAtMs by remember { mutableStateOf(0L) }
    var lastAnnouncedPriority by remember { mutableStateOf<AlertPriority?>(null) }

    val detector = remember {
        CameraXObstacleDetector(context) { trace ->
            latencyStats = latencyStats.copy(
                lastMs = trace.totalMs ?: 0L,
                worstMs = maxOf(latencyStats.worstMs, trace.totalMs ?: 0L),
                sampleCount = latencyStats.sampleCount + 1,
                exceedsBudget = trace.exceedsBudget(),
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { detector.unbind() }
    }

    LaunchedEffect(detector) {
        detector.obstacleUpdates().collect { obstacles ->
            val nearest = obstacles.filter { it.type == ObstacleType.PERSON }.minByOrNull { it.distanceMeters }
            nearestObstacle = nearest

            val priority = when {
                nearest == null -> null
                nearest.distanceMeters <= CRITICAL_DISTANCE_METERS -> AlertPriority.CRITICAL
                nearest.distanceMeters <= WARNING_DISTANCE_METERS -> AlertPriority.WARNING
                else -> null
            }

            val now = System.currentTimeMillis()
            val shouldAnnounce = priority != null &&
                (priority != lastAnnouncedPriority || now - lastAnnouncedAtMs >= MIN_ANNOUNCEMENT_INTERVAL_MS)
            if (shouldAnnounce && nearest != null) {
                lastAnnouncedAtMs = now
                lastAnnouncedPriority = priority
                feedback.emit(nearest.toAlert(priority))
            } else if (priority == null) {
                lastAnnouncedPriority = null
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
    ) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also { previewView ->
                        detector.bind(lifecycleOwner, previewView.surfaceProvider)
                    }
                },
            )
        } else {
            Text(
                text = "Engel algılama için kamera izni gerekiyor.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        DebugOverlay(
            nearestObstacle = nearestObstacle,
            latencyStats = latencyStats,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DebugOverlay(
    nearestObstacle: DetectedObstacle?,
    latencyStats: LatencyStats,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val obstacleText = if (nearestObstacle != null) {
            val distance = (nearestObstacle.distanceMeters * 10).roundToInt() / 10.0
            "Kişi: ${distance}m, açı ${nearestObstacle.bearingDeg.roundToInt()}°"
        } else {
            "Engel yok"
        }
        Text(text = obstacleText, style = MaterialTheme.typography.bodyMedium)

        val budgetLabel = if (latencyStats.exceedsBudget) "BÜTÇE AŞILDI" else "bütçe içinde"
        Text(
            text = "Gecikme: ${latencyStats.lastMs}ms (en kötü ${latencyStats.worstMs}ms, $budgetLabel, ${latencyStats.sampleCount} örnek)",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun DetectedObstacle.toAlert(priority: AlertPriority): NavigationAlert {
    val distanceRounded = (distanceMeters * 10).roundToInt() / 10.0
    val message = when (priority) {
        AlertPriority.CRITICAL -> "Dur, kişi çok yakın, yaklaşık $distanceRounded metre"
        AlertPriority.WARNING -> "Dikkat, kişi yaklaşık $distanceRounded metre önünüzde"
        AlertPriority.INFO -> "Kişi yaklaşık $distanceRounded metre önünüzde"
    }
    return NavigationAlert(
        id = UUID.randomUUID().toString(),
        priority = priority,
        message = message,
        relatedObstacle = this,
        createdAtMs = System.currentTimeMillis(),
    )
}
