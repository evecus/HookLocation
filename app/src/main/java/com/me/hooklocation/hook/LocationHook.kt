package com.me.hooklocation.hook

import android.content.ContentResolver
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hooks all standard Android location APIs to return fake GCJ-02 coordinates.
 * State is read from our ContentProvider via ContentResolver (cross-process safe).
 *
 * Hooked APIs:
 *   1. LocationManager.getLastKnownLocation()
 *   2. LocationManager.requestLocationUpdates() variants → injects fake Location via callback
 *   3. LocationManager.requestSingleUpdate()
 *   4. Location.getLatitude() / getLongitude()  (catch-all for any Location object)
 */
object LocationHook {

    private const val AUTHORITY = "com.me.hooklocation.provider"
    private const val TAG = "[HookLocation]"

    // ── State query ──────────────────────────────────────────────────────────

    private data class FakeState(
        val enabled: Boolean,
        val lat: Double,
        val lon: Double
    )

    /** Query our ContentProvider for the current fake state. */
    private fun queryState(resolver: ContentResolver): FakeState {
        return try {
            val uri = Uri.parse("content://$AUTHORITY/state")
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1
                    val lat = cursor.getString(cursor.getColumnIndexOrThrow("gcj_lat"))
                        .toDoubleOrNull() ?: 39.9042
                    val lon = cursor.getString(cursor.getColumnIndexOrThrow("gcj_lon"))
                        .toDoubleOrNull() ?: 116.4074
                    FakeState(enabled, lat, lon)
                } else FakeState(false, 39.9042, 116.4074)
            } ?: FakeState(false, 39.9042, 116.4074)
        } catch (e: Exception) {
            XposedBridge.log("$TAG queryState error: ${e.message}")
            FakeState(false, 39.9042, 116.4074)
        }
    }

    /** Build a realistic fake Location object. */
    private fun buildFakeLocation(provider: String, lat: Double, lon: Double): Location {
        return Location(provider).apply {
            latitude = lat
            longitude = lon
            altitude = 10.0
            accuracy = 5.0f
            speed = 0.0f
            bearing = 0.0f
            time = System.currentTimeMillis()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 5.0f
                speedAccuracyMetersPerSecond = 0.0f
                bearingAccuracyDegrees = 0.0f
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            }
        }
    }

    // ── Hook installation ────────────────────────────────────────────────────

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookGetLastKnownLocation(lpparam)
        hookRequestLocationUpdates(lpparam)
        hookRequestSingleUpdate(lpparam)
        hookLocationGetters(lpparam)
        hookFusedLocation(lpparam)
    }

    // 1. LocationManager.getLastKnownLocation(String provider)
    private fun hookGetLastKnownLocation(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            LocationManager::class.java,
            "getLastKnownLocation",
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = getContext(param) ?: return
                    val state = queryState(ctx.contentResolver)
                    if (!state.enabled) return

                    val provider = param.args[0] as? String ?: LocationManager.GPS_PROVIDER
                    param.result = buildFakeLocation(provider, state.lat, state.lon)
                    XposedBridge.log("$TAG getLastKnownLocation spoofed: ${state.lat}, ${state.lon}")
                }
            }
        )
    }

    // 2. LocationManager.requestLocationUpdates — multiple overloads
    private fun hookRequestLocationUpdates(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Overload: (String, long, float, LocationListener)
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java,
                "requestLocationUpdates",
                String::class.java, Long::class.java, Float::class.java,
                LocationListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ctx = getContext(param) ?: return
                        val state = queryState(ctx.contentResolver)
                        if (!state.enabled) return

                        val originalListener = param.args[3] as? LocationListener ?: return
                        param.args[3] = wrapListener(originalListener, state, ctx)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG hookRequestLocationUpdates (4-arg) failed: ${e.message}")
        }

        // Overload: (String, long, float, LocationListener, Handler)
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java,
                "requestLocationUpdates",
                String::class.java, Long::class.java, Float::class.java,
                LocationListener::class.java, android.os.Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ctx = getContext(param) ?: return
                        val state = queryState(ctx.contentResolver)
                        if (!state.enabled) return

                        val originalListener = param.args[3] as? LocationListener ?: return
                        param.args[3] = wrapListener(originalListener, state, ctx)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG hookRequestLocationUpdates (5-arg) failed: ${e.message}")
        }
    }

    // 3. requestSingleUpdate
    private fun hookRequestSingleUpdate(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java,
                "requestSingleUpdate",
                String::class.java, LocationListener::class.java, android.os.Looper::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ctx = getContext(param) ?: return
                        val state = queryState(ctx.contentResolver)
                        if (!state.enabled) return

                        val originalListener = param.args[1] as? LocationListener ?: return
                        param.args[1] = wrapListener(originalListener, state, ctx)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG hookRequestSingleUpdate failed: ${e.message}")
        }
    }

    // 4. Location.getLatitude() and getlongitude() — catch-all
    private fun hookLocationGetters(lpparam: XC_LoadPackage.LoadPackageParam) {
        val latHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val loc = param.thisObject as? Location ?: return
                // Only spoof if not already our fake location
                if (loc.provider == "fake_hooklocation") return
                val ctx = getContextFromApp() ?: return
                val state = queryState(ctx.contentResolver)
                if (!state.enabled) return
                param.result = state.lat
            }
        }
        val lonHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val loc = param.thisObject as? Location ?: return
                if (loc.provider == "fake_hooklocation") return
                val ctx = getContextFromApp() ?: return
                val state = queryState(ctx.contentResolver)
                if (!state.enabled) return
                param.result = state.lon
            }
        }
        try {
            XposedHelpers.findAndHookMethod(Location::class.java, "getLatitude", latHook)
            XposedHelpers.findAndHookMethod(Location::class.java, "getLongitude", lonHook)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG hookLocationGetters failed: ${e.message}")
        }
    }

    // 5. FusedLocationProviderClient (Google Play Services) — best-effort
    private fun hookFusedLocation(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val fusedClass = XposedHelpers.findClass(
                "com.google.android.gms.location.FusedLocationProviderClient",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                fusedClass, "getLastLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // FusedLocation returns a Task<Location>; harder to intercept directly.
                        // The Location.getLatitude/getLongitude hooks above cover most cases.
                        XposedBridge.log("$TAG FusedLocation.getLastLocation called")
                    }
                }
            )
        } catch (e: Throwable) {
            // Google Play Services may not be present — ignore
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Wrap a real LocationListener to inject fake coordinates. */
    private fun wrapListener(
        original: LocationListener,
        initialState: FakeState,
        ctx: android.content.Context
    ): LocationListener {
        return LocationListener { realLocation ->
            val state = queryState(ctx.contentResolver)
            if (state.enabled) {
                val fake = buildFakeLocation(
                    realLocation.provider ?: LocationManager.GPS_PROVIDER,
                    state.lat, state.lon
                )
                original.onLocationChanged(fake)
            } else {
                original.onLocationChanged(realLocation)
            }
        }
    }

    /** Try to get the application Context from the hooked process. */
    private var appContextCache: android.content.Context? = null

    private fun getContextFromApp(): android.content.Context? {
        if (appContextCache != null) return appContextCache
        return try {
            val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication"
            ) as? android.content.Context
            appContextCache = activityThread
            activityThread
        } catch (e: Throwable) {
            null
        }
    }

    private fun getContext(param: XC_MethodHook.MethodHookParam): android.content.Context? {
        return getContextFromApp()
    }
}
