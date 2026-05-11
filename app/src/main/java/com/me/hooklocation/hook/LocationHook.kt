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
import org.json.JSONObject
import java.io.File

/**
 * 系统进程级别定位 Hook。
 *
 * 状态读取：直接读 /data/local/tmp/hooklocation_state.json（App 侧 root 写入，权限 644）
 * 完全绕过 ContentProvider AppsFilter 和 XSharedPreferences 路径隔离问题。
 */
object LocationHook {

    private const val STATE_FILE = "/data/local/tmp/hooklocation_state.json"
    private const val TAG        = "[HookLocation]"

    // ── 状态缓存（每 500ms 最多重新读一次文件）────────────────────────────
    private const val CACHE_TTL = 500L
    private var cachedState  = FakeState(false, 39.9042, 116.4074)
    private var lastReadTime = 0L

    private data class FakeState(val enabled: Boolean, val lat: Double, val lon: Double)

    private fun getState(): FakeState {
        val now = System.currentTimeMillis()
        if (now - lastReadTime < CACHE_TTL) return cachedState
        lastReadTime = now

        return try {
            val text = File(STATE_FILE).readText().trim()
            val json = JSONObject(text)
            FakeState(
                enabled = json.optBoolean("enabled", false),
                lat     = json.optDouble("gcj_lat", 39.9042),
                lon     = json.optDouble("gcj_lon", 116.4074)
            )
        } catch (e: Throwable) {
            // 文件不存在或解析失败时保持上次缓存
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
