package com.bleradar.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bleradar.app.Settings
import com.bleradar.app.detect.Group
import com.bleradar.app.detect.Proximity
import com.bleradar.app.detect.Signal
import com.bleradar.app.detect.Source
import com.bleradar.app.detect.Threat
import com.bleradar.app.scan.Tracked
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

enum class Issue(val message: String, val action: String) {
    PERMISSIONS("The app needs Location and Nearby devices permissions to hear Bluetooth devices.", "Grant"),
    LOCATION_OFF("Location is turned off. Android won't report Bluetooth or Wi-Fi devices without it.", "Turn on"),
    BLUETOOTH_OFF("Bluetooth is off, so trackers and Bluetooth cameras can't be heard.", "Turn on"),
}

/** A tracker that has stayed in range this long may be travelling with you. */
const val FOLLOWING_MS = 10 * 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    scanning: Boolean,
    devices: List<Tracked>,
    issues: List<Issue>,
    problem: String?,
    settings: Settings,
    onSettings: (Settings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFixIssue: (Issue) -> Unit,
    onTrack: (Tracked) -> Unit,
    onLensFinder: () -> Unit,
) {
    val now by produceNow()
    var showSettings by remember { mutableStateOf(false) }

    val order = compareBy<Tracked> { it.classification.kind.threat.ordinal }.thenByDescending { it.latest.rssi }
    val cameras = devices.filter { it.classification.kind.group == Group.CAMERA }.sortedWith(order)
    val trackers = devices.filter { it.classification.kind.group == Group.TRACKER }.sortedWith(order)
    val others = devices.filter { it.classification.kind.group == Group.OTHER }.sortedByDescending { it.latest.rssi }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLE Radar", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { StatusCard(scanning, devices, cameras.size, trackers.size, onStart, onStop) }

            items(issues, key = { it.name }) { issue -> IssueCard(issue.message, issue.action) { onFixIssue(issue) } }
            if (problem != null && scanning) item { IssueCard(problem, null) {} }

            item { LensFinderCard(onLensFinder) }

            if (scanning) {
                if (cameras.isNotEmpty()) {
                    item { SectionTitle("📷 Possible hidden cameras", "Tap one to track it down.") }
                    items(cameras, key = { it.key }) { d -> DeviceCard(d, now) { onTrack(d) } }
                }
                if (trackers.isNotEmpty()) {
                    item { SectionTitle("🏷️ Trackers", "Tap one to track it down.") }
                    items(trackers, key = { it.key }) { d -> DeviceCard(d, now) { onTrack(d) } }
                }
                if (cameras.isEmpty() && trackers.isEmpty()) {
                    item {
                        Text(
                            "No trackers or camera signals yet. Walk slowly through the room or around the car; " +
                                "Bluetooth only reaches 10–30 m.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (settings.showAll && others.isNotEmpty()) {
                    item { SectionTitle("🔵 Other Bluetooth devices (${others.size})", "Anything else nearby. Tap one to track it.") }
                    items(others, key = { it.key }) { d -> DeviceCard(d, now) { onTrack(d) } }
                }
            } else {
                item { HowItWorks() }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showSettings) SettingsDialog(settings, onSettings) { showSettings = false }
}

@Composable
private fun StatusCard(
    scanning: Boolean,
    devices: List<Tracked>,
    cameras: Int,
    trackers: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val alert = scanning && (cameras + trackers) > 0
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (alert) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RadarScope(scanning, devices, Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    !scanning -> "Not scanning"
                    alert -> listOfNotNull(
                        if (cameras > 0) "$cameras possible camera${if (cameras == 1) "" else "s"}" else null,
                        if (trackers > 0) "$trackers tracker${if (trackers == 1) "" else "s"}" else null,
                    ).joinToString(" · ")
                    else -> "No trackers or cameras"
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (scanning) "${devices.size} Bluetooth signal${if (devices.size == 1) "" else "s"} in range"
                else "Tap start, then walk around slowly",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = if (scanning) onStop else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (scanning) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
                    contentColor = if (scanning) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (scanning) "Stop scanning" else "Start scanning", fontSize = 17.sp)
            }
        }
    }
}

/**
 * Radar picture: closer signals sit nearer the centre. Bluetooth can't tell direction,
 * so a blip's position around the circle is fixed per device but means nothing.
 */
