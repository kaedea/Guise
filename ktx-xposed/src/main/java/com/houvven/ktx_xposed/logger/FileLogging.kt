package com.houvven.ktx_xposed.logger

import android.app.ActivityManager
import android.app.AndroidAppHelper
import android.content.Context
import android.os.Environment
import android.os.Process
import com.houvven.ktx_xposed.logger.XposedLogger.TAG
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

internal class FileLogger private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: FileLogger? = null

        fun getInstance(): FileLogger? {
            AndroidAppHelper.currentApplication()?.let {
                return getInstance(it)
            }
            return null
        }

        private fun getInstance(context: Context): FileLogger {
            return instance ?: synchronized(this) {
                instance ?: FileLogger(context).also { instance = it }
            }
        }

        private fun safeLog(text: String) {
            if (debuggable()) {
                android.util.Log.i(TAG, text)
            }
        }
    }

    private val logQueue = LinkedBlockingQueue<String>()
    private var isRunning = true
    private val baseDir: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    init {
        // 创建基础目录：Download/FileLogs/<包名>/<进程名>
        val packageName = context.packageName
        val processName = run {
            context.getSystemService(ActivityManager::class.java).runningAppProcesses.forEach {
                if (it.pid == Process.myPid()) {
                    if (":" in it.processName) {
                        return@run it.processName.substring(it.processName.lastIndexOf(":") + 1)
                    } else {
                        return@run "main"
                    }
                }
            }
            return@run Process.myPid().toString()
        }
        baseDir = File(Environment.getExternalStorageDirectory(), "Download/FileLogs/$packageName/$processName")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        // 启动日志写入线程
        startLoggingThread()
    }

    private fun startLoggingThread() {
        thread(start = true, name = "FileLogger") {
            while (isRunning) {
                try {
                    // 从队列中获取日志
                    val logMessage = logQueue.take()

                    // 检查日期是否变化，如果变化则创建新文件
                    val date = dateFormat.format(Date())
                    val currentLogFile = createNewLogFile(date)

                    // 写入日志
                    currentLogFile?.let { file ->
                        FileWriter(file, true).use { writer ->
                            writer.append("$logMessage\n")
                            writer.flush()
                        }
                    }
                } catch (e: InterruptedException) {
                    // 线程被中断时退出
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun createNewLogFile(date: String): File? {
        val logFile = File(baseDir, "log_$date.txt")
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
        if (logFile.exists()) {
            return logFile
        }
        return null
    }

    private fun getLogPrefix(): String {
        return "${getCurrentTime()} ${Process.myPid()}-${Process.myTid()}"
    }

    private fun getCurrentTime(): String {
        return simpleDateFormat.format(Date())
    }

    fun log(message: String) {
        logQueue.offer("${getLogPrefix()} $message")
    }

    fun stop() {
        isRunning = false
    }
}
