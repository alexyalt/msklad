package ru.msklad.scanner.data

/**
 * Тип документа МойСклад, с которым работает приложение.
 *
 * [apiName] — путь в API 1.2, а не бытовое название: оприходование — это `enter`
 * (`supply` — это Приёмка, другой документ), списание — `loss`, перемещение — `move`.
 */
enum class DocumentType(val apiName: String, val hasTarget: Boolean) {
    MOVE("move", true),
    SUPPLY("enter", false),
    LOSS("loss", false),
}

/**
 * Буква операции для журнала сканирований: П — перемещение, О — оприходование, С — списание.
 * Нужна, чтобы в одной ленте различать операции разных типов.
 */
val DocumentType.journalLetter: String
    get() = when (this) {
        DocumentType.MOVE -> "П"
        DocumentType.SUPPLY -> "О"
        DocumentType.LOSS -> "С"
    }

data class StoreRef(val href: String, val name: String)

/**
 * Резолвнутый документ. Для перемещения [store] — исходный склад, [target] — целевой.
 * Для оприходования и списания [store] — склад документа, [target] = null.
 */
data class DocInfo(
    val type: DocumentType,
    val id: String,
    val name: String,
    val store: StoreRef,
    val target: StoreRef? = null,
)

data class AddResult(
    val docType: DocumentType,
    val productName: String,
    val productCode: String,
    val productHref: String,
    val quantity: Double,
    val uom: String,
    val sourceAfter: Double,
    val targetAfter: Double,
    val storeBefore: Double,
)

data class ProductInfo(val name: String, val code: String, val uom: String)
