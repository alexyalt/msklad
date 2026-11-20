package ru.msklad.scanner.api

import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiFactory {
    const val BASE_URL = "https://api.moysklad.ru/api/remap/1.2/"

    // OkHttp сам добавляет Accept-Encoding: gzip и прозрачно распаковывает ответ.
    fun create(login: String, password: String): MoySkladApi {
        val auth = Credentials.basic(login, password, Charsets.UTF_8)
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", auth)
                        .header("Accept", "application/json;charset=utf-8")
                        .build()
                )
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MoySkladApi::class.java)
    }
}
