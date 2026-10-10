package com.appvalence.umay.survey.ar

import android.app.Activity
import android.content.Context
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.appvalence.umay.positioning.mapping.ArPose
import com.appvalence.umay.positioning.mapping.DepthFrame
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2

/**
 * [ArSurveyCamera] on ARCore, with no camera preview: the surveyor looks at
 * the corridor, not the screen, and the screen shows the map being drawn.
 *
 * ARCore still needs an OpenGL context and a texture to stream the camera
 * into, so this keeps an offscreen (pbuffer) one on its own thread, and runs
 * every session call there — ARCore sessions aren't thread-safe, and
 * [Session.update] blocks until the next camera frame.
 */
@Singleton
class ArCoreSurveyCamera @Inject constructor(@param:ApplicationContext private val context: Context) : ArSurveyCamera {

    private val _availability = MutableStateFlow(ArAvailability.CHECKING)
    override val availability: StateFlow<ArAvailability> = _availability.asStateFlow()

    private val _supportsDepth = MutableStateFlow<Boolean?>(null)
    override val supportsDepth: StateFlow<Boolean?> = _supportsDepth.asStateFlow()

    private val _status = MutableStateFlow<ArCameraStatus>(ArCameraStatus.Off)
    override val status: StateFlow<ArCameraStatus> = _status.asStateFlow()

    // A slow consumer loses old frames, never blocks the camera thread.
    private val _frames = MutableSharedFlow<ArFrameSample>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val frames: SharedFlow<ArFrameSample> = _frames.asSharedFlow()

    private val handler: Handler by lazy { Handler(HandlerThread(THREAD_NAME).apply { start() }.looper) }

    // Everything below is touched on the AR thread only.
    private var session: Session? = null
    private var gl: OffscreenGlContext? = null
    private var running = false
    private var depthEnabled = false
    private var lastPoseEmittedMs = 0L
    private var lastDepthAttemptMs = 0L
    private var lastDepthTimestampNs = 0L

    override fun checkAvailability() {
        try {
            ArCoreApk.getInstance().checkAvailabilityAsync(context) { availability ->
                // The raw answer, for telling "this phone can't" from "couldn't check" in a bug report.
                Log.i(TAG, "ARCore availability: $availability")
                _availability.value = availabilityOf(availability)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "ARCore availability check failed", e)
            _availability.value = ArAvailability.UNKNOWN
        }
    }

    override fun requestInstall(activity: Activity, userRequested: Boolean): ArInstallResult = try {
        when (ArCoreApk.getInstance().requestInstall(activity, userRequested)) {
            ArCoreApk.InstallStatus.INSTALLED -> {
                _availability.value = ArAvailability.READY
                ArInstallResult.INSTALLED
            }
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> ArInstallResult.IN_PROGRESS
        }
    } catch (e: UnavailableUserDeclinedInstallationException) {
        ArInstallResult.DECLINED
    } catch (e: UnavailableDeviceNotCompatibleException) {
        _availability.value = ArAvailability.UNSUPPORTED
        ArInstallResult.FAILED
    } catch (e: RuntimeException) {
        Log.w(TAG, "ARCore install request failed", e)
        ArInstallResult.FAILED
    }

    override fun resume() {
        handler.post(::resumeOnArThread)
    }

    override fun pause() {
        handler.post {
            running = false
            session?.pause()
            if (_status.value !is ArCameraStatus.Failed) _status.value = ArCameraStatus.Off
        }
    }

    override fun close() {
        handler.post {
            running = false
            session?.close()
            session = null
            gl?.release()
            gl = null
            _supportsDepth.value = null
            _status.value = ArCameraStatus.Off
        }
    }

    private fun resumeOnArThread() {
        if (running) return
        _status.value = ArCameraStatus.Starting
        val session = session ?: openSession() ?: return
        try {
            session.resume()
        } catch (e: CameraNotAvailableException) {
            fail(ArCameraFailure.CAMERA_IN_USE, e)
            return
        } catch (e: SecurityException) {
            fail(ArCameraFailure.NO_PERMISSION, e)
            return
        }
        running = true
        handler.post(updateLoop)
    }

