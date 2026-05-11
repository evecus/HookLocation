package com.me.hooklocation.hook

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File

object LocationHook {

    private const val TAG = "[HookLocation]"

    // 多个候选路径，逐一尝试
    private val STATE_FILE_CANDIDATES = listOf(
        "/data/local/tmp/hooklocation_state.json",
        "/data/system/hooklocation_state.json",
        "/data/adb/hooklocation_state.json",
        "/mnt/user/0/hooklocation_state.json",
    )

    private const val CACHE_TTL = 500L
    private var cachedState  = FakeState(false, 39.9042, 116.4074)
    private var lastReadTime = 0L
    private var workingPath: String? = null   // 记住第一个成功读到的路径
    private var pathLogged  = false           // 只打一次路径日志

    private data class FakeState(val enabled: Boolean, val lat: Double, val lon: Double)

    private fun getState(): FakeState {
        val now = System.currentTimeMillis()
        if (now - lastReadTime < CACHE_TTL) return cachedState
        lastReadTime = now

        // 如果已找到可用路径，直接用
        val path = workingPath
        if (path != null) {
            return readFromFile(path)
        }

        // 还没找到可用路径，逐一尝试
        for (candidate in STATE_FILE_CANDIDATES) {
            val state = readFromFile(candidate)
            // readFromFile 成功时会设置 workingPath
            if (workingPath != null) {
                XposedBridge.log("$TAG state file working path: $workingPath")
                return state
            }
        }

        // 所有路径都失败，打一次汇总日志
        if (!pathLogged) {
            pathLogged = true
            XposedBridge.log("$TAG all state file paths failed, running in process: ${android.os.Process.myPid()}")
            // 打印每个路径的失败原因
            for (candidate in STATE_FILE_CANDIDATES) {
                try {
                    val f = File(candidate)
                    XposedBridge.log("$TAG   $candidate: exists=${f.exists()} canRead=${f.canRead()} parentExists=${f.parentFile?.exists()}")
                } catch (e: Throwable) {
                    XposedBridge.log("$TAG   $candidate: exception=${e.javaClass.simpleName} ${e.message}")
                }
            }
        }
        return cachedState
    }

    private fun readFromFile(path: String): FakeState {
        return try {
            val text = File(path).readText().trim()
            val json = JSONObject(text)
            val state = FakeState(
                enabled = json.optBoolean("enabled", false),
                lat     = json.optDouble("gcj_lat", 39.9042),
                lon     = json.optDouble("gcj_lon", 116.4074)
            )
            workingPath = path   // 标记为可用路径
            state.also { cachedState = it }
        } catch (_: Throwable) {
            cachedState
        }
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
        hookLocationConstructor()
    }

    // ── Hook 0: Location 构造函数 + isFromMockProvider ───────────────────────
    // 借鉴 GlobalTraveling：在对象创建时就注入坐标，最彻底；
    // 同时让 isFromMockProvider 返回 false，防止 App 检测到虚拟定位。

    private fun hookLocationConstructor() {
        // Hook Location(String provider) 构造函数
        try {
            XposedHelpers.findAndHookConstructor(
                Location::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val state = getState()
                            if (!state.enabled) return
                            val loc = param.thisObject as? Location ?: return
                            loc.latitude  = state.lat
                            loc.longitude = state.lon
                        } catch (_: Throwable) {}
                    }
                })
            XposedBridge.log("$TAG hooked Location(String) constructor")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG Location constructor hook failed: ${e.message}")
        }

        // Hook Location.set(Location) — 拦截坐标复制
        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java, "set", Location::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val state = getState()
                            if (!state.enabled) return
                            val loc = param.thisObject as? Location ?: return
                            loc.latitude  = state.lat
                            loc.longitude = state.lon
                        } catch (_: Throwable) {}
                    }
                })
            XposedBridge.log("$TAG hooked Location.set")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG Location.set hook failed: ${e.message}")
        }

        // isFromMockProvider → false，防止 App 检测到虚拟定位
        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java, "isFromMockProvider",
                XC_MethodReplacement.returnConstant(false)
            )
            XposedBridge.log("$TAG hooked isFromMockProvider")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG isFromMockProvider hook failed: ${e.message}")
        }
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
