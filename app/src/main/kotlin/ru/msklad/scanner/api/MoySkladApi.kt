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

    @GET("entity/move")
    suspend fun moves(
        @Query("filter") filter: String,
        @Query("expand") expand: String,
        @Query("limit") limit: Int = 2,
    ): JsonObject

    @GET("entity/move/{id}")
    suspend fun move(
        @Path("id") id: String,
        @Query("expand") expand: String,
    ): JsonObject

    @PUT("entity/move/{id}")
    suspend fun updateMove(
        @Path("id") id: String,
        @Body body: JsonObject,
    ): JsonObject

    @GET("report/stock/all")
    suspend fun stock(
        @Query("filter") filter: String,
        @Query("groupBy") groupBy: String? = null,
        @Query("limit") limit: Int = 1000,
    ): JsonObject
}
