package com.appvalence.umay.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.LatencyTrace
import com.appvalence.umay.coremodel.ObstacleSensorSource
import com.appvalence.umay.coremodel.ObstacleType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Faz 1 obstacle detector: CameraX ImageAnalysis feeding a TFLite EfficientDet-Lite0
 * model (FR-5), with monocular distance/bearing estimation (FR-6, [ObstacleGeometry])
 * and frame-to-frame velocity tracking (FR-7, [ObstacleTracker]).
 *
 * [onLatencyTrace] reports the sense->detect latency of every processed frame so
 * callers can verify the NFR-1 320ms end-to-end budget; this class only covers the
 * sensing+detection portion of that budget, not fusion/feedback.
 */
class CameraXObstacleDetector(
    private val context: Context,
    private val personScoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
    private val onLatencyTrace: (LatencyTrace) -> Unit = {},
) : ObstacleDetector {

    private val obstacles = MutableSharedFlow<List<DetectedObstacle>>(replay = 1, extraBufferCapacity = 1)
    private val tracker = ObstacleTracker()
    private var analyzerExecutor: ExecutorService? = null
    private var objectDetector: ObjectDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null

    override fun obstacleUpdates(): SharedFlow<List<DetectedObstacle>> = obstacles.asSharedFlow()

    /**
     * Binds with a visible camera preview (e.g. the "Engel Algılama" debug screen, where
     * seeing what the model sees has real value). Detection itself runs on [ImageAnalysis]
     * frames, entirely independent of this preview.
     */
    fun bind(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        bindInternal(lifecycleOwner, Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) })
    }

    /**
     * Binds for detection only, with no [Preview] use case and nothing to render — for
     * screens (like live navigation) where a raw camera feed isn't shown to the user and
     * would only be a pointless passthrough view to lay out around.
     */
    fun bind(lifecycleOwner: LifecycleOwner) {
        bindInternal(lifecycleOwner, preview = null)
    }

    private fun bindInternal(lifecycleOwner: LifecycleOwner, preview: Preview?) {
        val detector = ObjectDetector.createFromFileAndOptions(
            context,
            MODEL_ASSET_NAME,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setNumThreads(NUM_THREADS).build())
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(personScoreThreshold)
                .build(),
        )
        objectDetector = detector

        val executor = Executors.newSingleThreadExecutor()
        analyzerExecutor = executor

        val imageAnalysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(executor) { imageProxy -> analyzeFrame(imageProxy, detector) }
            }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()
                val useCases = listOfNotNull(preview, imageAnalysis).toTypedArray()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        // Stop and drain the analyzer thread before closing the detector, otherwise an
        // in-flight analyzeFrame() call can hit ObjectDetector.detect() after close().
        analyzerExecutor?.let { executor ->
            executor.shutdown()
            executor.awaitTermination(ANALYZER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        analyzerExecutor = null
        objectDetector?.close()
        objectDetector = null
    }

    private fun analyzeFrame(imageProxy: ImageProxy, detector: ObjectDetector) {
        val sensingStartMs = System.currentTimeMillis()
        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val uprightBitmap = imageProxy.toBitmap().rotated(rotationDegrees)
            val tensorImage = TensorImage.fromBitmap(uprightBitmap)

            val rawDetections = detector.detect(tensorImage)
                .mapNotNull { detection ->
                    val personCategory = detection.categories.firstOrNull { it.label.equals("person", ignoreCase = true) }
                        ?: return@mapNotNull null
                    val box = detection.boundingBox
                    ObstacleTracker.RawDetection(
                        centerXPx = box.centerX(),
                        centerYPx = box.centerY(),
                        distanceMeters = ObstacleGeometry.estimateDistanceMeters(box.height(), uprightBitmap.height),
                        bearingDeg = ObstacleGeometry.estimateBearingDegrees(box.centerX(), uprightBitmap.width),
                    ) to personCategory.score
                }

            val detectionDoneMs = System.currentTimeMillis()

            val tracked = tracker.update(rawDetections.map { it.first }, timestampMs = detectionDoneMs)
            val result = tracked.mapIndexed { index, t ->
                DetectedObstacle(
                    id = t.id,
                    type = ObstacleType.PERSON,
                    distanceMeters = t.distanceMeters,
                    bearingDeg = t.bearingDeg,
                    relativeVelocityMps = t.relativeVelocityMps,
                    confidence = rawDetections[index].second,
                    detectedAtMs = detectionDoneMs,
                    sensorSource = ObstacleSensorSource.CAMERA_ML,
                )
            }

            obstacles.tryEmit(result)
            onLatencyTrace(LatencyTrace(sensingStartMs = sensingStartMs, detectionDoneMs = detectionDoneMs))
        } catch (t: Throwable) {
            Log.e(TAG, "Frame analysis failed", t)
        } finally {
            imageProxy.close()
        }
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    companion object {
        private const val TAG = "CameraXObstacleDetector"
        const val MODEL_ASSET_NAME = "efficientdet_lite0.tflite"
        const val DEFAULT_SCORE_THRESHOLD = 0.5f
        const val MAX_RESULTS = 5
        const val NUM_THREADS = 4
        private const val ANALYZER_SHUTDOWN_TIMEOUT_MS = 500L
    }
}
