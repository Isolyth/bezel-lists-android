package com.example.bezellists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Connection settings: the iroh endpoint id and capability token. */
@Composable
fun SettingsScreen(
    server: String,
    token: String,
    status: String,
    connecting: Boolean,
    onConnect: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var serverField by remember { mutableStateOf(server) }
    var tokenField by remember { mutableStateOf(token) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text("Settings", style = MaterialTheme.typography.titleMedium)
        }

        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Connection", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(serverField, { serverField = it }, Modifier.fillMaxWidth(),
                label = { Text("iroh endpoint id") }, singleLine = true)
            OutlinedTextField(tokenField, { tokenField = it }, Modifier.fillMaxWidth(),
                label = { Text("capability token (bz1.…)") }, singleLine = true)
            Button(
                onClick = { onConnect(serverField.trim(), tokenField.trim()) },
                enabled = !connecting && serverField.isNotBlank() && tokenField.isNotBlank(),
            ) { Text(if (connecting) "dialing…" else "Connect") }
            Text(status, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
