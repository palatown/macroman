package com.dirkraft.macroman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 2f
private const val SCALE_STEP = 0.25f

/**
 * One row of the confirm sheet plus its portion slider. Numbers follow the slider from [base] (the 1x values),
 * so dragging back and forth never drifts. The amount text needs Claude to rewrite, so it only catches up on
 * release: [amount] is the last good text and [amountScale] the slider position it was written for.
 */
private data class ScaledFood(
    val key: Int,
    val shown: FoodDraft,
    val base: List<Double>,
    val scale: Float = 1f,
    val amount: String = shown.quantity,
    val amountScale: Float = 1f,
) {
    /** What the Amount field shows: the good text, marked with the pending factor until the rewrite lands. */
    val amountShown: String
        get() = if (scale == amountScale) amount else "$amount ×${fmtScale(scale / amountScale)}"

    fun draft() = shown.copy(quantity = amountShown)

    fun scaledTo(s: Float): ScaledFood {
        val v = base.map { it * s }
        return copy(
            scale = s,
            shown = shown.copy(
                calories = fmtNum(v[0], 0), protein = fmtNum(v[1]), carbs = fmtNum(v[2]),
                fat = fmtNum(v[3]), fiber = fmtNum(v[4]),
            ),
        )
    }

    /** A typed number stays as typed; its 1x base is back-computed so the slider continues from it. */
    fun typed(field: Int, text: String, set: (FoodDraft, String) -> FoodDraft) = copy(
        shown = set(shown, text),
        base = base.toMutableList().also { it[field] = (text.toDoubleOrNull() ?: 0.0) / scale },
    )

    companion object {
        fun of(key: Int, d: FoodDraft) = ScaledFood(
            key, d, listOf(d.calories, d.protein, d.carbs, d.fat, d.fiber).map { it.toDoubleOrNull() ?: 0.0 },
        )
    }
}

private fun fmtNum(v: Double, decimals: Int = 1): String {
    val r = if (decimals == 0) Math.round(v).toDouble() else Math.round(v * 10) / 10.0
    return if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
}

