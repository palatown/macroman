package com.dirkraft.macroman.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dirkraft.macroman.ai.Provider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPicker(selected: Provider, onSelect: (Provider) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = selected.label + if (selected.tested) "" else " (untested)",
            onValueChange = {},
            readOnly = true,
            label = { Text("AI provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Provider.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label + if (p.tested) "" else " (untested)") },
                    onClick = { onSelect(p); open = false },
                )
            }
        }
    }
}

/** Free text, with the provider's model list (filtered by what's typed) one tap away when it could be fetched. */
@Composable
fun ModelField(label: String, value: String, models: List<String>, hint: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val matches = models.filter { value.isBlank() || it.contains(value, ignoreCase = true) }.take(60)
        .ifEmpty { models.take(60) }
    androidx.compose.foundation.layout.Box {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.trim()) },
            label = { Text(label) },
            supportingText = { Text(hint) },
            trailingIcon = {
                if (models.isNotEmpty()) IconButton(onClick = { open = true }) { Icon(Icons.Default.ArrowDropDown, "Pick a model") }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            matches.forEach { id ->
                DropdownMenuItem(text = { Text(id) }, onClick = { onChange(id); open = false })
            }
        }
    }
}
