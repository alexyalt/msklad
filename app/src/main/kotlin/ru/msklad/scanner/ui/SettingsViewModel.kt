package ru.msklad.scanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.msklad.scanner.R
import ru.msklad.scanner.api.ApiException
import ru.msklad.scanner.api.ApiFactory
import ru.msklad.scanner.data.DocInfo
import ru.msklad.scanner.data.DocumentRepository
import ru.msklad.scanner.data.DocumentType
import ru.msklad.scanner.data.OfflineStore
import ru.msklad.scanner.data.Settings
import ru.msklad.scanner.data.SettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SettingsState(
    val loading: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val offlineStore = OfflineStore(app)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state

    private val _started = Channel<DocInfo>(Channel.BUFFERED)
    val started = _started.receiveAsFlow()

    fun savedSettings() = store.load()

    /**
     * Результат последней синхронизации офлайн-режима (запускается переключателем на экране
     * сканирования) + неотправленные позиции, если они остались.
     */
    fun savedSummary(): List<String> {
        val app = getApplication<Application>()
        val at = offlineStore.lastSyncAt()
        val lines = offlineStore.lastSyncLines().toMutableList()
        if (at > 0) {
            val time = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(at))
            lines.add(0, app.getString(R.string.last_sync_at, time))
        }
        val pending = offlineStore.load().pending.size
        if (pending > 0) lines.add(app.getString(R.string.offline_pending_in_snapshot, pending))
        return lines
    }

    fun start(settings: Settings) {
        if (_state.value.loading) return
        store.save(settings)
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val repository = newRepository(settings)
                val move = repository.findDocument(DocumentType.MOVE, settings.moveInput)
                // Кэшируем резолвнутые документы, чтобы экран сканирования не ходил в сеть
                // за оприходованием и списанием.
                offlineStore.saveDocs(listOf(move) + resolveExtra(repository, settings, strict = false))
                _state.value = _state.value.copy(loading = false)
                _started.send(move)
            } catch (e: ApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = unexpected(e))
            }
        }
    }

    /**
     * Оприходование и списание необязательны: пустое поле — документ не резолвим.
     * [strict] = false используется при обычном «Начать»: ненайденный документ корректировки
     * не должен блокировать сканирование.
     */
    private suspend fun resolveExtra(
        repository: DocumentRepository,
        settings: Settings,
        strict: Boolean = true,
    ): List<DocInfo> {
        val result = mutableListOf<DocInfo>()
        suspend fun resolve(type: DocumentType, input: String) {
            if (input.isBlank()) return
            if (strict) {
                result.add(repository.findDocument(type, input))
            } else {
                runCatching { repository.findDocument(type, input) }.getOrNull()?.let(result::add)
            }
        }
        resolve(DocumentType.SUPPLY, settings.supplyInput)
        resolve(DocumentType.LOSS, settings.lossInput)
        return result
    }

    private fun newRepository(settings: Settings) =
        DocumentRepository(ApiFactory.create(settings.login, settings.password))

    private fun unexpected(e: Exception): String = getApplication<Application>().getString(
        R.string.error_unexpected,
        e.message ?: e::class.java.simpleName,
    )
}
