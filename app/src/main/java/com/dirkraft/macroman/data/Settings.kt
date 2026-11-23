package com.dirkraft.macroman.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import com.dirkraft.macroman.ai.LlmConfig
import com.dirkraft.macroman.ai.Provider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Fiber is a target within the carbs, not on top of them. */
data class Targets(val calories: Int, val protein: Int, val carbs: Int, val fat: Int, val fiber: Int) {
    fun encode() = "$calories,$protein,$carbs,$fat,$fiber"

    companion object {
        /** Also reads the four-number form from before there was a fiber target (and old backups). */
        fun decode(s: String?, default: Targets): Targets =
            s?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.size == 4 || it.size == 5 }
                ?.let { Targets(it[0], it[1], it[2], it[3], it.getOrElse(4) { default.fiber }) } ?: default
    }
}

/** Settings live in app-private SharedPreferences, mirrored into Compose state so the UI recomposes on change. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * Re-reads the settings held in Compose state, after a restore rewrote the prefs underneath them.
     * Declared first: the delegated settings below register here as they are created.
     */
    private val reloaders = mutableListOf<() -> Unit>()

    // Anthropic's key and model keep their original keys, from before there was a provider choice.
    private var anthropicKey by pref("api_key", "")
    private var anthropicModel by pref("model", DEFAULT_MODEL)

    var provider: Provider
        get() = Provider.byId(read("provider", Provider.ANTHROPIC.id))
        set(v) = write("provider", v.id)

    fun apiKey(p: Provider = provider) = if (p == Provider.ANTHROPIC) anthropicKey else read("api_key_${p.id}", "")
    fun setApiKey(p: Provider, v: String) = if (p == Provider.ANTHROPIC) anthropicKey = v else write("api_key_${p.id}", v)

    /** Other providers start with no model: their lists change too often to guess, so one is picked in Settings. */
    fun model(p: Provider = provider) = if (p == Provider.ANTHROPIC) anthropicModel else read("model_${p.id}", "")
    fun setModel(p: Provider, v: String) = if (p == Provider.ANTHROPIC) anthropicModel = v else write("model_${p.id}", v)

    /** For small rewrite jobs; blank means use the main model. */
    fun quickModel(p: Provider = provider) =
        read("quick_model_${p.id}", if (p == Provider.ANTHROPIC) DEFAULT_QUICK_MODEL else "")
    fun setQuickModel(p: Provider, v: String) = write("quick_model_${p.id}", v)

    fun baseUrl(p: Provider = provider) = read("base_url_${p.id}", p.defaultBaseUrl)
    fun setBaseUrl(p: Provider, v: String) = write("base_url_${p.id}", v)

    /** Whether the chosen provider has what it needs to make a call. */
    fun configured(): Boolean {
        val p = provider
        return (p.keyOptional || apiKey(p).isNotBlank()) && (p == Provider.ANTHROPIC || baseUrl(p).isNotBlank())
    }

    fun llmConfig(): LlmConfig {
        val p = provider
        return LlmConfig(
            p, baseUrl(p).trim(), apiKey(p).trim(),
            model(p).trim().ifBlank { if (p == Provider.ANTHROPIC) DEFAULT_MODEL else "" }, quickModel(p).trim(),
        )
    }

    // Settings with keys made at runtime. Reads go straight to the prefs (whose writes are visible at once);
    // the counter is what tells Compose to read again.
    private val version = mutableIntStateOf(0)
    private fun read(key: String, default: String): String {
        version.intValue
        return prefs.getString(key, default) ?: default
    }
    private fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        version.intValue++
    }

    /** Hand dimensions, one per line, sent with food photos so a hand in frame gives scale. */
    var handReference by pref("hand_reference", "")

    /** How much space kept food pictures may take before the oldest are deleted. */
    var photoLimitMb: Int
        get() = photoLimitRaw.toIntOrNull() ?: DEFAULT_PHOTO_LIMIT_MB
        set(v) { photoLimitRaw = v.toString() }
    private var photoLimitRaw by pref("photo_limit_mb", DEFAULT_PHOTO_LIMIT_MB.toString())

    // Stored under the old rest day key, which is what carries the existing values over.
    private var targetsRaw by pref("targets_rest", DEFAULT_TARGETS.encode())

    var targets: Targets
        get() = Targets.decode(targetsRaw, DEFAULT_TARGETS)
        set(v) { targetsRaw = v.encode() }

    private fun pref(key: String, default: String) = object {
        private val state = mutableStateOf(prefs.getString(key, default) ?: default)
            .also { s -> reloaders += { s.value = prefs.getString(key, default) ?: default } }
        operator fun getValue(thisRef: Any?, p: Any?): String = state.value
        operator fun setValue(thisRef: Any?, p: Any?, value: String) {
            state.value = value
            prefs.edit().putString(key, value).apply()
        }
    }

    /** Where the backup file lives (a document URI), or blank for no backup. Not itself backed up. */
    var backupUri: String
        get() = read(BACKUP_URI, "")
        set(v) = write(BACKUP_URI, v)

    /** When the backup file was last written (epoch ms), 0 if not yet. */
    var backupLast: Long
        get() = read("backup_last", "0").toLongOrNull() ?: 0
        set(v) = write("backup_last", v.toString())

    /** Every setting except API keys and the backup location, for the backup file. */
    fun exportable(): Map<String, String> =
        prefs.all.filter { (k, v) -> v is String && !isPrivate(k) }.mapValues { it.value as String }

    /** Replaces the backed-up settings, leaving API keys and the backup location as they are on this phone. */
    fun import(values: Map<String, String>) {
        val edit = prefs.edit()
        prefs.all.keys.filterNot(::isPrivate).forEach { edit.remove(it) }
        values.filterKeys { !isPrivate(it) }.forEach { (k, v) -> edit.putString(k, v) }
        edit.commit()
        reloaders.forEach { it() }
        version.intValue++
    }

    private fun isPrivate(key: String) = key.startsWith("api_key") || key.startsWith("backup_")

    companion object {
        private const val BACKUP_URI = "backup_uri" // "backup_" settings stay out of the backup file
        const val DEFAULT_PHOTO_LIMIT_MB = 1024
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val DEFAULT_QUICK_MODEL = "claude-haiku-5-5"
        // Lean-gain starting points for roughly an 80 kg lifter; meant to be tuned in Settings.
        val DEFAULT_TARGETS = Targets(calories = 2400, protein = 180, carbs = 220, fat = 80, fiber = 30)
    }
}
