package ru.msklad.scanner.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Локальное хранилище офлайн-режима: снимок остатков, очередь неотправленных позиций
 * и резолвнутые документы (чтобы экран сканирования работал без сети).
 *
 * Дополнительно хранит «результат последней синхронизации» — сводку строк и её время.
 * Сводка переживает очистку локальных остатков, поэтому экран настроек всегда показывает,
 * что и когда загружалось последним.
 */
class OfflineStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("offline", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): OfflineSnapshot = runCatching {
        prefs.getString(KEY_SNAPSHOT, null)?.let { gson.fromJson(it, OfflineSnapshot::class.java) }
    }.getOrNull() ?: OfflineSnapshot()

    fun save(snapshot: OfflineSnapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, gson.toJson(snapshot)).apply()
    }

    /**
     * Удаляет локальные остатки: товарный снимок и очередь неотправленных позиций.
     * Документы и сводка последней синхронизации остаются — экран настроек продолжает
     * показывать результат. Нужно после успешной синхронизации, чтобы данные не перемешивались
     * при следующем включении офлайна.
     */
    fun clearRemnants() {
        val snapshot = load()
        save(
            snapshot.copy(
                items = emptyList(),
                pending = emptyList(),
                sourceCount = 0,
                sourceTotal = 0.0,
                targetCount = 0,
                targetTotal = 0.0,
            )
        )
    }

    /** Очистка очереди офлайн-сканов (кнопка «Очистить журнал»). */
    fun clearPending() {
        val snapshot = load()
        if (snapshot.pending.isEmpty()) return
        save(snapshot.copy(pending = emptyList()))
    }

    fun docs(): Map<DocumentType, DocInfo> = runCatching {
        val raw = prefs.getString(KEY_DOCS, null) ?: return@runCatching emptyList<DocInfo>()
        val type = object : TypeToken<List<DocInfo>>() {}.type
        gson.fromJson<List<DocInfo>>(raw, type).orEmpty()
    }.getOrDefault(emptyList()).associateBy { it.type }

    fun saveDocs(docs: Collection<DocInfo>) {
        prefs.edit().putString(KEY_DOCS, gson.toJson(docs.toList())).apply()
    }

    /** Время последней синхронизации (загрузки остатков или выгрузки очереди), мс; 0 — не было. */
    fun lastSyncAt(): Long = prefs.getLong(KEY_LAST_SYNC_AT, 0L)

    /** Сводка последней синхронизации — те же строки, что видит экран настроек. */
    fun lastSyncLines(): List<String> = runCatching {
        val raw = prefs.getString(KEY_LAST_SYNC_LINES, null) ?: return@runCatching emptyList<String>()
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(raw, type).orEmpty()
    }.getOrDefault(emptyList())

    fun saveLastSync(at: Long, lines: List<String>) {
        prefs.edit()
            .putLong(KEY_LAST_SYNC_AT, at)
            .putString(KEY_LAST_SYNC_LINES, gson.toJson(lines))
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_SNAPSHOT)
            .remove(KEY_DOCS)
            .remove(KEY_LAST_SYNC_AT)
            .remove(KEY_LAST_SYNC_LINES)
            .apply()
    }

    private companion object {
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_DOCS = "docs"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_SYNC_LINES = "last_sync_lines"
    }
}
