package com.appvalence.umay

import android.os.Bundle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.appvalence.umay.files.FilesScreen
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
