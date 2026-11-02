package com.appvalence.umay.survey.ar

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.appvalence.umay.R
import com.appvalence.umay.common.TextLink
import com.appvalence.umay.common.linkedStringResource

/**
 * The notice Google requires of every app using ARCore — "This application runs
 * on Google Play Services for AR (ARCore), which is provided by Google and
 * governed by the Google Privacy Policy" — with both links
 * (developers.google.com/ar/develop/privacy-requirements). Shown in Ayarlar →
 * Hakkında and wherever camera mapping is offered.
 */
@Composable
fun ArCoreDisclosure(modifier: Modifier = Modifier) {
    Text(
        text = linkedStringResource(
            R.string.arcore_disclosure,
            TextLink(stringResource(R.string.arcore_disclosure_service), stringResource(R.string.arcore_service_url)),
            TextLink(stringResource(R.string.arcore_disclosure_privacy_policy), stringResource(R.string.google_privacy_policy_url)),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
