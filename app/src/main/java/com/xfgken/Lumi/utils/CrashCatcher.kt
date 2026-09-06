package com.xfgken.Lumi.utils

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：把 Java 未捕获异常堆栈写入 filesDir/crash.log，
 * 下次启动由 LumiApp 读取并写入日志页，便于无 adb 环境下排查。
 */
object CrashCatcher {

    private const val TAG = "CrashCatcher"
    private const val FILE_NAME = "crash.log"

    fun install(context: android.content.Context) {
        val dir = context.filesDir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sb = StringBuilder()
                sb.append("=== Lumi CRASH ").append(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                ).append(" ===\n")
                sb.append("thread: ").append(thread.name).append('\n')
                sb.append(Log.getStackTraceString(throwable))
                File(dir, FILE_NAME).writeText(sb.toString())
                Log.e(TAG, "崩溃已记录: ${throwable.message}")
            } catch (_: Exception) {
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    /** 返回并清除上次崩溃记录 */
    fun drain(context: android.content.Context): String? {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists()) return null
        return try {
            val text = f.readText()
            f.delete()
            text
        } catch (_: Exception) {
            null
        }
    }
}