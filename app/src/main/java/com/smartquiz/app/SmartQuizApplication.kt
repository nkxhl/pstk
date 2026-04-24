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
package com.smartquiz.app

import android.app.Application
import android.content.Intent
import com.smartquiz.api.LLMApiService
import com.smartquiz.db.DatabaseHelper
import com.smartquiz.server.WebServerService
import com.smartquiz.util.DebugHelper
import kotlin.concurrent.thread

class SmartQuizApplication : Application() {

    companion object {
        lateinit var instance: SmartQuizApplication
            private set
        lateinit var llmService: LLMApiService
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 数据库初始化放在后台线程，避免压测后 WAL checkpoint 卡死主线程
        thread(name = "db-init") { DatabaseHelper.getInstance(this) }
        val prefs = getSharedPreferences("smart_quiz_config", android.content.Context.MODE_PRIVATE)
        // 根据当前选用的预设key前缀读取对应的API Key
        val currentKeyPref = prefs.getString("current_key_pref", "key_minimax") ?: "key_minimax"
        val apiKey = prefs.getString(currentKeyPref, "")?.ifBlank {
            prefs.getString("api_key", "") ?: ""  // 兼容旧版本
        } ?: ""
        llmService = LLMApiService(
            apiKey            = apiKey,
            baseUrl           = prefs.getString("api_url",      "https://api.minimax.chat/v1")    ?: "https://api.minimax.chat/v1",
            model             = prefs.getString("model",        "MiniMax-M2.7")                   ?: "MiniMax-M2.7",
            visionModel       = prefs.getString("vision_model", "MiniMax-M2.7")                   ?: "MiniMax-M2.7",
            customSystemPrompt = prefs.getString("system_prompt", "")                              ?: ""
        )

        // 全局未捕获异常处理：调试模式下复制错误到剪贴板
        setupDebugExceptionHandler()

        // App 启动时自动恢复 Web 服务（如果上次服务是开启状态）
        if (prefs.getBoolean("server_was_running", false)) {
            try {
                val port = prefs.getString("port", "8080")?.toIntOrNull() ?: 8080
                val serviceIntent = Intent(this, WebServerService::class.java).apply {
                    putExtra(WebServerService.EXTRA_PORT, port)
                }
                startForegroundService(serviceIntent)
            } catch (e: Exception) {
                // 前台服务启动失败（如后台限制），忽略，用户可手动重启服务
                android.util.Log.w("SmartQuiz", "自动恢复 Web 服务失败: ${e.message}")
            }
        }
    }

    private fun setupDebugExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DebugHelper.copyErrorIfDebug(this, throwable, "未捕获异常 [${thread.name}]")
            } catch (_: Exception) {}
            // 交还给系统默认处理器（弹出崩溃对话框）
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
