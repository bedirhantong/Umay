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
import java.util.concurrent.atomic.AtomicReference

/** Why obstacle detection couldn't start — typed, so the UI words it in the user's language. */
enum class CameraFailure { MODEL_UNAVAILABLE, CAMERA_UNAVAILABLE }

/**
 * Faz 1 obstacle detector: CameraX ImageAnalysis feeding a TFLite EfficientDet-Lite0
 * model (FR-5), with monocular distance/bearing estimation (FR-6, [ObstacleGeometry])
 * and frame-to-frame velocity tracking (FR-7, [ObstacleTracker]).
 *
 * [onLatencyTrace] reports the sense->detect latency of every processed frame so
 * callers can verify the NFR-1 320ms end-to-end budget; this class only covers the
 * sensing+detection portion of that budget, not fusion/feedback.
 *
 * Threading, and why it matters for the rest of the app: the camera is bound and
 * unbound every time the navigation screen appears and disappears (a tab switch,
 * the screen turning off). None of that may block the main thread —
 * - the model (~4.5 MB, plus building an interpreter) is loaded once, on the
 *   analyzer thread, and kept until [release], not reloaded on every [bind];
 * - [unbind] never waits for the analyzer thread; the cleanup that has to come
 *   after an in-flight frame is queued behind it on that same thread instead.
 * Frames are also analyzed at most every [MIN_FRAME_INTERVAL_MS]: back-to-back
 * inference on every camera frame kept several cores busy and starved the UI,
 * for no safety benefit at walking speed.
 *
 * [bind], [unbind] and [release] are main-thread only.
 */
