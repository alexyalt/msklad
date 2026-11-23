package ru.msklad.scanner.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import ru.msklad.scanner.api.ApiException
import ru.msklad.scanner.api.MoySkladApi
import ru.msklad.scanner.api.apiCall
import ru.msklad.scanner.api.href
import ru.msklad.scanner.api.metaType
import ru.msklad.scanner.api.obj
import ru.msklad.scanner.api.rows
import ru.msklad.scanner.api.str

data class StoreRef(val href: String, val name: String)

data class MoveInfo(
    val id: String,
    val name: String,
    val source: StoreRef,
    val target: StoreRef,
)

data class AddResult(
    val productName: String,
    val productCode: String,
    val productHref: String,
    val quantity: Double,
    val sourceAfter: Double,
    val targetAfter: Double,
)

data class ProductInfo(val name: String, val code: String)

class MoveRepository(private val api: MoySkladApi) {

    suspend fun findMove(numberOrId: String): MoveInfo = apiCall {
        val input = numberOrId.trim()
        val doc = if (UUID_REGEX.matches(input)) {
            try {
                api.move(input.lowercase(), STORES_EXPAND)
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 404) throw notFound() else throw e
            }
        } else {
            api.moves("name=$input", STORES_EXPAND).rows().firstOrNull() ?: throw notFound()
        }
        val id = doc.str("id") ?: throw notFound()
        MoveInfo(
            id = id,
            name = doc.str("name").orEmpty(),
            source = doc.store("sourceStore"),
            target = doc.store("targetStore"),
        )
    }

    suspend fun addItem(move: MoveInfo, code: String, quantity: Double): AddResult = apiCall {
        val product = findProduct(code.trim())
        val type = product.metaType()
        if (type != "product" && type != "variant") {
            throw ApiException("Позиция «${product.str("name").orEmpty()}» не является товаром или модификацией")
        }
        val productHref = product.href() ?: throw ApiException("У товара нет ссылки meta")

        val doc = api.move(move.id, "positions")
        val applied = doc.get("applicable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        // В проведённом перемещении существующие позиции уже учтены в остатках, в непроведённом нет.
        val notYetInStock = if (applied) 0.0 else quantityInDocument(doc, productHref)

        val sourceStock = stock(type, productHref, move.source.href) - notYetInStock
        if (quantity > sourceStock + EPSILON) {
            throw ApiException("Недостаточно товаров (на складе: ${formatQuantity(maxOf(sourceStock, 0.0))})")
        }
        val targetStock = stock(type, productHref, move.target.href) + notYetInStock

        val positions = mergePosition(doc, product, productHref, quantity)
        api.updateMove(move.id, JsonObject().apply { add("positions", positions) })

        AddResult(
            productName = product.str("name").orEmpty(),
            productCode = product.str("code").orEmpty(),
            productHref = productHref,
            quantity = quantity,
            sourceAfter = sourceStock - quantity,
            targetAfter = targetStock + quantity,
        )
    }

    suspend fun lookupProduct(code: String): ProductInfo = apiCall {
        val product = findProduct(code.trim())
        ProductInfo(product.str("name").orEmpty(), product.str("code").orEmpty())
    }

    suspend fun undoAdd(move: MoveInfo, added: AddResult) =
        removeQuantity(move, added.productHref, added.productName, added.quantity)

    suspend fun removeQuantity(move: MoveInfo, productHref: String, productName: String, quantity: Double) = apiCall {
        val doc = api.move(move.id, "positions")
        val positions = JsonArray()
        var found = false
        for (position in doc.obj("positions")?.rows().orEmpty()) {
            if (!found && position.obj("assortment")?.href() == productHref) {
                found = true
                val left = (position.get("quantity")?.asDouble ?: 0.0) - quantity
                if (left > EPSILON) {
                    positions.add(position.deepCopy().apply { addProperty("quantity", left) })
                }
            } else {
                positions.add(position.deepCopy())
            }
        }
        if (!found) throw ApiException("Позиции «$productName» уже нет в документе")
        api.updateMove(move.id, JsonObject().apply { add("positions", positions) })
    }

    private suspend fun findProduct(code: String): JsonObject {
        // Только точные фильтры: параметр search отдаёт частичные совпадения (или все товары) и приводит к добавлению чужого товара.
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
        var merged = false
        for (position in existing) {
            val copy = position.deepCopy()
            val assortmentHref = copy.obj("assortment")?.href()
            if (!merged && assortmentHref == productHref) {
                val current = copy.get("quantity")?.asDouble ?: 0.0
                copy.addProperty("quantity", current + quantity)
                merged = true
            }
            result.add(copy)
        }
        if (!merged) {
            result.add(JsonObject().apply {
                add("assortment", JsonObject().apply { add("meta", product.obj("meta")) })
                addProperty("quantity", quantity)
            })
        }
        return result
    }

    private fun JsonObject.store(field: String): StoreRef {
        val store = obj(field)
        val href = store?.href()
        val name = store?.str("name")
        if (href == null || name == null) {
            throw ApiException("В перемещении не указан склад ($field)")
        }
        return StoreRef(href, name)
    }

    private fun notFound() =
        ApiException("Документ «Перемещение» с таким номером или ID не найден")

    private companion object {
        const val STORES_EXPAND = "sourceStore,targetStore"
        const val EPSILON = 1e-9
        val SEARCH_FIELDS = listOf("barcode", "code", "article")
        val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")
    }
}