    private fun openSession(): Session? {
        val glContext = gl ?: OffscreenGlContext.create() ?: run {
            fail(ArCameraFailure.OTHER, null)
            return null
        }
        gl = glContext
        val session = try {
            Session(context)
        } catch (e: UnavailableArcoreNotInstalledException) {
            _availability.value = ArAvailability.NEEDS_INSTALL
            fail(ArCameraFailure.NOT_INSTALLED, e)
            return null
        } catch (e: UnavailableApkTooOldException) {
            _availability.value = ArAvailability.NEEDS_INSTALL
            fail(ArCameraFailure.NOT_INSTALLED, e)
            return null
        } catch (e: UnavailableSdkTooOldException) {
            fail(ArCameraFailure.UNSUPPORTED, e)
            return null
        } catch (e: UnavailableDeviceNotCompatibleException) {
            _availability.value = ArAvailability.UNSUPPORTED
            fail(ArCameraFailure.UNSUPPORTED, e)
            return null
        } catch (e: SecurityException) {
            fail(ArCameraFailure.NO_PERMISSION, e)
            return null
        } catch (e: RuntimeException) {
            fail(ArCameraFailure.OTHER, e)
            return null
        }

        // Raw depth, not the smoothed kind: the smoothing fills holes with guesses,
        // which on a wall map would draw walls that aren't there.
        depthEnabled = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        session.configure(
            Config(session)
                .setUpdateMode(Config.UpdateMode.BLOCKING)
                .setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL)
                .setLightEstimationMode(Config.LightEstimationMode.DISABLED)
                .setFocusMode(Config.FocusMode.AUTO)
                .setDepthMode(if (depthEnabled) Config.DepthMode.RAW_DEPTH_ONLY else Config.DepthMode.DISABLED),
        )
        session.setCameraTextureName(glContext.cameraTexture)
        // Nothing is displayed, but ARCore wants a geometry before the first update.
        session.setDisplayGeometry(Surface.ROTATION_0, 1, 1)
        _supportsDepth.value = depthEnabled
        this.session = session
        return session
    }

    private val updateLoop = object : Runnable {
        override fun run() {
            val session = session ?: return
            if (!running) return
            try {
                onFrame(session, session.update())
            } catch (e: SessionPausedException) {
                return
            } catch (e: CameraNotAvailableException) {
                running = false
                fail(ArCameraFailure.CAMERA_IN_USE, e)
                return
            } catch (e: RuntimeException) {
                running = false
                fail(ArCameraFailure.OTHER, e)
                return
            }
            if (running) handler.post(this)
        }
    }

    private fun onFrame(session: Session, frame: Frame) {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            val reason = camera.trackingFailureReason
            // Right after resuming ARCore is still initializing, with no reason to give.
            if (reason != TrackingFailureReason.NONE || _status.value == ArCameraStatus.Tracking) {
                _status.value = ArCameraStatus.Lost(reason.toProblem())
            }
            return
        }
        _status.value = ArCameraStatus.Tracking

        val now = System.currentTimeMillis()
        val depthDue = depthEnabled && now - lastDepthAttemptMs >= DEPTH_INTERVAL_MS
        if (!depthDue && now - lastPoseEmittedMs < POSE_INTERVAL_MS) return
        lastPoseEmittedMs = now

        val pose = camera.pose
        val zAxis = pose.zAxis
        // The camera looks along its −Z; on the plan (AR x, −z) that's (−zx, zz).
        val headingDeg = Math.toDegrees(atan2(-zAxis[0].toDouble(), zAxis[2].toDouble()))
        val arPose = ArPose(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble(), (headingDeg + 360.0) % 360.0, now)

        var depth: DepthFrame? = null
        var planeHeights = emptyList<Double>()
        if (depthDue) {
            lastDepthAttemptMs = now
            depth = readDepth(frame)
            if (depth != null) {
                planeHeights = session.getAllTrackables(Plane::class.java)
                    .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                    .map { it.centerPose.ty().toDouble() }
            }
        }
        _frames.tryEmit(ArFrameSample(arPose, planeHeights, depth))
    }

    private fun readDepth(frame: Frame): DepthFrame? {
        val depthImage = try {
            frame.acquireRawDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return null
        }
        depthImage.use { image ->
            // Raw depth updates slower than the camera; the same image twice adds nothing.
            if (image.timestamp == lastDepthTimestampNs) return null
            lastDepthTimestampNs = image.timestamp
            val confidence = try {
                frame.acquireRawDepthConfidenceImage().use(::copyConfidence)
            } catch (e: NotYetAvailableException) {
                null
            }

            val width = image.width
            val height = image.height
            val intrinsics = frame.camera.textureIntrinsics
            val focal = intrinsics.focalLength
            val principal = intrinsics.principalPoint
            val dimensions = intrinsics.imageDimensions
            if (dimensions[0] <= 0 || dimensions[1] <= 0) return null
            val scaleX = width.toFloat() / dimensions[0]
            val scaleY = height.toFloat() / dimensions[1]
            val cameraPose = FloatArray(16).also { frame.camera.pose.toMatrix(it, 0) }
            return DepthFrame(
                width = width,
                height = height,
                depthMm = copyDepth(image),
                confidence = confidence?.takeIf { it.size == width * height },
                fx = focal[0] * scaleX,
                fy = focal[1] * scaleY,
                cx = principal[0] * scaleX,
                cy = principal[1] * scaleY,
                cameraPose = cameraPose,
            )
        }
    }

    private fun copyDepth(image: Image): ShortArray {
        val plane = image.planes[0]
        val buffer = plane.buffer.order(ByteOrder.nativeOrder())
        val out = ShortArray(image.width * image.height)
        for (row in 0 until image.height) {
            for (column in 0 until image.width) {
                out[row * image.width + column] = buffer.getShort(row * plane.rowStride + column * plane.pixelStride)
            }
        }
        return out
    }

    private fun copyConfidence(image: Image): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val out = ByteArray(image.width * image.height)
        for (row in 0 until image.height) {
            for (column in 0 until image.width) {
                out[row * image.width + column] = buffer.get(row * plane.rowStride + column * plane.pixelStride)
            }
        }
        return out
    }

    private fun fail(failure: ArCameraFailure, cause: Throwable?) {
        Log.w(TAG, "AR camera failed: $failure", cause)
        _status.value = ArCameraStatus.Failed(failure)
    }


    private fun TrackingFailureReason.toProblem(): ArTrackingProblem = when (this) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> ArTrackingProblem.INSUFFICIENT_LIGHT
        TrackingFailureReason.EXCESSIVE_MOTION -> ArTrackingProblem.EXCESSIVE_MOTION
        TrackingFailureReason.INSUFFICIENT_FEATURES -> ArTrackingProblem.INSUFFICIENT_FEATURES
        TrackingFailureReason.CAMERA_UNAVAILABLE -> ArTrackingProblem.CAMERA_UNAVAILABLE
        else -> ArTrackingProblem.OTHER
    }

    private companion object {
        const val TAG = "ArCoreSurveyCamera"
        const val THREAD_NAME = "ArSurveyCamera"

        // The path needs no more than this; every extra pose is work for nothing.
        const val POSE_INTERVAL_MS = 100L

        // A few depth images a second is plenty at walking speed, and keeps the phone cool.
        const val DEPTH_INTERVAL_MS = 200L
    }
}

/**
 * ARCore's answer, as the survey screen needs it. Only "not capable" means the
 * phone can't: an error or a time-out only means the check didn't get an answer.
 */
internal fun availabilityOf(availability: ArCoreApk.Availability): ArAvailability = when (availability) {
    ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArAvailability.READY
    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD, ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArAvailability.NEEDS_INSTALL
    ArCoreApk.Availability.UNKNOWN_CHECKING -> ArAvailability.CHECKING
    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArAvailability.UNSUPPORTED
    ArCoreApk.Availability.UNKNOWN_ERROR, ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> ArAvailability.UNKNOWN
}

/** A 1×1 offscreen EGL surface, current on the thread that created it, and the camera texture ARCore streams into. */
private class OffscreenGlContext private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    private val surface: EGLSurface,
    val cameraTexture: Int,
) {
    fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(cameraTexture), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
    }

    companion object {
        fun create(): OffscreenGlContext? {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val config = configs[0] ?: return null

            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null
            val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroyContext(display, context)
                return null
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
                return null
            }

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            return OffscreenGlContext(display, context, surface, textures[0])
        }
    }
}
