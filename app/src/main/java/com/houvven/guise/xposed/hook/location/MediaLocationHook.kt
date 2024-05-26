package com.houvven.guise.xposed.hook.location

import android.database.Cursor
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Log
import com.houvven.guise.xposed.LoadPackageHandler
import com.houvven.ktx_xposed.hook.afterHookAllConstructors
import com.houvven.ktx_xposed.hook.afterHookedMethod
import com.houvven.ktx_xposed.logger.XposedLogger.TAG
import com.houvven.ktx_xposed.logger.logcat
import de.robv.android.xposed.XC_MethodHook.Unhook
import java.io.BufferedInputStream
import java.lang.reflect.Method
import java.util.*

/**
 * @author Kaede
 * @since  2024-05-22
 */
class MediaLocationHook : LoadPackageHandler {
    private val isFixGoogleMapDriftMode by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { config.fixGoogleMapDrift }
    private val lookUpAndroidxSet: MutableSet<Unhook> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { hashSetOf() }

    override fun onHook() {
        logcat {
            info("onHook config: latitude=${config.latitude}, longitude=${config.longitude}, fixGoogleMapDrift=${config.fixGoogleMapDrift}")
            info("\tfrom:")
            Throwable().stackTrace.forEach {
                info("\t\t$it")
            }
        }
        if (isFixGoogleMapDriftMode) {
            Log.i(TAG, "hookMediaLocation for pkg: ${config.packageName}")
            hookMediaLocation()
        }
    }

    private fun hookMediaLocation() {
        // Image File
        tryHookExifInterface()

        // Video File
        tryMediaMetadataRetriever()

        // MediaStore
        tryHookCursorGetDouble()
    }

    private fun tryHookExifInterface() {
        ExifInterface::class.java.run {
            for (method in declaredMethods) {
                if (method.name == "getLatLong") {
                    hookExifInterfaceGetLatLongMethod(this, method)
                }
            }
        }
        try {
            androidx.exifinterface.media.ExifInterface::class.java.run {
                Log.i(TAG, "list androidx ExifInterface: $declaredMethods")
                for (method in declaredMethods) {
                    Log.i(TAG, "list androidx ExifInterface: $method")
                    if (method.name == "getLatLong") {
                        hookExifInterfaceGetLatLongMethod(this, method)
                    }
                }
            }
            Log.i(TAG, "hookAndroidxExifInterface done")
        } catch (e: Throwable) {
            Log.i(TAG, "hookAndroidxExifInterfaceErr: $e")
            lookUpAndroidXExifInterface()
        }

    }

