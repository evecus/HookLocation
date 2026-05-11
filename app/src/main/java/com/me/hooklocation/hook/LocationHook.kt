package com.me.hooklocation.hook

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 系统进程级别定位 Hook。
 * 注入到系统定位服务进程（android / com.oplus.location 等），
 * 所有 App 调用系统定位时均自动返回虚假坐标，无需逐 App 勾选。
 *
 * 状态读取：XSharedPreferences（不受 AppsFilter/PackageVisibility 限制）
 *
 * Hook 点：
 *   1. Location.getLatitude() / getLongitude()          — 最底层兜底
 *   2. LocationManager.getLastKnownLocation()           — 客户端 API
 *   3. LocationManager.requestLocationUpdates()         — 客户端 API
 *   4. LocationManagerService（系统服务内部实现）
 *   5. GnssLocationProvider（GPS 硬件层）
 *   6. FusedLocationProvider（一体化位置）
 */
object LocationHook {

    private const val PREF_NAME = "hooklocation_prefs"
    private const val PKG_NAME  = "com.me.hooklocation"
    private const val TAG       = "[HookLocation]"

    // ── 状态缓存（XSharedPreferences 每 500ms 最多刷新一次）──────────────
    private const val CACHE_TTL = 500L
    private var cachedState = FakeState(false, 39.9042, 116.4074)
    private var lastQueryTime = 0L

    private data class FakeState(val enabled: Boolean, val lat: Double, val lon: Double)

    /**
     * 通过 XSharedPreferences 读取虚拟定位开关和坐标。
     * XSharedPreferences 在被注入的系统进程里直接读文件，
     * 完全绕过 AppsFilter / PackageVisibility 限制，不需要 ContentProvider。
     */
    private fun getState(): FakeState {
        val now = System.currentTimeMillis()
        if (now - lastQueryTime < CACHE_TTL) return cachedState
        lastQueryTime = now

        return try {
            @Suppress("DEPRECATION")
            val prefs = de.robv.android.xposed.XSharedPreferences(PKG_NAME, PREF_NAME)
            prefs.reload()
            FakeState(
                enabled = prefs.getBoolean("enabled", false),
                lat     = prefs.getFloat("gcj_lat", 39.9042f).toDouble(),
                lon     = prefs.getFloat("gcj_lon", 116.4074f).toDouble()
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG XSharedPreferences read failed: ${e.message}")
            cachedState
        }.also { cachedState = it }
    }

    // ── 构造仿真 Location 对象 ───────────────────────────────────────────────

    private fun fakeLocation(provider: String, lat: Double, lon: Double): Location =
        Location(provider).apply {
            latitude  = lat
            longitude = lon
            altitude  = 8.0
            accuracy  = 3.0f
            speed     = 0.0f
            bearing   = 0.0f
            time      = System.currentTimeMillis()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters       = 3.0f
                speedAccuracyMetersPerSecond = 0.0f
                bearingAccuracyDegrees       = 0.0f
                elapsedRealtimeNanos         = SystemClock.elapsedRealtimeNanos()
            }
        }

