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

/**
 * Локальный снимок остатков + очередь неотправленных позиций.
 *
 * `items` — базовый остаток, каким он был на сервере в момент последней загрузки (плюс уже
 * отправленные операции, см. [baked]). `pending` — операции, которых на сервере ещё нет.
 * Текущий («живой») остаток = `items` + вклад всех строк `pending`, см. [sourceQty] и [targetQty].
 *
 * Так сделано намеренно: офлайн-оприходование сразу увеличивает остаток для списания
 * и перемещения, офлайн-списание его уменьшает, офлайн-перемещение перекладывает товар
 * на целевой склад. Очистка очереди или удаление позиции из журнала автоматически
 * возвращает остаток к базовому, а успешная отправка на сервер «запекает» изменение в базовый.
 */
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

    /** Вклад офлайн-операции в остаток склада, с которого работаем (Источник «ИЗ»). */
    private fun PendingLine.sourceEffect(): Double = when (type) {
        DocumentType.SUPPLY -> quantity
        DocumentType.LOSS, DocumentType.MOVE -> -quantity
    }

    /** Вклад офлайн-операции в остаток целевого склада («В»); влияет только перемещение. */
    private fun PendingLine.targetEffect(): Double =
        if (type == DocumentType.MOVE) quantity else 0.0

    /** Суммарное изменение остатка склада-источника от всех неотправленных операций. */
    fun sourceDelta(href: String): Double =
        pending.filter { it.productHref == href }.sumOf { it.sourceEffect() }

    /** Суммарное изменение остатка целевого склада от всех неотправленных операций. */
    fun targetDelta(href: String): Double =
        pending.filter { it.productHref == href }.sumOf { it.targetEffect() }

    /** Живой остаток склада-источника: базовый + неотправленные офлайн-операции. */
    fun sourceQty(href: String): Double =
        (items.firstOrNull { it.href == href }?.sourceQty ?: 0.0) + sourceDelta(href)

    /** Живой остаток целевого склада: базовый + неотправленные перемещения. */
    fun targetQty(href: String): Double =
        (items.firstOrNull { it.href == href }?.targetQty ?: 0.0) + targetDelta(href)

    /** Доступно к списанию/перемещению в офлайне с учётом всех неотправленных операций. */
    fun available(href: String): Double = sourceQty(href)

    /** Снимок с «живыми» остатками — для показа сводок, пока очередь не отправлена. */
    fun effective(): OfflineSnapshot = if (pending.isEmpty()) this else recomputed(
        items.map { item ->
            item.copy(
                sourceQty = item.sourceQty + sourceDelta(item.href),
                targetQty = item.targetQty + targetDelta(item.href),
            )
        }
    )

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

    /**
     * Переносит операцию из очереди в базовый остаток: сервер её уже принял,
     * поэтому она больше не должна учитываться как «неотправленная».
     */
    fun baked(line: PendingLine): OfflineSnapshot {
        val updated = items.map { item ->
            if (item.href != line.productHref) item else item.copy(
                sourceQty = item.sourceQty + line.sourceEffect(),
                targetQty = item.targetQty + line.targetEffect(),
            )
        }
        return recomputed(updated).withoutPendingType(line.type, line.productHref, line.quantity)
    }

    private fun recomputed(items: List<OfflineItem>): OfflineSnapshot = copy(
        items = items,
        sourceCount = items.count { it.sourceQty > EPSILON },
        sourceTotal = items.sumOf { it.sourceQty },
        targetCount = items.count { it.targetQty > EPSILON },
        targetTotal = items.sumOf { it.targetQty },
    )

    companion object {
        private const val EPSILON = 1e-9
    }
}
