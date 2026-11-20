package ru.msklad.scanner.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
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
    data class Error(val message: String) : ResultState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private lateinit var move: MoveInfo
    private lateinit var repository: MoveRepository

    private val _result = MutableStateFlow<ResultState>(ResultState.Idle)
    val result: StateFlow<ResultState> = _result

    private val _scannerOn = MutableStateFlow(true)
    val scannerOn: StateFlow<Boolean> = _scannerOn

    private val _outcomes = Channel<Boolean>(Channel.BUFFERED)
    val outcomes = _outcomes.receiveAsFlow()

    private var lastScanCode: String? = null
    private var lastScanAt = 0L

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
        submit(code, quantityText)
        return true
    }

    fun submit(code: String, quantityText: String) {
        if (_result.value == ResultState.Loading) return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return fail("Введите код или штрихкод товара")
        val quantity = parseQuantity(quantityText)
            ?: return fail("Некорректное количество: нужно число больше нуля")

        _result.value = ResultState.Loading
        viewModelScope.launch {
            try {
                val added = repository.addItem(move, trimmed, quantity)
                _result.value = ResultState.Success(added)
                _outcomes.send(true)
            } catch (e: ApiException) {
                fail(e.message ?: "Неизвестная ошибка")
            }
        }
    }

    private fun fail(message: String) {
        _result.value = ResultState.Error(message)
        _outcomes.trySend(false)
    }

    private fun parseQuantity(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 1.0
        val value = trimmed.replace(',', '.').toDoubleOrNull() ?: return null
        return value.takeIf { it > 0 && it.isFinite() }
    }

    private companion object {
        const val DEBOUNCE_MS = 1500L
    }
}
