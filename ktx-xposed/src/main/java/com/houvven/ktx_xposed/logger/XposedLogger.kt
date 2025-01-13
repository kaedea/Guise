package com.houvven.ktx_xposed.logger

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AndroidAppHelper
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.contentValuesOf
import com.houvven.ktx_xposed.BuildConfig
import com.houvven.ktx_xposed.hook.afterHookedMethod
import com.houvven.ktx_xposed.hook.lppram
import com.houvven.ktx_xposed.logger.XposedLogger.TAG


@SuppressLint("StaticFieldLeak")
object XposedLogger {

    const val TAG = "XposedLogger"

    object Level {
        const val DEBUG = 'D'
        const val INFO = 'I'
        const val ERROR = 'E'
    }

    private val logList = mutableListOf<Pair<Char, String>>()

    private val uri = Uri.parse("content://com.houvven.xposed.runtime.log/module_log")

    fun d(msg: String) {
        basicLog(Level.DEBUG, msg)
    }

    fun i(msg: String) {
        basicLog(Level.INFO, msg)
    }

    fun e(msg: String) {
        basicLog(Level.ERROR, msg)
    }

    fun e(throwable: Throwable) {
        basicLog(Level.ERROR, throwable.toString())
    }

    @SuppressLint("PrivateApi")
    private fun basicLog(level: Char, msg: String) {
        logList.add(level to msg)
    }

    fun doHookModuleLog() {
        Activity::class.java.afterHookedMethod("onPause") { hookParam ->
            val application = hookParam.thisObject as Activity
            if (logList.isEmpty()) return@afterHookedMethod

            runCatching {
                logList.forEach { log ->
                    contentValuesOf(
                        "type" to log.first.toString(),
                        "source" to lppram.packageName,
                        "message" to log.second
                    ).let {
                        application.contentResolver.insert(uri, it)
                    }
                }
                logList.clear()
            }.onFailure {
            }
        }
    }


    class Wrapper(private val logToXposed: Boolean) {
        fun info(text: String, tag: String = TAG) {
            if (BuildConfig.DEBUG) {
                Log.i(tag, text)
                FileLogger.getInstance()?.log("$tag\tI\t$text")
            }
            if (logToXposed) {
                i(text)
            }
        }

        fun error(text: String, tag: String = TAG) {
            if (BuildConfig.DEBUG) {
                Log.e(tag, text)
                FileLogger.getInstance()?.log("$tag\tE\t$text")
            }
            if (logToXposed) {
                e(text)
            }
        }
    }
}


fun debuggable() = BuildConfig.DEBUG

inline fun logcat(logToXposed: Boolean = false, block: XposedLogger.Wrapper.() -> Unit) {
    if (!BuildConfig.DEBUG && !logToXposed) {
        return
    }
    synchronized(TAG) {
        block(XposedLogger.Wrapper(logToXposed))
    }
}

inline fun logcatInfo(tag: String = TAG, logToXposed: Boolean = false, block: () -> String) {
    if (!BuildConfig.DEBUG && !logToXposed) {
        return
    }
    synchronized(TAG) {
        XposedLogger.Wrapper(logToXposed).info(block(), tag)
    }
}

inline fun logcatWarn(tag: String = TAG, logToXposed: Boolean = false, block: () -> String) {
    if (!BuildConfig.DEBUG && !logToXposed) {
        return
    }
    synchronized(TAG) {
        XposedLogger.Wrapper(logToXposed).error(block(), tag)
    }
}

inline fun exit(tag: String = TAG, logToXposed: Boolean = false, block: () -> Throwable) {
    logcatWarn(tag, logToXposed) {
        block().message ?: "Error"
    }
    if (!BuildConfig.DEBUG) {
        Log.e(TAG, "Error", block())
        Runtime.getRuntime().exit(-1)
        return
    }
}

inline fun toast(crossinline block: () -> String) {
    if (!BuildConfig.DEBUG || AndroidAppHelper.currentApplication() == null) {
        return
    }
    val toast = {
        Toast.makeText(AndroidAppHelper.currentApplication(), block(), Toast.LENGTH_SHORT).show()
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        toast()
    } else {
        Handler(Looper.getMainLooper()).post {
            toast()
        }
    }
}
