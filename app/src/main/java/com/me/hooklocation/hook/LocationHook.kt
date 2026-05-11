package com.me.hooklocation.hook

import android.content.ContentResolver
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
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
 * Hook 点：
 *   1. LocationManagerService.getLastLocation()         — 系统服务层
 *   2. LocationManagerService.requestLocationUpdates()  — 系统服务层
 *   3. Location.getLatitude() / getLongitude()          — 兜底，覆盖所有 Location 对象
 *   4. GnssLocationProvider (GPS 硬件层)                — AOSP GPS 提供者
 *   5. FusedLocationProvider                            — 一体化位置
 */
object LocationHook {

    private const val AUTHORITY = "com.me.hooklocation.provider"
    private const val TAG = "[HookLocation]"

    // ── 状态缓存（避免每次都跨进程查 ContentProvider）──────────────────────
    // 每 500ms 最多刷新一次
    private var cachedState: FakeState = FakeState(false, 39.9042, 116.4074)
    private var lastQueryTime = 0L
    private const val CACHE_TTL = 500L

    private data class FakeState(val enabled: Boolean, val lat: Double, val lon: Double)

    private fun getState(resolver: ContentResolver): FakeState {
        val now = System.currentTimeMillis()
        if (now - lastQueryTime < CACHE_TTL) return cachedState
        lastQueryTime = now
        return try {
            val uri = Uri.parse("content://$AUTHORITY/state")
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    FakeState(
                        enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1,
                        lat = cursor.getString(cursor.getColumnIndexOrThrow("gcj_lat")).toDoubleOrNull() ?: 39.9042,
                        lon = cursor.getString(cursor.getColumnIndexOrThrow("gcj_lon")).toDoubleOrNull() ?: 116.4074
                    )
                } else FakeState(false, 39.9042, 116.4074)
            } ?: cachedState
        } catch (e: Exception) {
            cachedState
        }.also { cachedState = it }
    }

    // ── 构造仿真 Location 对象 ───────────────────────────────────────────────

    private fun fakeLocation(provider: String, lat: Double, lon: Double): Location =
        Location(provider).apply {
            latitude = lat
            longitude = lon
            altitude = 8.0
            accuracy = 3.0f
            speed = 0.0f
            bearing = 0.0f
            time = System.currentTimeMillis()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 3.0f
                speedAccuracyMetersPerSecond = 0.0f
                bearingAccuracyDegrees = 0.0f
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
        }

    // ── 安装所有 Hook ────────────────────────────────────────────────────────

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook 1: Location 对象的 getLatitude/getLongitude — 最底层兜底
        hookLocationObject()

        // Hook 2: LocationManager 客户端 API（在系统框架进程里也有）
        hookLocationManager(lpparam)

        // Hook 3: 系统服务内部实现类（各版本/厂商不同，全部 try-catch）
        hookLocationManagerService(lpparam)

        // Hook 4: GnssLocationProvider GPS 硬件层
        hookGnssProvider(lpparam)

        // Hook 5: FusedLocationProvider
        hookFusedProvider(lpparam)
    }

    // ── Hook 1: Location.getLatitude / getLongitude ──────────────────────────
    // 注意：不在注册时获取 context，而是在回调触发时懒加载。
    // 原因：系统进程（android）启动极早期 ActivityThread.currentApplication() 返回 null，
    // 若在 install() 时就取 context 会导致整个 Hook 被 ?: return 跳过，永远不生效。

    private fun hookLocationObject() {
        val latHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val ctx = getAppContext() ?: return
                    val state = getState(ctx.contentResolver)
                    if (!state.enabled) return
                    param.result = state.lat
                } catch (_: Throwable) {}
            }
        }
        val lonHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val ctx = getAppContext() ?: return
                    val state = getState(ctx.contentResolver)
                    if (!state.enabled) return
                    param.result = state.lon
                } catch (_: Throwable) {}
            }
        }
        try { XposedHelpers.findAndHookMethod(Location::class.java, "getLatitude", latHook) }
        catch (e: Throwable) { XposedBridge.log("$TAG getLatitude hook failed: ${e.message}") }

        try { XposedHelpers.findAndHookMethod(Location::class.java, "getLongitude", lonHook) }
        catch (e: Throwable) { XposedBridge.log("$TAG getLongitude hook failed: ${e.message}") }
    }

    // ── Hook 2: LocationManager ──────────────────────────────────────────────
    // 同样不在注册时获取 context，在每个回调内部懒加载，防止系统进程早期 null 导致跳过注册。

    private fun hookLocationManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        // getLastKnownLocation
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java, "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val ctx = getAppContext() ?: return
                            val state = getState(ctx.contentResolver)
                            if (!state.enabled) return
                            val provider = param.args[0] as? String ?: LocationManager.GPS_PROVIDER
                            param.result = fakeLocation(provider, state.lat, state.lon)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log("$TAG getLastKnownLocation hook failed: ${e.message}") }

        // requestLocationUpdates (String, long, float, LocationListener)
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java, "requestLocationUpdates",
                String::class.java, Long::class.java, Float::class.java, LocationListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val ctx = getAppContext() ?: return
                            val orig = param.args[3] as? LocationListener ?: return
                            param.args[3] = wrapListener(orig, ctx)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log("$TAG requestLocationUpdates hook failed: ${e.message}") }

        // requestLocationUpdates (String, long, float, LocationListener, Handler)
        try {
            XposedHelpers.findAndHookMethod(
                LocationManager::class.java, "requestLocationUpdates",
                String::class.java, Long::class.java, Float::class.java,
                LocationListener::class.java, android.os.Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val ctx = getAppContext() ?: return
                            val orig = param.args[3] as? LocationListener ?: return
                            param.args[3] = wrapListener(orig, ctx)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ── Hook 3: LocationManagerService 内部实现 ──────────────────────────────
    // 系统进程中的真正实现类，不同 Android 版本类名不同

    private val SERVICE_CLASS_NAMES = listOf(
        "com.android.server.location.LocationManagerService",
        "com.android.server.LocationManagerService",
        "android.location.LocationManager",
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
                                    val ctx = getAppContext() ?: return
                                    val state = getState(ctx.contentResolver)
                                    if (!state.enabled) return
                                    val result = param.result as? Location ?: return
                                    result.latitude = state.lat
                                    result.longitude = state.lon
                                } catch (_: Throwable) {}
                            }
                        })
                        XposedBridge.log("$TAG Hooked $className.$methodName")
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    // ── Hook 4: GnssLocationProvider (GPS 硬件层) ────────────────────────────

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
                            val ctx = getAppContext() ?: return
                            val state = getState(ctx.contentResolver)
                            if (!state.enabled) return
                            // reportLocation 第一个参数通常是 Location
                            val loc = param.args.firstOrNull { it is Location } as? Location ?: return
                            loc.latitude = state.lat
                            loc.longitude = state.lon
                        } catch (_: Throwable) {}
                    }
                })
                XposedBridge.log("$TAG Hooked $className.reportLocation")
            } catch (_: Throwable) {}
        }
    }

    // ── Hook 5: FusedLocationProvider ───────────────────────────────────────

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
                                    val ctx = getAppContext() ?: return
                                    val state = getState(ctx.contentResolver)
                                    if (!state.enabled) return
                                    val loc = (param.result as? Location)
                                        ?: (param.args.firstOrNull { it is Location } as? Location)
                                        ?: return
                                    loc.latitude = state.lat
                                    loc.longitude = state.lon
                                } catch (_: Throwable) {}
                            }
                        })
                    } catch (_: Throwable) {}
                }
                XposedBridge.log("$TAG Hooked FusedProvider: $className")
            } catch (_: Throwable) {}
        }
    }

    // ── 包装 LocationListener 回调 ───────────────────────────────────────────

    private fun wrapListener(
        original: LocationListener,
        ctx: android.content.Context
    ): LocationListener = LocationListener { real ->
        try {
            // ctx 由调用方在回调触发时传入（已在回调内部懒加载），此处直接用
            val state = getState(ctx.contentResolver)
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

    // ── 获取当前进程 Context ─────────────────────────────────────────────────

    private var contextCache: android.content.Context? = null

    private fun getAppContext(): android.content.Context? {
        contextCache?.let { return it }
        return try {
            val ctx = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication"
            ) as? android.content.Context
            contextCache = ctx
            ctx
        } catch (_: Throwable) { null }
    }
}
