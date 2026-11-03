package com.dirkraft.macroman.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirkraft.macroman.MainViewModel
import com.dirkraft.macroman.data.WorkoutEntry
import kotlinx.coroutines.launch

fun WorkoutEntry.summary(): String = buildList {
    if (sets > 0 && reps > 0) add("$sets × $reps")
    if (weight > 0) add("@ ${if (weight % 1.0 == 0.0) weight.toLong() else weight} $unit".trim())
    if (durationMin > 0) add("${if (durationMin % 1.0 == 0.0) durationMin.toLong() else durationMin} min")
}.joinToString(" ")

@Composable
fun WorkoutScreen(vm: MainViewModel, snackbar: SnackbarHostState, modifier: Modifier) {
    val day by vm.day.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val frequent by vm.frequentWorkouts.collectAsStateWithLifecycle()
    var text by rememberInputText()
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm.workoutDrafts) { if (vm.workoutDrafts != null) text = "" }

    Column(modifier) {
        DayBar(day, vm::shiftDay, vm::today)
        if (frequent.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(frequent, key = { it.id }) { entry ->
                    SuggestionChip(
                        onClick = {
                            scope.launch {
                                val added = vm.relogWorkout(entry)
                                val result = snackbar.showSnackbar(
                                    "Logged ${entry.exercise} ${entry.summary()}",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) vm.undoWorkout(added)
                            }
                        },
                        label = { Text("${entry.exercise} ${entry.summary()}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (workouts.isEmpty()) {
                item {
                    Text(
                        "No training logged. Describe it however you like, e.g. \"bench 3x8 185, then 4 sets of 10 pull-ups, 20 min incline walk\".",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(workouts, key = { it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.editWorkout(entry) }.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.exercise, style = MaterialTheme.typography.bodyLarge)
                        if (entry.notes.isNotBlank()) {
                            Text(entry.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(entry.summary(), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
        InputBar(
            hint = "What did you train?",
            busy = vm.busy,
            autoFocus = false,
            onSend = { vm.analyzeWorkout(it) },
            text = text,
            onText = { text = it },
        )
    }

    vm.workoutDrafts?.let { drafts ->
        WorkoutConfirmSheet(
            initial = drafts,
            onSave = vm::saveWorkouts,
            onDelete = { id -> vm.deleteWorkout(id); vm.workoutDrafts = null },
            onDismiss = { vm.workoutDrafts = null },
        )
    }
}
