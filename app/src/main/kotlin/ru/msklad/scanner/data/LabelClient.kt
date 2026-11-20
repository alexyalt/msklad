package ru.msklad.scanner.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.msklad.scanner.api.ApiException
import java.io.IOException
import java.util.concurrent.TimeUnit

object PrintServerAddress {
    private const val DEFAULT_PORT = 5000
    private val PATTERN = Regex("^(?:https?://)?([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(?::(\\d{1,5}))?/?$")

    fun parse(input: String): String? {
        val match = PATTERN.matchEntire(input.trim()) ?: return null
        val port = match.groupValues[2].ifEmpty { DEFAULT_PORT.toString() }.toInt()
        if (port !in 1..65535) return null
        return "http://${match.groupValues[1]}:$port"
    }
}

data class PrintResult(val name: String, val printed: Int)

class LabelClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun print(address: String, code: String, count: Int): PrintResult = withContext(Dispatchers.IO) {
        val baseUrl = PrintServerAddress.parse(address)
            ?: throw ApiException("Неверный адрес сервера печати")
        val body = JsonObject().apply {
            addProperty("code", code)
            addProperty("count", count)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder().url("$baseUrl/print").post(body).build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                if (!response.isSuccessful) {
                    val detail = json?.get("error")?.takeIf { it.isJsonPrimitive }?.asString
                    throw ApiException(detail ?: "Сервер печати вернул ошибку (HTTP ${response.code})")
                }
                if (json?.get("ok")?.asBoolean != true) {
                    throw ApiException("Неожиданный ответ сервера печати")
                }
                PrintResult(
                    name = json.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    printed = json.get("printed")?.takeIf { it.isJsonPrimitive }?.asInt ?: count,
                )
            }
        } catch (e: IOException) {
            throw ApiException("Сервер печати недоступен. Проверьте VPN (Tailscale/ZeroTier), адрес и что сервер запущен.", e)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
