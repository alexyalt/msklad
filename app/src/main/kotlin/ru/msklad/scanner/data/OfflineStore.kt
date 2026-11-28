package ru.msklad.scanner.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Локальное хранилище офлайн-режима: снимок остатков, очередь неотправленных позиций
 * и резолвнутые документы (чтобы экран сканирования работал без сети).
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

    fun docs(): Map<DocumentType, DocInfo> = runCatching {
        val raw = prefs.getString(KEY_DOCS, null) ?: return@runCatching emptyList<DocInfo>()
        val type = object : TypeToken<List<DocInfo>>() {}.type
        gson.fromJson<List<DocInfo>>(raw, type).orEmpty()
    }.getOrDefault(emptyList()).associateBy { it.type }

    fun saveDocs(docs: Collection<DocInfo>) {
        prefs.edit().putString(KEY_DOCS, gson.toJson(docs.toList())).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SNAPSHOT).remove(KEY_DOCS).apply()
    }

    private companion object {
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_DOCS = "docs"
    }
}
