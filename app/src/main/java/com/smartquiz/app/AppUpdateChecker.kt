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

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 在线版本更新检查器。
 * 从 https://pstk.inc.work/version.json 获取最新版本信息，与当前安装版本比对，
 * 有新版本时弹对话框提示用户下载安装。
 */
object AppUpdateChecker {

    private const val VERSION_URL_PRIMARY = "https://pstk.inc.work/version.json"
    private const val VERSION_URL_FALLBACK = "http://opt.set.work:8000/version.json"
    private const val PREFS_NAME  = "update_prefs"
    private const val KEY_SKIP    = "skip_version_code"

    /**
     * 在 Activity 启动后调用，静默检查更新。
     * [forceCheck] = true 时忽略"跳过此版本"记录，始终弹窗。
     */
    fun check(context: Context, forceCheck: Boolean = false) {
        Thread {
            try {
                val info = fetchVersionInfo()
                val currentCode = currentVersionCode(context)

                if (info == null) {
                    // 网络请求成功但解析失败（正常网络错误走 catch）
                    if (forceCheck) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "获取版本信息失败，请检查网络", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@Thread
                }

                if (info.versionCode <= currentCode) {
                    // 已是最新版本
                    if (forceCheck) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "已是最新版本 v${info.versionName}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@Thread
                }

                // 用户曾点"跳过此版本"（仅自动检查时生效）
                if (!forceCheck) {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (prefs.getInt(KEY_SKIP, -1) == info.versionCode) return@Thread
                }

                Handler(Looper.getMainLooper()).post {
                    showUpdateDialog(context, info, currentCode)
                }
            } catch (e: Exception) {
                Log.e("AppUpdateChecker", "check 异常: ${e.javaClass.simpleName}: ${e.message}", e)
                if (forceCheck) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "检查更新失败，请检查网络连接", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    // ── 内部数据类 ──────────────────────────────────────────────
    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val changeLog: String,
        val forceUpdate: Boolean,
        val releaseDate: String
    )

    // ── 从服务器读取 version.json ────────────────────────────────
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)   // 手动跟随，跳过私有地址重定向
        .followSslRedirects(false)
        .build()

    private fun fetchVersionInfo(): VersionInfo? {
        val t = System.currentTimeMillis()
        // 主地址：先试 pstk.inc.work，失败后切换备用地址
        val text = try {
            fetchSkippingPrivateRedirects("$VERSION_URL_PRIMARY?t=$t")
                .also { Log.i("AppUpdateChecker", "主地址请求成功") }
        } catch (e: Exception) {
            Log.w("AppUpdateChecker", "主地址失败(${e.javaClass.simpleName}: ${e.message})，切换备用地址")
            try {
                fetchDirect("$VERSION_URL_FALLBACK?t=$t")
                    .also { Log.i("AppUpdateChecker", "备用地址请求成功") }
            } catch (e2: Exception) {
                Log.e("AppUpdateChecker", "备用地址也失败: ${e2.javaClass.simpleName}: ${e2.message}", e2)
                return null
            }
        }
        return try {
            val json = JSONObject(text)
            VersionInfo(
                versionCode  = json.getInt("versionCode"),
                versionName  = json.getString("versionName"),
                downloadUrl  = json.getString("downloadUrl"),
                changeLog    = json.optString("changeLog", ""),
                forceUpdate  = json.optBoolean("forceUpdate", false),
                releaseDate  = json.optString("releaseDate", "")
            )
        } catch (e: Exception) {
            Log.e("AppUpdateChecker", "JSON 解析失败: ${e.message}", e)
            null
        }
    }

    /**
     * 跟随重定向，但遇到私有/局域网地址时跳过，直接返回原始请求的响应体。
     * 解决服务器将公网域名 308 重定向到局域网地址导致外网用户无法更新的问题。
     */
    private fun fetchSkippingPrivateRedirects(urlStr: String, maxRedirects: Int = 5): String {
        var url = urlStr
        var redirectsLeft = maxRedirects
        while (redirectsLeft-- > 0) {
            val req = Request.Builder().url(url).build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            if (code in 300..399) {
                val location = resp.header("Location")
                resp.close()
                if (location == null) throw Exception("重定向无 Location 头")
                val nextUrl = if (location.startsWith("http")) location
                              else URL(URL(url), location).toString()
                // 若重定向目标是私有地址则直接用 OkHttp 允许重定向重新请求原始 URL
                if (isPrivateAddress(nextUrl)) {
                    Log.w("AppUpdateChecker", "跳过私有地址重定向: $nextUrl，回退直连原始地址")
                    return fetchDirect(urlStr)
                }
                url = nextUrl
                continue
            }
            val body = resp.body?.string() ?: throw Exception("响应体为空")
            resp.close()
            if (code !in 200..299) throw Exception("HTTP 错误码 $code，内容: ${body.take(200)}")
            return body
        }
        throw Exception("重定向次数超限")
    }

    /** 直接用 OkHttpClient（允许重定向）访问，作为回退策略 */
    private fun fetchDirect(urlStr: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(urlStr).build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: throw Exception("响应体为空")
        resp.close()
        if (resp.code !in 200..299) throw Exception("直连 HTTP 错误码 ${resp.code}")
        return body
    }

    /** 判断 URL 的 host 是否为私有/局域网地址 */
    private fun isPrivateAddress(urlStr: String): Boolean {
        return try {
            val host = URL(urlStr).host
            // 常见私有域名特征
            if (host.endsWith(".local") || host == "localhost") return true
            val addr = InetAddress.getByName(host)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
        } catch (_: Exception) { false }
    }

    // ── 获取当前安装版本号 ───────────────────────────────────────
    private fun currentVersionCode(context: Context): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionCode
            }
        } catch (_: PackageManager.NameNotFoundException) { 0 }
    }

    // ── 弹出更新对话框 ───────────────────────────────────────────
    private fun showUpdateDialog(context: Context, info: VersionInfo, currentCode: Int) {
        val msg = buildString {
            append("当前版本：v${currentVersionName(context)}\n")
            append("最新版本：v${info.versionName}")
            if (info.releaseDate.isNotEmpty()) append("  (${info.releaseDate})")
            if (info.changeLog.isNotEmpty()) {
                append("\n\n更新内容：\n${info.changeLog}")
            }
        }

        val builder = AlertDialog.Builder(context)
            .setTitle("🎉 发现新版本")
            .setMessage(msg)
            .setPositiveButton("立即更新") { _, _ ->
                downloadAndInstall(context, info)
            }

        if (!info.forceUpdate) {
            builder.setNegativeButton("稍后再说", null)
            builder.setNeutralButton("跳过此版本") { _, _ ->
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_SKIP, info.versionCode).apply()
            }
        } else {
            builder.setCancelable(false)
        }

        builder.show()
    }

    private fun currentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
    }

    // ── 下载 APK 并安装 ──────────────────────────────────────────
    private fun downloadAndInstall(context: Context, info: VersionInfo) {
        Toast.makeText(context, "开始下载新版本，请稍候…", Toast.LENGTH_SHORT).show()

        val fileName = "pstk-${info.versionName}.apk"
        val destFile = File(context.cacheDir, "update/$fileName")
        destFile.parentFile?.mkdirs()
        destFile.takeIf { it.exists() }?.delete()

        // 优先用 DownloadManager（显示通知栏进度）
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("拍书题库 更新包")
            .setDescription("v${info.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val downloadId = dm.enqueue(request)

        // 监听下载完成广播
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                context.unregisterReceiver(this)

                val query  = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val statusCol  = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    if (statusCol >= 0 && cursor.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL) {
                        val localUri = if (localUriCol >= 0) cursor.getString(localUriCol) else null
                        if (localUri != null) {
                            installApk(context, File(Uri.parse(localUri).path ?: return))
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "下载失败，请前往官网手动下载", Toast.LENGTH_LONG).show()
                            openWebsite(context)
                        }
                    }
                }
                cursor.close()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "安装包文件不存在，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openWebsite(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://pstk.inc.work"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
