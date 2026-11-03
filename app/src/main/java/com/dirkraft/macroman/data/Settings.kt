package com.dirkraft.macroman.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class Targets(val calories: Int, val protein: Int, val carbs: Int, val fat: Int) {
    fun encode() = "$calories,$protein,$carbs,$fat"

    companion object {
        fun decode(s: String?, default: Targets): Targets =
            s?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.size == 4 }
                ?.let { Targets(it[0], it[1], it[2], it[3]) } ?: default
    }
}

/** Settings live in app-private SharedPreferences, mirrored into Compose state so the UI recomposes on change. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey by pref("api_key", "")
    var model by pref("model", DEFAULT_MODEL)

    /** Hand dimensions, one per line, sent with food photos so a hand in frame gives scale. */
    var handReference by pref("hand_reference", "")

    private var trainingRaw by pref("targets_training", DEFAULT_TRAINING.encode())
    private var restRaw by pref("targets_rest", DEFAULT_REST.encode())

    var trainingTargets: Targets
        get() = Targets.decode(trainingRaw, DEFAULT_TRAINING)
        set(v) { trainingRaw = v.encode() }

    var restTargets: Targets
        get() = Targets.decode(restRaw, DEFAULT_REST)
        set(v) { restRaw = v.encode() }

    private fun pref(key: String, default: String) = object {
        private val state = mutableStateOf(prefs.getString(key, default) ?: default)
        operator fun getValue(thisRef: Any?, p: Any?): String = state.value
        operator fun setValue(thisRef: Any?, p: Any?, value: String) {
            state.value = value
            prefs.edit().putString(key, value).apply()
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        // Lean-gain starting points for roughly an 80 kg lifter; meant to be tuned in Settings.
        val DEFAULT_TRAINING = Targets(calories = 2700, protein = 180, carbs = 300, fat = 75)
        val DEFAULT_REST = Targets(calories = 2400, protein = 180, carbs = 220, fat = 80)
    }
}
