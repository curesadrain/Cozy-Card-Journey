package com.cozycards.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.Room
import com.cozycards.app.audio.GameAudio
import com.cozycards.app.cards.CardBitmapCache
import com.cozycards.app.data.ContentRepository
import com.cozycards.app.data.EffectsQuality
import com.cozycards.app.data.GameRepository
import com.cozycards.app.data.SettingsRepository
import com.cozycards.app.data.db.AppDatabase
import com.cozycards.app.data.db.RoomGameStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Manual dependency container: one instance per process. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val content = ContentRepository(app)
    val settings = SettingsRepository(app)
    val audio by lazy { GameAudio(app) }
    val database: AppDatabase by lazy { openDatabase(app) }
    val store by lazy { RoomGameStore(database) }
    val repository by lazy { GameRepository(app, store, content, scope) }
    val bitmaps = CardBitmapCache(app, content)

    private val _ready = MutableStateFlow<StartupState>(StartupState.Loading)
    val ready: StateFlow<StartupState> = _ready

    /** Device class used for the AUTO graphics quality. */
    val autoQuality: EffectsQuality by lazy {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        when {
            am.isLowRamDevice || am.memoryClass < 192 -> EffectsQuality.LOW
            Runtime.getRuntime().availableProcessors() < 6 -> EffectsQuality.MEDIUM
            else -> EffectsQuality.HIGH
        }
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            _ready.value = try {
                com.cozycards.app.ui.gfx.Sprites.load(app)
                repository.start()
                StartupState.Ready(repository.restoredFromBackup, content.packWarnings.toList())
            } catch (t: Throwable) {
                Log.e("AppContainer", "startup failed", t)
                StartupState.Failed(t.message ?: t.toString())
            }
        }
        scope.launch { settings.settings.collect { audio.apply(it) } }
    }

    companion object {
        /**
         * Opens the database, verifying integrity. A corrupted file is moved aside (kept for
         * diagnostics) and a fresh database is created; progress is then restored from the
         * newest valid backup by [GameRepository.start].
         */
        fun openDatabase(context: Context): AppDatabase {
            fun build() = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
                .addMigrations(*AppDatabase.ALL_MIGRATIONS)
                .build()
            val db = build()
            val ok = runCatching {
                db.openHelper.writableDatabase.query("PRAGMA quick_check").use { c -> c.moveToFirst() && c.getString(0) == "ok" }
            }.getOrDefault(false)
            if (ok) return db
            Log.w("AppContainer", "Database failed integrity check; recreating and restoring from backup")
            db.close()
            val file = context.getDatabasePath(AppDatabase.NAME)
            file.renameTo(context.getDatabasePath(AppDatabase.NAME + ".corrupt"))
            context.deleteDatabase(AppDatabase.NAME)
            return build()
        }
    }
}

sealed interface StartupState {
    data object Loading : StartupState
    data class Ready(val restoredFromBackup: Boolean, val warnings: List<String>) : StartupState
    data class Failed(val message: String) : StartupState
}

class CozyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

val Context.container: AppContainer get() = (applicationContext as CozyApp).container
