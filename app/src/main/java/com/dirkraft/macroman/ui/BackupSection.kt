package com.dirkraft.macroman.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirkraft.macroman.data.Backup
import com.dirkraft.macroman.data.BackupFile
import com.dirkraft.macroman.data.Settings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupSection(backup: Backup, settings: Settings) {
    val scope = rememberCoroutineScope()
    val target = settings.backupUri
    val last = settings.backupLast
    val fileName by produceState<String?>(null, target) { value = backup.fileName() }
    // The backup picked for restoring, and the file it came from.
    var pending by remember { mutableStateOf<Pair<BackupFile, android.net.Uri>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // A new file, made through the system save dialog, where Drive shows up as a location.
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { backup.choose(uri) }.onFailure { message = "Couldn't use that file." }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                pending = backup.read(uri) to uri
            } catch (e: Exception) {
                message = e.message
            }
        }
    }

    Text("Backup", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Keeps a copy of your log and settings in a file you pick, for example in Google Drive. It's updated a few " +
            "seconds after each change. API keys and photos aren't included.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (target.isNotBlank()) {
        val status = when {
            backup.error != null -> backup.error!!
            last > 0 -> "Last saved ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))}"
            else -> "Not saved yet"
        }
        Text(
            "Backup file: ${fileName ?: "chosen"}\n$status",
            style = MaterialTheme.typography.bodyMedium,
            color = if (backup.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { create.launch("macroman-backup.json") }) {
            Text(if (target.isBlank()) "Back up to a file…" else "Change file…")
        }
        if (target.isNotBlank()) TextButton(onClick = { backup.stop() }) { Text("Stop") }
    }
    TextButton(onClick = { pickRestore.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) {
        Text("Restore from a backup…")
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    pending?.let { (file, source) ->
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.exportedAt))
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Saved $date, with ${file.food.size} food entries, ${file.workouts.size} exercises and " +
                        "${file.water.size} days of water.\n\nThis replaces everything logged on this phone, and " +
                        "your settings. API keys stay as they are. Backups then keep going to this file."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        message = try {
                            backup.restore(file)
                            // Carry on backing up to the file that was restored from. Some storage apps only allow
                            // reading a file opened this way; then the user picks a backup file as usual.
                            if (runCatching { backup.choose(source) }.isSuccess) null
                            else "Restored. Choose a backup file to keep backing up."
                        } catch (e: Exception) {
                            "Restore failed; nothing was changed."
                        }
                    }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
