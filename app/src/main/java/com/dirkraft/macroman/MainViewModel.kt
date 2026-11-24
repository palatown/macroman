package com.dirkraft.macroman

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirkraft.macroman.ai.FoodItem
import com.dirkraft.macroman.ai.WorkoutItem
import com.dirkraft.macroman.data.FoodEntry
import com.dirkraft.macroman.data.FoodSearch
import com.dirkraft.macroman.data.WorkoutEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** An editable food row in the confirm sheet. Strings so text fields can hold partial input like "12.". */
data class FoodDraft(
    val id: Long = 0,
    val name: String,
    val quantity: String,
    val calories: String,
    val protein: String,
    val carbs: String,
    val fat: String,
    val fiber: String,
    /** Picture this was estimated from, shared by every item that came out of it. */
    val photo: String? = null,
)

data class WorkoutDraft(
    val id: Long = 0,
    val exercise: String,
    val sets: String,
    val reps: String,
    val weight: String,
    val unit: String,
    val durationMin: String,
    val notes: String,
)

private fun Double.fmt() = if (this % 1.0 == 0.0) toLong().toString() else "%.1f".format(this)
private fun String.num() = trim().toDoubleOrNull() ?: 0.0

fun FoodItem.toDraft() = FoodDraft(0, name, quantity, calories.fmt(), protein.fmt(), carbs.fmt(), fat.fmt(), fiber.fmt())
fun FoodEntry.toDraft() = FoodDraft(id, name, quantity, calories.fmt(), protein.fmt(), carbs.fmt(), fat.fmt(), fiber.fmt(), photo)
fun WorkoutItem.toDraft() = WorkoutDraft(
    0, exercise, sets.toString(), reps.toString(), weight.fmt(), unit, durationMin.fmt(), notes,
)
fun WorkoutEntry.toDraft() = WorkoutDraft(
    id, exercise, sets.toString(), reps.toString(), weight.fmt(), unit, durationMin.fmt(), notes,
)

@OptIn(ExperimentalCoroutinesApi::class)
private const val SUGGEST_DAYS = 7L
private const val SUGGEST_MAX = 10
/** How quickly a past log stops counting as "around now": weight 0.6 at 1.5 h away, 0.14 at 3 h, near 0 by 5 h. */
private const val SUGGEST_SPREAD_MIN = 90.0

/**
 * Ranks each distinct food (by name) by the sum of its logs' closeness in time of day to [now], so a food eaten
 * around now on several days beats one eaten around now once. Each food is offered as its most recent entry.
 */