    private fun lookUpAndroidXExifInterface() {
        Log.i(TAG, "lookUpAndroidXExifInterface by BufferedInputStream")
        try {
            BufferedInputStream::class.java.afterHookAllConstructors { hookParam ->
                    synchronized(this) {
                        if (lookUpAndroidxSet.isNotEmpty()) {
                            Log.i(TAG, "onCreateBufferedInputStream: " +
                                    "${hookParam.method}, args=${hookParam.args?.joinToString { it?.toString() ?: "null" }}")
                            val stackTrace = Throwable().stackTrace
                            val caller = run {
                                var callerIndex = 0
                                stackTrace.forEachIndexed { index, it ->
                                    if (it.className == "LSPHooker_" && it.methodName == "constructor") {
                                        callerIndex = index + 1
                                        return@forEachIndexed
                                    }
                                }
                                return@run if (callerIndex >= 0) stackTrace[callerIndex] else null
                            }.also {
                                if (it != null
                                    && !it.className.startsWith("android.")
                                    && !it.className.startsWith("com.android.")) {
                                    Log.i(TAG, "\tfrom:")
                                    stackTrace.forEach {
                                        Log.i(TAG, "\t\t$it")
                                    }
                                }
                                Log.i(TAG, "\tcaller: $it")
                            }
                            try {
                                val callerClass = Class.forName(
                                    caller!!.className,
                                    true,
                                    Thread.currentThread().contextClassLoader
                                )
                                Log.i(TAG, "\tcallerClass: $callerClass")
                                val getLatLngMethod1 =
                                    callerClass.declaredMethods.find { it.parameters.isEmpty() && it.returnType == DoubleArray::class.java }
                                val getLatLngMethod2 =
                                    callerClass.declaredMethods.find { it.parameters.size == 1 && it.parameters[0].type == FloatArray::class.java && it.returnType == Boolean::class.java }
                                Log.i(TAG, "\tgetLatLngMethod1: $getLatLngMethod1")
                                Log.i(TAG, "\tgetLatLngMethod2: $getLatLngMethod2")

                                if (getLatLngMethod1 != null) {
                                    val getLatLngMethod3 =
                                        callerClass.methods.find { it.parameters.size == 1 && it.parameters[0].type == FloatArray::class.java && it.returnType == Boolean::class.java }
                                    Log.i(TAG, "\tgetLatLngMethod3: $getLatLngMethod3")
                                    Log.i(TAG, "\tdump class methods:")
                                    callerClass.declaredMethods.forEach {
                                        Log.i(TAG, "\t\t$it")
                                    }
                                    try {
                                        Log.i(TAG, "hookGetLatLongMethod1: $getLatLngMethod1")
                                        hookExifInterfaceGetLatLongMethod(callerClass, getLatLngMethod1)
                                        lookUpAndroidxSet.removeAll {
                                            it.unhook()
                                            return@removeAll true
                                        }
                                    } catch (e: Exception) {
                                        Log.i(TAG, "hookGetLatLongMethod1Err: $e")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.i(TAG, "getCallerClassErr: $e")
                            }
                        }
                    }
                }.forEach { unHook ->
                lookUpAndroidxSet.add(unHook)
            }
            Log.i(TAG, "hookBufferedInputStream done")
        } catch (e: Exception) {
            Log.i(TAG, "hookBufferedInputStream: $e")
        }
    }

    private fun tryMediaMetadataRetriever() {
        MediaMetadataRetriever::class.java.run {
            for (method in declaredMethods) {
                if (method.name == "extractMetadata" && method.returnType == String::class.java) {
                    if (method.parameters.size == 1 && method.parameters[0].type == Int::class.java) {
                        hookExtractMetadataMethod(this, method)
                        break
                    }
                }
            }
        }
        Log.i(TAG, "hook MediaMetadataRetriever done")
    }

    private fun hookExifInterfaceGetLatLongMethod(clazz: Class<*>, method: Method) {
        Log.i(TAG, "hookGetLatLongMethod: $method")
        val target = method.name
        val paramsTypes = method.parameterTypes
        clazz.afterHookedMethod(target, *paramsTypes) { hookParam ->
            logcat {
                info("onMethodInvoke $target, args=${hookParam.args}, result=${hookParam.result}")
                // info("\tfrom:")
                // Throwable().stackTrace.forEach {
                //     info("\t\t$it")
                // }
            }
            if (hookParam.result != null) {
                if (hookParam.result is Boolean && (hookParam.result as Boolean)) {
                    if (hookParam.args != null && hookParam.args.size == 1 && hookParam.args[0] is FloatArray) {
                        val output = hookParam.args[0] as FloatArray
                        logcat {
                            info("onGetLatLong: ${output.contentToString()}")
                        }
                    }
                } else if (hookParam.result is DoubleArray) {
                    if (hookParam.args == null || hookParam.args!!.isEmpty()) {
                        val gcj02 = CoordTransform.wgs84ToGcj02(CoordTransform.LatLng((hookParam.result as DoubleArray)[0], (hookParam.result as DoubleArray)[1]))
                        logcat {
                            info("onGetLatLong: ${(hookParam.result as DoubleArray).contentToString()} >> [${gcj02?.latitude}, ${gcj02?.longitude}]")
                        }
                        if (gcj02 != null) {
                            (hookParam.result as DoubleArray)[0] = gcj02.latitude
                            (hookParam.result as DoubleArray)[1] = gcj02.longitude
                        }
                    }
                }
            }
        }
    }

    private fun hookExtractMetadataMethod(clazz: Class<*>, method: Method) {
        Log.i(TAG, "hookExtractMetadataMethod: $method")
        val target = method.name
        val paramsTypes = method.parameterTypes
        clazz.afterHookedMethod(target, *paramsTypes) { hookParam ->
            logcat {
                info("onMethodInvoke $target, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                // info("\tfrom:")
                // Throwable().stackTrace.forEach {
                //     info("\t\t$it")
                // }
            }
            if (hookParam.result is String) {
                if (hookParam.args != null && hookParam.args.size == 1 && hookParam.args[0] is Int) {
                    val type = hookParam.args[0] as Int
                    if (type == MediaMetadataRetriever.METADATA_KEY_LOCATION) {
                        // Example
                        // onMethodInvoke extractMetadata, args=[23], result=+23.1584+113.3839/
                        val text = hookParam.result as String
                        logcat {
                            info("get METADATA_KEY_LOCATION: $text")
                        }
                        if ((text.startsWith("+") || text.startsWith("-")) && text.endsWith("/")) {
                            var separatorIndex = text.lastIndexOf("+")
                            if (separatorIndex <= 0) {
                                separatorIndex = text.lastIndexOf("-")
                            }
                            if (separatorIndex > 0) {
                                val lat = text.substring(0, separatorIndex).toDouble()
                                val lng = text.substring(separatorIndex, text.length - 1).toDouble()
                                val gcj02 = CoordTransform.wgs84ToGcj02(CoordTransform.LatLng(lat, lng))
                                if (gcj02 != null) {
                                    val newLatLogText = "${if (gcj02.latitude >= 0) "+" else ""}${gcj02.latitude}${if (gcj02.longitude >= 0) "+" else ""}${gcj02.longitude}/"
                                    logcat {
                                        info("onGetLatLong: ${hookParam.result} >> $newLatLogText")
                                        hookParam.result = newLatLogText
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun tryHookCursorGetDouble() {
        Cursor::class.java.run {
            for (method in declaredMethods) {
                if (method.name == "getDouble") {
                    hookCursorGetDoubleMethod(this, method)
                    break
                }
            }
        }
        Log.i(TAG, "hook Cursor done")
    }

    @Suppress("KotlinConstantConditions")
    private fun hookCursorGetDoubleMethod(clazz: Class<*>, method: Method) {
        Log.i(TAG, "hookCursorGetDoubleMethod: $method")
        val target = method.name
        val paramsTypes = method.parameterTypes
        clazz.afterHookedMethod(target, *paramsTypes) { hookParam ->
            logcat {
                info("onMethodInvoke $target, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                // info("\tfrom:")
                // Throwable().stackTrace.forEach {
                //     info("\t\t$it")
                // }
            }
            if (hookParam.result is Double) {
                if (hookParam.args != null && hookParam.args.size == 1 && hookParam.args[0] is String) {
                    val column = hookParam.args[0] as String
                    if (column == MediaStore.Images.ImageColumns.LATITUDE || column == MediaStore.Video.VideoColumns.LATITUDE) {
                        logcat {
                            info("getColumn LATITUDE: ${hookParam.result}")
                        }                    }
                    if (column == MediaStore.Images.ImageColumns.LONGITUDE || column == MediaStore.Video.VideoColumns.LONGITUDE) {
                        logcat {
                            info("getColumn LONGITUDE: ${hookParam.result}")
                        }
                    }
                }
            }
        }
    }
}
