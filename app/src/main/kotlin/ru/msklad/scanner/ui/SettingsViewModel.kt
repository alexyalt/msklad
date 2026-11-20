package ru.msklad.scanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.msklad.scanner.api.ApiException
import ru.msklad.scanner.api.ApiFactory
import ru.msklad.scanner.data.MoveInfo
import ru.msklad.scanner.data.MoveRepository
import ru.msklad.scanner.data.Settings
import ru.msklad.scanner.data.SettingsStore

data class SettingsState(val loading: Boolean = false, val error: String? = null)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state

    private val _started = Channel<MoveInfo>(Channel.BUFFERED)
    val started = _started.receiveAsFlow()

    fun savedSettings() = store.load()

    fun start(settings: Settings) {
        if (_state.value.loading) return
        store.save(settings)
        _state.value = SettingsState(loading = true)
        viewModelScope.launch {
            try {
                val repo = MoveRepository(ApiFactory.create(settings.login, settings.password))
                val move = repo.findMove(settings.moveInput)
                _state.value = SettingsState()
                _started.send(move)
            } catch (e: ApiException) {
                _state.value = SettingsState(error = e.message)
            }
        }
    }
}
