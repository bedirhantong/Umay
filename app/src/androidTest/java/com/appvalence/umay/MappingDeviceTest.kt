package com.appvalence.umay

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlin.math.abs
import androidx.test.platform.app.InstrumentationRegistry
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.positioning.mapping.MappingFrameGuard
import com.appvalence.umay.positioning.mapping.MetricDepth
import com.appvalence.umay.positioning.mapping.SpatialReconstructor
import com.appvalence.umay.survey.ar.ArAvailability
import com.appvalence.umay.survey.ar.ArCameraStatus
import com.appvalence.umay.survey.ar.ArCoreSurveyCamera
import com.google.ar.core.Config
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in hardware exercise. Captures geometry/diagnostic counts only, never camera imagery. */
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class MappingDeviceTest {
    @Test
    fun captureRealDepthAndExerciseLifecycle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Enable explicitly with -e mappingScan true; requires an operator moving the device", arguments.getString("mappingScan") == "true")
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val mode = if (arguments.getString("depthComputation") == "automatic") Config.DepthMode.AUTOMATIC else Config.DepthMode.RAW_DEPTH_ONLY
        val camera = ArCoreSurveyCamera(context, mode)
        val started = SystemClock.elapsedRealtime()
        val report = JSONObject().put("depthComputation",mode.name).put("device", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
        val directory = File(context.cacheDir, "mapping-validation").apply { mkdirs() }
        var minimumX = Double.POSITIVE_INFINITY; var maximumX = Double.NEGATIVE_INFINITY
        var minimumY = Double.POSITIVE_INFINITY; var maximumY = Double.NEGATIVE_INFINITY
        var minimumZ = Double.POSITIVE_INFINITY; var maximumZ = Double.NEGATIVE_INFINITY
        var planeFrames = 0
        var trackedFrames = 0
        var depthFrames = 0
        var validPoints = 0L
        var nonzeroPixels = 0L
        var inRangePixels = 0L
        var confidentPixels = 0L
        var rangeAndConfidencePixels = 0L
        var maximumConfidence = 0
        var maxMatrixError = 0.0
        var calibration: JSONObject? = null
        var lostEvents = 0
        var maxThermal = 0
        var maxProcessingMs = 0L
        var firstFrameId: String? = null
        var engine: SpatialReconstructor? = null
        val guard = MappingFrameGuard()
        val power = context.getSystemService(PowerManager::class.java)
        val statuses = mutableListOf<String>()
        val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stateJob = scanScope.launch {
            camera.status.collect { state ->
                synchronized(statuses) { statuses += state.toString() }
                if (state is ArCameraStatus.Lost) lostEvents++
            }
        }
        var frameJob: Job? = null
        try {
            camera.checkAvailability()
            val availability = withTimeout(15_000) { camera.availability.first { it != ArAvailability.CHECKING } }
            report.put("availability", availability.name)
            assertEquals("ARCore must be installed and supported", ArAvailability.READY, availability)
            frameJob = scanScope.launch {
                camera.frames.collect { sample ->
                    trackedFrames++
                    minimumX=minOf(minimumX,sample.pose.x); maximumX=maxOf(maximumX,sample.pose.x)
                    minimumY=minOf(minimumY,sample.pose.y); maximumY=maxOf(maximumY,sample.pose.y)
                    minimumZ=minOf(minimumZ,sample.pose.z); maximumZ=maxOf(maximumZ,sample.pose.z)
                    if (sample.upwardPlaneHeights.isNotEmpty()) planeFrames++
                    if (firstFrameId == null) firstFrameId = sample.coordinateFrameId
                    val transform = guard.accept(sample.coordinateFrameId, sample.pose) ?: return@collect
                    val depth = sample.depth ?: return@collect
                    val before = SystemClock.elapsedRealtime()
                    depthFrames++
                    val m = depth.cameraPose
                    for (a in 0..2) for (b in a..2) {
                        val dot = (0..2).sumOf { (m[a*4+it] * m[b*4+it]).toDouble() }
                        maxMatrixError = maxOf(maxMatrixError, abs(dot - if (a == b) 1.0 else 0.0))
                    }
                    if (calibration == null) calibration = JSONObject().put("width",depth.width).put("height",depth.height)
                        .put("fx",depth.fx.toDouble()).put("fy",depth.fy.toDouble()).put("cx",depth.cx.toDouble()).put("cy",depth.cy.toDouble())
                        .put("depthLength",depth.depthMm.size).put("confidenceLength",depth.confidence?.size ?: -1)
                        .put("poseBottomRow",listOf(m[3],m[7],m[11],m[15]).joinToString())
                    for (i in depth.depthMm.indices) {
                        val d = depth.depthMm[i].toInt() and 65535
                        val c = depth.confidence?.get(i)?.toInt()?.and(255) ?: 0
                        if (d > 0) nonzeroPixels++
                        if (d in 300..4000) inRangePixels++
                        if (c >= 128) confidentPixels++
                        if (d in 300..4000 && c >= 128) rangeAndConfidencePixels++
                        maximumConfidence = maxOf(maximumConfidence,c)
                    }
                    validPoints += MetricDepth.points(depth).size
                    val reconstruction = engine ?: SpatialReconstructor(sample.coordinateFrameId).also { engine = it }
                    reconstruction.integrate(depth, transform, sample.pose.y, sample.upwardPlaneHeights)
                    maxProcessingMs = maxOf(maxProcessingMs, SystemClock.elapsedRealtime() - before)
                    maxThermal = maxOf(maxThermal, power.currentThermalStatus)
                }
            }
            camera.resume()
            withTimeout(20_000) { camera.supportsDepth.first { it != null } }
            report.put("supportsRawDepth", camera.supportsDepth.value)
            assertEquals("Device must support selected depth computation mode for this exercise", true, camera.supportsDepth.value)
            Log.i(TAG, "SCAN_STARTED: move slowly, observe broad floor and walls for 60 seconds")
            delay(arguments.getString("scanDurationMs")?.toLongOrNull()?.coerceIn(10_000,120_000) ?: 60_000)
            camera.pause()
            withTimeout(10_000) { camera.status.first { it == ArCameraStatus.Off } }
            frameJob.cancelAndJoin()
            val map = engine?.snapshot()
            report.put("motionSpanXMeters",if (trackedFrames > 0) maximumX-minimumX else 0.0)
                .put("motionSpanYMeters",if (trackedFrames > 0) maximumY-minimumY else 0.0)
                .put("motionSpanZMeters",if (trackedFrames > 0) maximumZ-minimumZ else 0.0)
                .put("horizontalPlaneFrames",planeFrames)
            report.put("trackedFrames", trackedFrames).put("depthFrames", depthFrames).put("validDepthPoints", validPoints)
                .put("nonzeroDepthPixels",nonzeroPixels).put("inRangeDepthPixels",inRangePixels)
                .put("confidentPixels",confidentPixels).put("rangeAndConfidencePixels",rangeAndConfidencePixels)
                .put("maximumConfidence",maximumConfidence).put("maxMatrixOrthonormalError",maxMatrixError).put("calibration",calibration)
                .put("trackingLossEvents", lostEvents).put("alignmentRequired", guard.alignmentRequired)
                .put("maxThermalStatus", maxThermal).put("maxIntegrationMs", maxProcessingMs)
                .put("floorSupportFrames", map?.floor?.independentFrames ?: 0).put("walls", map?.walls?.size ?: 0)
                .put("voxels", map?.voxels?.size ?: 0).put("issues", map?.issues?.joinToString() ?: "no geometry")
            if (map != null) File(directory, "map.json").writeText(BuildingMapJson.encode(
                BuildingMap("device-validation", listOf(FloorMap(0, emptyList(), emptyList(), occupancy = engine?.grid?.snapshot(), reconstruction = map)))))
            // Same-session resume must preserve frame identity; a brand-new session is never silently aligned.
            val resumed = scanScope.async { withTimeout(15_000) { camera.frames.first() } }
            camera.resume()
            assertEquals("Pause/resume changed the AR session frame", firstFrameId, resumed.await().coordinateFrameId)
            camera.close()
            withTimeout(10_000) { camera.supportsDepth.first { it == null } }
            report.put("pauseResumeSameFrame", true).put("closeReleasesSession", true)
            assertTrue("No tracked camera frames; inspect lighting/aim and report statuses", trackedFrames > 0)
            assertTrue("No usable depth points; inspect raw pixel counts, calibration and pose validation in report.json", depthFrames > 0 && validPoints > 0)
            assertFalse("A coordinate discontinuity was detected", guard.alignmentRequired)
        } finally {
            camera.close()
            frameJob?.cancelAndJoin()
            stateJob.cancelAndJoin()
            scanScope.cancel()
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            report.put("batteryTemperatureC", (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0)/10.0)
                .put("durationMs", SystemClock.elapsedRealtime()-started)
                .put("statuses", synchronized(statuses) { statuses.distinct().joinToString() })
            File(directory, "report.json").writeText(report.toString(2))
            Log.i(TAG, report.toString())
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    companion object { const val TAG = "MappingDeviceTest" }
}
