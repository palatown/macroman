package com.dirkraft.macroman.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What goes in the backup file: the whole log plus settings, minus API keys. Photos stay on the phone. */
@Serializable
data class BackupFile(
    val app: String = "macroman",
    val version: Int = 1,
    val exportedAt: Long,
    val settings: Map<String, String>,
    val food: List<FoodEntry>,
    val workouts: List<WorkoutEntry>,
    val water: List<WaterDay>,
)

/**
 * Keeps one JSON file up to date in a place the user picked through the system file picker, which lets it be
 * a file in Google Drive (or any other storage app) without the app needing an account or API access of its own.
 * The file is rewritten a few seconds after the log changes, and when the app goes to the background.
 */
@OptIn(FlowPreview::class)
class Backup(context: Context, private val db: AppDb, private val settings: Settings) {
    private val resolver = context.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Problem with the last write, shown in Settings until a write succeeds. */
    var error by mutableStateOf<String?>(null)
        private set

    fun start() {
        scope.launch {
            // The first emission is the current state at startup, which doesn't need saving.
            db.invalidationTracker.createFlow("food", "workout", "water").drop(1).debounce(5_000).collect { save() }
        }
    }

    fun saveSoon() {
        scope.launch { save() }
    }

    private suspend fun save() {
        val target = settings.backupUri.ifBlank { return }
        lock.withLock {
            error = try {
                val bytes = json.encodeToString(BackupFile.serializer(), snapshot()).toByteArray()
                val uri = Uri.parse(target)
                // "wt" truncates; some storage apps only accept "w", which they treat as a full rewrite anyway.
                val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri, "w")
                (out ?: throw IllegalStateException("no stream")).use { it.write(bytes) }
                settings.backupLast = System.currentTimeMillis()
                null
            } catch (e: Exception) {
                "Couldn't write the backup file. Pick it again if it was moved or deleted."
            }
        }
    }

    private suspend fun snapshot(): BackupFile {
        val dao = db.dao()
        return BackupFile(
            exportedAt = System.currentTimeMillis(),
            settings = settings.exportable(),
            food = dao.allFood(),
            workouts = dao.allWorkouts(),
            water = dao.allWater(),
        )
    }

    /** Starts backing up to a file the user just created or picked, keeping access to it across restarts. */
    fun choose(uri: Uri) {
        release()
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        settings.backupUri = uri.toString()
        settings.backupLast = 0
        error = null
        saveSoon()
    }

    fun stop() {
        release()
        settings.backupUri = ""
        settings.backupLast = 0
        error = null
    }

    private fun release() {
        val old = settings.backupUri.ifBlank { return }
        runCatching {
            resolver.releasePersistableUriPermission(
                Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** The backup file's name as the storage app shows it, e.g. "macroman-backup.json". */
    suspend fun fileName(): String? = withContext(Dispatchers.IO) {
        val uri = settings.backupUri.ifBlank { return@withContext null }
        runCatching {
            resolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    /** Reads a backup file for restoring; throws with a message fit to show if it isn't one. */
    suspend fun read(uri: Uri): BackupFile = withContext(Dispatchers.IO) {
        val text = resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("Couldn't open that file.")
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }.getOrNull()
        if (file == null || file.app != "macroman") throw IllegalStateException("That isn't a macroman backup file.")
        if (file.version > 1) throw IllegalStateException("That backup is from a newer version of the app.")
        file
    }

    /** Replaces everything logged on this phone, and the settings, with the backup's. API keys are kept. */
    suspend fun restore(file: BackupFile) = withContext(Dispatchers.IO) {
        lock.withLock {
            db.dao().replaceAll(file.food, file.workouts, file.water)
            settings.import(file.settings)
        }
    }
}
