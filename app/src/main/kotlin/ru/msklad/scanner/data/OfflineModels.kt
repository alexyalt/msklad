package ru.msklad.scanner.data

/** Товар в локальной копии: справочные данные + остатки на исходном и целевом складе. */
data class OfflineItem(
    val href: String,
    val name: String,
    val code: String,
    val article: String,
    val barcode: String,
    val uom: String,
    val sourceQty: Double,
    val targetQty: Double,
)

/** Позиция, накопленная в офлайн-режиме и ожидающая отправки в документ. */
data class PendingLine(
    val type: DocumentType,
    val productHref: String,
    val code: String,
    val name: String,
    val quantity: Double,
    val uom: String,
)

/** Локальный снимок остатков + очередь неотправленных позиций. */
data class OfflineSnapshot(
    val moveId: String = "",
    val moveName: String = "",
    val savedAt: Long = 0L,
    val sourceName: String = "",
    val targetName: String = "",
    val sourceCount: Int = 0,
    val sourceTotal: Double = 0.0,
    val targetCount: Int = 0,
    val targetTotal: Double = 0.0,
    val items: List<OfflineItem> = emptyList(),
    val pending: List<PendingLine> = emptyList(),
) {
    val hasData: Boolean get() = items.isNotEmpty()

    fun pendingQuantity(type: DocumentType, href: String): Double =
        pending.filter { it.type == type && it.productHref == href }.sumOf { it.quantity }

    fun find(code: String): OfflineItem? {
        val key = code.trim()
        if (key.isEmpty()) return null
        return items.firstOrNull { it.barcode.isNotEmpty() && it.barcode == key }
            ?: items.firstOrNull { it.code.isNotEmpty() && it.code == key }
            ?: items.firstOrNull { it.article.isNotEmpty() && it.article == key }
    }

    /** Доступный локальный остаток с учётом ещё не отправленных позиций по этому документу. */
    fun available(type: DocumentType, href: String): Double {
        val item = items.firstOrNull { it.href == href } ?: return 0.0
        val pending = pendingQuantity(type, href)
        return when (type) {
            DocumentType.MOVE, DocumentType.LOSS -> item.sourceQty - pending
            DocumentType.SUPPLY -> item.sourceQty + pending
        }
    }

    fun withPending(line: PendingLine): OfflineSnapshot {
        val index = pending.indexOfFirst { it.type == line.type && it.productHref == line.productHref }
        val updated = pending.toMutableList()
        if (index >= 0) {
            val old = updated[index]
            updated[index] = old.copy(quantity = old.quantity + line.quantity)
        } else {
            updated.add(line)
        }
        return copy(pending = updated)
    }

    fun withoutPending(type: DocumentType, href: String): OfflineSnapshot =
        copy(pending = pending.filterNot { it.type == type && it.productHref == href })

    fun withoutPendingType(type: DocumentType, href: String, quantity: Double): OfflineSnapshot {
        val updated = pending.toMutableList()
        val index = updated.indexOfFirst { it.type == type && it.productHref == href }
        if (index < 0) return this
        val old = updated[index]
        val left = old.quantity - quantity
        if (left > EPSILON) updated[index] = old.copy(quantity = left) else updated.removeAt(index)
        return copy(pending = updated)
    }

    companion object {
        private const val EPSILON = 1e-9
    }
}