    // ── 安装所有 Hook ────────────────────────────────────────────────────────

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG install() in ${lpparam.packageName}")
        hookLocationObject()
        hookLocationManager()
        hookLocationManagerService(lpparam)
        hookGnssProvider(lpparam)
        hookFusedProvider(lpparam)
    }

    // ── Hook 1: Location.getLatitude / getLongitude ──────────────────────────

    private fun hookLocationObject() {
        try {
            XposedHelpers.findAndHookMethod(Location::class.java, "getLatitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val state = getState()
                            if (state.enabled) param.result = state.lat
                        } catch (_: Throwable) {}
                    }
                })
            XposedBridge.log("$TAG hooked Location.getLatitude")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG getLatitude hook failed: ${e.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(Location::class.java, "getLongitude",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val state = getState()
                            if (state.enabled) param.result = state.lon
                        } catch (_: Throwable) {}
                    }
                })
            XposedBridge.log("$TAG hooked Location.getLongitude")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG getLongitude hook failed: ${e.message}")
        }
    }

    // ── Hook 2 & 3: LocationManager 客户端 API ───────────────────────────────

    private fun hookLocationManager() {
        // getLastKnownLocation
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java, "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val state = getState()
                            if (!state.enabled) return
                            val provider = param.args[0] as? String ?: LocationManager.GPS_PROVIDER
                            param.result = fakeLocation(provider, state.lat, state.lon)
                        } catch (_: Throwable) {}
                    }
                })
            XposedBridge.log("$TAG hooked LocationManager.getLastKnownLocation")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG getLastKnownLocation hook failed: ${e.message}")
        }

        // requestLocationUpdates (String, long, float, LocationListener)
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java, "requestLocationUpdates",
                String::class.java, Long::class.java, Float::class.java,
                LocationListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val orig = param.args[3] as? LocationListener ?: return
                            param.args[3] = wrapListener(orig)
                        } catch (_: Throwable) {}
                    }
                })
        } catch (_: Throwable) {}

        // requestLocationUpdates (String, long, float, LocationListener, Handler)
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java, "requestLocationUpdates",
                String::class.java, Long::class.java, Float::class.java,
                LocationListener::class.java, android.os.Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val orig = param.args[3] as? LocationListener ?: return
                            param.args[3] = wrapListener(orig)
                        } catch (_: Throwable) {}
                    }
                })
        } catch (_: Throwable) {}
    }

    // ── Hook 4: LocationManagerService 内部实现 ──────────────────────────────

    private val SERVICE_CLASS_NAMES = listOf(
        "com.android.server.location.LocationManagerService",
        "com.android.server.LocationManagerService",
    )

    private fun hookLocationManagerService(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (className in SERVICE_CLASS_NAMES) {
            try {
                val clz = XposedHelpers.findClass(className, lpparam.classLoader)
                for (methodName in listOf("getLastLocation", "getLastKnownLocation")) {
                    try {
                        XposedBridge.hookAllMethods(clz, methodName, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val state = getState()
                                    if (!state.enabled) return
                                    val result = param.result as? Location ?: return
                                    result.latitude  = state.lat
                                    result.longitude = state.lon
                                } catch (_: Throwable) {}
                            }
                        })
                        XposedBridge.log("$TAG hooked $className.$methodName")
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    // ── Hook 5: GnssLocationProvider (GPS 硬件层) ────────────────────────────

    private val GNSS_CLASS_NAMES = listOf(
        "com.android.server.location.gnss.GnssLocationProvider",
        "com.android.server.location.GnssLocationProvider",
        "com.android.server.location.gnss.hal.GnssNative",
    )

    private fun hookGnssProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (className in GNSS_CLASS_NAMES) {
            try {
                val clz = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedBridge.hookAllMethods(clz, "reportLocation", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val state = getState()
                            if (!state.enabled) return
                            val loc = param.args.firstOrNull { it is Location } as? Location ?: return
                            loc.latitude  = state.lat
                            loc.longitude = state.lon
                        } catch (_: Throwable) {}
                    }
                })
                XposedBridge.log("$TAG hooked $className.reportLocation")
            } catch (_: Throwable) {}
        }
    }

    // ── Hook 6: FusedLocationProvider ───────────────────────────────────────

    private val FUSED_CLASS_NAMES = listOf(
        "com.android.server.location.fused.FusedLocationProvider",
        "com.android.location.fused.FusedLocationProvider",
        "com.google.android.gms.location.internal.FusedLocationProviderService",
    )

    private fun hookFusedProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (className in FUSED_CLASS_NAMES) {
            try {
                val clz = XposedHelpers.findClass(className, lpparam.classLoader)
                for (methodName in listOf("getLastLocation", "onLocationChanged", "reportLocation")) {
                    try {
                        XposedBridge.hookAllMethods(clz, methodName, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val state = getState()
                                    if (!state.enabled) return
                                    val loc = (param.result as? Location)
                                        ?: (param.args.firstOrNull { it is Location } as? Location)
                                        ?: return
                                    loc.latitude  = state.lat
                                    loc.longitude = state.lon
                                } catch (_: Throwable) {}
                            }
                        })
                    } catch (_: Throwable) {}
                }
                XposedBridge.log("$TAG hooked FusedProvider: $className")
            } catch (_: Throwable) {}
        }
    }

    // ── 包装 LocationListener 回调 ───────────────────────────────────────────

    private fun wrapListener(original: LocationListener): LocationListener =
        LocationListener { real ->
            try {
                val state = getState()
                if (state.enabled) {
                    original.onLocationChanged(
                        fakeLocation(real.provider ?: LocationManager.GPS_PROVIDER, state.lat, state.lon)
                    )
                } else {
                    original.onLocationChanged(real)
                }
            } catch (_: Throwable) {
                original.onLocationChanged(real)
            }
        }
}