/** Two decimals so factors like 0.75 or 1.25 aren't blurred; whole factors drop the ".0". */
private fun fmtScale(s: Float): String {
    val r = Math.round(s * 100) / 100.0
    return if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodConfirmSheet(
    initial: List<FoodDraft>,
    onSave: (List<FoodDraft>) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
    onRescaleAmount: suspend (String, Double) -> String,
    onDuplicate: (FoodDraft) -> Unit,
) {
    val rows = remember(initial) { mutableStateListOf(*initial.mapIndexed(ScaledFood::of).toTypedArray()) }
    val items = rows.map { it.draft() }
    val scope = rememberCoroutineScope()
    val editing = initial.singleOrNull()?.id?.takeIf { it != 0L }
    fun update(key: Int, f: (ScaledFood) -> ScaledFood) {
        val i = rows.indexOfFirst { it.key == key }
        if (i >= 0) rows[i] = f(rows[i])
    }
    val rewrites = remember { mutableMapOf<Int, Job>() }
    var waiting by remember { mutableStateOf(false) }
    fun ScaledFood.pending() = scale != amountScale && amount.isNotBlank()
    fun rewriteAmount(key: Int) {
        val row = rows.firstOrNull { it.key == key } ?: return
        if (!row.pending()) return
        val target = row.scale
        rewrites[key] = scope.launch {
            // On failure the field keeps "amount ×factor", which is still accurate and is what gets saved.
            val text = runCatching { onRescaleAmount(row.amount, (target / row.amountScale).toDouble()) }.getOrNull()
                ?: return@launch
            // Drop the result if the slider moved again meanwhile; that release starts its own rewrite.
            update(key) { if (it.scale == target && it.amount == row.amount) it.copy(amount = text, amountScale = target) else it }
        }
    }
    /**
     * Save and Duplicate wait for amount rewrites still in flight, so a quick tap after moving the slider
     * doesn't store the "×factor" placeholder. Bounded, so a slow call can't trap the sheet; past that,
     * or if a rewrite fails, the placeholder is saved, which is still accurate.
     */
    fun afterRewrites(action: (List<FoodDraft>) -> Unit) {
        if (waiting) return
        rows.filter { it.pending() && rewrites[it.key]?.isActive != true }.forEach { rewriteAmount(it.key) }
        val inFlight = rewrites.values.filter { it.isActive }
        if (inFlight.isEmpty()) return action(rows.map { it.draft() })
        waiting = true
        scope.launch {
            withTimeoutOrNull(10_000) { inFlight.joinAll() }
            waiting = false
            action(rows.map { it.draft() })
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            val total = { f: (FoodDraft) -> String -> items.sumOf { f(it).toDoubleOrNull() ?: 0.0 }.roundToInt() }
            Text(
                if (editing != null) "Edit entry" else "Confirm",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "${total { it.calories }} kcal · P ${total { it.protein }} · C ${total { it.carbs }} (fiber ${total { it.fiber }}) · F ${total { it.fat }}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.key }) { row ->
                    val d = row.shown
                    val k = row.key
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Field("Food", d.name, Modifier.weight(1f)) { v -> update(k) { it.copy(shown = it.shown.copy(name = v)) } }
                                if (rows.size > 1) {
                                    IconButton(onClick = { rows.removeAll { it.key == k } }) { Icon(Icons.Default.Close, "Remove") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Typed text is taken as written for the current slider position.
                                Field("Amount", row.amountShown, Modifier.weight(3f)) { v ->
                                    update(k) { it.copy(amount = v, amountScale = it.scale) }
                                }
                                NumField("Fiber g", d.fiber, Modifier.weight(1.4f), MacroColors.carbs) { v ->
                                    update(k) { it.typed(4, v) { f, t -> f.copy(fiber = t) } }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NumField("kcal", d.calories, Modifier.weight(1.2f)) { v ->
                                    update(k) { it.typed(0, v) { f, t -> f.copy(calories = t) } }
                                }
                                NumField("P", d.protein, Modifier.weight(1f), MacroColors.protein) { v ->
                                    update(k) { it.typed(1, v) { f, t -> f.copy(protein = t) } }
                                }
                                NumField("C", d.carbs, Modifier.weight(1f), MacroColors.carbs) { v ->
                                    update(k) { it.typed(2, v) { f, t -> f.copy(carbs = t) } }
                                }
                                NumField("F", d.fat, Modifier.weight(1f), MacroColors.fat) { v ->
                                    update(k) { it.typed(3, v) { f, t -> f.copy(fat = t) } }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Slider(
                                    value = row.scale,
                                    // Snap exactly onto the step so returning to 1x compares equal to 1f.
                                    onValueChange = { s ->
                                        val snapped = Math.round(s / SCALE_STEP) * SCALE_STEP
                                        if (snapped != row.scale) update(k) { it.scaledTo(snapped) }
                                    },
                                    onValueChangeFinished = { rewriteAmount(k) },
                                    valueRange = MIN_SCALE..MAX_SCALE,
                                    steps = ((MAX_SCALE - MIN_SCALE) / SCALE_STEP).toInt() - 1,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${fmtScale(row.scale)}×",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(start = 8.dp).width(44.dp),
                                )
                            }
                        }
                    }
                }
            }
            SheetButtons(
                saveLabel = if (editing != null) "Save" else "Log ${items.size} item${if (items.size == 1) "" else "s"}",
                saveEnabled = items.isNotEmpty() && !waiting,
                onSave = { afterRewrites(onSave) },
                onDelete = editing?.let { id -> { onDelete(id) } },
                onDuplicate = editing?.let { { afterRewrites { onDuplicate(it.single()) } } },
                onDismiss = onDismiss,
                busy = waiting,
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
    onDuplicate: (WorkoutDraft) -> Unit,
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
                onDuplicate = editing?.let { { onDuplicate(items.single()) } },
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
    onDuplicate: (() -> Unit)?,
    onDismiss: () -> Unit,
    busy: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        if (onDuplicate != null) {
            TextButton(onClick = onDuplicate) { Text("Duplicate") }
        }
        Row(Modifier.weight(1f)) {}
        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        Button(onClick = onSave, enabled = saveEnabled) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(saveLabel, fontWeight = FontWeight.SemiBold)
        }
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
