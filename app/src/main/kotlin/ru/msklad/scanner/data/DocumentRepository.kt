package ru.msklad.scanner.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import ru.msklad.scanner.api.ApiException
import ru.msklad.scanner.api.MoySkladApi
import ru.msklad.scanner.api.apiCall
import ru.msklad.scanner.api.href
import ru.msklad.scanner.api.metaType
import ru.msklad.scanner.api.obj
import ru.msklad.scanner.api.rows
import ru.msklad.scanner.api.str

/** Итог выгрузки офлайн-очереди: сколько строк ушло и что пришлось пропустить. */
data class SyncOutcome(val sent: Int, val skipped: List<PendingLine>)

class DocumentRepository(private val api: MoySkladApi) {

    private var uomCache: Map<String, String>? = null

    // ---------------------------------------------------------------- документы

    suspend fun findDocument(type: DocumentType, numberOrId: String): DocInfo = apiCall {
        val input = numberOrId.trim()
        if (input.isEmpty()) throw emptyMessage(type)
        val doc = if (UUID_REGEX.matches(input)) {
            requestDocument(type, input.lowercase())
        } else {
            val found = requestList(type, "name=$input").rows().firstOrNull()
                ?: throw notFound(type)
            // Строка списка может не содержать раскрытых складов — дочитываем документ целиком.
            requestDocument(type, found.str("id").orEmpty())
        }
        toDocInfo(type, doc)
    }

    /**
     * У `move` склады раскрываются через expand, у `enter` и `loss` expand может не поддерживаться.
     * На 400 повторяем запрос без раскрытия — имя склада тогда дочитаем отдельно.
     */
    private suspend fun requestDocument(type: DocumentType, id: String): JsonObject = try {
        api.document(type.apiName, id, expand(type))
    } catch (e: retrofit2.HttpException) {
        when (e.code()) {
            404 -> throw notFound(type)
            400 -> api.document(type.apiName, id, null)
            else -> throw e
        }
    }

    private suspend fun requestList(type: DocumentType, filter: String): JsonObject = try {
        api.documents(type.apiName, filter, expand(type))
    } catch (e: retrofit2.HttpException) {
        if (e.code() == 400) api.documents(type.apiName, filter, null) else throw e
    }

    private suspend fun toDocInfo(type: DocumentType, doc: JsonObject): DocInfo {
        val id = doc.str("id")
        if (id.isNullOrBlank()) throw notFound(type)
        val name = doc.str("name").orEmpty()
        return if (type.hasTarget) {
            DocInfo(type, id, name, doc.store("sourceStore", type), doc.store("targetStore", type))
        } else {
            DocInfo(type, id, name, doc.store("store", type))
        }
    }

    private fun expand(type: DocumentType): String =
        if (type.hasTarget) "sourceStore,targetStore" else "store"

    // ---------------------------------------------------------------- позиции документа

    suspend fun addItem(doc: DocInfo, code: String, quantity: Double): AddResult = apiCall {
        addToDocument(doc, findProduct(code.trim()), quantity)
    }

    suspend fun addItemByHref(doc: DocInfo, productHref: String, quantity: Double): AddResult = apiCall {
        // Фильтр id= принимает только UUID, полный href даёт 400 (ошибка 1014).
        val product = api.assortment(filter = "id=${uuidOf(productHref)}").rows().firstOrNull()
            ?: throw ApiException("Товар не найден на сервере МойСклад")
        addToDocument(doc, product, quantity)
    }

