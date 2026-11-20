package ru.msklad.scanner.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.msklad.scanner.api.ApiException
import ru.msklad.scanner.api.ApiFactory
import ru.msklad.scanner.data.AddResult
import ru.msklad.scanner.data.MoveInfo
import ru.msklad.scanner.data.MoveRepository
import ru.msklad.scanner.data.SettingsStore

sealed interface ResultState {
    data object Idle : ResultState
    data object Loading : ResultState
    data class Success(val result: AddResult) : ResultState
    data class Undone(val result: AddResult) : ResultState
    data class Error(val message: String) : ResultState
}

enum class Highlight { NONE, SENDING, SUCCESS, ERROR }

enum class Sound { SCAN, SUCCESS, ERROR }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private lateinit var move: MoveInfo
    private lateinit var repository: MoveRepository

    private val _result = MutableStateFlow<ResultState>(ResultState.Idle)
    val result: StateFlow<ResultState> = _result

    private val _highlight = MutableStateFlow(Highlight.NONE)
    val highlight: StateFlow<Highlight> = _highlight

    private val _scannerOn = MutableStateFlow(true)
    val scannerOn: StateFlow<Boolean> = _scannerOn

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private val _sounds = Channel<Sound>(Channel.BUFFERED)
    val sounds = _sounds.receiveAsFlow()

    private val _clearInput = Channel<Unit>(Channel.BUFFERED)
    val clearInput = _clearInput.receiveAsFlow()

    private var lastAdded: AddResult? = null
    private var lastScanCode: String? = null
    private var lastScanAt = 0L
    private var resetJob: Job? = null

    fun init(move: MoveInfo) {
        if (::move.isInitialized) return
        this.move = move
        val settings = SettingsStore(getApplication()).load()
        repository = MoveRepository(ApiFactory.create(settings.login, settings.password))
    }

    fun setScannerOn(on: Boolean) { _scannerOn.value = on }

    fun onScanned(code: String, quantityText: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (_result.value == ResultState.Loading) return false
        if (code == lastScanCode && now - lastScanAt < DEBOUNCE_MS) return false
        lastScanCode = code
        lastScanAt = now
        _sounds.trySend(Sound.SCAN)
        submit(code, quantityText)
        return true
    }

    fun submit(code: String, quantityText: String) {
        if (_result.value == ResultState.Loading) return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return fail("Введите код или штрихкод товара")
        val quantity = parseQuantity(quantityText)
            ?: return fail("Некорректное количество: нужно число больше нуля")

        lastAdded = null
        _canUndo.value = false
        startSending()
        viewModelScope.launch {
            try {
                val added = repository.addItem(move, trimmed, quantity)
                lastAdded = added
                _canUndo.value = true
                show(ResultState.Success(added), Highlight.SUCCESS)
                _sounds.send(Sound.SUCCESS)
                _clearInput.send(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                fail(e.message ?: "Неизвестная ошибка")
            } catch (e: Exception) {
                fail("Неожиданная ошибка: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    fun undo() {
        val added = lastAdded ?: return
        if (_result.value == ResultState.Loading) return
        _canUndo.value = false
        startSending()
        viewModelScope.launch {
            try {
                repository.undoAdd(move, added)
                lastAdded = null
                show(ResultState.Undone(added), Highlight.SUCCESS)
                _sounds.send(Sound.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _canUndo.value = true
                fail(e.message ?: "Неизвестная ошибка")
            } catch (e: Exception) {
                _canUndo.value = true
                fail("Неожиданная ошибка: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    private fun startSending() {
        resetJob?.cancel()
        _result.value = ResultState.Loading
        _highlight.value = Highlight.SENDING
    }

    private fun show(state: ResultState, highlight: Highlight) {
        _result.value = state
        _highlight.value = highlight
        resetJob?.cancel()
        resetJob = viewModelScope.launch {
            delay(HIGHLIGHT_MS)
            _highlight.value = Highlight.NONE
        }
    }

    private fun fail(message: String) {
        show(ResultState.Error(message), Highlight.ERROR)
        _sounds.trySend(Sound.ERROR)
    }

    private fun parseQuantity(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 1.0
        val value = trimmed.replace(',', '.').toDoubleOrNull() ?: return null
        return value.takeIf { it > 0 && it.isFinite() }
    }

    private companion object {
        const val DEBOUNCE_MS = 1500L
        const val HIGHLIGHT_MS = 3000L
    }
}
