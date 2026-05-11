package com.me.hooklocation.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * ContentProvider exposed to other processes (hook side).
 * Hook reads enabled state + coordinates via query() without needing
 * world-readable prefs (which are deprecated on newer Android).
 *
 * URI: content://com.me.hooklocation.provider/state
 * Columns: enabled (0/1), gcj_lat (double as string), gcj_lon (double as string)
 */
class LocationProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.me.hooklocation.provider"
        const val PATH_STATE = "state"
        const val COL_ENABLED = "enabled"
        const val COL_LAT = "gcj_lat"
        const val COL_LON = "gcj_lon"
        const val COL_NAME = "name"

        val URI_STATE: Uri = Uri.parse("content://$AUTHORITY/$PATH_STATE")

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_STATE, 1)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        if (uriMatcher.match(uri) != 1) return null
        val ctx = context ?: return null

        val cursor = MatrixCursor(
            arrayOf(COL_ENABLED, COL_LAT, COL_LON, COL_NAME)
        )
        val enabled = if (PrefManager.isEnabled(ctx)) 1 else 0
        val lat = PrefManager.getActiveGcjLat(ctx)
        val lon = PrefManager.getActiveGcjLon(ctx)
        val name = PrefManager.getActiveLocationName(ctx)
        cursor.addRow(arrayOf(enabled, lat.toString(), lon.toString(), name))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/state"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
