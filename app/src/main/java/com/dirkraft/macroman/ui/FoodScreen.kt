package com.dirkraft.macroman.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirkraft.macroman.MainViewModel
import com.dirkraft.macroman.data.FoodEntry
import com.dirkraft.macroman.data.Targets
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
fun FoodScreen(vm: MainViewModel, snackbar: SnackbarHostState, modifier: Modifier) {
    val day by vm.day.collectAsStateWithLifecycle()
    val food by vm.food.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val frequent by vm.frequentFood.collectAsStateWithLifecycle()
    var text by rememberInputText()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Any workout logged on the day makes it a training day: no extra toggle to remember.
    val training = workouts.isNotEmpty()
    val targets = if (training) vm.settings.trainingTargets else vm.settings.restTargets

    val photoUri = androidx.compose.runtime.remember {
        val file = File(context.cacheDir, "photos/capture.jpg").apply { parentFile?.mkdirs() }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    // The photo is attached rather than sent, so a note can be typed or spoken to go with it.
    var photoAttached by rememberSaveable { mutableStateOf(false) }
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            photoAttached = true
            thumbnail = null
        }
    }
    LaunchedEffect(photoAttached, thumbnail) {
        if (photoAttached && thumbnail == null) {
            thumbnail = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(photoUri, Size(256, 256), null).asImageBitmap()
                }.getOrNull()
            }
        }
    }

    // A successful analysis opens the confirm sheet; the text and photo have done their job.
    LaunchedEffect(vm.foodDrafts) {
        if (vm.foodDrafts != null) {
            text = ""
            photoAttached = false
            thumbnail = null
        }
    }

    Column(modifier) {
        DayBar(day, vm::shiftDay, vm::today, trailing = if (training) "Training day" else "Rest day")
        MacroSummary(food, targets)
        if (frequent.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(frequent, key = { it.id }) { entry ->
                    SuggestionChip(
                        onClick = {
                            scope.launch {
                                val added = vm.relogFood(entry)
                                val result = snackbar.showSnackbar(
                                    "Logged ${entry.name}", actionLabel = "Undo", duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) vm.undoFood(added)
                            }
                        },
                        label = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (food.isEmpty()) {
                item {
                    Text(
                        "Nothing logged yet. Type, speak, or snap a photo of what you ate.",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(food, key = { it.id }) { entry ->
                FoodRow(entry) { vm.editFood(entry) }
                HorizontalDivider()
            }
        }
        InputBar(
            hint = "What did you eat?",
            busy = vm.busy,
            autoFocus = true,
            onSend = { vm.analyzeFood(it, if (photoAttached) photoUri else null) },
            onCamera = if (photoAttached) null else ({ camera.launch(photoUri) }),
            text = text,
            onText = { text = it },
            attachment = if (!photoAttached) null else ({
                PhotoChip(thumbnail, enabled = !vm.busy) {
                    photoAttached = false
                    thumbnail = null
                }
            }),
            sendable = photoAttached || text.isNotBlank(),
        )
    }

    vm.foodDrafts?.let { drafts ->
        FoodConfirmSheet(
            initial = drafts,
            onSave = vm::saveFood,
            onDelete = { id -> vm.deleteFood(id); vm.foodDrafts = null },
            onDismiss = { vm.foodDrafts = null },
        )
    }
}

@Composable
private fun PhotoChip(thumbnail: ImageBitmap?, enabled: Boolean, onRemove: () -> Unit) {
    Box {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(thumbnail, "Attached photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (enabled) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, "Remove photo", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

@Composable
private fun MacroSummary(food: List<FoodEntry>, t: Targets) {
    val p = food.sumOf { it.protein }
    val c = food.sumOf { it.carbs }
    val f = food.sumOf { it.fat }
    val kcal = food.sumOf { it.calories }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MacroBar("Protein", p, t.protein, "g", MacroColors.protein)
        MacroBar("Carbs", c, t.carbs, "g", MacroColors.carbs)
        MacroBar("Fat", f, t.fat, "g", MacroColors.fat)
        MacroBar("Calories", kcal, t.calories, "", MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(4.dp))
}

object MacroColors {
    val protein = Color(0xFFE57373)
    val carbs = Color(0xFF64B5F6)
    val fat = Color(0xFFFFB74D)
}

@Composable
private fun MacroBar(label: String, value: Double, target: Int, unit: String, color: Color) {
    val left = target - value.roundToInt()
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "${value.roundToInt()} / $target$unit",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (left >= 0) "  $left$unit left" else "  ${-left}$unit over",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (target > 0) (value / target).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 2.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun FoodRow(entry: FoodEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                entry.quantity,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${entry.calories.roundToInt()} kcal", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("P ${entry.protein.roundToInt()}", color = MacroColors.protein, style = MaterialTheme.typography.labelMedium)
                Text("C ${entry.carbs.roundToInt()}", color = MacroColors.carbs, style = MaterialTheme.typography.labelMedium)
                Text("F ${entry.fat.roundToInt()}", color = MacroColors.fat, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
