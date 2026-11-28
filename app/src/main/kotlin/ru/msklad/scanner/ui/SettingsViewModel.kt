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
import ru.msklad.scanner.data.OfflineSnapshot
import ru.msklad.scanner.data.OfflineStore
import ru.msklad.scanner.data.Settings
import ru.msklad.scanner.data.SettingsStore
import ru.msklad.scanner.data.formatQuantity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SettingsState(
    val loading: Boolean = false,
    val error: String? = null,
    val offlineLoading: Boolean = false,
    val offlineError: String? = null,
    val offlineLines: List<String> = emptyList(),
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val offlineStore = OfflineStore(app)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state

    private val _started = Channel<DocInfo>(Channel.BUFFERED)
    val started = _started.receiveAsFlow()

    fun savedSettings() = store.load()

    /** Сводка уже скачанной локальной копии — показываем при открытии экрана. */
    fun savedSummary(): List<String> {
        val snapshot = offlineStore.load()
        return if (snapshot.hasData) summary(snapshot) else emptyList()
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
     * «Офлайн Синхронизация»: скачиваем остатки обоих складов и позиции документов
     * оприходования/списания, чтобы потом работать без сети.
     */
    fun downloadOffline(settings: Settings) {
        if (_state.value.offlineLoading) return
        store.save(settings)
        _state.value = _state.value.copy(offlineLoading = true, offlineError = null, offlineLines = emptyList())
        viewModelScope.launch {
            try {
                val repository = newRepository(settings)
                val move = repository.findDocument(DocumentType.MOVE, settings.moveInput)
                val extra = resolveExtra(repository, settings, strict = true)
                val supply = extra.firstOrNull { it.type == DocumentType.SUPPLY }
                val loss = extra.firstOrNull { it.type == DocumentType.LOSS }
                offlineStore.saveDocs(listOf(move) + extra)

                val fresh = repository.loadSnapshot(move, supply, loss)
                // Очередь неотправленных позиций не теряем при обновлении остатков.
                val snapshot = fresh.copy(pending = offlineStore.load().pending)
                offlineStore.save(snapshot)
                _state.value = _state.value.copy(
                    offlineLoading = false,
                    offlineLines = summary(snapshot),
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(offlineLoading = false, offlineError = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(offlineLoading = false, offlineError = unexpected(e))
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

    private fun summary(snapshot: OfflineSnapshot): List<String> {
        val app = getApplication<Application>()
        val lines = mutableListOf(
            app.getString(
                R.string.offline_summary,
                snapshot.sourceName,
                snapshot.sourceCount,
                formatQuantity(snapshot.sourceTotal),
            ),
            app.getString(
                R.string.offline_summary,
                snapshot.targetName,
                snapshot.targetCount,
                formatQuantity(snapshot.targetTotal),
            ),
        )
        if (snapshot.savedAt > 0) {
            val time = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(snapshot.savedAt))
            lines.add(app.getString(R.string.offline_loaded_at, time))
        }
        if (snapshot.pending.isNotEmpty()) {
            lines.add(app.getString(R.string.offline_pending_in_snapshot, snapshot.pending.size))
        }
        return lines
    }
}
