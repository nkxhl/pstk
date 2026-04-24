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
 * 开机自启广播接收器
 * 设备重启后自动恢复 Web 服务
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = context.getSharedPreferences("smart_quiz_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("server_was_running", false)) return

        val port = prefs.getString("port", "8080")?.toIntOrNull() ?: 8080
        val serviceIntent = Intent(context, WebServerService::class.java).apply {
            putExtra(WebServerService.EXTRA_PORT, port)
        }
        context.startForegroundService(serviceIntent)
    }
}
