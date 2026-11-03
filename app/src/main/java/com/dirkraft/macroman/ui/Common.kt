package com.dirkraft.macroman.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DayBar(day: LocalDate, onShift: (Long) -> Unit, onToday: () -> Unit, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onShift(-1) }) { Icon(Icons.Default.ChevronLeft, "Previous day") }
        val today = LocalDate.now()
        val label = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        }
        TextButton(onClick = onToday) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = { onShift(1) }, enabled = day < today) {
            Icon(Icons.Default.ChevronRight, "Next day")
        }
        if (trailing != null) {
            Text(
                trailing,
                Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/**
 * The always-visible logging bar. Mic dictates and sends in one go; the camera (if offered)
 * sends the photo together with whatever text is already typed.
 */
@Composable
fun InputBar(
    hint: String,
    busy: Boolean,
    autoFocus: Boolean,
    onSend: (String) -> Unit,
    onCamera: (() -> Unit)? = null,
    text: String,
    onText: (String) -> Unit,
    /** Shown above the text field, e.g. an attached photo. */
    attachment: (@Composable () -> Unit)? = null,
    /** Whether Send is allowed; an attached photo makes an empty message sendable. */
    sendable: Boolean = text.isNotBlank(),
) {
    val focus = remember { FocusRequester() }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            val combined = listOf(text, spoken).filter { it.isNotBlank() }.joinToString(" ")
            onSend(combined)
        }
    }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(tonalElevation = 3.dp) {
        Column {
            if (attachment != null) {
                Box(Modifier.padding(start = 12.dp, top = 8.dp)) { attachment() }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text(hint) },
                    maxLines = 4,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (sendable) onSend(text) }),
                )
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 3.dp)
                } else {
                    // Mic stays available until something is typed, so a photo can still get a spoken note.
                    if (text.isBlank()) {
                        IconButton(onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            runCatching { speech.launch(intent) }
                        }) { Icon(Icons.Default.Mic, "Speak") }
                    }
                    if (onCamera != null) {
                        IconButton(onClick = onCamera) { Icon(Icons.Default.PhotoCamera, "Photo") }
                    }
                    if (sendable) {
                        FilledIconButton(onClick = { onSend(text) }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberInputText() = rememberSaveable { mutableStateOf("") }
