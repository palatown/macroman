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
import java.time.LocalDate
import java.time.ZoneId

/** An editable food row in the confirm sheet. Strings so text fields can hold partial input like "12.". */
data class FoodDraft(
    val id: Long = 0,
    val name: String,
    val quantity: String,
    val calories: String,
    val protein: String,
    val carbs: String,
    val fat: String,
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

fun FoodItem.toDraft() = FoodDraft(0, name, quantity, calories.fmt(), protein.fmt(), carbs.fmt(), fat.fmt())
fun FoodEntry.toDraft() = FoodDraft(id, name, quantity, calories.fmt(), protein.fmt(), carbs.fmt(), fat.fmt())
fun WorkoutItem.toDraft() = WorkoutDraft(
    0, exercise, sets.toString(), reps.toString(), weight.fmt(), unit, durationMin.fmt(), notes,
)
fun WorkoutEntry.toDraft() = WorkoutDraft(
    id, exercise, sets.toString(), reps.toString(), weight.fmt(), unit, durationMin.fmt(), notes,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val macroman = app as MacromanApp
    private val dao = macroman.db.dao()
    private val ai = macroman.ai
    val settings = macroman.settings

    val day = MutableStateFlow(LocalDate.now())

    val food: StateFlow<List<FoodEntry>> = day.flatMapLatest { dao.food(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val workouts: StateFlow<List<WorkoutEntry>> = day.flatMapLatest { dao.workouts(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val waterMl: StateFlow<Int> = day.flatMapLatest { dao.waterMl(it.toString()) }
        .map { it ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val frequentFood =dao.frequentFood().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val frequentWorkouts = dao.frequentWorkouts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** Non-null while the confirm sheet is open. */
    var foodDrafts by mutableStateOf<List<FoodDraft>?>(null)
    var workoutDrafts by mutableStateOf<List<WorkoutDraft>?>(null)

    fun shiftDay(days: Long) { day.value = day.value.plusDays(days) }
    fun today() { day.value = LocalDate.now() }

    private var lastResumeDate = LocalDate.now()

    /** Reopening the app the next morning lands on the new day; returning from the camera keeps the chosen day. */
    fun onResume() {
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
        val jpeg = photo?.let { loadJpeg(it) }
        val items = ai.parseFood(text, jpeg)
        if (items.isEmpty()) error = "Couldn't find any food in that." else foodDrafts = items.map { it.toDraft() }
    }

    fun analyzeWorkout(text: String) = run {
        val items = ai.parseWorkout(text)
        if (items.isEmpty()) error = "Couldn't find any exercises in that." else workoutDrafts = items.map { it.toDraft() }
    }

    suspend fun checkKey(): String = ai.check()

    fun measureHand(photo: Uri) = run {
        settings.handReference = ai.measureHand(loadJpeg(photo))
    }

    fun editFood(entry: FoodEntry) { foodDrafts = listOf(entry.toDraft()) }
    fun editWorkout(entry: WorkoutEntry) { workoutDrafts = listOf(entry.toDraft()) }

    fun saveFood(drafts: List<FoodDraft>) {
        foodDrafts = null
        val dayKey = day.value.toString()
        val time = stamp()
        viewModelScope.launch {
            val (existing, fresh) = drafts.map { d ->
                FoodEntry(
                    id = d.id, day = dayKey, time = time, name = d.name.trim(), quantity = d.quantity.trim(),
                    calories = d.calories.num(), protein = d.protein.num(), carbs = d.carbs.num(), fat = d.fat.num(),
                )
            }.partition { it.id != 0L }
            // Edits keep their original day and time.
            existing.forEach { e ->
                food.value.find { it.id == e.id }?.let { dao.updateFood(e.copy(day = it.day, time = it.time)) }
            }
            if (fresh.isNotEmpty()) dao.insertFood(fresh)
        }
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
        val copy = entry.copy(id = 0, day = day.value.toString(), time = stamp())
        return copy.copy(id = dao.insertFood(listOf(copy)).first())
    }

    suspend fun relogWorkout(entry: WorkoutEntry): WorkoutEntry {
        val copy = entry.copy(id = 0, day = day.value.toString(), time = stamp())
        return copy.copy(id = dao.insertWorkouts(listOf(copy)).first())
    }

    fun deleteFood(id: Long) = viewModelScope.launch {
        food.value.find { it.id == id }?.let { dao.deleteFood(it) }
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
    private suspend fun loadJpeg(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
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
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    private companion object {
        const val MAX_EDGE = 1568
    }
}
