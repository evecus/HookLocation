package com.me.hooklocation.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 模块入口。
 *
 * 作用域勾选（LSPosed 管理器中）：
 *   ✅ 系统框架          (android)
 *   ✅ 电话服务          (com.android.phone)
 *   ✅ 定位服务          (com.oplus.location / com.android.location.fused 等)
 *   ✅ 蓝牙             (com.android.bluetooth)
 *
 * Hook 在系统定位服务进程内生效，所有调用系统定位的 App 都自动被虚拟，
 * 无需对每个 App 单独勾选，也不会导致目标 App 闪退。
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        /**
         * 需要注入 Hook 的进程包名，对应 LSPosed 作用域里要勾的项目。
         * 不同厂商定位服务包名不同，全部列出，LSPosed 只会注入实际存在的进程。
         */
        private val TARGET_PROCESSES = setOf(
            "android",                          // 系统框架
            "com.android.phone",                // 电话服务
            "com.android.bluetooth",            // 蓝牙

            // 定位服务 — 不同厂商包名不同
            "com.android.location.fused",       // AOSP 一体化位置信息
            "com.google.android.gms",           // Google Play Services (FusedLocation)
            "com.oplus.location",               // OnePlus / OPPO
            "com.coloros.location",             // ColorOS
            "com.miui.location",                // MIUI / 小米
            "com.huawei.location",              // 华为
            "com.vivo.location",                // vivo
            "com.samsung.location",             // 三星
            "com.meizu.location",               // 魅族
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.me.hooklocation") return
        if (lpparam.packageName !in TARGET_PROCESSES) return

        XposedBridge.log("[HookLocation] 注入进程: ${lpparam.packageName}")

        try {
            LocationHook.install(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("[HookLocation] 注入失败 ${lpparam.packageName}: ${e.message}")
        }
    }
}
