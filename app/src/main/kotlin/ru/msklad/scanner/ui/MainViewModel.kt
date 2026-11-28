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
import ru.msklad.scanner.R
import ru.msklad.scanner.api.ApiException
import ru.msklad.scanner.api.ApiFactory
import ru.msklad.scanner.data.AddResult
import ru.msklad.scanner.data.DocInfo
import ru.msklad.scanner.data.DocumentRepository
import ru.msklad.scanner.data.DocumentType
import ru.msklad.scanner.data.OfflineSnapshot
import ru.msklad.scanner.data.OfflineStore
import ru.msklad.scanner.data.PendingLine
import ru.msklad.scanner.data.SettingsStore
import ru.msklad.scanner.data.formatQuantity

sealed interface ResultState {
    data object Idle : ResultState
    data object Loading : ResultState
    data class Success(val result: AddResult) : ResultState
    data class Undone(val result: AddResult) : ResultState
    data class Removed(val entry: ScanEntry) : ResultState
    data class Error(val message: String) : ResultState
    data class Synced(val message: String) : ResultState
}

/**
 * Одна строка журнала сканирований. На каждое сканирование — своя запись: товар может
 * встретиться несколько раз, и суммировать его в общую строку нельзя, иначе не видно,
 * что именно и в каком документе было отсканировано. [seq] — сквозной номер записи.
 */
data class ScanEntry(
    val seq: Long,
    val code: String,
    val name: String,
    val productHref: String,
    val quantity: Double,
    val docType: DocumentType = DocumentType.MOVE,
    val uom: String = "",
)

enum class InputMode { SCANNER, MIXED, KEYBOARD }

enum class Highlight { NONE, SENDING, SUCCESS, ERROR }

