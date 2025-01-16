package com.houvven.guise.xposed.hook.location

import com.houvven.guise.xposed.config.ModuleConfig
import com.houvven.ktx_xposed.logger.exit
import com.houvven.ktx_xposed.logger.logcat
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.*

/**
 * @author Kaede
 * @since  2025-01-16
 */
class GmsLocationHookOffsetMode(override val config: ModuleConfig) : LocationHookBase(config) {

    override fun start(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Better way to modify google map locations
        // Should find class from lpparam.classLoader
        logcat {
            XposedHelpers.findClassIfExists("com.google.android.gms.maps.model.LatLng", lpparam.classLoader).let { clazz ->
                error("FindGmsLatLng: LatLng, $clazz")
                clazz?.declaredConstructors?.forEach {
                    error("\tFindGmsLatLng constructor: $it")
                    try {
                        error("\tHook constructors")
                        XposedBridge.hookMethod(it, object : XC_MethodHook() {
                            override fun beforeHookedMethod(hookParam: MethodHookParam) {
                                // Does not work?
                                logcat {
                                    info("onConstructorInvoke ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                }
                            }
                        })
                    } catch (e: Throwable) {
                        exit { IllegalStateException("\tHook constructors error", e) }
                    } finally {
                        error("\tHook constructors done")
                    }
                }
                clazz?.declaredMethods?.forEach {
                    error("\tFindGmsLatLng method: $it")
                }
            }

            XposedHelpers.findClassIfExists("com.google.android.gms.maps.model.LatLngBounds", lpparam.classLoader).let { clazz ->
                error("FindGmsLatLng: LatLngBounds, $clazz")
                clazz?.declaredMethods?.forEach {
                    error("\tFindGmsLatLng method: $it")
                }
            }
            XposedHelpers.findClassIfExists("com.google.android.gms.maps.model.LatLngBounds\$Builder", lpparam.classLoader).let { clazz ->
                error("FindGmsLatLng: LatLngBounds\$Builder, $clazz")
                clazz?.declaredMethods?.forEach {
                    error("\tFindGmsLatLng method: $it")
                }
            }
        }
    }
}
