package com.appvalence.umay.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.feedback.TtsFeedbackController

/**
 * Shown *before* the system permission dialog, instead of firing that dialog
 * the instant a screen opens: explains in plain language why the permission
 * is needed, and only requests it once the person taps through. A blind user
 * can't read the system dialog's fine print either way, so [message] is also
 * spoken via TTS as soon as this appears — the system prompt that follows
 * (after [onContinue]) still needs a sighted person or TalkBack to act on,
 * but at least the *reason* is available to everyone up front.
 */
@Composable
fun PermissionRationale(
    icon: ImageVector,
    title: String,
    message: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    buttonLabel: String = "Devam et",
) {
    val context = LocalContext.current
    val feedback = remember { TtsFeedbackController(context) }
    DisposableEffect(Unit) { onDispose { feedback.shutdown() } }
    LaunchedEffect(message) {
        feedback.emit(
            NavigationAlert(
                id = "permission-rationale",
                priority = AlertPriority.INFO,
                message = message,
                relatedObstacle = null,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), onClick = onContinue) {
            Text(buttonLabel)
        }
    }
}
