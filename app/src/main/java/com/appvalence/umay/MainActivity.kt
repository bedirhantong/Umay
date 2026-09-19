package com.appvalence.umay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appvalence.umay.files.BuildingImportDialogs
import com.appvalence.umay.files.BuildingImportViewModel
import com.appvalence.umay.files.FilesScreen
import com.appvalence.umay.navigation.NavigationScreen
import com.appvalence.umay.onboarding.OnboardingTour
import com.appvalence.umay.settings.SettingsScreen
import com.appvalence.umay.survey.BeaconScannerScreen
import com.appvalence.umay.ui.theme.UmayTheme
import dagger.hilt.android.AndroidEntryPoint

private enum class Screen(@param:StringRes val label: Int, val icon: ImageVector) {
    NAVIGATION(R.string.tab_navigation, Icons.Filled.Navigation),
    BEACON_SCANNER(R.string.tab_survey, Icons.Filled.Sensors),
    FILES(R.string.tab_files, Icons.Filled.Folder),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Shared with every screen that imports (hiltViewModel() in this activity resolves
    // to this same instance), so its dialogs show here whichever screen started it.
    private val importViewModel: BuildingImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh launch: after a configuration change or process restore the same
        // intent comes back, and importing it a second time would be a surprise.
        if (savedInstanceState == null) mapFileFrom(intent)?.let(importViewModel::importFrom)
        enableEdgeToEdge()
        setContent {
            UmayTheme {
                val mainViewModel: MainViewModel = hiltViewModel()
                val showTour by mainViewModel.showTour.collectAsStateWithLifecycle()
                if (showTour) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        OnboardingTour(onFinish = mainViewModel::onTourFinished, modifier = Modifier.padding(innerPadding))
                    }
                    return@UmayTheme
                }

                // Navigasyon is the actual product experience for the end user (find a
                // destination, get guided there); Beacon Tarayıcı is a setup tool for
                // whoever installs and surveys the building, so the app starts on
                // Navigasyon the way a mapping app opens on the map, not on a settings page.
                var selectedScreen by rememberSaveable { mutableStateOf(Screen.NAVIGATION) }
                // Each tab's composition is thrown away when another tab is picked; this
                // keeps what it had saved (its scroll position, say) for when it comes back.
                // Screen state that matters lives in the (activity-scoped) ViewModels.
                val tabState = rememberSaveableStateHolder()

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
                        tabState.SaveableStateProvider(selectedScreen.name) {
                            when (selectedScreen) {
                                Screen.BEACON_SCANNER -> BeaconScannerScreen(modifier = Modifier.weight(1f))
                                Screen.NAVIGATION -> NavigationScreen(modifier = Modifier.weight(1f))
                                Screen.FILES -> FilesScreen(modifier = Modifier.weight(1f))
                                Screen.SETTINGS -> SettingsScreen(onReplayTour = mainViewModel::replayTour, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                BuildingImportDialogs(importViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mapFileFrom(intent)?.let(importViewModel::importFrom)
    }

    /** The file in an "open with" (VIEW) or "share to" (SEND) intent, if that's what this is. */
    private fun mapFileFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
