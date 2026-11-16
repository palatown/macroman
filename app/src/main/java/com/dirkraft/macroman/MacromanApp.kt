package com.dirkraft.macroman

import android.app.Application
import com.dirkraft.macroman.ai.MacroAi
import com.dirkraft.macroman.data.AppDb
import com.dirkraft.macroman.data.PhotoStore
import com.dirkraft.macroman.data.Settings

class MacromanApp : Application() {
    val db by lazy { AppDb.open(this) }
    val settings by lazy { Settings(this) }
    val photos by lazy { PhotoStore(this, db.dao(), settings) }
    val ai by lazy { MacroAi(settings) }
}
