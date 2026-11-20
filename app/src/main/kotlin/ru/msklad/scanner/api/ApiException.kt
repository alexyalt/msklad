package ru.msklad.scanner.api

import com.google.gson.JsonParser
import retrofit2.HttpException
import java.io.IOException

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

suspend fun <T> apiCall(block: suspend () -> T): T {
    try {
        return block()
    } catch (e: ApiException) {
        throw e
    } catch (e: HttpException) {
        throw ApiException(httpMessage(e), e)
    } catch (e: IOException) {
        throw ApiException("Нет связи с сервером МойСклад. Проверьте интернет.", e)
    }
}

private fun httpMessage(e: HttpException): String {
    val detail = runCatching {
        val body = e.response()?.errorBody()?.string().orEmpty()
        JsonParser.parseString(body).asJsonObject
            .getAsJsonArray("errors")?.firstOrNull()?.asJsonObject
            ?.get("error")?.asString
    }.getOrNull()
    val base = when (e.code()) {
        401, 403 -> "Неверный логин или пароль, либо нет прав доступа"
        404 -> "Объект не найден"
        429 -> "Слишком много запросов, повторите чуть позже"
        else -> "Ошибка API (${e.code()})"
    }
    return if (detail.isNullOrBlank()) base else "$base: $detail"
}
