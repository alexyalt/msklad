package ru.msklad.scanner.ui

import android.app.Application
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
import ru.msklad.scanner.data.LabelClient
import ru.msklad.scanner.data.DocumentRepository
import ru.msklad.scanner.data.ProductInfo
import ru.msklad.scanner.data.SettingsStore

sealed interface LookupState {
    data object Idle : LookupState
    data object Loading : LookupState
    data class Found(val product: ProductInfo) : LookupState
    data class Failed(val message: String) : LookupState
}

sealed interface PrintState {
    data object Idle : PrintState
    data object Sending : PrintState
    data class Done(val name: String, val count: Int) : PrintState
    data class Failed(val message: String) : PrintState
}

class LabelViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val client = LabelClient()

    private val _lookup = MutableStateFlow<LookupState>(LookupState.Idle)
    val lookup: StateFlow<LookupState> = _lookup

    private val _print = MutableStateFlow<PrintState>(PrintState.Idle)
    val print: StateFlow<PrintState> = _print

    private val _highlight = MutableStateFlow(Highlight.NONE)
    val highlight: StateFlow<Highlight> = _highlight

    private val _outcomes = Channel<Boolean>(Channel.BUFFERED)
    val outcomes = _outcomes.receiveAsFlow()

    private var resetJob: Job? = null

    fun clearLookup() {
        if (_lookup.value !is LookupState.Idle && _lookup.value !is LookupState.Loading) {
            _lookup.value = LookupState.Idle
        }
    }

    fun lookupProduct(code: String) {
        if (_lookup.value == LookupState.Loading) return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            _lookup.value = LookupState.Failed("Введите код или штрихкод товара")
            return
        }
        val settings = store.load()
        if (settings.login.isEmpty() || settings.password.isEmpty()) {
            _lookup.value = LookupState.Failed("Сначала введите логин и пароль на стартовом экране")
            return
        }
        _lookup.value = LookupState.Loading
        viewModelScope.launch {
            _lookup.value = try {
                val repo = DocumentRepository(ApiFactory.create(settings.login, settings.password))
                LookupState.Found(repo.lookupProduct(trimmed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                LookupState.Failed(e.message ?: "Неизвестная ошибка")
            } catch (e: Exception) {
                LookupState.Failed("Неожиданная ошибка: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    fun print(code: String, countText: String) {
        if (_print.value == PrintState.Sending) return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return fail("Введите код или штрихкод товара")
        val count = parseCount(countText)
            ?: return fail("Количество: целое число не меньше 1")
        val address = store.load().printServer
        if (address.isBlank()) return fail("Не задан адрес сервера печати в настройках")

        resetJob?.cancel()
        _print.value = PrintState.Sending
        _highlight.value = Highlight.SENDING
        viewModelScope.launch {
            try {
                val result = client.print(address, trimmed, count)
                show(PrintState.Done(result.name, result.printed), Highlight.SUCCESS)
                _outcomes.send(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                fail(e.message ?: "Неизвестная ошибка")
            } catch (e: Exception) {
                fail("Неожиданная ошибка: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    private fun parseCount(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 1
        return trimmed.toIntOrNull()?.takeIf { it >= 1 }
    }

    private fun show(state: PrintState, highlight: Highlight) {
        _print.value = state
        _highlight.value = highlight
        resetJob?.cancel()
        resetJob = viewModelScope.launch {
            delay(HIGHLIGHT_MS)
            _highlight.value = Highlight.NONE
        }
    }

    private fun fail(message: String) {
        show(PrintState.Failed(message), Highlight.ERROR)
        _outcomes.trySend(false)
    }

    private companion object {
        const val HIGHLIGHT_MS = 3000L
    }
}
