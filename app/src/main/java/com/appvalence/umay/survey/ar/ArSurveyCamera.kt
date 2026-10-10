package com.appvalence.umay.survey.ar

import android.app.Activity
import com.appvalence.umay.positioning.mapping.ArPose
import com.appvalence.umay.positioning.mapping.DepthFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether this phone can survey with the camera. */
enum class ArAvailability {
    CHECKING,
    READY,

    /** Supported, but "Google Play Services for AR" is missing or too old: [ArSurveyCamera.requestInstall] fixes it. */
    NEEDS_INSTALL,

    /** This phone model isn't on Google's list of ARCore devices, camera or not. */
    UNSUPPORTED,

    /**
     * ARCore couldn't tell (an error, or a time-out: with ARCore not installed it asks
     * the Play Store, which needs the internet). Worth checking again, or installing.
     */
    UNKNOWN,
}

enum class ArInstallResult { INSTALLED, IN_PROGRESS, DECLINED, FAILED }

/** Why the camera can't place the phone right now — each has something the surveyor can do about it. */
enum class ArTrackingProblem { INSUFFICIENT_LIGHT, EXCESSIVE_MOTION, INSUFFICIENT_FEATURES, CAMERA_UNAVAILABLE, OTHER }

enum class ArCameraFailure { NOT_INSTALLED, UNSUPPORTED, NO_PERMISSION, CAMERA_IN_USE, OTHER }

sealed interface ArCameraStatus {
    data object Off : ArCameraStatus
    data object Starting : ArCameraStatus
    data object Tracking : ArCameraStatus
    data class Lost(val problem: ArTrackingProblem) : ArCameraStatus
    data class Failed(val failure: ArCameraFailure) : ArCameraStatus
}

/**
 * One tracked camera frame: where the phone is ([pose]), what horizontal
 * surfaces it has found ([upwardPlaneHeights], AR-world heights — the floor is
 * one of them, a table another), and, a few times a second on phones that can
 * measure it, what it sees ([depth]).
 */
class ArFrameSample(
    val pose: ArPose,
    val upwardPlaneHeights: List<Double>,
    val depth: DepthFrame?,
)

/**
 * The phone's camera as a measuring instrument for the survey: motion tracking
 * (ARCore) for a centimetre-accurate walked path, and depth for the walls
 * around it. The camera image itself is never shown or stored.
 *
 * Only works while the app is on screen (Android's camera rule), so the survey
 * screen calls [resume] and [pause] with its own lifecycle; everything else
 * reads [status] and [frames].
 */
interface ArSurveyCamera {
    val availability: StateFlow<ArAvailability>

    /** Null until a session has opened; then whether walls can be measured, not just the path. */
    val supportsDepth: StateFlow<Boolean?>

    val status: StateFlow<ArCameraStatus>

    /** Tracked frames only; a loss shows up in [status] instead. */
    val frames: SharedFlow<ArFrameSample>

    fun checkAvailability()

    /** Asks the Play Store to install or update ARCore; call again when the activity resumes. */
    fun requestInstall(activity: Activity, userRequested: Boolean): ArInstallResult

    /** Opens the camera (needs the CAMERA permission) and starts tracking. */
    fun resume()

    /** Releases the camera; tracking picks up where it was on [resume] if ARCore can relocalize. */
    fun pause()

    /** Ends the AR session: the next [resume] starts a new world frame. */
    fun close()
}
