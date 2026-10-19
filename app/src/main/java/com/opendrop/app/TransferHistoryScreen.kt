package com.opendrop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TransferHistoryScreen(
    entries: List<TransferHistoryEntry>,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showConfirmation = remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Transfer history", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(
                onClick = { showConfirmation.value = true },
                enabled = entries.isNotEmpty()
            ) {
                Text("Clear")
            }
        }

        if (entries.isEmpty()) {
            Text("No transfers yet. Your recent transfers will appear here.")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                entry.fileName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("${entry.direction} • ${entry.outcome.name}")
                            Text(entry.formattedTimestamp())
                            Text(
                                entry.sizeBytes?.let { formatHistorySize(it) }
                                    ?: "Size unknown",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    if (showConfirmation.value) {
        AlertDialog(
            onDismissRequest = { showConfirmation.value = false },
            title = { Text("Clear transfer history?") },
            text = { Text("This removes saved history entries from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation.value = false
                        onClearHistory()
                    }
                ) {
                    Text("Clear history")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatHistorySize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
