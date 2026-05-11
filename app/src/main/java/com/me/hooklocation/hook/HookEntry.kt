package com.me.hooklocation.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module entry point.
 * Loaded into every app that has this module enabled in its scope.
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Don't hook ourselves
        if (lpparam.packageName == "com.me.hooklocation") return

        XposedBridge.log("[HookLocation] Loaded into: ${lpparam.packageName}")

        try {
            LocationHook.install(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("[HookLocation] Install failed: ${e.message}")
        }
    }
}