enum class Sound { SCAN, SUCCESS, ERROR }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private lateinit var move: DocInfo
    private lateinit var repository: DocumentRepository
    private lateinit var offlineStore: OfflineStore
    private var docs: Map<DocumentType, DocInfo> = emptyMap()
    private var snapshot = OfflineSnapshot()

    private val _result = MutableStateFlow<ResultState>(ResultState.Idle)
    val result: StateFlow<ResultState> = _result

    private val _highlight = MutableStateFlow(Highlight.NONE)
    val highlight: StateFlow<Highlight> = _highlight

    private val _scannerOn = MutableStateFlow(true)
    val scannerOn: StateFlow<Boolean> = _scannerOn

    private val _inputMode = MutableStateFlow(InputMode.MIXED)
    val inputMode: StateFlow<InputMode> = _inputMode

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline

    private val _docType = MutableStateFlow(DocumentType.MOVE)
    val docType: StateFlow<DocumentType> = _docType

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    private val _sounds = Channel<Sound>(Channel.BUFFERED)
    val sounds = _sounds.receiveAsFlow()

    private val _clearInput = Channel<Unit>(Channel.BUFFERED)
    val clearInput = _clearInput.receiveAsFlow()

    private val _recent = MutableStateFlow<List<ScanEntry>>(emptyList())
    val recent: StateFlow<List<ScanEntry>> = _recent

    private var lastAdded: AddResult? = null
    private var lastScanCode: String? = null
    private var lastScanAt = 0L
    private var resetJob: Job? = null
    private val session = mutableListOf<ScanEntry>()
    private var nextSeq = 1L
    private var lastEntrySeq = 0L
    private var internalBarcodesOnly = false

    fun init(move: DocInfo) {
        if (::move.isInitialized) return
        this.move = move
        val settings = SettingsStore(getApplication()).load()
        internalBarcodesOnly = settings.internalBarcodesOnly
        _offline.value = settings.offlineMode
        repository = DocumentRepository(ApiFactory.create(settings.login, settings.password))
        offlineStore = OfflineStore(getApplication())
        docs = offlineStore.docs() + (DocumentType.MOVE to move)
        snapshot = offlineStore.load()
        _pendingCount.value = snapshot.pending.size
    }

    fun setScannerOn(on: Boolean) { _scannerOn.value = on }

    fun setInputMode(mode: InputMode) { _inputMode.value = mode }

    fun setDocType(type: DocumentType) { _docType.value = type }

    fun setOffline(on: Boolean) {
        _offline.value = on
        SettingsStore(getApplication()).setOfflineMode(on)
        if (on) {
            snapshot = offlineStore.load()
            _pendingCount.value = snapshot.pending.size
        }
    }

    fun onScanned(code: String, quantityText: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (_result.value == ResultState.Loading) return false
        if (code == lastScanCode && now - lastScanAt < DEBOUNCE_MS) return false
        lastScanCode = code
        lastScanAt = now
        // Режим «только внутренние штрихкоды»: этикетки производителей (EAN-13) не принимаем.
        // Пропускаем их молча — это ожидаемое поведение включённого фильтра, а не ошибка.
        // Ручной ввод кода или артикула этот фильтр не затрагивает.
        if (internalBarcodesOnly && !isInternalBarcode(code)) return false
        _sounds.trySend(Sound.SCAN)
        submit(code, quantityText)
        return true
    }

    fun submit(code: String, quantityText: String) {
        if (_result.value == ResultState.Loading) return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return fail(text(R.string.error_code_required))
        val quantity = parseQuantity(quantityText)
            ?: return fail(text(R.string.error_quantity_invalid))

        lastAdded = null
        _canUndo.value = false
        if (_offline.value) {
            submitOffline(trimmed, quantity)
            return
        }
        val doc = docFor(_docType.value) ?: return fail(notConfigured(_docType.value))
        startSending()
        viewModelScope.launch {
            try {
                val added = repository.addItem(doc, trimmed, quantity)
                onAdded(added)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                fail(e.message ?: text(R.string.error_unknown))
            } catch (e: Exception) {
                fail(text(R.string.error_unexpected, e.message ?: e::class.java.simpleName))
            }
        }
    }

    /** Офлайн: работаем только с локальной копией, сервер не трогаем. */
    private fun submitOffline(code: String, quantity: Double) {
        if (!snapshot.hasData) return fail(text(R.string.offline_no_data))
        val type = _docType.value
        if (docFor(type) == null) return fail(notConfigured(type))

        val item = snapshot.find(code) ?: return fail(text(R.string.offline_product_missing, code))
        val available = snapshot.available(type, item.href)
        if (type != DocumentType.SUPPLY && quantity > available + EPSILON) {
            return fail(text(R.string.offline_insufficient, formatQuantity(maxOf(available, 0.0))))
        }

        val pendingBefore = snapshot.pendingQuantity(type, item.href)
        val line = PendingLine(
            type = type,
            productHref = item.href,
            code = item.code.ifBlank { code },
            name = item.name,
            quantity = quantity,
            uom = item.uom,
        )
        snapshot = snapshot.withPending(line)
        offlineStore.save(snapshot)
        _pendingCount.value = snapshot.pending.size
        val pendingAfter = snapshot.pendingQuantity(type, item.href)

        val added = when (type) {
            DocumentType.MOVE -> AddResult(
                docType = type,
                productName = item.name,
                productCode = item.code,
                productHref = item.href,
                quantity = quantity,
                uom = item.uom,
                sourceAfter = item.sourceQty - pendingAfter,
                targetAfter = item.targetQty + pendingAfter,
                storeBefore = item.sourceQty - pendingBefore,
            )

            DocumentType.LOSS -> AddResult(
                docType = type,
                productName = item.name,
                productCode = item.code,
                productHref = item.href,
                quantity = quantity,
                uom = item.uom,
                sourceAfter = item.sourceQty - pendingAfter,
                targetAfter = 0.0,
                storeBefore = item.sourceQty,
            )

            DocumentType.SUPPLY -> AddResult(
                docType = type,
                productName = item.name,
                productCode = item.code,
                productHref = item.href,
                quantity = quantity,
                uom = item.uom,
                sourceAfter = item.sourceQty + pendingAfter,
                targetAfter = 0.0,
                storeBefore = item.sourceQty + pendingBefore,
            )
        }
        onAdded(added)
    }

    private fun onAdded(added: AddResult) {
        lastAdded = added
        recordScan(added)
        _canUndo.value = true
        show(ResultState.Success(added), Highlight.SUCCESS)
        _sounds.trySend(Sound.SUCCESS)
        _clearInput.trySend(Unit)
    }

    fun undo() {
        val added = lastAdded ?: return
        if (_result.value == ResultState.Loading) return
        if (_offline.value) {
            snapshot = snapshot.withoutPendingType(added.docType, added.productHref, added.quantity)
            offlineStore.save(snapshot)
            _pendingCount.value = snapshot.pending.size
            lastAdded = null
            _canUndo.value = false
            forgetLastScan()
            show(ResultState.Undone(added), Highlight.SUCCESS)
            _sounds.trySend(Sound.SUCCESS)
            return
        }
        val doc = docFor(added.docType) ?: return fail(notConfigured(added.docType))
        _canUndo.value = false
        startSending()
        viewModelScope.launch {
            try {
                repository.undoAdd(doc, added)
                lastAdded = null
                forgetLastScan()
                show(ResultState.Undone(added), Highlight.SUCCESS)
                _sounds.send(Sound.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _canUndo.value = true
                fail(e.message ?: text(R.string.error_unknown))
            } catch (e: Exception) {
                _canUndo.value = true
                fail(text(R.string.error_unexpected, e.message ?: e::class.java.simpleName))
            }
        }
    }

    /** Удаляет из документа одну строку журнала — то количество, которое дало это сканирование. */
    fun removeScanned(entry: ScanEntry) {
        if (session.none { it.seq == entry.seq }) return
        if (_result.value == ResultState.Loading) return
        if (_offline.value) {
            snapshot = snapshot.withoutPending(entry.docType, entry.productHref)
            offlineStore.save(snapshot)
            _pendingCount.value = snapshot.pending.size
            lastAdded = null
            _canUndo.value = false
            dropEntry(entry.seq)
            show(ResultState.Removed(entry), Highlight.SUCCESS)
            _sounds.trySend(Sound.SUCCESS)
            return
        }
        val doc = docFor(entry.docType) ?: return fail(notConfigured(entry.docType))
        _canUndo.value = false
        startSending()
        viewModelScope.launch {
            try {
                repository.removeQuantity(doc, entry.productHref, entry.name, entry.quantity)
                lastAdded = null
                dropEntry(entry.seq)
                show(ResultState.Removed(entry), Highlight.SUCCESS)
                _sounds.send(Sound.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _canUndo.value = lastAdded != null
                fail(e.message ?: text(R.string.error_unknown))
            } catch (e: Exception) {
                _canUndo.value = lastAdded != null
                fail(text(R.string.error_unexpected, e.message ?: e::class.java.simpleName))
            }
        }
    }

    /**
     * Синхронизация: выгружаем накопленную очередь (оприходование → списание → перемещение),
     * затем перезагружаем остатки. При ошибке очередь остаётся локально.
     */
    fun sync() {
        if (_syncing.value) return
        if (_result.value == ResultState.Loading) return
        viewModelScope.launch {
            _syncing.value = true
            startSending()
            try {
                val pending = snapshot.pending
                var sent = 0
                if (pending.isNotEmpty()) {
                    val outcome = repository.pushPending(docs, pending)
                    sent = outcome.sent
                    // Невыгружаемое (не задан документ) оставляем в очереди и сообщаем пользователю.
                    snapshot = snapshot.copy(pending = outcome.skipped)
                    offlineStore.save(snapshot)
                    _pendingCount.value = snapshot.pending.size
                    if (outcome.skipped.isNotEmpty()) {
                        fail(text(R.string.sync_skipped, skippedTypes(outcome.skipped)))
                        return@launch
                    }
                }

                val fresh = repository.loadSnapshot(
                    move,
                    docs[DocumentType.SUPPLY],
                    docs[DocumentType.LOSS],
                )
                snapshot = fresh
                offlineStore.save(snapshot)

                val summary = summaryLine(fresh)
                val message = if (sent > 0) {
                    text(R.string.sync_done, sent) + "\n" + summary
                } else {
                    text(R.string.sync_nothing) + "\n" + summary
                }
                show(ResultState.Synced(message), Highlight.SUCCESS)
                _sounds.trySend(Sound.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                fail(text(R.string.sync_aborted, e.message ?: text(R.string.error_unknown)))
            } catch (e: Exception) {
                fail(text(R.string.sync_aborted, e.message ?: e::class.java.simpleName))
            } finally {
                _syncing.value = false
            }
        }
    }

    private fun summaryLine(snapshot: OfflineSnapshot): String =
        text(
            R.string.offline_summary,
            snapshot.sourceName,
            snapshot.sourceCount,
            formatQuantity(snapshot.sourceTotal),
        ) + "\n" + text(
            R.string.offline_summary,
            snapshot.targetName,
            snapshot.targetCount,
            formatQuantity(snapshot.targetTotal),
        )

    private fun skippedTypes(lines: List<PendingLine>): String =
        lines.map { it.type }.distinct().joinToString(", ") { documentTitle(it) }

    private fun docFor(type: DocumentType): DocInfo? = when (type) {
        DocumentType.MOVE -> move
        else -> docs[type]
    }

    private fun notConfigured(type: DocumentType) =
        text(R.string.error_document_not_configured, documentTitle(type))

    private fun documentTitle(type: DocumentType): String = text(
        when (type) {
            DocumentType.MOVE -> R.string.doc_move_title
            DocumentType.SUPPLY -> R.string.doc_supply_title
            DocumentType.LOSS -> R.string.doc_loss_title
        }
    )

    private fun text(res: Int, vararg args: Any): String =
        getApplication<Application>().getString(res, *args)

    private fun recordScan(added: AddResult) {
        val seq = nextSeq++
        session.add(
            ScanEntry(
                seq = seq,
                code = added.productCode,
                name = added.productName,
                productHref = added.productHref,
                quantity = added.quantity,
                docType = added.docType,
                uom = added.uom,
            )
        )
        while (session.size > SESSION_LIMIT) session.removeAt(0)
        lastEntrySeq = seq
        publishRecent()
    }

    /** Отмена последней операции снимает ровно её строку журнала. */
    private fun forgetLastScan() {
        if (lastEntrySeq != 0L) dropEntry(lastEntrySeq)
        lastEntrySeq = 0L
        publishRecent()
    }

    private fun dropEntry(seq: Long) {
        session.removeAll { it.seq == seq }
        publishRecent()
    }

    private fun publishRecent() {
        _recent.value = session.toList()
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

    private fun isInternalBarcode(code: String) = code.trim().startsWith(INTERNAL_BARCODE_PREFIX)

    private fun parseQuantity(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 1.0
        val value = trimmed.replace(',', '.').toDoubleOrNull() ?: return null
        return value.takeIf { it > 0 && it.isFinite() }
    }

    private companion object {
        const val DEBOUNCE_MS = 1500L
        const val HIGHLIGHT_MS = 3000L
        const val SESSION_LIMIT = 200
        const val EPSILON = 1e-9
        const val INTERNAL_BARCODE_PREFIX = "2"
    }
}
