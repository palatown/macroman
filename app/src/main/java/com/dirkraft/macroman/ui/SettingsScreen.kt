package com.dirkraft.macroman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.dirkraft.macroman.data.Settings
import com.dirkraft.macroman.data.Targets

@Composable
fun SettingsScreen(settings: Settings, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = settings.apiKey,
            onValueChange = { settings.apiKey = it.trim() },
            label = { Text("Anthropic API key") },
            supportingText = { Text("Stored only on this phone. Get one at console.anthropic.com.") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.model,
            onValueChange = { settings.model = it.trim() },
            label = { Text("Model") },
            supportingText = { Text("Default: ${Settings.DEFAULT_MODEL}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        TargetsEditor("Training day targets", settings.trainingTargets) { settings.trainingTargets = it }
        TargetsEditor("Rest day targets", settings.restTargets) { settings.restTargets = it }
        Text(
            "A day counts as a training day as soon as any workout is logged on it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
