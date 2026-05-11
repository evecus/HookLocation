package com.me.hooklocation.model

import java.util.UUID

/**
 * Represents a saved fake location entry.
 * @param id        Unique identifier for list operations
 * @param name      User-friendly display name (e.g. "上海东方明珠")
 * @param wgsLat    WGS-84 latitude  (raw input or from Nominatim)
 * @param wgsLon    WGS-84 longitude
 * @param gcjLat    GCJ-02 latitude  (converted, used for hook)
 * @param gcjLon    GCJ-02 longitude
 * @param createdAt Timestamp millis
 */
data class SavedLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val wgsLat: Double,
    val wgsLon: Double,
    val gcjLat: Double,
    val gcjLon: Double,
    val createdAt: Long = System.currentTimeMillis()
)
