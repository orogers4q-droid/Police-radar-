package com.bleradar.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bleradar.app.Settings
import com.bleradar.app.detect.Group
import com.bleradar.app.detect.Proximity
import com.bleradar.app.detect.Signal
import com.bleradar.app.detect.SignalTracker
import com.bleradar.app.detect.Source
import com.bleradar.app.detect.Trend
import com.bleradar.app.scan.HuntFeedback
import com.bleradar.app.scan.Scanner
import com.bleradar.app.scan.Tracked
import kotlinx.coroutines.delay

/** Track one device down: a big closeness meter plus beeps and buzzes that speed up as you close in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuntScreen(
    target: Tracked,
    scanning: Boolean,
    settings: Settings,
    onSettings: (Settings) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val tracker = remember(target.key) {
        SignalTracker().apply { add(target.latest.rssi, target.latest.timeMs) }
    }
    val feedback = remember { HuntFeedback(context) }
    var tick by remember { mutableIntStateOf(0) }
    val now by produceNow()
    val scanningNow by rememberUpdatedState(scanning)

    DisposableEffect(Unit) {
        feedback.start()
        onDispose { feedback.stop() }
    }
    LaunchedEffect(settings.sound, settings.vibrate) {
        feedback.sound = settings.sound
        feedback.vibrate = settings.vibrate
    }
    LaunchedEffect(target.key) {
        Scanner.sightings.collect { s ->
            if (s.key == target.key) {
                tracker.add(s.rssi, s.timeMs)
                tick++
            }
        }
    }
    // Keep the beeper in step with the latest reading, and go quiet when the signal is lost.
    LaunchedEffect(target.key) {
        while (true) {
            val smoothed = tracker.smoothed
            val lost = tracker.isLost(System.currentTimeMillis(), if (target.latest.source == Source.WIFI) 75_000 else 6_000)
            feedback.active = scanningNow && !lost && smoothed != null
            if (smoothed != null) feedback.closeness = Signal.closeness(smoothed)
            delay(100)
        }
    }

    // Reading tick makes the screen redraw on every new reading.
    val samples = if (tick >= 0) tracker.samples else 0
    val smoothed = tracker.smoothed ?: target.latest.rssi.toDouble()
    val wifi = target.latest.source == Source.WIFI
    val lost = tracker.isLost(now, if (wifi) 75_000 else 6_000)
    val closeness = Signal.closeness(smoothed)
    val trend = tracker.trend()
    val kind = target.classification.kind
    val lastSeenAgo = ((now - tracker.lastSeenMs) / 1000).coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track it down", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${if (wifi) "📶" else kind.emoji} ${target.latest.name ?: kind.label}",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Text(
                "${kind.label} · ${target.latest.address.uppercase()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            CloseMeter(
                closeness = if (lost) 0f else closeness,
                peak = tracker.peak?.let { Signal.closeness(it) } ?: 0f,
                lost = lost,
                headline = when {
                    !scanning -> "Scanner off"
                    lost -> "Signal lost"
                    else -> Proximity.fromRssi(smoothed).label
                },
                sub = when {
                    !scanning -> "Go back and tap Start scanning"
                    lost -> "Last heard ${lastSeenAgo}s ago"
                    else -> "${smoothed.toInt()} dBm · ${Signal.formatMeters(Signal.estimateMeters(smoothed, target.latest.txPower))}"
                },
                modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(1f),
            )

            Text(
                "$samples readings · strongest ${tracker.peak?.toInt() ?: smoothed.toInt()} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!lost && scanning) TrendBanner(trend)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.sound,
                    onClick = { onSettings(settings.copy(sound = !settings.sound)) },
                    label = { Text(if (settings.sound) "🔊 Beeping" else "🔇 Beeping off") },
                )
                FilterChip(
                    selected = settings.vibrate,
                    onClick = { onSettings(settings.copy(vibrate = !settings.vibrate)) },
                    label = { Text(if (settings.vibrate) "📳 Vibration" else "Vibration off") },
                )
            }
            OutlinedButton(onClick = { tracker.resetPeak(); tick++ }) { Text("Reset strongest mark") }

            Tips(wifi, kind.group, lost && lastSeenAgo > 45)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CloseMeter(closeness: Float, peak: Float, lost: Boolean, headline: String, sub: String, modifier: Modifier) {
    val animated by animateFloatAsState(closeness, tween(250), label = "closeness")
    val color by animateColorAsState(
        when {
            lost -> Muted
            closeness > 0.75f -> AlertRed
            closeness > 0.5f -> WarnAmber
            closeness > 0.25f -> RadarCyan
            else -> SafeGreen
        },
        tween(300), label = "color",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val start = 135f
            val sweep = 270f
            drawArc(color.copy(alpha = 0.15f), start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, start, sweep * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            // Strongest-so-far mark.
            if (peak > 0f) {
                drawArc(
                    androidx.compose.ui.graphics.Color.White, start + sweep * peak - 1f, 2f, false,
                    Offset(inset, inset), arcSize, style = Stroke(stroke + 8.dp.toPx()),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (lost) "—" else "${(closeness * 100).toInt()}%", fontSize = 56.sp, fontWeight = FontWeight.Black, color = color)
            Text(headline, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrendBanner(trend: Trend) {
    val color = when (trend) {
        Trend.WARMER -> SafeGreen
        Trend.COLDER -> AlertRed
        Trend.STEADY -> Muted
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "${trend.arrow}  ${trend.label}",
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            textAlign = TextAlign.Center, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color,
        )
    }
}

@Composable
private fun Tips(wifi: Boolean, group: Group, longLost: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How to find it", fontWeight = FontWeight.Bold)
            if (longLost && group == Group.TRACKER) {
                Text(
                    "⚠ Trackers change their Bluetooth address from time to time. If it doesn't come back, go back " +
                        "and pick the newest tracker in the list.",
                    color = WarnAmber,
                )
            }
            Text("• Faster, higher beeps mean closer. The white mark on the dial is the strongest reading so far.")
            Text("• Move slowly and pause for a second or two: readings settle after each step.")
            Text("• To get a direction, hold the phone against your chest and turn slowly in a circle. Your body blocks the signal, so it's strongest when you face the device.")
            if (wifi) {
                Text("• This is a Wi-Fi hotspot: Android only refreshes Wi-Fi every 30 seconds, so walk very slowly and wait for updates.", color = WarnAmber)
            }
            if (group == Group.CAMERA) {
                Text("• Check smoke alarms, clocks, chargers, air purifiers, picture frames, vents and anything pointing at the bed or shower. Then use the Lens finder.")
            } else {
                Text("• Trackers hide in bag linings, coat pockets, under car seats, in wheel arches and behind bumpers.")
            }
        }
    }
}
