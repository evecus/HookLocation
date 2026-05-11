package com.me.hooklocation.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.me.hooklocation.model.SavedLocation
import java.io.File

/**
 * Central preferences manager.
 *
 * 状态同步方案：
 *   - App 内部仍用 SharedPreferences 存完整数据（保存位置列表等）
 *   - 每次开关或切换位置时，额外把 {enabled, gcj_lat, gcj_lon} 写入
 *     /data/local/tmp/hooklocation_state.json（权限 644，所有进程可读）
 *   - Hook 侧直接读这个 JSON 文件，绕过 AppsFilter / XSharedPreferences 路径问题
 */
object PrefManager {

    const val PREF_NAME = "hooklocation_prefs"

    const val KEY_ENABLED       = "enabled"
    const val KEY_GCJ_LAT       = "gcj_lat"
    const val KEY_GCJ_LON       = "gcj_lon"
    const val KEY_WGS_LAT       = "wgs_lat"
    const val KEY_WGS_LON       = "wgs_lon"
    const val KEY_LOCATION_NAME = "location_name"
    const val KEY_SAVED_LOCATIONS = "saved_locations"

    /** Hook 侧和 App 侧约定的共享状态文件路径 */
    const val STATE_FILE = "/data/local/tmp/hooklocation_state.json"

    private const val TAG = "PrefManager"
    private val gson = Gson()

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Enable / Disable ─────────────────────────────────────────────────────

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        syncStateFile(context)
    }

    fun isEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_ENABLED, false)

    // ── Active location ───────────────────────────────────────────────────────

    fun setActiveLocation(context: Context, loc: SavedLocation) {
        getPrefs(context).edit()
            .putFloat(KEY_GCJ_LAT, loc.gcjLat.toFloat())
            .putFloat(KEY_GCJ_LON, loc.gcjLon.toFloat())
            .putFloat(KEY_WGS_LAT, loc.wgsLat.toFloat())
            .putFloat(KEY_WGS_LON, loc.wgsLon.toFloat())
            .putString(KEY_LOCATION_NAME, loc.name)
            .apply()
        syncStateFile(context)
    }

    fun getActiveGcjLat(context: Context): Double =
        getPrefs(context).getFloat(KEY_GCJ_LAT, 39.9042f).toDouble()

    fun getActiveGcjLon(context: Context): Double =
        getPrefs(context).getFloat(KEY_GCJ_LON, 116.4074f).toDouble()

    fun getActiveLocationName(context: Context): String =
        getPrefs(context).getString(KEY_LOCATION_NAME, "") ?: ""

    // ── Saved location list ───────────────────────────────────────────────────

    fun getSavedLocations(context: Context): MutableList<SavedLocation> {
        val json = getPrefs(context).getString(KEY_SAVED_LOCATIONS, null)
            ?: return mutableListOf()
        val type = object : TypeToken<MutableList<SavedLocation>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveLocation(context: Context, loc: SavedLocation) {
        val list = getSavedLocations(context)
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

    // ── 同步状态到共享文件 ────────────────────────────────────────────────────

    /**
     * 把当前的 enabled / gcj_lat / gcj_lon 写入 /data/local/tmp/hooklocation_state.json。
     * 文件权限设为 0644，系统进程和所有 App 均可读。
     * 使用 root shell 写文件并 chmod，保证权限正确。
     */
    private fun syncStateFile(context: Context) {
        val prefs   = getPrefs(context)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val lat     = prefs.getFloat(KEY_GCJ_LAT, 39.9042f)
        val lon     = prefs.getFloat(KEY_GCJ_LON, 116.4074f)

        val json = """{"enabled":$enabled,"gcj_lat":$lat,"gcj_lon":$lon}"""

        try {
            // 用 su 写文件并 chmod，确保系统进程可读
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "echo '$json' > $STATE_FILE && chmod 644 $STATE_FILE"))
            process.waitFor()
            Log.d(TAG, "State synced: $json")
        } catch (e: Throwable) {
            // 没有 root 时降级：直接写文件（可能权限不足，但先试试）
            try {
                File(STATE_FILE).writeText(json)
                File(STATE_FILE).setReadable(true, false)
                Log.d(TAG, "State synced (no-root fallback): $json")
            } catch (e2: Throwable) {
                Log.e(TAG, "syncStateFile failed: ${e2.message}")
            }
        }
    }
}
