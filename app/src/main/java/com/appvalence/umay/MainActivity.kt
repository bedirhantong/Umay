package com.appvalence.umay

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import com.appvalence.umay.files.FilesScreen
import com.appvalence.umay.files.rememberBuildingImporter
import com.appvalence.umay.navigation.NavigationScreen
import com.appvalence.umay.onboarding.OnboardingTour
import com.appvalence.umay.settings.SettingsScreen
import com.appvalence.umay.settings.UserSettings
import com.appvalence.umay.survey.BeaconScannerScreen
import com.appvalence.umay.ui.theme.UmayTheme

private enum class Screen(@param:StringRes val label: Int, val icon: ImageVector) {
    NAVIGATION(R.string.tab_navigation, Icons.Filled.Navigation),
    BEACON_SCANNER(R.string.tab_survey, Icons.Filled.Sensors),
    FILES(R.string.tab_files, Icons.Filled.Folder),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    // A map file handed over by another app (see the VIEW/SEND intent filters in the
    // manifest), waiting to go through the regular import flow once the UI is up.
    private var incomingMapFile by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh launch: after a configuration change or process restore the same
        // intent comes back, and importing it a second time would be a surprise.
        if (savedInstanceState == null) incomingMapFile = mapFileFrom(intent)
        enableEdgeToEdge()
        setContent {
            UmayTheme {
                // Navigasyon is the actual product experience for the end user (find a
                // destination, get guided there); Beacon Tarayıcı is a setup tool for
                // whoever installs and surveys the building, so the app starts on
                // Navigasyon the way a mapping app opens on the map, not on a settings page.
                var selectedScreen by remember { mutableStateOf(Screen.NAVIGATION) }
                val settings = remember { UserSettings(this) }
                // First launch opens on the spoken tour instead of dropping a blind user onto
                // an unexplained screen; replayable from Ayarlar.
                var showTour by rememberSaveable { mutableStateOf(!settings.onboardingCompleted) }
                if (showTour) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        OnboardingTour(
                            onFinish = {
                                settings.onboardingCompleted = true
                                showTour = false
                            },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                    return@UmayTheme
                }

                // Bumped whenever a map is imported from outside, so screens that listed the
                // buildings when they opened show the new one instead of a stale list.
                var mapsVersion by remember { mutableIntStateOf(0) }
                val importer = rememberBuildingImporter(onImported = { mapsVersion++ })
                LaunchedEffect(incomingMapFile) {
                    val uri = incomingMapFile ?: return@LaunchedEffect
                    incomingMapFile = null
                    importer.importFrom(uri)
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            Screen.entries.forEach { screen ->
                                NavigationBarItem(
                                    selected = selectedScreen == screen,
                                    onClick = { selectedScreen = screen },
                                    // null, not screen.label: the visible label below already
                                    // provides the accessible name, and a screen reader merges
                                    // this item's icon and label into one stop — repeating the
                                    // same text on both would read it twice.
                                    icon = { Icon(screen.icon, contentDescription = null) },
                                    label = { Text(stringResource(screen.label)) },
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        key(mapsVersion) {
                            when (selectedScreen) {
                                Screen.BEACON_SCANNER -> BeaconScannerScreen(modifier = Modifier.weight(1f))
                                Screen.NAVIGATION -> NavigationScreen(modifier = Modifier.weight(1f))
                                Screen.FILES -> FilesScreen(modifier = Modifier.weight(1f))
                                Screen.SETTINGS -> SettingsScreen(onReplayTour = { showTour = true }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mapFileFrom(intent)?.let { incomingMapFile = it }
    }

    /** The file in an "open with" (VIEW) or "share to" (SEND) intent, if that's what this is. */
    private fun mapFileFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
