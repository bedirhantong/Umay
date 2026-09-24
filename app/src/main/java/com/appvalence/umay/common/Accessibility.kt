package com.appvalence.umay.common

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Whether a screen reader that speaks on touch (TalkBack) is running, kept up
 * to date if it's switched on or off while the app is open.
 *
 * Screens that read their own explanation aloud (onboarding, permission
 * rationale) must not also do so while TalkBack is on: both voices would
 * speak at once. With TalkBack on they announce through it instead (a live
 * region), so the person hears one voice — the one they chose.
 */
@Composable
fun rememberIsScreenReaderOn(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var enabled by remember { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * Whether the system-wide "remove animations" accessibility setting is on
 * (mirrored by the animator duration scale developer setting). Purely
 * decorative motion — page-transition slides, crossfades — should collapse
 * to an instant cut instead of fighting a preference the user already set.
 */
@Composable
fun rememberIsReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