internal fun suggestFood(entries: List<FoodEntry>, now: LocalTime): List<FoodEntry> {
    val zone = ZoneId.systemDefault()
    val nowMin = now.toSecondOfDay() / 60.0
    return entries
        .groupBy { it.name.trim().lowercase() }
        .map { (_, logs) ->
            val score = logs.sumOf { e ->
                val t = Instant.ofEpochMilli(e.time).atZone(zone).toLocalTime().toSecondOfDay() / 60.0
                val diff = abs(t - nowMin).let { minOf(it, 1440 - it) } // wraps around midnight
                exp(-0.5 * (diff / SUGGEST_SPREAD_MIN).pow(2))
            }
            logs.maxBy { it.time } to score
        }
        .sortedByDescending { it.second }
        .take(SUGGEST_MAX)
        .map { it.first }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val macroman = app as MacromanApp
    private val dao = macroman.db.dao()
    private val ai = macroman.ai
    val photos = macroman.photos
    val backup = macroman.backup
    val settings = macroman.settings

    val day = MutableStateFlow(LocalDate.now())

    val food: StateFlow<List<FoodEntry>> = day.flatMapLatest { dao.food(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val workouts: StateFlow<List<WorkoutEntry>> = day.flatMapLatest { dao.workouts(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val waterMl: StateFlow<Int> = day.flatMapLatest { dao.waterMl(it.toString()) }
        .map { it ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    /** Time of day the suggestions are ranked for; refreshed each time the app comes to the front. */
    private val clock = MutableStateFlow(LocalTime.now())

    /** The one-tap re-log row: foods from the previous week, ranked by how often they were eaten around this time. */
    val suggestedFood: StateFlow<List<FoodEntry>> = day
        .flatMapLatest { d -> dao.foodBetween(d.minusDays(SUGGEST_DAYS).toString(), d.minusDays(1).toString()) }
        .combine(clock) { entries, now -> suggestFood(entries, now) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val frequentWorkouts = dao.frequentWorkouts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** Non-null while the confirm sheet is open. */
    var foodDrafts by mutableStateOf<List<FoodDraft>?>(null)
    var workoutDrafts by mutableStateOf<List<WorkoutDraft>?>(null)

    init {
        // Catches pictures left behind by a cancelled sheet, and a limit lowered while the app was closed.
        viewModelScope.launch { photos.enforceLimit() }
    }

    fun shiftDay(days: Long) { day.value = day.value.plusDays(days) }
    fun today() { day.value = LocalDate.now() }

    private var lastResumeDate = LocalDate.now()

    /** Reopening the app the next morning lands on the new day; returning from the camera keeps the chosen day. */
    fun onResume() {
        clock.value = LocalTime.now()
        val now = LocalDate.now()
        if (now != lastResumeDate) {
            lastResumeDate = now
            day.value = now
        }
    }

    fun addWater(deltaMl: Int) {
        val dayKey = day.value.toString()
        viewModelScope.launch { dao.addWater(dayKey, deltaMl) }
    }

    /** Entries logged on today get the current time; back-filled days get noon so they sort sensibly. */
    private fun stamp(): Long {
        val d = day.value
        return if (d == LocalDate.now()) System.currentTimeMillis()
        else d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    fun analyzeFood(text: String, photo: Uri? = null) = run {
        val shot = photo?.let { loadJpeg(it) }
        val items = ai.parseFood(text, shot?.jpeg)
        if (items.isEmpty()) {
            error = "Couldn't find any food in that."
        } else {
            // Kept only once it produced something to log. The phone keeps the camera's original file;
            // only the downscaled, EXIF-free copy was sent to Claude.
            val name = shot?.let { photos.save(it.original, it.takenAt) }
            foodDrafts = items.map { it.toDraft().copy(photo = name) }
        }
    }

    fun analyzeWorkout(text: String) = run {
        val items = ai.parseWorkout(text)
        if (items.isEmpty()) error = "Couldn't find any exercises in that." else workoutDrafts = items.map { it.toDraft() }
    }

    suspend fun checkKey(): String = ai.check()
    suspend fun listModels(): List<String> = ai.models()

    /** Rewrites an entry's amount text after the portion slider moved; throws if Claude can't be reached. */
    suspend fun rescaleAmount(amount: String, factor: Double): String = ai.rescaleAmount(amount, factor)

    fun measureHand(photo: Uri) = run {
        settings.handReference = ai.measureHand(loadJpeg(photo).jpeg)
    }

    fun editFood(entry: FoodEntry) { foodDrafts = listOf(entry.toDraft()) }
    fun editWorkout(entry: WorkoutEntry) { workoutDrafts = listOf(entry.toDraft()) }

    /**
     * Turns the entry open in the editor into a new, unsaved one for today, keeping any unsaved changes, so it
     * can be adjusted before logging. The original entry is left as it was.
     */
    fun duplicateFood(draft: FoodDraft) {
        today()
        // The picture belongs to the original meal, so the copy doesn't carry it.
        foodDrafts = listOf(draft.copy(id = 0, photo = null))
    }

    fun duplicateWorkout(draft: WorkoutDraft) {
        today()
        workoutDrafts = listOf(draft.copy(id = 0))
    }

    fun saveFood(drafts: List<FoodDraft>) {
        foodDrafts = null
        val dayKey = day.value.toString()
        val time = stamp()
        viewModelScope.launch {
            val (existing, fresh) = drafts.map { d ->
                FoodEntry(
                    id = d.id, day = dayKey, time = time, name = d.name.trim(), quantity = d.quantity.trim(),
                    calories = d.calories.num(), protein = d.protein.num(), carbs = d.carbs.num(), fat = d.fat.num(),
                    fiber = d.fiber.num(), photo = d.photo,
                )
            }.partition { it.id != 0L }
            // Edits keep their original day and time.
            existing.forEach { e ->
                food.value.find { it.id == e.id }?.let { dao.updateFood(e.copy(day = it.day, time = it.time)) }
            }
            if (fresh.isNotEmpty()) dao.insertFood(fresh)
            // Items removed from the sheet may have left their picture unused.
            drafts.mapNotNull { it.photo }.distinct().forEach { photos.release(it) }
        }
    }

    /** Closing the sheet without saving drops a new picture that no entry ended up using. */
    fun dismissFood() {
        val dropped = foodDrafts.orEmpty().mapNotNull { it.photo }.distinct()
        foodDrafts = null
        viewModelScope.launch { dropped.forEach { photos.release(it) } }
    }

    fun setPhotoLimit(mb: Int) {
        settings.photoLimitMb = mb
        viewModelScope.launch { photos.enforceLimit() }
    }

    fun saveWorkouts(drafts: List<WorkoutDraft>) {
        workoutDrafts = null
        val dayKey = day.value.toString()
        val time = stamp()
        viewModelScope.launch {
            val (existing, fresh) = drafts.map { d ->
                WorkoutEntry(
                    id = d.id, day = dayKey, time = time, exercise = d.exercise.trim(),
                    sets = d.sets.num().toInt(), reps = d.reps.num().toInt(), weight = d.weight.num(),
                    unit = d.unit.trim(), durationMin = d.durationMin.num(), notes = d.notes.trim(),
                )
            }.partition { it.id != 0L }
            existing.forEach { e ->
                workouts.value.find { it.id == e.id }?.let { dao.updateWorkout(e.copy(day = it.day, time = it.time)) }
            }
            if (fresh.isNotEmpty()) dao.insertWorkouts(fresh)
        }
    }

    /** One-tap re-log of a previous entry onto the selected day. Returns the new row so it can be undone. */
    suspend fun relogFood(entry: FoodEntry): FoodEntry {
        val copy = entry.copy(id = 0, day = day.value.toString(), time = stamp(), photo = null)
        return copy.copy(id = dao.insertFood(listOf(copy)).first())
    }

    /** Every food ever logged, one per name, for the history search. Read when the search opens. */
    suspend fun foodHistory(): List<FoodSearch.Food> = withContext(Dispatchers.Default) { FoodSearch.distinct(dao.allFood()) }

    suspend fun relogWorkout(entry: WorkoutEntry): WorkoutEntry {
        val copy = entry.copy(id = 0, day = day.value.toString(), time = stamp())
        return copy.copy(id = dao.insertWorkouts(listOf(copy)).first())
    }

    fun deleteFood(id: Long) = viewModelScope.launch {
        food.value.find { it.id == id }?.let {
            dao.deleteFood(it)
            photos.release(it.photo)
        }
    }

    fun deleteWorkout(id: Long) = viewModelScope.launch {
        workouts.value.find { it.id == id }?.let { dao.deleteWorkout(it) }
    }

    suspend fun undoFood(entry: FoodEntry) = dao.deleteFood(entry)
    suspend fun undoWorkout(entry: WorkoutEntry) = dao.deleteWorkout(entry)

    /**
     * Downscale so the long edge is at most 1568 px: the size Claude uses anyway, and far faster to upload.
     * Re-encoding from bare pixels also drops all EXIF metadata (GPS, timestamps, device), so only the
     * image itself leaves the phone. The EXIF rotation is applied to the pixels first so it isn't lost.
     */
    /** [jpeg] is what goes to Claude; [original] is the untouched camera file, which only stays on the phone. */
    private class Shot(val jpeg: ByteArray, val original: ByteArray, val takenAt: Long)

    private suspend fun loadJpeg(uri: Uri): Shot = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val original = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Couldn't read the photo.")
        val exif = ExifInterface(original.inputStream())
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        // When the picture was taken, read before the metadata is stripped; it orders eviction.
        val takenAt = exif.dateTimeOriginal.takeIf { it > 0 } ?: System.currentTimeMillis()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("Couldn't read the photo.")
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f || rotation != 0f) {
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation)
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else decoded
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        Shot(jpeg, original, takenAt)
    }

    private companion object {
        const val MAX_EDGE = 1568
    }
}
