package com.dirkraft.macroman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dirkraft.macroman.FoodDraft
import com.dirkraft.macroman.WorkoutDraft
import kotlin.math.roundToInt

/**
 * Review step between the AI guess and the log. For a fresh guess Save is one tap; every number
 * stays editable. Opening an existing entry reuses the same sheet with a Delete button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodConfirmSheet(
    initial: List<FoodDraft>,
    onSave: (List<FoodDraft>) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = remember(initial) { mutableStateListOf(*initial.toTypedArray()) }
    val editing = initial.singleOrNull()?.id?.takeIf { it != 0L }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            val total = { f: (FoodDraft) -> String -> items.sumOf { f(it).toDoubleOrNull() ?: 0.0 }.roundToInt() }
            Text(
                if (editing != null) "Edit entry" else "Confirm",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "${total { it.calories }} kcal · P ${total { it.protein }} · C ${total { it.carbs }} · F ${total { it.fat }}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(items) { i, d ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Field("Food", d.name, Modifier.weight(1f)) { items[i] = d.copy(name = it) }
                                if (items.size > 1) {
                                    IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Default.Close, "Remove") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Field("Amount", d.quantity, Modifier.weight(3f)) { items[i] = d.copy(quantity = it) }
                                NumField("Weighed g", d.weighed, Modifier.weight(1.4f)) { items[i] = d.copy(weighed = it) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NumField("kcal", d.calories, Modifier.weight(1.2f)) { items[i] = d.copy(calories = it) }
                                NumField("P", d.protein, Modifier.weight(1f), MacroColors.protein) { items[i] = d.copy(protein = it) }
                                NumField("C", d.carbs, Modifier.weight(1f), MacroColors.carbs) { items[i] = d.copy(carbs = it) }
                                NumField("F", d.fat, Modifier.weight(1f), MacroColors.fat) { items[i] = d.copy(fat = it) }
                            }
                        }
                    }
                }
            }
            SheetButtons(
                saveLabel = if (editing != null) "Save" else "Log ${items.size} item${if (items.size == 1) "" else "s"}",
                saveEnabled = items.isNotEmpty(),
                onSave = { onSave(items.toList()) },
                onDelete = editing?.let { id -> { onDelete(id) } },
                onDismiss = onDismiss,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutConfirmSheet(
    initial: List<WorkoutDraft>,
    onSave: (List<WorkoutDraft>) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = remember(initial) { mutableStateListOf(*initial.toTypedArray()) }
    val editing = initial.singleOrNull()?.id?.takeIf { it != 0L }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(if (editing != null) "Edit exercise" else "Confirm", style = MaterialTheme.typography.titleLarge)
            LazyColumn(
                Modifier.weight(1f, fill = false).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items) { i, d ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Field("Exercise", d.exercise, Modifier.weight(1f)) { items[i] = d.copy(exercise = it) }
                                if (items.size > 1) {
                                    IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Default.Close, "Remove") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NumField("Sets", d.sets, Modifier.weight(1f)) { items[i] = d.copy(sets = it) }
                                NumField("Reps", d.reps, Modifier.weight(1f)) { items[i] = d.copy(reps = it) }
                                NumField("Weight", d.weight, Modifier.weight(1.3f)) { items[i] = d.copy(weight = it) }
                                Field("Unit", d.unit, Modifier.weight(1f)) { items[i] = d.copy(unit = it) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NumField("Minutes", d.durationMin, Modifier.weight(1f)) { items[i] = d.copy(durationMin = it) }
                                Field("Notes", d.notes, Modifier.weight(2.5f)) { items[i] = d.copy(notes = it) }
                            }
                        }
                    }
                }
            }
            SheetButtons(
                saveLabel = if (editing != null) "Save" else "Log ${items.size} exercise${if (items.size == 1) "" else "s"}",
                saveEnabled = items.isNotEmpty(),
                onSave = { onSave(items.toList()) },
                onDelete = editing?.let { id -> { onDelete(id) } },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun SheetButtons(
    saveLabel: String,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.weight(1f)) {}
        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        Button(onClick = onSave, enabled = saveEnabled) { Text(saveLabel, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = true)
}

@Composable
private fun NumField(
    label: String,
    value: String,
    modifier: Modifier,
    color: Color = Color.Unspecified,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.all { it.isDigit() || it == '.' }) onChange(v) },
        modifier = modifier,
        label = { Text(label, color = color) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
