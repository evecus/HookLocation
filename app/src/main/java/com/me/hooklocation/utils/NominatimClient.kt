package com.me.hooklocation.utils

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Nominatim (OpenStreetMap) search client.
 * Returns a list of places matching the query with WGS-84 coordinates.
 * No API key required. Rate limit: 1 req/sec (we add a small delay).
 */
object NominatimClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    data class Place(
        @SerializedName("place_id") val placeId: Long,
        @SerializedName("display_name") val displayName: String,
        @SerializedName("lat") val lat: String,
        @SerializedName("lon") val lon: String,
        @SerializedName("type") val type: String = "",
        @SerializedName("importance") val importance: Double = 0.0
    ) {
        val latitude: Double get() = lat.toDoubleOrNull() ?: 0.0
        val longitude: Double get() = lon.toDoubleOrNull() ?: 0.0

        /** Short display name — first segment before the first comma */
        val shortName: String get() = displayName.substringBefore(",").trim()
    }

    /**
     * Search for places by name. Returns up to [limit] results.
     * Runs on IO dispatcher — call from a coroutine.
     */
    suspend fun search(query: String, limit: Int = 8): Result<List<Place>> =
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search" +
                        "?q=$encodedQuery&format=json&limit=$limit&addressdetails=0"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "HookLocation/1.0 (com.me.hooklocation)")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }

                val body = response.body?.string() ?: return@withContext Result.success(emptyList())
                val type = com.google.gson.reflect.TypeToken.getParameterized(
                    List::class.java, Place::class.java
                ).type
                val places: List<Place> = gson.fromJson(body, type)
                Result.success(places.sortedByDescending { it.importance })
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