    private suspend fun addToDocument(doc: DocInfo, product: JsonObject, quantity: Double): AddResult {
        val type = product.metaType()
        if (type != "product" && type != "variant") {
            throw ApiException("Позиция «${product.str("name").orEmpty()}» не является товаром или модификацией")
        }
        val productHref = product.href() ?: throw ApiException("У товара нет ссылки meta")
        val uom = uomName(product.obj("uom"))

        val docJson = api.document(doc.type.apiName, doc.id, "positions")
        val applied = docJson.get("applicable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        // В проведённом документе его позиции уже учтены в остатках, в непроведённом — нет.
        val notYetInStock = if (applied) 0.0 else quantityInDocument(docJson, productHref)

        val storeStock = stock(type, productHref, doc.store.href)
        val sourceAfter: Double
        val targetAfter: Double
        val storeBefore: Double = when (doc.type) {
            DocumentType.MOVE -> storeStock - notYetInStock
            DocumentType.LOSS -> storeStock - notYetInStock
            DocumentType.SUPPLY -> storeStock + notYetInStock
        }

        when (doc.type) {
            DocumentType.MOVE -> {
                if (quantity > storeBefore + EPSILON) throw insufficient(storeBefore)
                val target = doc.target ?: throw ApiException("В перемещении не указан целевой склад")
                val targetStock = stock(type, productHref, target.href) + notYetInStock
                sourceAfter = storeBefore - quantity
                targetAfter = targetStock + quantity
            }

            DocumentType.LOSS -> {
                if (quantity > storeBefore + EPSILON) throw insufficient(storeBefore)
                sourceAfter = storeBefore - quantity
                targetAfter = 0.0
            }

            DocumentType.SUPPLY -> {
                sourceAfter = storeBefore + quantity
                targetAfter = 0.0
            }
        }

        val positions = mergePosition(docJson, product, productHref, quantity)
        api.updateDocument(doc.type.apiName, doc.id, JsonObject().apply { add("positions", positions) })

        return AddResult(
            docType = doc.type,
            productName = product.str("name").orEmpty(),
            productCode = product.str("code").orEmpty(),
            productHref = productHref,
            quantity = quantity,
            uom = uom,
            sourceAfter = sourceAfter,
            targetAfter = targetAfter,
            storeBefore = storeBefore,
        )
    }

    suspend fun undoAdd(doc: DocInfo, added: AddResult) =
        removeQuantity(doc, added.productHref, added.productName, added.quantity)

    suspend fun removeQuantity(doc: DocInfo, productHref: String, productName: String, quantity: Double) = apiCall {
        val document = api.document(doc.type.apiName, doc.id, "positions")
        val positions = JsonArray()
        var found = false
        var left = quantity
        for (position in document.obj("positions")?.rows().orEmpty()) {
            val copy = position.deepCopy()
            // Один товар может стоять в документе несколькими строками: вычитаем по порядку,
            // пока не израсходуем отменяемое количество.
            if (left > EPSILON && copy.obj("assortment")?.href() == productHref) {
                found = true
                val current = copy.get("quantity")?.asDouble ?: 0.0
                val rest = current - left
                left -= current
                if (rest > EPSILON) {
                    copy.addProperty("quantity", rest)
                } else {
                    continue
                }
            }
            positions.add(copy)
        }
        if (!found) throw ApiException("Позиции «$productName» уже нет в документе")
        api.updateDocument(doc.type.apiName, doc.id, JsonObject().apply { add("positions", positions) })
    }

    suspend fun lookupProduct(code: String): ProductInfo = apiCall {
        val product = findProduct(code.trim())
        ProductInfo(
            name = product.str("name").orEmpty(),
            code = product.str("code").orEmpty(),
            uom = uomName(product.obj("uom")),
        )
    }

    // ---------------------------------------------------------------- офлайн-снимок

    suspend fun loadSnapshot(move: DocInfo, supply: DocInfo?, loss: DocInfo?): OfflineSnapshot = apiCall {
        val target = move.target ?: throw ApiException("В перемещении не указан целевой склад")
        val sourceRows = stockRows(move.store.href)
        val targetRows = stockRows(target.href)

        // Позиции документов корректировки тоже держим в индексе: товар может быть в документе,
        // но уже отсутствовать в остатках склада.
        val docRows = LinkedHashMap<String, Double>()
        for (doc in listOfNotNull(supply, loss)) {
            val positions = try {
                documentPositions(doc.type, doc.id)
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 404) emptyMap() else throw e
            }
            positions.forEach { (href, qty) -> docRows[href] = (docRows[href] ?: 0.0) + qty }
        }

        val hrefs = LinkedHashSet<String>()
        hrefs.addAll(sourceRows.keys)
        hrefs.addAll(targetRows.keys)
        hrefs.addAll(docRows.keys)
        val info = assortmentInfo(hrefs.toList())

        val items = hrefs.map { href ->
            val source = sourceRows[href]
            val targetRow = targetRows[href]
            val known = info[href]
            OfflineItem(
                href = href,
                name = known?.name?.takeIf(String::isNotBlank) ?: source?.name ?: targetRow?.name.orEmpty(),
                code = known?.code?.takeIf(String::isNotBlank) ?: source?.code ?: targetRow?.code.orEmpty(),
                article = known?.article?.takeIf(String::isNotBlank)
                    ?: source?.article ?: targetRow?.article.orEmpty(),
                barcode = known?.barcode.orEmpty(),
                uom = known?.uom?.takeIf(String::isNotBlank) ?: source?.uom ?: targetRow?.uom.orEmpty(),
                sourceQty = source?.qty ?: 0.0,
                targetQty = targetRow?.qty ?: 0.0,
            )
        }

        OfflineSnapshot(
            moveId = move.id,
            moveName = move.name,
            savedAt = System.currentTimeMillis(),
            sourceName = move.store.name,
            targetName = target.name,
            sourceCount = items.count { it.sourceQty > EPSILON },
            sourceTotal = items.sumOf { it.sourceQty },
            targetCount = items.count { it.targetQty > EPSILON },
            targetTotal = items.sumOf { it.targetQty },
            items = items,
        )
    }