@Composable
private fun RadarScope(scanning: Boolean, devices: List<Tracked>, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val angle by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "angle",
    )
    val blink by transition.animateFloat(
        0.4f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "blink",
    )
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(Color(0xFF0B1220), r, c)
        for (i in 1..4) drawCircle(RadarCyan.copy(alpha = 0.25f), r * i / 4, c, style = Stroke(1.5.dp.toPx()))
        drawLine(RadarCyan.copy(alpha = 0.15f), Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(RadarCyan.copy(alpha = 0.15f), Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        if (scanning) {
            rotate(angle, c) {
                drawCircle(
                    Brush.sweepGradient(
                        0f to Color.Transparent, 0.85f to Color.Transparent, 1f to RadarCyan.copy(alpha = 0.35f),
                        center = c,
                    ),
                    r, c,
                )
                drawLine(RadarCyan, c, Offset(c.x + r, c.y), strokeWidth = 2.dp.toPx())
            }
            for (d in devices) {
                val threat = d.classification.kind.threat
                val dist = 1f - Signal.closeness(d.latest.rssi.toDouble())
                val a = ((d.key.hashCode() and 0x7fffffff) % 360) * (Math.PI / 180)
                val p = Offset(c.x + (r * 0.92f * dist * cos(a)).toFloat(), c.y + (r * 0.92f * dist * sin(a)).toFloat())
                val important = threat == Threat.HIGH || threat == Threat.MEDIUM
                drawCircle(
                    threatColor(threat).copy(alpha = if (important) blink else 0.6f),
                    (if (important) 6f else 3.5f).dp.toPx(), p,
                )
            }
        }
        drawCircle(RadarCyan, 4.dp.toPx(), c)
    }
}

@Composable
private fun DeviceCard(d: Tracked, now: Long, onClick: () -> Unit) {
    val s = d.latest
    val kind = d.classification.kind
    val accent = threatColor(kind.threat)
    val withYouMs = now - d.firstSeenMs
    val following = kind.group == Group.TRACKER && kind.threat != Threat.LOW && withYouMs >= FOLLOWING_MS
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.source == Source.WIFI) "📶" else kind.emoji, fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (kind.threat != Threat.NONE) {
                    Text(kind.label.uppercase(), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(s.name ?: "Unnamed device", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    "${s.source.label} · ${s.address.uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                if (kind.threat != Threat.NONE) {
                    Text(d.classification.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${Proximity.fromRssi(s.rssi).label} · ${s.rssi} dBm · ${Signal.formatMeters(Signal.estimateMeters(s.rssi.toDouble(), s.txPower))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (following) {
                    Text(
                        "⚠ In range for ${withYouMs / 60_000} min: it may be travelling with you",
                        color = AlertRed, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FilledTonalButton(onClick = onClick) { Text("Track") }
        }
    }
}

@Composable
private fun LensFinderCard(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔦", fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Lens finder", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    "Many hidden cameras don't use Bluetooth at all. Use the flashlight to make their lenses glint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClick) { Text("Open") }
        }
    }
}

@Composable
private fun SettingsDialog(settings: Settings, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingRow("Beeping", "When tracking, and for new finds", settings.sound) { onChange(settings.copy(sound = it)) }
                SettingRow("Vibration", "When tracking, and for new finds", settings.vibrate) { onChange(settings.copy(vibrate = it)) }
                SettingRow("Alert on new finds", "Chime when a tracker or possible camera first shows up", settings.alerts) {
                    onChange(settings.copy(alerts = it))
                }
                SettingRow("Show all devices", "List every Bluetooth device, not just trackers and cameras", settings.showAll) {
                    onChange(settings.copy(showAll = it))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun SettingRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun IssueCard(message: String, action: String?, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = WarnAmber)
            Spacer(Modifier.width(10.dp))
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            if (action != null) TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun SectionTitle(text: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HowItWorks() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How it works", fontWeight = FontWeight.Bold)
            Text("BLE Radar listens to every Bluetooth Low Energy device around you and flags the ones worth worrying about:")
            Text("🏷️ Trackers: AirTags and other Find My tags, Tile, Samsung SmartTag, Chipolo and Google Find My Device tags. One that stays near you for 10+ minutes is flagged as possibly travelling with you.")
            Text("📷 Possible hidden cameras: Bluetooth devices named like cameras, and the Wi-Fi hotspots cheap spy cameras create.")
            Text("Tap any device to track it down: the phone beeps and buzzes faster, and the beep gets higher, as you get closer.")
            Text(
                "It only listens; it never connects to anything. Matches are educated guesses: many cameras use only Wi-Fi " +
                    "or nothing at all, so also use the Lens finder.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Current time, ticking every second. */
@Composable
fun produceNow(): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(1000)
        }
    }
    return state
}
