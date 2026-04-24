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
package com.smartquiz.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

object NetworkUtils {
    /**
     * 获取设备在局域网中的IP地址（单个，兼容旧逻辑）
     */
    @Suppress("DEPRECATION")
    fun getLocalIpAddress(context: Context): String {
        return getAllLocalIpAddresses().firstOrNull()?.second ?: "0.0.0.0"
    }

    /**
     * 获取所有可用的 WiFi IPv4 地址（含热点），返回 Pair(网卡描述, IP地址)
     * 只保留 wlan/ap/swlan 类型网卡，过滤 USB、以太网等
     */
    fun getAllLocalIpAddresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nic = interfaces.nextElement()
                if (nic.isLoopback || !nic.isUp) continue
                val name = nic.name.lowercase()
                val isHotspot = name.startsWith("ap") || name.startsWith("swlan")
                val isWifi = name.startsWith("wlan")
                if (!isWifi && !isHotspot) continue
                val addresses = nic.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        // 热点判断：ap/swlan 前缀，或 wlan0 以外的 wlan 接口，或热点常用 IP 段
                        val isHotspotIp = isHotspot
                                || (isWifi && name != "wlan0")
                                || ip.startsWith("192.168.43.")
                                || ip.startsWith("10.")
                        val label = if (isHotspotIp) "📶 热点 ($ip)" else "🛜 WiFi ($ip)"
                        result.add(Pair(label, ip))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}

object TimeUtils {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun formatTime(timestamp: Long): String = dateFormat.format(Date(timestamp))

    fun formatDuration(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return if (min > 0) "${min}分${sec}秒" else "${sec}秒"
    }
}

/**
 * 调试模式工具：报错时自动复制错误信息到剪贴板
 */
object DebugHelper {

    fun isDebugMode(context: Context): Boolean {
        return context.getSharedPreferences("smart_quiz_config", Context.MODE_PRIVATE)
            .getBoolean("debug_mode", false)
    }

    /**
     * 调试模式下将错误信息复制到剪贴板并提示用户
     */
    fun copyErrorIfDebug(context: Context, e: Throwable, tag: String = "") {
        if (!isDebugMode(context)) return
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val fullError = buildString {
            append("【智能题库 调试信息】\n")
            append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}\n")
            if (tag.isNotBlank()) append("位置: $tag\n")
            append("错误: ${e.message}\n")
            append("─────────────────\n")
            append(sw.toString())
        }
        copyToClipboard(context, fullError)
    }

    /**
     * 调试模式下将任意文本错误信息复制到剪贴板
     */
    fun copyErrorIfDebug(context: Context, errorMsg: String, tag: String = "") {
        if (!isDebugMode(context)) return
        val fullError = buildString {
            append("【智能题库 调试信息】\n")
            append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}\n")
            if (tag.isNotBlank()) append("位置: $tag\n")
            append("─────────────────\n")
            append(errorMsg)
        }
        copyToClipboard(context, fullError)
    }

    private fun copyToClipboard(context: Context, text: String) {
        val action = Runnable {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("debug_error", text))
                Toast.makeText(context, "📋 错误信息已复制到剪贴板", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }
}
