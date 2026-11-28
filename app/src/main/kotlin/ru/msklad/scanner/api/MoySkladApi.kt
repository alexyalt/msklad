package ru.msklad.scanner.api

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface MoySkladApi {

    @GET("entity/assortment")
    suspend fun assortment(
        @Query("filter") filter: String,
        @Query("limit") limit: Int = 1,
    ): JsonObject

    /** Список документов любого типа: move, supply, loss. */
    @GET("entity/{type}")
    suspend fun documents(
        @Path("type") type: String,
        @Query("filter") filter: String,
        @Query("expand") expand: String? = null,
        @Query("limit") limit: Int = 10,
    ): JsonObject

    @GET("entity/{type}/{id}")
    suspend fun document(
        @Path("type") type: String,
        @Path("id") id: String,
        @Query("expand") expand: String? = null,
    ): JsonObject

    @PUT("entity/{type}/{id}")
    suspend fun updateDocument(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: JsonObject,
    ): JsonObject

    @GET("report/stock/all")
    suspend fun stock(
        @Query("filter") filter: String,
        @Query("groupBy") groupBy: String? = null,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 1000,
    ): JsonObject
}
