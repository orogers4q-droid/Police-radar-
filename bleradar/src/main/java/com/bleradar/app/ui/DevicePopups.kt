package com.bleradar.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bleradar.app.PopupMode
import com.bleradar.app.detect.Group
import com.bleradar.app.detect.Proximity
import com.bleradar.app.detect.Signal
import com.bleradar.app.detect.Source
import com.bleradar.app.detect.Threat
import com.bleradar.app.scan.Scanner
import com.bleradar.app.scan.Tracked
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A newly found device to announce, plus how many others turned up at the same time. */
class PopupContent(val device: Tracked, val others: Int)

private fun Tracked.isSuspicious() = classification.kind.threat.let { it == Threat.HIGH || it == Threat.MEDIUM }

/**
 * Announces each newly found device in turn. When many turn up at once (walking into a busy
 * place), it shows the most interesting one plus "and N more" instead of a long queue.
 */
@Composable
fun rememberNewDevicePopup(mode: PopupMode): State<PopupContent?> {
    val state = remember { mutableStateOf<PopupContent?>(null) }
    LaunchedEffect(mode) {
        state.value = null
        if (mode == PopupMode.OFF) return@LaunchedEffect
        val queue = Channel<Tracked>(Channel.UNLIMITED)
        launch {
            Scanner.newDevices.collect { d ->
                if (mode == PopupMode.ALL || d.isSuspicious()) queue.trySend(d)
            }
        }
        while (true) {
            val batch = mutableListOf(queue.receive())
            while (true) batch += queue.tryReceive().getOrNull() ?: break
            val top = batch.minWith(
                compareBy<Tracked> { it.classification.kind.threat.ordinal }.thenByDescending { it.latest.rssi },
            )
            state.value = PopupContent(top, batch.size - 1)
            delay(if (top.isSuspicious()) 4_000 else 2_500)
            state.value = null
            delay(300)
        }
    }
    return state
}

/** Banner that slides in at the top: "NEW · AirPods Pro · Close". Tap it for details. */
@Composable
fun NewDevicePopup(popup: State<PopupContent?>, now: Long, modifier: Modifier, onClick: (Tracked) -> Unit) {
    val current = popup.value
    var last by remember { mutableStateOf<PopupContent?>(null) }
    if (current != null) last = current
    AnimatedVisibility(
        visible = current != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val p = last ?: return@AnimatedVisibility
        val d = p.device
        val threat = d.classification.kind.threat
        val accent = if (threat == Threat.NONE) RadarCyan else threatColor(threat)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2B47)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth().clickable { onClick(d) },
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (d.latest.source == Source.WIFI) "📶" else d.identity.emoji, fontSize = 30.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (d.classification.kind.group == Group.OTHER) "NEW DEVICE FOUND" else "NEW ${d.classification.kind.label.uppercase()}",
                        color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(d.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val what = if (d.title == d.identity.what) d.identity.maker else d.identity.line
                    if (what != null) {
                        Text(what, style = MaterialTheme.typography.bodySmall, color = RadarCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "${Proximity.fromRssi(d.latest.rssi).label} · " +
                            Signal.formatMeters(Signal.estimateMeters(d.latest.rssi.toDouble(), d.latest.txPower)) +
                            if (p.others > 0) " · and ${p.others} more new" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Everything known about one device. */
@Composable
fun DeviceDetailsDialog(d: Tracked, now: Long, onDismiss: () -> Unit, onTrack: () -> Unit) {
    val s = d.latest
    val kind = d.classification.kind
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text(if (s.source == Source.WIFI) "📶" else d.identity.emoji, fontSize = 36.sp) },
        title = { Text(d.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (kind.threat != Threat.NONE) {
                    Text(kind.label.uppercase(), color = threatColor(kind.threat), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Detail("What it is", d.identity.what)
                d.identity.maker?.let { Detail("Made by", it) }
                Detail("Name it broadcasts", d.knownName ?: "None (many devices don't send one)")
                d.deviceInfo?.let { info ->
                    info.name?.let { Detail("Says its name is", it) }
                    info.manufacturer?.let { Detail("Says it's made by", it) }
                    info.model?.let { Detail("Model", it) }
                }
                if (kind.threat != Threat.NONE) Detail("Why it's flagged", d.classification.reason)
                Detail(
                    "Signal",
                    "${Proximity.fromRssi(s.rssi).label} · ${s.rssi} dBm · " +
                        Signal.formatMeters(Signal.estimateMeters(s.rssi.toDouble(), s.txPower)),
                )
                Detail("In range for", formatDuration(now - d.firstSeenMs))
                Detail("Address", s.address.uppercase(), mono = true)
                if (d.deviceInfo == null && s.source == Source.BLE) {
                    Text(
                        "Not sure what it is? Track it and turn on ⚡ Fast mode: the app will ask the device its name and model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onTrack) { Text("Track it down") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Detail(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

fun formatDuration(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60} min"
        else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    }
}
