package com.dirkraft.macroman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Straighten
import com.dirkraft.macroman.MainViewModel
import com.dirkraft.macroman.data.Settings
import com.dirkraft.macroman.data.Targets

private const val CONSOLE_KEYS_URL = "https://console.anthropic.com/settings/keys"
private const val CONSOLE_BILLING_URL = "https://platform.claude.com/settings/billing"

private sealed interface KeyStatus {
    data object Empty : KeyStatus
    data object Checking : KeyStatus
    data class Ok(val model: String) : KeyStatus
    data class Failed(val message: String) : KeyStatus
}

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier) {
    val settings = vm.settings
    var status by remember { mutableStateOf<KeyStatus>(KeyStatus.Empty) }
    val pickHandPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.measureHand(uri)
    }

    // Re-check whenever the key or model changes, after typing/pasting settles.
    LaunchedEffect(settings.apiKey, settings.model) {
        if (settings.apiKey.isBlank()) {
            status = KeyStatus.Empty
            return@LaunchedEffect
        }
        delay(600)
        status = KeyStatus.Checking
        status = try {
            KeyStatus.Ok(vm.checkKey())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeyStatus.Failed(e.message ?: "Check failed")
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        val good = Color(0xFF2E7D32)
        OutlinedTextField(
            value = settings.apiKey,
            onValueChange = { settings.apiKey = it.trim() },
            label = { Text("Anthropic API key") },
            supportingText = {
                when (val s = status) {
                    KeyStatus.Empty -> Text("Stored only on this phone.")
                    KeyStatus.Checking -> Text("Checking key…")
                    is KeyStatus.Ok -> Text("Key works · ${s.model}", color = good)
                    is KeyStatus.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                }
            },
            trailingIcon = {
                when (status) {
                    KeyStatus.Empty -> {}
                    KeyStatus.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    is KeyStatus.Ok -> Icon(Icons.Default.CheckCircle, "Key works", tint = good)
                    is KeyStatus.Failed -> Icon(Icons.Default.Error, "Key problem", tint = MaterialTheme.colorScheme.error)
                }
            },
            isError = status is KeyStatus.Failed,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val uriHandler = LocalUriHandler.current
        OutlinedButton(onClick = { uriHandler.openUri(CONSOLE_KEYS_URL) }) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
            Text("Get an API key", Modifier.padding(start = 8.dp))
        }
        // A normal API key can't read its credit balance, so this opens the page that shows it.
        OutlinedButton(onClick = { uriHandler.openUri(CONSOLE_BILLING_URL) }) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
            Text("Check credits and usage", Modifier.padding(start = 8.dp))
        }
        OutlinedTextField(
            value = settings.model,
            onValueChange = { settings.model = it.trim() },
            label = { Text("Model") },
            supportingText = { Text("Default: ${Settings.DEFAULT_MODEL}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Hand for scale", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Photograph your hand flat next to a tape measure, then pick that photo here. " +
                "After that, keep your hand in food photos so Claude can judge portion sizes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                pickHandPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = !vm.busy,
        ) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Straighten, null, Modifier.size(18.dp))
            Text(if (vm.busy) "Measuring…" else "Measure from photo", Modifier.padding(start = 8.dp))
        }
        OutlinedTextField(
            value = settings.handReference,
            onValueChange = { settings.handReference = it },
            label = { Text("Hand measurements") },
            placeholder = { Text("e.g. Index fingertip to thumb tip spread: 15 cm (5.9 in)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        TargetsEditor("Daily targets", settings.targets) { settings.targets = it }
    }
}

@Composable
private fun TargetsEditor(title: String, t: Targets, onChange: (Targets) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IntField("kcal", t.calories, Modifier.weight(1.2f)) { onChange(t.copy(calories = it)) }
        IntField("Protein g", t.protein, Modifier.weight(1f)) { onChange(t.copy(protein = it)) }
        IntField("Carbs g", t.carbs, Modifier.weight(1f)) { onChange(t.copy(carbs = it)) }
        IntField("Fat g", t.fat, Modifier.weight(1f)) { onChange(t.copy(fat = it)) }
    }
}

@Composable
private fun IntField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { v -> if (v.all(Char::isDigit) && v.length <= 5) onChange(v.toIntOrNull() ?: 0) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
