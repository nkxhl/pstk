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

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.smartquiz.R
import com.smartquiz.databinding.ActivitySplashBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SplashActivity : AppCompatActivity() {

	private lateinit var binding: ActivitySplashBinding

	private val handler = Handler(Looper.getMainLooper())
	private var countDown = 10
	private var autoPageRunnable: Runnable? = null
	private var countDownRunnable: Runnable? = null
	private var indicators = listOf<View>()
	private lateinit var splashPrefs: SharedPreferences

	// 本地降级图片资源
	private val localImages = listOf(
		R.drawable.splash_1,
		R.drawable.splash_2,
		R.drawable.splash_3
	)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// 全屏沉浸式
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		window.decorView.systemUiVisibility = (
			View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				or View.SYSTEM_UI_FLAG_FULLSCREEN
			)

		binding = ActivitySplashBinding.inflate(layoutInflater)
		setContentView(binding.root)

		splashPrefs = getSharedPreferences("splash_cache", MODE_PRIVATE)

		binding.btnSkip.setOnClickListener { goMain() }

		// 立即显示本地图片，同时后台拉取网络图片
		setupSplash(emptyList())
		fetchSplashImages()
	}

	private fun fetchSplashImages() {
		// 如有缓存 URL，优先用缓存（Glide 磁盘缓存已有图片直接展示）
		val cachedKey = splashPrefs.getString("cached_url_key", null)
		val cachedUrls = splashPrefs.getString("cached_urls", null)
			?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
		if (cachedUrls.isNotEmpty()) {
			switchToRemote(cachedUrls)
		}

		val client = OkHttpClient.Builder()
			.connectTimeout(4, TimeUnit.SECONDS)
			.readTimeout(4, TimeUnit.SECONDS)
			.build()
		val request = Request.Builder()
			.url("https://pstk.inc.work/api/splash.json")
			.build()
		client.newCall(request).enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				// 网络失败：本地缓存已在上方处理，无需额外操作
			}

			override fun onResponse(call: Call, response: Response) {
				val urls = mutableListOf<String>()
				var newKey = ""
				try {
					if (response.isSuccessful) {
						val body = response.body?.string() ?: ""
						val json = JSONObject(body)
						val arr = json.optJSONArray("images")
						if (arr != null) {
							for (i in 0 until arr.length()) {
								val url = arr.optString(i)
								if (url.isNotBlank()) urls.add(url)
							}
							newKey = urls.joinToString(",")
						}
					}
				} catch (_: Exception) {}

				if (urls.isNotEmpty()) {
					// URL 有变化时更新缓存并立即切换；相同时 Glide 磁盘缓存直接命中
					if (newKey != cachedKey) {
						splashPrefs.edit()
							.putString("cached_url_key", newKey)
							.putString("cached_urls", newKey)
							.apply()
					}
					runOnUiThread { switchToRemote(urls) }
				}
			}
		})
	}

	/** 将 ViewPager 切换为网络图片（保持当前页位置，重建适配器） */
	private fun switchToRemote(urls: List<String>) {
		if (urls.isEmpty()) return
		val currentPos = binding.splashViewPager.currentItem
		val count = urls.size
		setupIndicators(count)
		binding.splashViewPager.adapter = SplashAdapter(
			count = count,
			getItem = { pos -> SplashItem.Remote(urls[pos]) }
		)
		binding.splashViewPager.setCurrentItem(minOf(currentPos, count - 1), false)
		binding.splashViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
			override fun onPageSelected(position: Int) { updateIndicator(position) }
		})
		autoPageRunnable?.let { handler.removeCallbacks(it) }
		startAutoPage(count)
	}

	private fun setupSplash(remoteUrls: List<String>) {
		// 首次调用：显示本地图片，同时启动倒计时和自动翻页
		val count = localImages.size
		setupIndicators(count)
		binding.splashViewPager.adapter = SplashAdapter(
			count = count,
			getItem = { pos -> SplashItem.Local(localImages[pos]) }
		)
		binding.splashViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
			override fun onPageSelected(position: Int) { updateIndicator(position) }
		})
		startAutoPage(count)
		startCountDown()
	}

	private fun setupIndicators(count: Int) {
		binding.indicatorLayout.removeAllViews()
		if (count <= 1) {
			indicators = emptyList()
			return
		}
		val dp6 = (6 * resources.displayMetrics.density).toInt()
		val dp4 = (4 * resources.displayMetrics.density).toInt()
		val list = mutableListOf<View>()
		repeat(count) { i ->
			val dot = View(this).apply {
				layoutParams = ViewGroup.MarginLayoutParams(dp6, dp6).also {
					it.leftMargin = dp4
					it.rightMargin = dp4
				}
				background = getDrawable(android.R.drawable.presence_online)
				alpha = if (i == 0) 1f else 0.4f
			}
			binding.indicatorLayout.addView(dot)
			list.add(dot)
		}
		indicators = list
	}

	private fun updateIndicator(pos: Int) {
		indicators.forEachIndexed { i, v -> v.alpha = if (i == pos) 1f else 0.4f }
	}

	private fun startAutoPage(count: Int) {
		if (count <= 1) return
		autoPageRunnable = object : Runnable {
			override fun run() {
				val next = (binding.splashViewPager.currentItem + 1) % count
				binding.splashViewPager.setCurrentItem(next, true)
				handler.postDelayed(this, 3000)
			}
		}
		handler.postDelayed(autoPageRunnable!!, 3000)
	}

	private fun startCountDown() {
		countDownRunnable = object : Runnable {
			override fun run() {
				countDown--
				if (countDown <= 0) {
					goMain()
				} else {
					binding.btnSkip.text = "跳过 $countDown"
					handler.postDelayed(this, 1000)
				}
			}
		}
		handler.postDelayed(countDownRunnable!!, 1000)
	}

	private fun goMain() {
		handler.removeCallbacksAndMessages(null)
		startActivity(Intent(this, MainActivity::class.java).apply {
			// 透传原始 intent（例如分享文件启动）
			val src = intent
			if (src.action != null && src.action != Intent.ACTION_MAIN) {
				action = src.action
				data = src.data
				src.extras?.let { putExtras(it) }
				src.categories?.forEach { addCategory(it) }
			}
		})
		finish()
		overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
	}

	override fun onDestroy() {
		super.onDestroy()
		handler.removeCallbacksAndMessages(null)
	}

	// ── 数据模型 ──────────────────────────────────────────────────────────────
	sealed class SplashItem {
		data class Remote(val url: String) : SplashItem()
		data class Local(val resId: Int) : SplashItem()
	}

	// ── 适配器 ────────────────────────────────────────────────────────────────
	class SplashAdapter(
		private val count: Int,
		private val getItem: (Int) -> SplashItem
	) : RecyclerView.Adapter<SplashAdapter.VH>() {

		override fun getItemCount() = count

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
			val iv = ImageView(parent.context).apply {
				layoutParams = ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT
				)
				scaleType = ImageView.ScaleType.CENTER_CROP
				setBackgroundColor(Color.BLACK)
			}
			return VH(iv)
		}

		override fun onBindViewHolder(holder: VH, position: Int) {
			when (val item = getItem(position)) {
				is SplashItem.Remote -> Glide.with(holder.iv)
					.load(item.url)
					.diskCacheStrategy(DiskCacheStrategy.ALL)
					.centerCrop()
					.into(holder.iv)
				is SplashItem.Local -> Glide.with(holder.iv)
					.load(item.resId)
					.centerCrop()
					.into(holder.iv)
			}
		}

		class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)
	}
}
