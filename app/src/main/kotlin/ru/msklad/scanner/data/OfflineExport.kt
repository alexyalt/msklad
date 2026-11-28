package ru.msklad.scanner.data

/**
 * Текстовая выгрузка очереди офлайн-сканов для ручного переноса во внешний инструмент.
 *
 * Формат строки: `X;код;кол-во;;` — `;` разделитель полей, `;;` в конце строки.
 * Порядок выгрузки совпадает с порядком документов: оприходование → списание → перемещение,
 * внутри одного документа — по коду товара.
 */
object OfflineExport {
    const val FIELD_SEPARATOR = ";"
    const val ROW_SEPARATOR = ";;"

    private val ORDER = listOf(DocumentType.SUPPLY, DocumentType.LOSS, DocumentType.MOVE)

    fun rows(pending: List<PendingLine>): List<String> =
        pending
            .sortedWith(compareBy({ ORDER.indexOf(it.type).let { i -> if (i < 0) ORDER.size else i } }, { it.code }))
            .map { line ->
                buildString {
                    append(line.type.journalLetter)
                    append(FIELD_SEPARATOR)
                    append(line.code)
                    append(FIELD_SEPARATOR)
                    append(formatQuantity(line.quantity))
                    append(ROW_SEPARATOR)
                }
            }

    fun text(pending: List<PendingLine>): String = rows(pending).joinToString("\n")
}
