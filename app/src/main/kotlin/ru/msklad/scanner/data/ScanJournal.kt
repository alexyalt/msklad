package ru.msklad.scanner.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
    /**
     * Скан сделан в офлайне и ещё не передан в МойСклад (в журнале — жирным). Gson для старых записей
     * без поля оставляет false — это корректно: до 1.6.0 признак не вёлся.
     */
    val offline: Boolean = false,
)

/**
 * Журнал последних операций, сохраняется в памяти устройства (SharedPreferences + Gson) и переживает
 * перезапуск приложения. Порядок хранения — самые свежие первыми; при переполнении вытесняется самая
 * старая запись. Очищается только явно, кнопкой «Очистить журнал».
 */
class ScanJournal(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("journal", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): List<ScanEntry> = runCatching {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return@runCatching emptyList<ScanEntry>()
        val type = object : TypeToken<List<ScanEntry>>() {}.type
        gson.fromJson<List<ScanEntry>>(raw, type).orEmpty()
    }.getOrDefault(emptyList())
        // Gson не вызывает конструктор Kotlin: поля без значения в JSON приходят null — отбрасываем битые записи.
        .filter { runCatching { it.docType.name; it.code.length; it.name.length; it.uom.length }.isSuccess }
        .sortedByDescending { it.seq }
        .take(LIMIT)

    fun nextSeq(): Long = prefs.getLong(KEY_NEXT_SEQ, 1L)

    fun save(entries: List<ScanEntry>, nextSeq: Long) {
        prefs.edit()
            .putString(KEY_ENTRIES, gson.toJson(entries.take(LIMIT)))
            .putLong(KEY_NEXT_SEQ, nextSeq)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).putLong(KEY_NEXT_SEQ, 1L).apply()
    }

    companion object {
        const val LIMIT = 200
        private const val KEY_ENTRIES = "entries"
        private const val KEY_NEXT_SEQ = "next_seq"
    }
}
