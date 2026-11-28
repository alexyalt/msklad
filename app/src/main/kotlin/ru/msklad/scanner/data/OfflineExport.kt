package ru.msklad.scanner.data

/**
 * Текстовая выгрузка офлайн-сканов для ручного сохранения (например, когда связи нет совсем
 * и нужно пережить закрытие приложения).
 *
 * Формат строки: `{буква документа}√{код}√{количество}√√`, где `√` — разделитель внутри строки,
 * `√√` — конец строки. Символ выбран потому, что не встречается в названиях и кодах товаров.
 * Разделитель не зависит от локали: подходит для импорта в стороннюю программу.
 */
object OfflineExport {
    const val FIELD_SEPARATOR = "√"
    const val ROW_SEPARATOR = "√√"

    /** Порядок выгрузки совпадает с порядком отправки: поступление → списание → перемещение. */
    private val ORDER = listOf(DocumentType.SUPPLY, DocumentType.LOSS, DocumentType.MOVE)

    /**
     * Строки выгрузки по очереди неотправленных позиций. Количество берётся как есть
     * (без единицы измерения), чтобы его можно было импортировать числом.
     */
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
