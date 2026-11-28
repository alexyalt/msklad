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
import ru.msklad.scanner.data.OfflineExport
import ru.msklad.scanner.data.OfflineSnapshot
import ru.msklad.scanner.data.OfflineStore
import ru.msklad.scanner.data.PendingLine
import ru.msklad.scanner.data.ScanEntry
import ru.msklad.scanner.data.ScanJournal
import ru.msklad.scanner.data.SettingsStore
import ru.msklad.scanner.data.SyncOutcome
import ru.msklad.scanner.data.journalLetter
import ru.msklad.scanner.data.formatQuantity
import ru.msklad.scanner.data.withUom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ResultState {
    data object Idle : ResultState
    data object Loading : ResultState
    data class Success(val result: AddResult) : ResultState
    data class Removed(val entry: ScanEntry) : ResultState
    data class Error(val message: String) : ResultState
    /**
     * Итог синхронизации / служебное сообщение. Выводится в блок названия и кода (под кнопками П/О/С),
     * а не в нижнюю зону, которую частично перекрывает журнал. [detail] — одна строка под основным текстом.
     */
    data class Synced(val message: String, val detail: String = "", val error: Boolean = false) : ResultState
}

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

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline

    private val _docType = MutableStateFlow(DocumentType.MOVE)
    val docType: StateFlow<DocumentType> = _docType

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    /** Время последней синхронизации (загрузка остатков или выгрузка очереди), 0 — не было. */
    private val _lastSyncAt = MutableStateFlow(0L)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt

    /** Ключи `тип|href` строк очереди, которые сервер отверг при последней выгрузке (в журнале — красным). */
    private val _failedKeys = MutableStateFlow<Set<String>>(emptySet())
    val failedKeys: StateFlow<Set<String>> = _failedKeys

    private val _sounds = Channel<Sound>(Channel.BUFFERED)
    val sounds = _sounds.receiveAsFlow()

    private val _clearInput = Channel<Unit>(Channel.BUFFERED)
    val clearInput = _clearInput.receiveAsFlow()

    private val _recent = MutableStateFlow<List<ScanEntry>>(emptyList())
    val recent: StateFlow<List<ScanEntry>> = _recent

    private var lastScanCode: String? = null
    private var lastScanAt = 0L
    private var resetJob: Job? = null
    private lateinit var journal: ScanJournal
    /** Журнал: самые свежие записи первыми. */
    private val session = mutableListOf<ScanEntry>()
    private var nextSeq = 1L
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
        // Остатки, оставшиеся от прошлых версий в онлайне, не нужны: при включении офлайна они грузятся заново.
        if (!settings.offlineMode && snapshot.pending.isEmpty() && snapshot.hasData) {
            offlineStore.clearRemnants()
            snapshot = offlineStore.load()
        }
        _pendingCount.value = snapshot.pending.size
        _lastSyncAt.value = offlineStore.lastSyncAt()
        journal = ScanJournal(getApplication())
        session.clear()
        session.addAll(journal.load())
        nextSeq = maxOf(journal.nextSeq(), (session.maxOfOrNull { it.seq } ?: 0L) + 1)
        publishRecent()
    }

    fun setScannerOn(on: Boolean) { _scannerOn.value = on }

    fun setInputMode(mode: InputMode) { _inputMode.value = mode }

    fun setDocType(type: DocumentType) { _docType.value = type }

    /**
     * Переключатель офлайна на экране сканирования.
     *
     * Включение: сразу загружаем свежие остатки с сервера (переключатель уже стоит «вкл», сканы
     * заблокированы состоянием Loading). Без связи — если в памяти осталась прежняя копия (например,
     * с неотправленными строками), работаем с ней; если копии нет, офлайн не включается.
     *
     * Выключение: сначала выгружаем накопленную очередь (иначе онлайн-сканы уйдут в документ раньше
     * офлайновых и остатки разойдутся). Обрыв связи — офлайн остаётся, кнопка синхронизации продолжит
     * с оставшихся строк. Строки, отвергнутые сервером, остаются в очереди и в журнале (жирные, красные):
     * их можно удалить из журнала или очистить журнал, после чего офлайн выключается. После полной
     * выгрузки локальные остатки удаляются, чтобы данные не перемешались со следующей сессией.
     */
    fun setOffline(on: Boolean) {
        if (on == _offline.value) return
        if (_syncing.value || _result.value == ResultState.Loading) return
        if (on) enableOffline() else disableOffline()
    }

    private fun enableOffline() {
        _offline.value = true
        viewModelScope.launch {
            _syncing.value = true
            startSending()
            try {
                val fresh = repository.loadSnapshot(move, docs[DocumentType.SUPPLY], docs[DocumentType.LOSS])
                // Неотправленные строки (если остались) не теряем.
                snapshot = fresh.copy(pending = offlineStore.load().pending)
                offlineStore.save(snapshot)
                _pendingCount.value = snapshot.pending.size
                applyOffline(true)
                val title = text(R.string.offline_loaded, timeText(stampSync()))
                saveSyncResult(listOf(title) + summaryLines(snapshot))
                show(ResultState.Synced(title + "\n" + summaryLines(snapshot).joinToString("\n")), Highlight.SUCCESS)
                _sounds.trySend(Sound.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = reasonOf(e)
                snapshot = offlineStore.load()
                if (snapshot.hasData) {
                    applyOffline(true)
                    val time = timeText(snapshot.savedAt)
                    show(
                        ResultState.Synced(text(R.string.offline_load_failed_old, reason, time), error = true),
                        Highlight.ERROR,
                    )
                    _sounds.trySend(Sound.ERROR)
                } else {
                    _offline.value = false
                    syncFail(text(R.string.offline_load_failed, reason))
                }
            } finally {
                _syncing.value = false
            }
        }
    }

    private fun disableOffline() {
        snapshot = offlineStore.load()
        if (snapshot.pending.isEmpty()) {
            goOnline(sent = 0)
            return
        }
        viewModelScope.launch {
            _syncing.value = true
            startSending()
            try {
                val outcome = pushQueue()
                if (snapshot.pending.isEmpty()) {
                    goOnline(outcome.sent)
                } else {
                    reportLeftovers(outcome)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val lines = listOf(text(R.string.sync_auto_failed, reasonOf(e)), pendingLeft())
                stampSync()
                saveSyncResult(lines)
                syncFail(lines[0], lines[1])
            } finally {
                _syncing.value = false
            }
        }
    }

    /** Очередь пуста: удаляем локальные остатки и переходим в онлайн. */
    private fun goOnline(sent: Int) {
        offlineStore.clearRemnants()
        snapshot = offlineStore.load()
        _pendingCount.value = 0
        _failedKeys.value = emptySet()
        markAllSent()
        applyOffline(false)
        val message = if (sent > 0) text(R.string.sync_auto_done, sent) else text(R.string.offline_off_nothing)
        if (sent > 0) {
            stampSync()
            saveSyncResult(listOf(message, text(R.string.offline_remnants_cleared)))
        }
        show(ResultState.Synced(message, text(R.string.offline_remnants_cleared)), Highlight.SUCCESS)
        _sounds.trySend(Sound.SUCCESS)
    }

    /** Часть строк не ушла (не задан документ / отвергнута сервером): офлайн остаётся, показываем какие. */
    private fun reportLeftovers(outcome: SyncOutcome) {
        val lines = mutableListOf<String>()
        if (outcome.sent > 0) lines.add(text(R.string.sync_done, outcome.sent))
        if (outcome.skipped.isNotEmpty()) lines.add(text(R.string.sync_skipped, skippedTypes(outcome.skipped)))
        if (outcome.failed.isNotEmpty()) {
            lines.add(
                text(
                    R.string.sync_failed_lines,
                    outcome.failed.size,
                    outcome.failed.joinToString(", ") { it.line.type.journalLetter + " " + it.line.code },
                )
            )
        }
        val detail = outcome.failed.firstOrNull()?.reason?.let { text(R.string.sync_failed_reason, it) }
            ?: pendingLeft()
        stampSync()
        saveSyncResult(lines + detail)
        syncFail(lines.joinToString("\n"), detail)
    }

    private fun applyOffline(on: Boolean) {
        _offline.value = on
        SettingsStore(getApplication()).setOfflineMode(on)
    }

    /**
     * Выгрузка очереди с сохранением прогресса после каждой строки (повтор не задублирует уже отправленное).
     * Отправленные строки снимают жирное выделение в журнале; отвергнутые сервером — помечаются красным.
     * Исключение наружу — только при потере связи/перегрузке сервера (см. [DocumentRepository.pushPending]).
     */
    private suspend fun pushQueue(): SyncOutcome {
        val pending = snapshot.pending
        if (pending.isEmpty()) return SyncOutcome(0, emptyList())
        _failedKeys.value = emptySet()
        val outcome = repository.pushPending(docs, pending, onSent = { line ->
            snapshot = snapshot.baked(line)
            offlineStore.save(snapshot)
            _pendingCount.value = snapshot.pending.size
            markSent(line.type, line.productHref)
        })
        _failedKeys.value = outcome.failed.map { key(it.line.type, it.line.productHref) }.toSet()
        return outcome
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
        val available = snapshot.available(item.href)
        if (type != DocumentType.SUPPLY && quantity > available + EPSILON) {
            return fail(text(R.string.offline_insufficient, formatQuantity(maxOf(available, 0.0))))
        }

        // Остаток берём «живой»: с учётом всех ещё не отправленных офлайн-операций любого типа,
        // иначе офлайн-оприходование не видно списанию и перемещению, а офлайн-перемещение — списанию.
        val sourceBefore = snapshot.sourceQty(item.href)
        val targetBefore = snapshot.targetQty(item.href)
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

        val added = when (type) {
            DocumentType.MOVE -> AddResult(
                docType = type,
                productName = item.name,
                productCode = item.code,
                productHref = item.href,
                quantity = quantity,
                uom = item.uom,
                sourceAfter = sourceBefore - quantity,
                targetAfter = targetBefore + quantity,
                storeBefore = sourceBefore,
            )

            DocumentType.LOSS -> AddResult(
                docType = type,
                productName = item.name,
                productCode = item.code,
                productHref = item.href,
                quantity = quantity,
                uom = item.uom,
                sourceAfter = sourceBefore - quantity,
                targetAfter = 0.0,
                storeBefore = sourceBefore,
            )

            DocumentType.SUPPLY -> AddResult(
                docType = type,
                productName = item.name,
                productCode = item.code,
                productHref = item.href,
                quantity = quantity,
                uom = item.uom,
                sourceAfter = sourceBefore + quantity,
                targetAfter = 0.0,
                storeBefore = sourceBefore,
            )
        }
        onAdded(added)
    }

    private fun onAdded(added: AddResult) {
        recordScan(added)
        show(ResultState.Success(added), Highlight.SUCCESS)
        _sounds.trySend(Sound.SUCCESS)
        _clearInput.trySend(Unit)
    }

    /**
     * Удаляет из документа одну строку журнала — то количество, которое дало это сканирование.
     * Если это количество ещё в офлайн-очереди — снимаем его локально (в любом режиме), иначе удаляем
     * с сервера (только онлайн). Удалить из очереди всю позицию целиком нельзя — только количество строки.
     */
    fun removeScanned(entry: ScanEntry) {
        if (session.none { it.seq == entry.seq }) return
        if (_result.value == ResultState.Loading) return
        val stored = offlineStore.load()
        val queued = stored.pendingQuantity(entry.docType, entry.productHref)
        // Офлайн-скан (жирный) снимаем только локально: на сервер он не уходил. Если очередь уже частично
        // пуста (строку отвергли и она была удалена раньше) — снимаем сколько есть.
        if (entry.offline || queued >= entry.quantity - EPSILON) {
            snapshot = stored.withoutPendingType(entry.docType, entry.productHref, minOf(entry.quantity, queued))
            offlineStore.save(snapshot)
            _pendingCount.value = snapshot.pending.size
            if (snapshot.pendingQuantity(entry.docType, entry.productHref) <= EPSILON) {
                _failedKeys.value = _failedKeys.value - key(entry.docType, entry.productHref)
            }
            dropEntry(entry.seq)
            show(ResultState.Removed(entry), Highlight.SUCCESS)
            _sounds.trySend(Sound.SUCCESS)
            return
        }
        if (_offline.value) return syncFail(text(R.string.remove_offline_already_sent))
        val doc = docFor(entry.docType) ?: return fail(notConfigured(entry.docType))
        startSending()
        viewModelScope.launch {
            try {
                repository.removeQuantity(doc, entry.productHref, entry.name, entry.quantity)
                dropEntry(entry.seq)
                show(ResultState.Removed(entry), Highlight.SUCCESS)
                _sounds.send(Sound.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                fail(e.message ?: text(R.string.error_unknown))
            } catch (e: Exception) {
                fail(text(R.string.error_unexpected, e.message ?: e::class.java.simpleName))
            }
        }
    }

    /**
     * Очистка журнала последних операций вместе с внутренней памятью офлайн-сканов (очередью
     * неотправленных строк). Нужна, чтобы сбросить ошибки синхронизации (например, в офлайне насканировали
     * больше, чем есть на самом деле) и снова иметь возможность выключить офлайн. Документы в МойСклад
     * и загруженные остатки не меняются.
     */
    fun clearJournal() {
        if (_syncing.value || _result.value == ResultState.Loading) return
        val dropped = offlineStore.load().pending.size
        offlineStore.clearPending()
        snapshot = offlineStore.load()
        _pendingCount.value = 0
        _failedKeys.value = emptySet()
        session.clear()
        nextSeq = 1L
        journal.clear()
        publishRecent()
        val detail = if (dropped > 0) text(R.string.journal_cleared_pending, dropped) else ""
        show(ResultState.Synced(text(R.string.journal_cleared), detail), Highlight.NONE)
    }

    /**
     * Текст для ручного сохранения офлайн-сканов (когда связи нет совсем):
     * `{буква документа};{код};{количество};;` на строку.
     */
    fun exportText(): String = OfflineExport.text(offlineStore.load().pending)

    /** Название документа в нижнем регистре для диалога подтверждения. */
    fun documentNameLower(type: DocumentType): String = text(
        when (type) {
            DocumentType.MOVE -> R.string.doc_genitive_move
            DocumentType.SUPPLY -> R.string.doc_genitive_supply
            DocumentType.LOSS -> R.string.doc_genitive_loss
        }
    )

    fun quantityText(entry: ScanEntry): String = withUom(entry.quantity, entry.uom)

    /**
     * Ручная синхронизация (стрелки, только в офлайне): выгружаем очередь (оприходование → списание →
     * перемещение), затем перезагружаем остатки — офлайн продолжается уже с актуальными данными.
     * Обрыв связи — очередь остаётся, повторное нажатие продолжит с оставшихся строк.
     */
    fun sync() {
        if (_syncing.value) return
        if (_result.value == ResultState.Loading) return
        viewModelScope.launch {
            _syncing.value = true
            startSending()
            try {
                val outcome = pushQueue()
                if (snapshot.pending.isNotEmpty()) {
                    reportLeftovers(outcome)
                    return@launch
                }
                markAllSent()
                val fresh = repository.loadSnapshot(
                    move,
                    docs[DocumentType.SUPPLY],
                    docs[DocumentType.LOSS],
                )
                snapshot = fresh.copy(pending = offlineStore.load().pending)
                offlineStore.save(snapshot)
                val time = timeText(stampSync())
                val title = if (outcome.sent > 0) {
                    text(R.string.sync_done, outcome.sent) + " · " + text(R.string.offline_loaded, time)
                } else {
                    text(R.string.sync_nothing) + " · " + time
                }
                val summary = summaryLines(snapshot)
                saveSyncResult(listOf(title) + summary)
                show(ResultState.Synced(title + "\n" + summary.joinToString("\n")), Highlight.SUCCESS)
                _sounds.trySend(Sound.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val lines = listOf(text(R.string.sync_aborted, reasonOf(e)), pendingLeft())
                stampSync()
                saveSyncResult(lines)
                syncFail(lines[0], lines[1])
            } finally {
                _syncing.value = false
            }
        }
    }

    private fun summaryLines(snapshot: OfflineSnapshot): List<String> {
        // Пока очередь не отправлена, показываем «живые» остатки: они уже включают офлайн-операции.
        val live = snapshot.effective()
        return listOf(
            text(
                R.string.offline_summary,
                live.sourceName,
                live.sourceCount,
                formatQuantity(live.sourceTotal),
            ),
            text(
                R.string.offline_summary,
                live.targetName,
                live.targetCount,
                formatQuantity(live.targetTotal),
            ),
        )
    }

    private fun pendingLeft(): String = text(R.string.sync_pending_left, snapshot.pending.size)

    private fun stampSync(): Long {
        val now = System.currentTimeMillis()
        _lastSyncAt.value = now
        return now
    }

    /** Итог синхронизации сохраняется и показывается также на экране настроек. */
    private fun saveSyncResult(lines: List<String>) {
        offlineStore.saveLastSync(_lastSyncAt.value, lines.filter { it.isNotBlank() })
    }

    private fun timeText(at: Long): String =
        if (at <= 0) "—" else SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(at))

    private fun reasonOf(e: Exception): String =
        (e as? ApiException)?.message ?: e.message ?: e::class.java.simpleName

    private fun syncFail(message: String, detail: String = "") {
        show(ResultState.Synced(message, detail, error = true), Highlight.ERROR)
        _sounds.trySend(Sound.ERROR)
    }

    private fun key(type: DocumentType, href: String) = type.name + "|" + href

    /** Строки товара ушли на сервер — снимаем жирное выделение с его офлайн-сканов. */
    private fun markSent(type: DocumentType, href: String) {
        var changed = false
        for (i in session.indices) {
            val e = session[i]
            if (e.offline && e.docType == type && e.productHref == href) {
                session[i] = e.copy(offline = false)
                changed = true
            }
        }
        if (changed) publishRecent()
    }

    private fun markAllSent() {
        if (session.none { it.offline }) return
        for (i in session.indices) session[i] = session[i].copy(offline = false)
        publishRecent()
    }

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
        // Свежие записи — в начало списка; при переполнении уходит самая старая (в конце).
        session.add(
            0,
            ScanEntry(
                seq = seq,
                code = added.productCode,
                name = added.productName,
                productHref = added.productHref,
                quantity = added.quantity,
                docType = added.docType,
                uom = added.uom,
                offline = _offline.value,
            )
        )
        while (session.size > ScanJournal.LIMIT) session.removeAt(session.lastIndex)
        publishRecent()
    }

    private fun dropEntry(seq: Long) {
        session.removeAll { it.seq == seq }
        publishRecent()
    }

    private fun publishRecent() {
        if (::journal.isInitialized) journal.save(session, nextSeq)
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
        const val EPSILON = 1e-9
        const val INTERNAL_BARCODE_PREFIX = "2"
    }
}
