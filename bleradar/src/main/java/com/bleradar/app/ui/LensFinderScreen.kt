package com.bleradar.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Hidden camera lenses reflect light straight back at its source. With the flashlight on and the
 * camera looking from right beside it, a lens shows up as a small, sharp, bright dot.
 * The front camera usually has no infrared filter, so it can also see a camera's night-vision LEDs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LensFinderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(false) }
    var darken by remember { mutableStateOf(true) }
    var crosshair by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lens finder", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                if (granted) {
                    CameraPreview(front = front, darken = darken)
                    if (crosshair) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(RadarCyan.copy(alpha = 0.7f)))
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text("The lens finder needs the camera.")
                        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    }
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !front, onClick = { front = false }, label = { Text("🔦 Lens glint") })
                    FilterChip(selected = front, onClick = { front = true }, label = { Text("🟣 Infrared check") })
                    FilterChip(selected = darken, onClick = { darken = !darken }, label = { Text("Darken") })
                }
                FilterChip(selected = crosshair, onClick = { crosshair = !crosshair }, label = { Text("Centre dot") })
                Text(
                    if (!front) {
                        "Turn the room lights off. Hold the phone at eye level and sweep slowly across the room. " +
                            "A camera lens shows up as a tiny, sharp blue or white sparkle that stays put as you move. " +
                            "Check it up close: glass and mirrors glint too."
                    } else {
                        "In a dark room, point the screen side of the phone around. Night-vision cameras have infrared " +
                            "LEDs that are invisible to you but show up here as a purple or white glow."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(front: Boolean, darken: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(front, owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(owner, selector, preview).also { cam ->
                    if (!front && cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(true)
                }
            } catch (e: Exception) {
                camera = null
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            camera?.cameraControl?.enableTorch(false)
            provider?.unbindAll()
            camera = null
        }
    }

    // Under-exposing hides the room and leaves only bright reflections and LEDs.
    LaunchedEffect(camera, darken) {
        val cam = camera ?: return@LaunchedEffect
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        if (cam.cameraInfo.exposureState.isExposureCompensationSupported) {
            cam.cameraControl.setExposureCompensationIndex(if (darken) range.lower else 0)
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
