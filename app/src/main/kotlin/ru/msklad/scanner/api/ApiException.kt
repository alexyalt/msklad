package ru.msklad.scanner.api

import com.google.gson.JsonParser
import retrofit2.HttpException
import java.io.IOException

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Ошибка, при которой продолжать пакетную выгрузку бессмысленно: нет связи, сервер перегружен (429/5xx)
 * или не принимает учётные данные (401/403). Остальные ошибки относятся к конкретной строке
 * (недостаточно товара, товар удалён и т.п.) — выгрузка остальных строк продолжается.
 */
val Throwable.abortsBatch: Boolean
    get() {
        val root = if (this is ApiException) cause ?: return false else this
        return when (root) {
            is IOException -> true
            is HttpException -> root.code() == 429 || root.code() == 401 || root.code() == 403 || root.code() >= 500
            else -> false
        }
    }

/** Превышен лимит запросов МойСклад (429) — имеет смысл подождать и повторить. */
val Throwable.isRateLimited: Boolean
    get() = ((this as? ApiException)?.cause ?: this).let { it is HttpException && it.code() == 429 }

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
