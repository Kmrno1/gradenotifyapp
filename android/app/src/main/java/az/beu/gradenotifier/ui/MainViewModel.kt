package az.beu.gradenotifier.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import az.beu.gradenotifier.data.AppState
import az.beu.gradenotifier.data.PrefsStateStore
import az.beu.gradenotifier.data.Settings
import az.beu.gradenotifier.net.clearPortalSession
import az.beu.gradenotifier.net.hasPortalSession
import az.beu.gradenotifier.poll.applySchedule
import az.beu.gradenotifier.poll.checkNow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PrefsStateStore(app)

    val state: StateFlow<AppState> get() = store.state

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> get() = _checking

    private val _signedIn = MutableStateFlow(hasPortalSession())
    val signedIn: StateFlow<Boolean> get() = _signedIn

    fun refreshSessionState() {
        _signedIn.value = hasPortalSession()
    }

    fun start() {
        viewModelScope.launch { applySchedule(getApplication(), store) }
    }

    fun checkNow() {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            try {
                checkNow(getApplication(), store)
                refreshSessionState()
            } finally {
                _checking.value = false
            }
        }
    }

    fun signOut() {
        clearPortalSession()
        refreshSessionState()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            store.update { it.copy(settings = transform(it.settings)) }
            applySchedule(getApplication(), store)
        }
    }
}
