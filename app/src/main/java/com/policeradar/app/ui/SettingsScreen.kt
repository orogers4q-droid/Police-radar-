package com.policeradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.policeradar.app.data.AppSettings
import com.policeradar.app.detect.Category
import com.policeradar.app.detect.Proximity
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onChange: (AppSettings) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Alert me for", fontWeight = FontWeight.SemiBold)
                Category.entries.forEach { c ->
                    val on = c in settings.alertCategories
                    Toggle(
                        "${c.emoji} ${c.label}",
                        if (c == Category.RESPONDER) "Equipment shared by several services, so the type is unknown" else null,
                        on,
                    ) { checked ->
                        onChange(settings.copy(alertCategories = if (checked) settings.alertCategories + c else settings.alertCategories - c))
                    }
                }
            }

            Column {
                Text("Alert range", fontWeight = FontWeight.SemiBold)
                Text(
                    "Alert when a signal is at least \"${Proximity.fromRssi(settings.minRssi).label.lowercase()}\" " +
                        "(${settings.minRssi} dBm). Further = earlier warnings but more false alarms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    // Slider runs short (left) to long (right) range.
                    value = (-settings.minRssi).toFloat(),
                    onValueChange = { onChange(settings.copy(minRssi = -(it.roundToInt()))) },
                    valueRange = 55f..100f,
                    steps = 8,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("Short", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("Long", style = MaterialTheme.typography.bodySmall)
                }
            }

            Column {
                Text("Repeat alerts for the same device after ${settings.cooldownMinutes} min", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = settings.cooldownMinutes.toFloat(),
                    onValueChange = { onChange(settings.copy(cooldownMinutes = it.roundToInt())) },
                    valueRange = 1f..30f,
                )
            }

            Toggle("Siren sound", "Plays a short alarm tone", settings.sound) { onChange(settings.copy(sound = it)) }
            Toggle("Vibrate", "Buzzes the phone", settings.vibrate) { onChange(settings.copy(vibrate = it)) }
            Toggle("Spoken alerts", "Reads the alert out loud, e.g. \"Police nearby, close.\"", settings.voice) {
                onChange(settings.copy(voice = it))
            }

            Toggle(
                "Learning mode",
                "Also list ordinary nearby devices so you can tap one and teach the app it's police, fire or an ambulance. " +
                    "Turn it on when parked next to an emergency vehicle.",
                settings.learningMode,
            ) { onChange(settings.copy(learningMode = it)) }

            Text(
                "Tip: many phones pause Bluetooth scanning when the screen is off, so the app keeps the " +
                    "screen on while scanning. Mount your phone and don't use it while driving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
