package com.policeradar.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.policeradar.app.data.DetectionRepository
import com.policeradar.app.data.SettingsStore
import com.policeradar.app.detect.Detector
import com.policeradar.app.scan.ScannerService
import com.policeradar.app.ui.HomeScreen
import com.policeradar.app.ui.Issue
import com.policeradar.app.ui.PoliceRadarTheme
import com.policeradar.app.ui.SettingsScreen
import com.policeradar.app.ui.SignaturesScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore.get(this)
        setContent {
            PoliceRadarTheme {
                App(store)
            }
        }
    }

    private enum class Screen { HOME, SETTINGS, SIGNATURES }

    @Composable
    private fun App(store: SettingsStore) {
        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
        val scanning by DetectionRepository.scanning.collectAsStateWithLifecycle()
        val devices by DetectionRepository.devices.collectAsStateWithLifecycle()
        val problem by DetectionRepository.scannerProblem.collectAsStateWithLifecycle()
        val settings by store.settings.collectAsStateWithLifecycle()
        val signatures by store.signatures.collectAsStateWithLifecycle()

        // Re-check permissions and radios whenever the user comes back to the app.
        var refresh by remember { mutableIntStateOf(0) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
        val issues = remember(refresh) { currentIssues() }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { refresh++ }

        // When signatures change, re-label devices already on screen.
        LaunchedEffect(signatures) { DetectionRepository.rematch { Detector.match(signatures, it) } }
        LaunchedEffect(settings.learningMode) { if (!settings.learningMode) DetectionRepository.dropUnmatched() }

        // Keep the screen on while scanning: many phones pause Bluetooth scans when it's off.
        LaunchedEffect(scanning) {
            if (scanning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

        when (screen) {
            Screen.HOME -> HomeScreen(
                scanning = scanning,
                devices = devices.values.toList(),
                issues = issues,
                problem = problem,
                learningMode = settings.learningMode,
                onStart = {
                    if (issues.any { it == Issue.PERMISSIONS }) {
                        permissionLauncher.launch(requiredPermissions())
                    } else {
                        ScannerService.start(this)
                    }
                },
                onStop = { ScannerService.stop(this) },
                onFixIssue = { issue ->
                    when (issue) {
                        Issue.PERMISSIONS -> permissionLauncher.launch(requiredPermissions())
                        Issue.LOCATION_OFF -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        Issue.BLUETOOTH_OFF -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                },
                onAddSignature = { label, category, type, pattern -> store.addCustom(label, category, type, listOf(pattern)) },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenSignatures = { screen = Screen.SIGNATURES },
            )
            Screen.SETTINGS -> SettingsScreen(
                settings = settings,
                onChange = { new -> store.updateSettings { new } },
                onBack = { screen = Screen.HOME },
            )
            Screen.SIGNATURES -> SignaturesScreen(
                signatures = signatures,
                onToggle = store::setEnabled,
                onAdd = { label, category, type, patterns -> store.addCustom(label, category, type, patterns) },
                onDelete = store::removeCustom,
                onBack = { screen = Screen.HOME },
            )
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun currentIssues(): List<Issue> = buildList {
        val missing = requiredPermissions().any {
            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) add(Issue.PERMISSIONS)
        val lm = getSystemService(LocationManager::class.java)
        if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) add(Issue.LOCATION_OFF)
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        if (bt != null && !bt.isEnabled) add(Issue.BLUETOOTH_OFF)
    }
}
