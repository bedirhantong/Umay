package com.appvalence.umay.perception

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.appvalence.umay.coremodel.MarkerSighting
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * "Kamerayla bul": the camera, with nothing shown, looking only for place
 * markers while the person turns slowly where they stand — before a walk,
 * when no walk is running. Reads the frame's luminance plane
 * as it comes (QR decoding doesn't care which way up it is).
 *
 * [bind] and [unbind] are main-thread only.
 */
class CameraMarkerScanner(private val context: Context) {
    private val sightings = MutableSharedFlow<MarkerSighting>(extraBufferCapacity = 8)
    private val reader = QrMarkerReader()
    private val throttle = FrameThrottle(MARKER_INTERVAL_MS)
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var bindGeneration = 0
    private var luminance = ByteArray(0)

    fun sightings(): SharedFlow<MarkerSighting> = sightings.asSharedFlow()

    /** [onFailed] when the camera can't be opened (in use, missing). */
    fun bind(lifecycleOwner: LifecycleOwner, onFailed: () -> Unit) {
        val generation = ++bindGeneration
        val analyzer = executor ?: Executors.newSingleThreadExecutor { Thread(it, "umay-marker-scanner") }.also { executor = it }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Standing still to find where they are, the walker can afford the full detail.
            .setResolutionSelector(analysisResolution())
            .build()
            .also { it.setAnalyzer(analyzer, ::analyze) }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (generation != bindGeneration) return@addListener
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                } catch (t: Throwable) {
                    Log.e(TAG, "Camera binding failed", t)
                    onFailed()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun unbind() {
        bindGeneration++
        provider?.unbindAll()
        provider = null
        executor?.shutdown()
        executor = null
        throttle.reset()
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (!throttle.tryAcquire(now)) return
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            if (luminance.size != width * height) luminance = ByteArray(width * height)
            val buffer = plane.buffer
            for (row in 0 until height) {
                buffer.position(row * plane.rowStride)
                if (plane.pixelStride == 1) {
                    buffer.get(luminance, row * width, width)
                } else {
                    for (column in 0 until width) luminance[row * width + column] = buffer.get(row * plane.rowStride + column * plane.pixelStride)
                }
            }
            reader.read(luminance, width, height, now).forEach { sightings.tryEmit(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "Marker scan failed", t)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val TAG = "CameraMarkerScanner"

        // Markers don't move: three looks a second is plenty, and decoding isn't free.
        const val MARKER_INTERVAL_MS = 350L
    }
}

/** The analysis frame size asked of the camera (see [CameraOptics.ANALYSIS_WIDTH_PX]), or the nearest below it. */
private fun analysisResolution(): ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(CameraOptics.ANALYSIS_WIDTH_PX, CameraOptics.ANALYSIS_HEIGHT_PX),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
        ),
    )
    .build()