    private data class StockRow(
        val name: String,
        val code: String,
        val article: String,
        val uom: String,
        val qty: Double,
    )

    /** Все позиции с ненулевым/нулевым остатком по складу, постранично, с суммированием по товару. */
    private suspend fun stockRows(storeHref: String): Map<String, StockRow> {
        val result = LinkedHashMap<String, StockRow>()
        var offset = 0
        while (true) {
            val rows = api.stock(filter = "store=$storeHref", offset = offset, limit = PAGE_LIMIT).rows()
            for (row in rows) {
                // В отчёте report/stock/all строка описывает товар напрямую: в meta.href лежит
                // ссылка на entity/product (или entity/variant), а поля assortment в ответе нет.
                // Пока здесь читали только assortment, все строки пропускались и остатки
                // приходили нулевыми — снимок загружался «пустым» без единой ошибки.
                val href = row.obj("assortment")?.href() ?: row.href() ?: continue
                val qty = row.stockQuantity()
                val previous = result[href]
                result[href] = StockRow(
                    name = row.str("name") ?: previous?.name.orEmpty(),
                    code = row.str("code") ?: previous?.code.orEmpty(),
                    article = row.str("article") ?: previous?.article.orEmpty(),
                    uom = uomName(row.obj("uom")).ifBlank { previous?.uom.orEmpty() },
                    qty = (previous?.qty ?: 0.0) + qty,
                )
            }
            if (rows.size < PAGE_LIMIT) break
            offset += PAGE_LIMIT
            if (offset >= MAX_PAGES * PAGE_LIMIT) break
        }
        return result
    }

    /** Остаток из строки отчёта: обычно `stock`, но при отдельных режимах приходит объект с `value`. */
    private fun JsonObject.stockQuantity(): Double {
        val raw = get("stock") ?: get("quantity") ?: return 0.0
        if (raw.isJsonPrimitive) return raw.asDouble
        if (!raw.isJsonObject) return 0.0
        val nested = raw.asJsonObject.get("value") ?: return 0.0
        return if (nested.isJsonPrimitive) nested.asDouble else 0.0
    }

