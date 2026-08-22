package az.beu.gradenotifier.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Persistence for everything the app remembers between polls.
 *
 * Only grades and settings are stored here. The portal session lives in the
 * WebView cookie store and the password is never seen by the app at all.
 */
interface StateStore {
    val state: StateFlow<AppState>
    suspend fun read(): AppState
    suspend fun update(transform: (AppState) -> AppState): AppState
}

private const val PREFS = "beu-grade-notifier"
private const val KEY_STATE = "state"

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class PrefsStateStore(context: Context) : StateStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val flow = MutableStateFlow(load())

    override val state: StateFlow<AppState> get() = flow

    private fun load(): AppState {
        val raw = prefs.getString(KEY_STATE, null) ?: return AppState()
        return runCatching { json.decodeFromString<AppState>(raw) }.getOrElse { AppState() }
    }

    override suspend fun read(): AppState = flow.value

    override suspend fun update(transform: (AppState) -> AppState): AppState = mutex.withLock {
        val next = transform(flow.value)
        prefs.edit().putString(KEY_STATE, json.encodeToString(AppState.serializer(), next)).commit()
        flow.value = next
        next
    }
}

/** In-memory store, used by unit tests. */
class InMemoryStateStore(initial: AppState = AppState()) : StateStore {
    private val mutex = Mutex()
    private val flow = MutableStateFlow(initial)

    override val state: StateFlow<AppState> get() = flow
    override suspend fun read(): AppState = flow.value

    override suspend fun update(transform: (AppState) -> AppState): AppState = mutex.withLock {
        flow.value = transform(flow.value)
        flow.value
    }
}
