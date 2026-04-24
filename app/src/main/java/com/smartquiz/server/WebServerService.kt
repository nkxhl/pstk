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

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.smartquiz.app.MainActivity
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Web服务器前台服务 - 多层保活机制保持HTTP服务持续运行
 */
class WebServerService : Service() {

    private var webServer: QuizWebServer? = null
    private var buzzerWebServer: BuzzerWebServer? = null

    companion object {
        const val CHANNEL_ID = "web_server_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_PORT = "port"
        const val ACTION_RESTART = "com.smartquiz.action.RESTART_SERVICE"
        private const val WATCHDOG_REQUEST_CODE = 2001
        private const val WATCHDOG_INTERVAL_MS = 60_000L  // 60秒看门狗

        @Volatile
        var isRunning = false
            private set
        @Volatile
        var currentPort = 8080
            private set
        @Volatile
        var serverInstance: QuizWebServer? = null
            private set
        @Volatile
        var buzzerServerInstance: BuzzerWebServer? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = if (intent != null && intent.hasExtra(EXTRA_PORT)) {
            intent.getIntExtra(EXTRA_PORT, 8080)
        } else {
            getSharedPreferences("smart_quiz_config", MODE_PRIVATE)
                .getString("port", "8080")?.toIntOrNull() ?: 8080
        }
        currentPort = port

        startForeground(NOTIFICATION_ID, createNotification(port))

        if (webServer == null) {
            webServer = QuizWebServer(applicationContext, port)
            try {
                // 核心线程 16 条，最大 200 条，队列 512：支撑 500 持续并发
                // DiscardPolicy：队列满时直接丢弃（连接已被 Semaphore 限流，此处不应再触发）
                val executor = ThreadPoolExecutor(
                    16, 200, 60L, TimeUnit.SECONDS,
                    LinkedBlockingQueue(512),
                    ThreadPoolExecutor.DiscardPolicy()
                )
                // Semaphore 限制最大同时飞行连接数为 300：
                // 超出部分会因 acquire 阻塞 accept 线程，让多余连接在 OS TCP backlog 排队，
                // 而不是全部进入内存分配 buffer，从根本上防止 OOM。
                val connSemaphore = java.util.concurrent.Semaphore(300)
                val clientHandlers = java.util.Collections.synchronizedList(mutableListOf<NanoHTTPD.ClientHandler>())
                webServer?.setAsyncRunner(object : NanoHTTPD.AsyncRunner {
                    override fun exec(code: NanoHTTPD.ClientHandler) {
                        connSemaphore.acquire()   // 超过 300 时阻塞 accept 线程，形成背压
                        clientHandlers.add(code)
                        executor.execute {
                            try { code.run() } finally {
                                connSemaphore.release()
                            }
                        }
                    }
                    override fun closed(code: NanoHTTPD.ClientHandler) {
                        clientHandlers.remove(code)
                    }
                    override fun closeAll() {
                        synchronized(clientHandlers) { clientHandlers.toList() }.forEach { it.close() }
                        clientHandlers.clear()
                    }
                })
                webServer?.start()
                isRunning = true
                serverInstance = webServer
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
                return START_STICKY
            }
        }

        // 启动抢答比赛统计服务器（8081端口，老师端专用）
        if (buzzerWebServer == null) {
            buzzerWebServer = BuzzerWebServer(applicationContext, 8081)
            try {
                buzzerWebServer?.start()
                buzzerServerInstance = buzzerWebServer
            } catch (e: Exception) {
                e.printStackTrace()
                // 8081端口失败不影响主服务器运行
                buzzerWebServer = null
                buzzerServerInstance = null
            }
        }

        // 每次启动都重新调度看门狗
        scheduleWatchdog()

        return START_STICKY
    }

    override fun onDestroy() {
        // 如果是被系统意外杀死（非用户主动停止），立即重启
        val prefs = getSharedPreferences("smart_quiz_config", MODE_PRIVATE)
        if (prefs.getBoolean("server_was_running", false)) {
            scheduleImmediateRestart()
        }

        webServer?.stop()
        webServer = null
        serverInstance = null
        buzzerWebServer?.stop()
        buzzerWebServer = null
        buzzerServerInstance = null
        isRunning = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App 从任务列表划掉时，通过 AlarmManager 延迟重启
        val prefs = getSharedPreferences("smart_quiz_config", MODE_PRIVATE)
        if (prefs.getBoolean("server_was_running", false)) {
            scheduleImmediateRestart()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 调度看门狗：每隔60秒触发一次广播，确保服务存活 */
    private fun scheduleWatchdog() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ServiceRestartReceiver::class.java).apply {
            action = ACTION_RESTART
        }
        val pi = PendingIntent.getBroadcast(
            this, WATCHDOG_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
            pi
        )
    }

    /** 立即调度一次重启（1秒后），用于被系统杀死或 onTaskRemoved 时 */
    private fun scheduleImmediateRestart() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ServiceRestartReceiver::class.java).apply {
            action = ACTION_RESTART
        }
        val pi = PendingIntent.getBroadcast(
            this, WATCHDOG_REQUEST_CODE + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1_000L,
            pi
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "题库Web服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持题库Web服务器在后台运行"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(port: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("智能题库服务运行中")
            .setContentText("学生端口: $port | 抢答统计: 8081")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