    private suspend fun documentPositions(type: DocumentType, id: String): Map<String, Double> {
        val doc = try {
            api.document(type.apiName, id, "positions.assortment")
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 400) api.document(type.apiName, id, "positions") else throw e
        }
        val result = LinkedHashMap<String, Double>()
        doc.obj("positions")?.rows().orEmpty().forEach { position ->
            val href = position.obj("assortment")?.href() ?: return@forEach
            val qty = position.get("quantity")?.asDouble ?: 0.0
            result[href] = (result[href] ?: 0.0) + qty
        }
        return result
    }

    private data class ItemInfo(
        val name: String,
        val code: String,
        val article: String,
        val barcode: String,
        val uom: String,
    )

    /**
     * Название, код, артикул, штрихкод и единица измерения по ссылкам на товары.
     * Запрашиваем батчами по фильтру id=..., чтобы не тянуть весь справочник.
     */
    private suspend fun assortmentInfo(hrefs: List<String>): Map<String, ItemInfo> {
        if (hrefs.isEmpty()) return emptyMap()
        val result = HashMap<String, ItemInfo>()
        for (chunk in hrefs.chunked(ID_BATCH)) {
            val rows = try {
                // Только UUID, условия разделяются «;» (через «,» и с полным href API отвечает 400).
                api.assortment(filter = chunk.joinToString(";") { "id=${uuidOf(it)}" }, limit = ID_BATCH).rows()
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 400) continue else throw e
            }
            for (row in rows) {
                val href = row.href() ?: continue
                val barcode = row.get("barcodes")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.firstOrNull { it.isJsonObject }
                    ?.asJsonObject
                    ?.let { code -> BARCODE_FIELDS.firstNotNullOfOrNull { code.str(it)?.takeIf(String::isNotBlank) } }
                result[href] = ItemInfo(
                    name = row.str("name").orEmpty(),
                    code = row.str("code").orEmpty(),
                    article = row.str("article").orEmpty(),
                    barcode = barcode.orEmpty(),
                    uom = uomName(row.obj("uom")),
                )
            }
        }
        return result
    }

    // ---------------------------------------------------------------- выгрузка офлайн-очереди

    /**
     * Отправляет накопленные в офлайне позиции в документы в порядке
     * оприходование → списание → перемещение. Ошибка прерывает выгрузку.
     */
    suspend fun pushPending(
        docs: Map<DocumentType, DocInfo>,
        pending: List<PendingLine>,
        onProgress: (PendingLine) -> Unit = {},
    ): SyncOutcome {
        var sent = 0
        val skipped = mutableListOf<PendingLine>()
        for (type in SYNC_ORDER) {
            val lines = pending.filter { it.type == type }
            if (lines.isEmpty()) continue
            val doc = docs[type]
            if (doc == null) {
                skipped.addAll(lines)
                continue
            }
            for (line in lines) {
                onProgress(line)
                if (line.productHref.isNotBlank()) {
                    addItemByHref(doc, line.productHref, line.quantity)
                } else {
                    addItem(doc, line.code, line.quantity)
                }
                sent++
            }
        }
        return SyncOutcome(sent, skipped)
    }

    // ---------------------------------------------------------------- служебное

    /** UUID сущности из href (`…/entity/product/<uuid>?expand=…` → `<uuid>`); UUID возвращается как есть. */
    private fun uuidOf(hrefOrId: String): String =
        hrefOrId.substringBefore('?').trimEnd('/').substringAfterLast('/')

    private suspend fun findProduct(code: String): JsonObject {
        // Только точные фильтры: параметр search отдаёт частичные совпадения и приводит
        // к добавлению чужого товара.
        for (field in SEARCH_FIELDS) {
            val row = try {
                api.assortment(filter = "$field=$code").rows().firstOrNull()
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 400) null else throw e
            }
            if (row != null) return row
        }
        throw ApiException("Товар с кодом или штрихкодом «$code» не найден")
    }

    private suspend fun stock(type: String?, productHref: String, storeHref: String): Double {
        val key = if (type == "variant") "variant" else "product"
        val report = api.stock(
            filter = "$key=$productHref;store=$storeHref",
            groupBy = if (type == "variant") "variant" else null,
        )
        return report.rows().sumOf { row ->
            row.get("stock")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        }
    }

    private fun quantityInDocument(doc: JsonObject, productHref: String): Double =
        doc.obj("positions")?.rows().orEmpty()
            .filter { it.obj("assortment")?.href() == productHref }
            .sumOf { it.get("quantity")?.asDouble ?: 0.0 }

    private fun mergePosition(
        doc: JsonObject,
        product: JsonObject,
        productHref: String,
        quantity: Double,
    ): JsonArray {
        val existing = doc.obj("positions")?.rows().orEmpty()
        val result = JsonArray()
        var firstIndex = -1
        for (position in existing) {
            val copy = position.deepCopy()
            if (copy.obj("assortment")?.href() != productHref) {
                result.add(copy)
                continue
            }
            val current = copy.get("quantity")?.asDouble ?: 0.0
            if (firstIndex < 0) {
                copy.addProperty("quantity", current + quantity)
                firstIndex = result.size()
                result.add(copy)
            } else {
                // Дубли одной и той же позиции сворачиваем в первую строку, иначе товар
                // попадает в документ несколько раз.
                val first = result[firstIndex].asJsonObject
                first.addProperty("quantity", (first.get("quantity")?.asDouble ?: 0.0) + current)
            }
        }
        if (firstIndex < 0) {
            result.add(JsonObject().apply {
                add("assortment", JsonObject().apply { add("meta", product.obj("meta")) })
                addProperty("quantity", quantity)
            })
        }
        return result
    }

    private suspend fun JsonObject.store(field: String, type: DocumentType): StoreRef {
        val store = obj(field)
        val href = store?.href()
            ?: throw ApiException("В документе «${title(type)}» не указан склад ($field)")
        val name = store.str("name")?.takeIf { it.isNotBlank() } ?: storeName(href)
        return StoreRef(href, name)
    }

    /** Склад без раскрытого имени дочитываем отдельным запросом `entity/store/{id}`. */
    private suspend fun storeName(href: String): String = try {
        api.store(href.substringAfterLast('/')).str("name").orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ""
    }

    /**
     * Единица измерения товара. В API `uom` — это meta-ссылка без имени, поэтому имя берём
     * из раскрытого объекта, а если его нет — из справочника `entity/uom` по id из ссылки.
     */
    private suspend fun uomName(element: JsonObject?): String {
        element?.str("name")?.takeIf { it.isNotBlank() }?.let { return it }
        val fromDictionary = element?.href()?.let { href ->
            uomDictionary()[href.substringAfterLast('/')]
        }.orEmpty()
        // У товара без явно заданной единицы МойСклад подставляет базовую единицу учёта.
        // Без этого «шт» не показывалось вообще: поле uom в ответе просто отсутствует.
        return fromDictionary.ifBlank { DEFAULT_UOM }
    }

    /** Справочник единиц измерения грузим один раз за сессию. */
    private suspend fun uomDictionary(): Map<String, String> {
        uomCache?.let { return it }
        val map = HashMap<String, String>()
        try {
            api.uoms(limit = UOM_LIMIT).rows().forEach { row ->
                val id = row.str("id") ?: return@forEach
                map[id] = row.str("name").orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Справочник недоступен — работаем без единиц измерения, не ломая сканирование.
        }
        if (map.isNotEmpty()) uomCache = map
        return map
    }

    private fun insufficient(available: Double) =
        ApiException("Недостаточно товаров (на складе: ${formatQuantity(maxOf(available, 0.0))})")

    private fun notFound(type: DocumentType) =
        ApiException("Документ «${title(type)}» с таким номером или ID не найден")

    private fun emptyMessage(type: DocumentType) =
        ApiException("Укажите номер или ID документа «${title(type)}» в настройках")

    private fun title(type: DocumentType) = when (type) {
        DocumentType.MOVE -> "Перемещение"
        DocumentType.SUPPLY -> "Оприходование"
        DocumentType.LOSS -> "Списание"
    }

    private companion object {
        const val EPSILON = 1e-9
        const val PAGE_LIMIT = 1000
        const val MAX_PAGES = 20
        const val ID_BATCH = 50
        const val UOM_LIMIT = 100
        const val DEFAULT_UOM = "шт"
        val SYNC_ORDER = listOf(DocumentType.SUPPLY, DocumentType.LOSS, DocumentType.MOVE)
        val SEARCH_FIELDS = listOf("barcode", "code", "article")
        val BARCODE_FIELDS = listOf("ean13", "ean8", "code128", "gtin", "gtin13", "itf14")
        val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")
    }
}