class CameraXObstacleDetector(
    private val context: Context,
    private val personScoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
    private val onLatencyTrace: (LatencyTrace) -> Unit = {},
    private val onBindFailed: (CameraFailure) -> Unit = {},
) : ObstacleDetector {

    private val obstacles = MutableSharedFlow<List<DetectedObstacle>>(replay = 1, extraBufferCapacity = 1)
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    // Analyzer-thread only.
    private var tracker = ObstacleTracker()
    private var objectDetector: ObjectDetector? = null
    private var modelLoadFailed = false
    private val frameThrottle = FrameThrottle(MIN_FRAME_INTERVAL_MS)

    // Main-thread only.
    private var analyzerExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Bumped by every bind/unbind, so the asynchronous steps of an older bind (model
    // loading, camera-provider lookup) can tell they've been superseded and back off.
    private var bindGeneration = 0

    // The most recent upright frame this detector processed, for on-demand secondary
    // readers (sign text) that want a look at what the camera currently sees without
    // binding a second camera session — CameraX only allows one binding at a time, so
    // every consumer in this app shares this one instead of fighting it for the camera.
    private val latestFrame = AtomicReference<Bitmap?>(null)

    init {
        // NavigationOrchestrator combine()s this with the position stream, which only
        // emits once *both* sources have emitted at least once — without a seed value,
        // a camera/model failure (see bindInternal's try/catch) would silently hang the
        // entire navigation experience, including position and route guidance, even
        // though obstacle detection is meant to be a best-effort secondary signal.
        obstacles.tryEmit(emptyList())
    }

    override fun obstacleUpdates(): SharedFlow<List<DetectedObstacle>> = obstacles.asSharedFlow()

    /** The camera's current view, upright — null before the first frame or after [unbind]. See [SignTextReader]. */
    fun latestFrameOrNull(): Bitmap? = latestFrame.get()

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
        val generation = ++bindGeneration
        val executor = analyzerExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "umay-obstacle-analyzer")
        }.also { analyzerExecutor = it }

        // Model loading, camera-provider acquisition, and bindToLifecycle can each throw
        // for reasons outside this app's control (corrupted asset on a flaky install, no
        // back camera on some tablets, camera service down) — obstacle detection is a
        // best-effort secondary signal, not something a failure here should take the
        // whole app down for. The seeded empty obstacles emission (see init) means the
        // rest of navigation (position, routing, TTS guidance) keeps working regardless.
        executor.execute {
            val detector = loadDetectorIfNeeded()
            mainExecutor.execute {
                if (generation != bindGeneration) return@execute // unbound (or rebound) meanwhile
                if (detector == null) {
                    onBindFailed(CameraFailure.MODEL_UNAVAILABLE)
                    return@execute
                }
                bindCamera(lifecycleOwner, preview, executor, detector, generation)
            }
        }
    }

    /** Analyzer thread only. Null if the model can't be loaded (and won't be retried). */
    private fun loadDetectorIfNeeded(): ObjectDetector? {
        objectDetector?.let { return it }
        if (modelLoadFailed) return null
        return try {
            ObjectDetector.createFromFileAndOptions(
                context,
                MODEL_ASSET_NAME,
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setNumThreads(NUM_THREADS).build())
                    .setMaxResults(MAX_RESULTS)
                    .setScoreThreshold(personScoreThreshold)
                    .build(),
            ).also { objectDetector = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Obstacle detection model failed to load", t)
            modelLoadFailed = true
            null
        }
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner, preview: Preview?, executor: ExecutorService, detector: ObjectDetector, generation: Int) {
        val analysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor) { imageProxy -> analyzeFrame(imageProxy, detector) } }
        imageAnalysis = analysis

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                if (generation != bindGeneration) return@addListener
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    provider.unbindAll()
                    val useCases = listOfNotNull(preview, analysis).toTypedArray()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
                } catch (t: Throwable) {
                    Log.e(TAG, "Camera binding failed", t)
                    onBindFailed(CameraFailure.CAMERA_UNAVAILABLE)
                }
            },
            mainExecutor,
        )
    }

    /**
     * Stops the camera and clears what it last saw. The clear matters: [obstacles]
     * replays its latest value, so without it a person seen just before the camera
     * stopped (screen turned off, app backgrounded) would stay "in front of" the
     * user indefinitely and keep re-triggering alerts for someone long gone.
     *
     * The loaded model is kept for the next [bind]; [release] frees it.
     */
    fun unbind() {
        bindGeneration++
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        // Queued behind any frame still being analyzed rather than done right here: a
        // late result from that frame would otherwise land *after* this reset and put the
        // person it saw straight back "in front of" the user.
        val executor = analyzerExecutor
        if (executor != null) executor.execute(::resetDetections) else resetDetections()
    }

    /** [unbind], and frees the model and the analyzer thread. The detector can still be bound again afterwards. */
    fun release() {
        unbind()
        val executor = analyzerExecutor ?: return
        analyzerExecutor = null
        executor.execute {
            objectDetector?.close()
            objectDetector = null
        }
        executor.shutdown()
    }

    private fun resetDetections() {
        tracker = ObstacleTracker()
        frameThrottle.reset()
        obstacles.tryEmit(emptyList())
        latestFrame.set(null)
    }

    private fun analyzeFrame(imageProxy: ImageProxy, detector: ObjectDetector) {
        val sensingStartMs = System.currentTimeMillis()
        if (!frameThrottle.tryAcquire(sensingStartMs)) {
            imageProxy.close()
            return
        }
        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val uprightBitmap = imageProxy.toBitmap().rotated(rotationDegrees)
            latestFrame.set(uprightBitmap)
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

        // Two, not four: with frames already capped at MIN_FRAME_INTERVAL_MS, extra
        // interpreter threads mostly compete with the UI thread for cores. Both are
        // starting points, recorded in every field log, to retune from measured latency.
        const val NUM_THREADS = 2

        // ~8 frames a second: at walking speed (~1.4 m/s) that's under 20 cm between
        // looks, well inside the 1.5 m CRITICAL / 3.5 m WARNING distances.
        const val MIN_FRAME_INTERVAL_MS = 125L
    }
}
