package ru.msklad.scanner.api

import okhttp3.Interceptor
import okhttp3.Response
import java.util.ArrayDeque

class RateLimitInterceptor(
    private val maxRequests: Int = 2,
    private val windowMs: Long = 1000L,
) : Interceptor {
    private val starts = ArrayDeque<Long>()

    @Synchronized
    private fun awaitSlot() {
        while (true) {
            val now = nowMs()
            while (starts.isNotEmpty() && now - starts.first() >= windowMs) starts.removeFirst()
            if (starts.size < maxRequests) {
                starts.addLast(now)
                return
            }
            Thread.sleep(starts.first() + windowMs - now)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        awaitSlot()
        return chain.proceed(chain.request())
    }

    private fun nowMs() = System.nanoTime() / 1_000_000L
}
