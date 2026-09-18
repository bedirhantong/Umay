package com.appvalence.umay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.appvalence.umay.navigation.NavigationScreen
import com.appvalence.umay.survey.BeaconScannerScreen
import com.appvalence.umay.ui.theme.UmayTheme

private enum class Screen(val label: String, val icon: ImageVector) {
    NAVIGATION("Navigasyon", Icons.Filled.Navigation),
    BEACON_SCANNER("Beacon Tarayıcı", Icons.Filled.Sensors),
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
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            Screen.entries.forEach { screen ->
                                NavigationBarItem(
                                    selected = selectedScreen == screen,
                                    onClick = { selectedScreen = screen },
                                    icon = { Icon(screen.icon, contentDescription = screen.label) },
                                    label = { Text(screen.label) },
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        when (selectedScreen) {
                            Screen.BEACON_SCANNER -> BeaconScannerScreen(modifier = Modifier.weight(1f))
                            Screen.NAVIGATION -> NavigationScreen(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
