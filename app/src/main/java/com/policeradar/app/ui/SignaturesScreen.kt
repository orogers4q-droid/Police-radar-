package com.policeradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.policeradar.app.detect.MatchType
import com.policeradar.app.detect.Signature

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignaturesScreen(
    signatures: List<Signature>,
    onToggle: (id: String, enabled: Boolean) -> Unit,
    onAdd: (label: String, type: MatchType, patterns: List<String>) -> Unit,
    onDelete: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What it looks for") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add your own")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Each entry describes radio signals that police equipment gives off. Turn off any that " +
                        "give you false alarms, or add your own. The easiest way to add one is to tap a device " +
                        "under \"Show all nearby signals\" on the main screen when you're next to a patrol car.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(signatures, key = { it.id }) { sig ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sig.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${sig.matchType.label}: ${sig.patterns.joinToString(", ")}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Text(sig.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!sig.builtIn) {
                            IconButton(onClick = { onDelete(sig.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete ${sig.label}")
                            }
                        }
                        Switch(checked = sig.enabled, onCheckedChange = { onToggle(sig.id, it) })
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (adding) {
        AddSignatureDialog(
            onDismiss = { adding = false },
            onSave = { label, type, patterns ->
                onAdd(label, type, patterns)
                adding = false
            },
        )
    }
}

@Composable
private fun AddSignatureDialog(onDismiss: () -> Unit, onSave: (String, MatchType, List<String>) -> Unit) {
    var type by remember { mutableStateOf(MatchType.WIFI_NAME) }
    var patterns by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val parsed = patterns.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add your own") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MatchType.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = type == t, onClick = { type = t }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.label)
                    }
                }
                OutlinedTextField(
                    value = patterns,
                    onValueChange = { patterns = it },
                    label = { Text("Match (separate several with commas)") },
                    placeholder = { Text(type.hint) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Call it") },
                    placeholder = { Text("e.g. County sheriff car") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed.isNotEmpty(), onClick = { onSave(label, type, parsed) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
