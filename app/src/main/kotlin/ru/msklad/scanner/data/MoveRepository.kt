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
    val quantity: Double,
    val sourceStock: Double,
    val targetStockBefore: Double,
    val targetStockAfter: Double,
)

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

        val sourceBefore = stock(type, productHref, move.source.href)
        val targetBefore = stock(type, productHref, move.target.href)

        val doc = api.move(move.id, "positions")
        val positions = mergePosition(doc, product, productHref, quantity)
        api.updateMove(move.id, JsonObject().apply { add("positions", positions) })

        AddResult(
            productName = product.str("name").orEmpty(),
            quantity = quantity,
            sourceStock = sourceBefore - quantity,
            targetStockBefore = targetBefore,
            targetStockAfter = targetBefore + quantity,
        )
    }

    private suspend fun findProduct(code: String): JsonObject {
        // Порядок: вариант из ТЗ, затем документированный параметр search, затем фильтр по штрихкоду.
        val attempts = listOf<suspend () -> JsonObject>(
            { api.assortment(filter = "search=$code") },
            { api.assortment(search = code) },
            { api.assortment(filter = "barcode=$code") },
        )
        for (attempt in attempts) {
            val row = try {
                attempt().rows().firstOrNull()
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
        val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")
    }
}
