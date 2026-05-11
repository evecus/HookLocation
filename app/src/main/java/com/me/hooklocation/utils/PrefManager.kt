package com.me.hooklocation.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.me.hooklocation.model.SavedLocation

/**
 * Central preferences manager.
 * Pref file is MODE_WORLD_READABLE so XSharedPreferences can read it from hook side.
 *
 * Keys used by the hook:
 *   KEY_ENABLED   – Boolean – whether spoofing is active
 *   KEY_GCJ_LAT   – Float   – current GCJ-02 latitude
 *   KEY_GCJ_LON   – Float   – current GCJ-02 longitude
 */
object PrefManager {

    const val PREF_NAME = "hooklocation_prefs"

    const val KEY_ENABLED = "enabled"
    const val KEY_GCJ_LAT = "gcj_lat"
    const val KEY_GCJ_LON = "gcj_lon"
    const val KEY_WGS_LAT = "wgs_lat"
    const val KEY_WGS_LON = "wgs_lon"
    const val KEY_LOCATION_NAME = "location_name"
    const val KEY_SAVED_LOCATIONS = "saved_locations"

    private val gson = Gson()

    @Suppress("DEPRECATION")
    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE)

    // ── Enable / Disable ────────────────────────────────────────────────────

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ENABLED, false)

    // ── Active location ──────────────────────────────────────────────────────

    fun setActiveLocation(context: Context, loc: SavedLocation) {
        getPrefs(context).edit()
            .putFloat(KEY_GCJ_LAT, loc.gcjLat.toFloat())
            .putFloat(KEY_GCJ_LON, loc.gcjLon.toFloat())
            .putFloat(KEY_WGS_LAT, loc.wgsLat.toFloat())
            .putFloat(KEY_WGS_LON, loc.wgsLon.toFloat())
            .putString(KEY_LOCATION_NAME, loc.name)
            .apply()
    }

    fun getActiveGcjLat(context: Context): Double =
        getPrefs(context).getFloat(KEY_GCJ_LAT, 39.9042f).toDouble()

    fun getActiveGcjLon(context: Context): Double =
        getPrefs(context).getFloat(KEY_GCJ_LON, 116.4074f).toDouble()

    fun getActiveLocationName(context: Context): String =
        getPrefs(context).getString(KEY_LOCATION_NAME, "") ?: ""

    // ── Saved location list ───────────────────────────────────────────────────

    fun getSavedLocations(context: Context): MutableList<SavedLocation> {
        val json = getPrefs(context).getString(KEY_SAVED_LOCATIONS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<SavedLocation>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveLocation(context: Context, loc: SavedLocation) {
        val list = getSavedLocations(context)
        // Remove duplicates by id
        list.removeAll { it.id == loc.id }
        list.add(0, loc)
        persistList(context, list)
    }

    fun deleteLocation(context: Context, id: String) {
        val list = getSavedLocations(context)
        list.removeAll { it.id == id }
        persistList(context, list)
    }

    private fun persistList(context: Context, list: List<SavedLocation>) {
        getPrefs(context).edit()
            .putString(KEY_SAVED_LOCATIONS, gson.toJson(list))
            .apply()
    }
}
