package com.policeradar.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.policeradar.app.data.SeenDevice
import com.policeradar.app.detect.Confidence
import com.policeradar.app.detect.Detector
import com.policeradar.app.detect.MatchType
import com.policeradar.app.detect.Proximity
import com.policeradar.app.detect.SignalSource
import kotlinx.coroutines.delay

enum class Issue(val message: String, val action: String) {
    PERMISSIONS("The app needs Location, Nearby devices and Notifications permissions to scan.", "Grant"),
    LOCATION_OFF("Location is turned off. Android won't report Wi-Fi or Bluetooth signals without it.", "Turn on"),
    BLUETOOTH_OFF("Bluetooth is off, so body cameras and other Bluetooth gear can't be detected.", "Turn on"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    scanning: Boolean,
    devices: List<SeenDevice>,
    issues: List<Issue>,
    problem: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFixIssue: (Issue) -> Unit,
    onAddSignature: (label: String, type: MatchType, pattern: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSignatures: () -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    var marking by remember { mutableStateOf<SeenDevice?>(null) }
    val now by produceNow()

    val police = devices.filter { it.signature != null }
        .sortedWith(compareBy<SeenDevice> { it.signature!!.confidence.ordinal }.thenByDescending { it.latest.rssi })
    val others = devices.filter { it.signature == null }.sortedByDescending { it.latest.rssi }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Police Radar", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSignatures) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "What it looks for")
                    }
                    IconButton(onClick = onOpenSettings) {
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
            item { StatusCard(scanning, police.size, devices.size, onStart, onStop) }

            items(issues, key = { it.name }) { issue ->
                IssueCard(issue.message, issue.action) { onFixIssue(issue) }
            }
            if (problem != null && scanning) {
                item { IssueCard(problem, null) {} }
            }

            if (scanning) {
                item { SectionTitle(if (police.isEmpty()) "No police signals right now" else "Police signals detected") }
                items(police, key = { it.latest.key }) { d -> DeviceCard(d, now, highlight = true) {} }

                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Show all nearby signals", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Tap one to mark it as police so it alerts next time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = showAll, onCheckedChange = { showAll = it })
                    }
                }
                if (showAll) {
                    items(others, key = { it.latest.key }) { d -> DeviceCard(d, now, highlight = false) { marking = d } }
                }
            } else {
                item { HowItWorks() }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    marking?.let { d ->
        MarkDeviceDialog(
            device = d,
            onDismiss = { marking = null },
            onConfirm = { label, type, pattern ->
                onAddSignature(label, type, pattern)
                marking = null
            },
        )
    }
}

@Composable
private fun StatusCard(scanning: Boolean, policeCount: Int, totalCount: Int, onStart: () -> Unit, onStop: () -> Unit) {
    val alert = scanning && policeCount > 0
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (alert) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RadarDot(scanning, alert)
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    !scanning -> "Not scanning"
                    alert -> "Police signal nearby!"
                    else -> "All clear"
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (scanning) "$totalCount signals in range" else "Tap start before you drive",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = if (scanning) onStop else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (scanning) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (scanning) "Stop scanning" else "Start scanning", fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun RadarDot(scanning: Boolean, alert: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (alert) 400 else 1200), RepeatMode.Reverse),
        label = "alpha",
    )
    val color = when {
        !scanning -> MaterialTheme.colorScheme.onSurfaceVariant
        alert -> PoliceRed
        else -> SafeGreen
    }
    Box(
        Modifier.size(56.dp).alpha(if (scanning) pulse else 0.5f).clip(CircleShape).background(color),
    )
}

@Composable
private fun DeviceCard(d: SeenDevice, now: Long, highlight: Boolean, onClick: () -> Unit) {
    val obs = d.latest
    val sig = d.signature
    val proximity = Proximity.fromRssi(obs.rssi)
    val accent = when (sig?.confidence) {
        Confidence.HIGH -> PoliceRed
        Confidence.MEDIUM -> WarnAmber
        Confidence.LOW -> PoliceBlue
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !highlight, onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (obs.source == SignalSource.WIFI) "📶" else "🔵", fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (sig != null) {
                    Text(sig.confidence.label.uppercase(), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(sig.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                } else {
                    Text(obs.name ?: "Unnamed ${obs.source.label} device", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    buildString {
                        append(obs.source.label)
                        if (sig != null && obs.name != null) append(" · \"").append(obs.name).append('"')
                        append(" · ").append(Detector.normalizeMac(obs.address))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val ago = ((now - obs.timeMs) / 1000).coerceAtLeast(0)
                Text(
                    "${proximity.label} (${obs.rssi} dBm) · seen ${ago}s ago",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SignalBars(obs.rssi, accent)
        }
    }
}

@Composable
private fun SignalBars(rssi: Int, color: Color) {
    val level = when (Proximity.fromRssi(rssi)) {
        Proximity.VERY_CLOSE -> 4
        Proximity.CLOSE -> 3
        Proximity.NEARBY -> 2
        Proximity.FAR -> 1
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(6.dp)
                    .height((6 + i * 5).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= level) color else color.copy(alpha = 0.2f)),
            )
        }
    }
}

@Composable
private fun IssueCard(message: String, action: String?, onClick: () -> Unit) {
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
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun HowItWorks() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How it works", fontWeight = FontWeight.Bold)
            Text("Patrol cars and officers carry gear that constantly broadcasts radio signals: in-car Wi-Fi routers, body cameras, Tasers. Police Radar listens for those signals and pings you when one gets close, telling you what it is.")
            Text("It only listens. It never connects to, or interferes with, anything.")
            Text(
                "Matches are educated guesses. The same routers are used by ambulances, buses and businesses, and some police gear isn't detectable. Always drive safely and legally.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MarkDeviceDialog(
    device: SeenDevice,
    onDismiss: () -> Unit,
    onConfirm: (label: String, type: MatchType, pattern: String) -> Unit,
) {
    val obs = device.latest
    val random = Detector.isRandomAddress(obs.address)
    data class Option(val text: String, val type: MatchType, val pattern: String)
    val options = buildList {
        add(Option("This exact device", MatchType.MAC_PREFIX, Detector.normalizeMac(obs.address)))
        if (!random) {
            add(Option("Any device from the same maker (${Detector.manufacturerPrefix(obs.address)})", MatchType.MAC_PREFIX, Detector.manufacturerPrefix(obs.address)))
        }
        if (!obs.name.isNullOrBlank()) {
            val type = if (obs.source == SignalSource.WIFI) MatchType.WIFI_NAME else MatchType.BLE_NAME
            add(Option("Anything named \"${obs.name}\"", type, obs.name))
        }
    }
    var selected by remember { mutableStateOf(0) }
    var label by remember { mutableStateOf(obs.name ?: "Police device") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark as police") },
        text = {
            Column {
                Text("Alert me when I'm near:")
                options.forEachIndexed { i, opt ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected == i, onClick = { selected = i }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == i, onClick = { selected = i })
                        Text(opt.text)
                    }
                }
                if (random) {
                    Text(
                        "This device uses a randomly changing address, so matching \"this exact device\" may stop working later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Call it") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { options[selected].let { onConfirm(label, it.type, it.pattern) } }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Current time, ticking every second so "seen Xs ago" stays fresh. */
@Composable
private fun produceNow(): androidx.compose.runtime.State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(1000)
        }
    }
    return state
}
