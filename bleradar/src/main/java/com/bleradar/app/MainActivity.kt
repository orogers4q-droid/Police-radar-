package com.bleradar.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
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
import com.bleradar.app.scan.Scanner
import com.bleradar.app.scan.Tracked
import com.bleradar.app.ui.BleRadarTheme
import com.bleradar.app.ui.HomeScreen
import com.bleradar.app.ui.HuntScreen
import com.bleradar.app.ui.Issue
import com.bleradar.app.ui.LensFinderScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BleRadarTheme { App() } }
    }

    override fun onDestroy() {
        if (isFinishing) Scanner.stop()
        super.onDestroy()
    }

    private enum class Screen { HOME, HUNT, LENS }

    @Composable
    private fun App() {
        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
        var target by remember { mutableStateOf<Tracked?>(null) }
        val scanning by Scanner.scanning.collectAsStateWithLifecycle()
        val devices by Scanner.devices.collectAsStateWithLifecycle()
        val problem by Scanner.problem.collectAsStateWithLifecycle()
        val settings by Prefs.get(this).collectAsStateWithLifecycle()

        var refresh by remember { mutableIntStateOf(0) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
        val issues = remember(refresh) { currentIssues() }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            refresh++
            if (result.values.all { it }) Scanner.start(this)
        }

        // Keep the screen on while scanning: many phones pause Bluetooth scans when it's off.
        LaunchedEffect(scanning) {
            if (scanning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val hunting = target
        if (screen == Screen.HUNT && hunting == null) screen = Screen.HOME
        BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

        when (screen) {
            Screen.HOME -> HomeScreen(
                scanning = scanning,
                devices = devices.values.toList(),
                issues = issues,
                problem = problem,
                settings = settings,
                onSettings = { new -> Prefs.update(this) { new } },
                onStart = {
                    if (Issue.PERMISSIONS in issues) permissionLauncher.launch(requiredPermissions())
                    else Scanner.start(this)
                },
                onStop = Scanner::stop,
                onFixIssue = { issue ->
                    when (issue) {
                        Issue.PERMISSIONS -> permissionLauncher.launch(requiredPermissions())
                        Issue.LOCATION_OFF -> startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS))
                        Issue.BLUETOOTH_OFF -> startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
                    }
                },
                onTrack = { d ->
                    target = d
                    screen = Screen.HUNT
                },
                onLensFinder = { screen = Screen.LENS },
            )
            Screen.HUNT -> if (hunting != null) {
                HuntScreen(
                    target = hunting,
                    scanning = scanning,
                    settings = settings,
                    onSettings = { new -> Prefs.update(this) { new } },
                    onBack = { screen = Screen.HOME },
                )
            }
            Screen.LENS -> LensFinderScreen(onBack = { screen = Screen.HOME })
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
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
