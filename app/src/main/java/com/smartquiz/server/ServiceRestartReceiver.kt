/*
 * Copyright (c) 2025 xionghonglin (183209@qq.com)
 *
 * 本作品依据 知识共享 署名-非商业性使用 4.0 国际许可协议（CC BY-NC 4.0）授权。
 * 您可以自由地共享和改编本作品，但须遵守以下条件：
 *   - 署名：您必须注明原作者（xionghonglin / 183209@qq.com）并提供许可协议链接。
 *   - 非商业性使用：您不得将本作品用于商业目的。
 *
 * 许可协议全文：https://creativecommons.org/licenses/by-nc/4.0/
 */
package com.smartquiz.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 服务重启广播接收器
 * 由 AlarmManager 看门狗触发，确保服务持续运行
 */
class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("smart_quiz_config", Context.MODE_PRIVATE)
        val shouldRun = prefs.getBoolean("server_was_running", false)
        if (!shouldRun) return

        if (!WebServerService.isRunning) {
            val port = prefs.getString("port", "8080")?.toIntOrNull() ?: 8080
            val serviceIntent = Intent(context, WebServerService::class.java).apply {
                putExtra(WebServerService.EXTRA_PORT, port)
            }
            context.startForegroundService(serviceIntent)
        } else {
            // 服务仍在运行，重新调度下一次看门狗（通过发一个空 intent 给服务让其调用 scheduleWatchdog）
            val serviceIntent = Intent(context, WebServerService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
