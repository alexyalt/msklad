package ru.msklad.scanner.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject

fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

fun JsonObject.rows(): List<JsonObject> =
    getAsJsonArray("rows")?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()

fun JsonObject.href(): String? = obj("meta")?.str("href")?.substringBefore('?')

fun JsonObject.metaType(): String? = obj("meta")?.str("type")
