package com.houvven.guise.xposed

import com.houvven.guise.BuildConfig
import com.houvven.guise.xposed.config.ModuleConfig
import com.houvven.guise.xposed.hook.*
import com.houvven.guise.xposed.hook.location.CellLocationHook
import com.houvven.guise.xposed.hook.location.LocationHook
import com.houvven.guise.xposed.hook.netowork.NetworkHook
import com.houvven.guise.xposed.other.BlankPass
import com.houvven.guise.xposed.other.HookSuccessHint
import com.houvven.ktx_xposed.handler.HookLoadPackageHandler
import com.houvven.ktx_xposed.logger.XposedLogger
import de.robv.android.xposed.callbacks.XC_LoadPackage

@Suppress("unused")
class HookInit : HookLoadPackageHandler {

    private val packageConfig: ModuleConfig
        get() = PackageConfig.current

    override val packageName = BuildConfig.APPLICATION_ID

    override fun loadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.processName != lpparam.packageName
            && !lpparam.processName.startsWith("${lpparam.packageName}:")
            && !lpparam.isFirstApplication) {
            // Avoid multi-hooking within one process
            XposedLogger.i("skip loadPackage: proc=${lpparam.processName}, pkg=${lpparam.packageName}[${lpparam.appInfo.name}], firstApp=${lpparam.isFirstApplication}")
            return
        }

        XposedLogger.i("start loadPackage: proc=${lpparam.processName}, pkg=${lpparam.packageName}[${lpparam.appInfo.name}]")
        PackageConfig.doRefresh(lpparam.packageName)
        if (!packageConfig.isEnable) {
            XposedLogger.i("loadPackage: ${lpparam.packageName} is not enable, skip.")
            return
        }

        listOf(
            HookSuccessHint(),
            BatteryHook(),
            LocalHook(),
            LocationHook(),
            CellLocationHook(),
            NetworkHook(),
            OsBuildHook(),
            ScreenshotsHook(),
            UniquelyIdHook(),
            BlankPass(),
            BuildConfigHook()
        ).let { doHookLoadPackage(it) }
    }

}