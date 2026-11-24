package com.dirkraft.macroman.ui

import com.dirkraft.macroman.data.FoodSearch
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.produceState
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Search
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
    val frequent by vm.suggestedFood.collectAsStateWithLifecycle()
    val waterMl by vm.waterMl.collectAsStateWithLifecycle()
    var text by rememberInputText()
    var viewing by remember { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    BackHandler(searching) { searching = false }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val photoUri = androidx.compose.runtime.remember {
        val file = File(context.cacheDir, "photos/capture.jpg").apply { parentFile?.mkdirs() }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    // Taking the picture sends it, along with any text already entered. It stays attached until
    // the analysis succeeds, so a failed attempt can be retried without retaking the photo.
    var photoAttached by rememberSaveable { mutableStateOf(false) }
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            photoAttached = true
            thumbnail = null
            vm.analyzeFood(text, photoUri)
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
        DayBar(day, vm::shiftDay, vm::today)
        MacroSummary(food, vm.settings.targets, waterMl, vm::addWater)
        val relog = { entry: FoodEntry ->
            scope.launch {
                val added = vm.relogFood(entry)
                val result = snackbar.showSnackbar(
                    "Logged ${entry.name}", actionLabel = "Undo", duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) vm.undoFood(added)
            }
            Unit
        }
        if (searching) {
            HistorySearch(vm, onPick = { relog(it); searching = false }, onClose = { searching = false })
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "search") {
                    SuggestionChip(
                        onClick = { searching = true },
                        label = { Icon(Icons.Default.Search, "Search past food", Modifier.size(18.dp)) },
                    )
                }
                items(frequent, key = { it.id }) { entry ->
                    SuggestionChip(
                        onClick = { relog(entry) },
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
                FoodRow(entry, entry.photo?.let(vm.photos::file), onPhoto = { viewing = entry.photo }) { vm.editFood(entry) }
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
            onDismiss = vm::dismissFood,
            onRescaleAmount = vm::rescaleAmount,
            onDuplicate = vm::duplicateFood,
        )
    }
    viewing?.let { name -> PhotoViewer(vm.photos.file(name)) { viewing = null } }
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
private fun MacroSummary(food: List<FoodEntry>, t: Targets, waterMl: Int, onWater: (Int) -> Unit) {
    val p = food.sumOf { it.protein }
    val c = food.sumOf { it.carbs }
    val f = food.sumOf { it.fat }
    val kcal = food.sumOf { it.calories }
    Row(
        Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MacroBar("Calories", kcal, t.calories, "", MaterialTheme.colorScheme.primary)
            MacroBar("Protein", p, t.protein, "g", MacroColors.protein)
            MacroBar("Carbs", c, t.carbs, "g", MacroColors.carbs, fiber = food.sumOf { it.fiber }, fiberTarget = t.fiber)
            MacroBar("Fat", f, t.fat, "g", MacroColors.fat)
        }
        WaterLogger(waterMl, onWater)
    }
    Spacer(Modifier.height(4.dp))
}

private const val WATER_STEP_ML = 500

@Composable
private fun WaterLogger(ml: Int, onChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Default.WaterDrop, null, Modifier.size(22.dp), tint = MacroColors.carbs)
        Text(
            "%.1f L".format(ml / 1000.0),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(
                onClick = { onChange(-WATER_STEP_ML) },
                enabled = ml > 0,
                modifier = Modifier.size(40.dp),
            ) { Icon(Icons.Default.Remove, "Remove 0.5 litres of water") }
            FilledTonalIconButton(
                onClick = { onChange(WATER_STEP_ML) },
                modifier = Modifier.size(40.dp),
            ) { Icon(Icons.Default.Add, "Add 0.5 litres of water") }
        }
    }
}

/** A search field over everything logged before, with matches as one-tap re-log chips underneath. */
@Composable
private fun HistorySearch(vm: MainViewModel, onPick: (FoodEntry) -> Unit, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val history by produceState<List<FoodSearch.Food>?>(null) { value = vm.foodHistory() }
    val matches = remember(history, query) { history?.let { FoodSearch.search(it, query) } }
    // A lazy row keeps its scroll anchored to an item across changes; new results should start at the left.
    val rowState = rememberLazyListState()
    LaunchedEffect(matches) { rowState.scrollToItem(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search past food") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close search") } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focus),
        )
        when {
            matches == null -> {}
            matches.isEmpty() -> Text(
                if (history!!.isEmpty()) "Nothing logged yet." else "No matches.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(matches, key = { it.entry.id }) { match ->
                    val entry = match.entry
                    SuggestionChip(
                        onClick = { onPick(entry) },
                        label = {
                            // When the amount is what matched, show it so similar logs can be told apart.
                            Text(
                                if (match.byAmount && entry.quantity.isNotBlank()) "${entry.name} · ${entry.quantity}" else entry.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 280.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

object MacroColors {
    val protein = Color(0xFFE57373)
    val carbs = Color(0xFF64B5F6)
    val fat = Color(0xFFFFB74D)
    /** A darker carbs blue, because fiber is drawn as a portion of the carbs bar. */
    val fiber = Color(0xFF1565C0)
}

@Composable
private fun MacroBar(
    label: String, value: Double, target: Int, unit: String, color: Color,
    fiber: Double? = null, fiberTarget: Int = 0,
) {
    val left = target - value.roundToInt()
    val share = { amount: Double -> if (target > 0) (amount / target).toFloat().coerceIn(0f, 1f) else 0f }
    val tickColor = MaterialTheme.colorScheme.onSurface
    val tickAt = if (fiber != null && fiberTarget > 0 && target > 0) share(fiberTarget.toDouble()) else null
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                when {
                    fiber == null -> label
                    fiberTarget > 0 -> "$label · ${fiber.roundToInt()}/$fiberTarget fiber"
                    else -> "$label · ${fiber.roundToInt()} fiber"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        // The tick marks the fiber target, measured along the carbs bar. It runs a little past the bar's edges
        // so it still shows once the bar fills past it.
        Box(
            Modifier.drawWithContent {
                drawContent()
                if (tickAt != null) {
                    val x = (size.width * tickAt).coerceIn(1.dp.toPx(), size.width - 1.dp.toPx())
                    drawLine(tickColor, Offset(x, 0f), Offset(x, size.height + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
            }
        ) {
            LinearProgressIndicator(
                progress = { share(value) },
                modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 2.dp),
                color = color,
                trackColor = color.copy(alpha = 0.2f),
                drawStopIndicator = {},
            )
            // Fiber is part of the carbs, so it's drawn inside that bar rather than added to it.
            if (fiber != null && fiber > 0) {
                LinearProgressIndicator(
                    progress = { share(minOf(fiber, value)) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 2.dp),
                    color = MacroColors.fiber,
                    trackColor = Color.Transparent,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun FoodRow(entry: FoodEntry, photo: File?, onPhoto: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (photo != null) PhotoThumb(photo, 44.dp, onPhoto)
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
                Text(
                    "C ${entry.carbs.roundToInt()}" + if (entry.fiber > 0) " (${entry.fiber.roundToInt()} fiber)" else "",
                    color = MacroColors.carbs,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text("F ${entry.fat.roundToInt()}", color = MacroColors.fat, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
