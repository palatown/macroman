package com.dirkraft.macroman.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Food pictures kept on the phone, in the app's no-backup folder so they never go to cloud backup or a
 * device transfer. Each file is named after the moment the picture was taken ("<epoch ms>.jpg"), which is
 * what eviction goes by: the oldest pictures are deleted first, whatever day their entries are logged on.
 * Several entries can share one picture (one photo of a plate becomes several items).
 */
class PhotoStore(context: Context, private val dao: LogDao, private val settings: Settings) {
    private val dir = File(context.noBackupFilesDir, "photos").apply { mkdirs() }
    private val lock = Mutex()

    fun file(name: String) = File(dir, name)

    /** Stores a picture and returns its name, then trims the store back under the limit. */
    suspend fun save(jpeg: ByteArray, takenAt: Long): String = withContext(Dispatchers.IO) {
        val name = lock.withLock {
            var t = takenAt
            while (file("$t.jpg").exists()) t++
            file("$t.jpg").writeBytes(jpeg)
            "$t.jpg"
        }
        enforceLimit(keep = name)
        name
    }

    /** Deletes a picture once no entry uses it any more. */
    suspend fun release(name: String?) {
        if (name == null) return
        withContext(Dispatchers.IO) {
            lock.withLock { if (dao.photoRefs(name) == 0) file(name).delete() }
        }
    }

    fun usedBytes(): Long = files().sumOf { it.length() }

    /**
     * Deletes pictures no entry uses (left behind when a confirm sheet was cancelled) once they are a day old,
     * then the oldest-taken pictures until the store fits the limit. [keep] protects a picture not yet saved
     * to any entry.
     */
    suspend fun enforceLimit(keep: String? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            val used = dao.photos().toSet()
            val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            files().filter { it.name !in used && it.name != keep && takenAt(it) < dayAgo }.forEach { it.delete() }

            val limit = settings.photoLimitMb * 1024L * 1024L
            var total = usedBytes()
            for (f in files().sortedBy(::takenAt)) {
                if (total <= limit) break
                if (f.name == keep) continue
                total -= f.length()
                f.delete()
                dao.forgetPhoto(f.name)
            }
        }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.toList().orEmpty()

    private fun takenAt(f: File) = f.name.removeSuffix(".jpg").toLongOrNull() ?: f.lastModified()
}
