package com.houvven.guise.xposed.hook.location

import android.location.*
import android.os.*
import com.houvven.guise.xposed.LoadPackageHandler
import com.houvven.ktx_xposed.hook.*
import com.houvven.ktx_xposed.logger.logcat
import com.houvven.ktx_xposed.logger.logcatWarn
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.*


@Suppress("DEPRECATION")
class LocationHook : LoadPackageHandler {
    companion object {
        private const val TAG = "LocationHook"
    }

    @Volatile private var hasInit = false

    private val isFakeLocationMode by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { config.latitude != -1.0 && config.longitude != -1.0 && !isFixGoogleMapDriftMode }
    private val isFixGoogleMapDriftMode by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { config.fixGoogleMapDrift }
    private val fakeMode by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { LocationHookFakeMode(config) }
    private val offsetMode by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { LocationHookOffsetMode(config) }
    private val gmsOffsetMode by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { GmsLocationHookOffsetMode(config) }

    override fun onHook() {
        error("Deprecated")
    }

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        synchronized(this) {
            if (hasInit) {
                logcat {
                    error("${TAG}#onHook: already init!")
                    error("\tloadPackage: proc=${lpparam.processName}, pkg=${lpparam.packageName}[${lpparam.appInfo.name}], firstApp=${lpparam.isFirstApplication}")
                    error("\tfrom:")
                    Throwable().stackTrace.forEach {
                        error("\t\t$it")
                    }
                }
            } else {
                logcat {
                    info("${TAG}#onHook: latitude=${config.latitude}, longitude=${config.longitude}, fixGoogleMapDrift=${config.fixGoogleMapDrift}")
                    info("\tloadPackage: proc=${lpparam.processName}, pkg=${lpparam.packageName}[${lpparam.appInfo.name}], firstApp=${lpparam.isFirstApplication}")
                    info("\tfrom:")
                    Throwable().stackTrace.forEach {
                        info("\t\t$it")
                    }
                }
                if (!isFakeLocationMode && !isFixGoogleMapDriftMode) {
                    // Disabled
                    logcatWarn { "${TAG}#disabled" }
                } else {
                    // Enabled
                    init(lpparam)
                    hasInit = true
                }
            }
        }
    }

    private fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (isFakeLocationMode) {
            fakeMode.start(lpparam)
        } else if (isFixGoogleMapDriftMode) {
            offsetMode.start(lpparam)
            // gmsOffsetMode.start(lpparam)
        }
    }
}
