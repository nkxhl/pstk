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

import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.smartquiz.R
import com.smartquiz.api.LLMApiService
import com.smartquiz.databinding.FragmentSettingsBinding
import com.smartquiz.db.DatabaseHelper
import com.smartquiz.model.QuestionBank
import com.smartquiz.server.WebServerService
import com.smartquiz.util.NetworkUtils
import com.smartquiz.util.QrCodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: SharedPreferences
    private lateinit var llmService: LLMApiService
    private val selectedIps = mutableSetOf<String>()

    // 导入文件选择器
    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) doImportData(uri)
    }

    data class ModelPreset(val name: String, val baseUrl: String, val textModel: String, val visionModel: String, val keyPrefKey: String, val maxTokens: Int, val mergePrompt: Boolean = false)

    private val modelPresets = listOf(
        // CopilotCLI：默认 GPT-4o
        ModelPreset("CopilotCLI GPT-4o",            "http://opt.set.work:5050/v1",  "gpt-4o",                 "gpt-4o",                 "key_copilotcli", 16000),
        ModelPreset("CopilotCLI GPT-4.1",          "http://opt.set.work:5050/v1",  "gpt-4.1",                "gpt-4.1",                "key_copilotcli", 16000),
        ModelPreset("CopilotCLI GPT-5 Mini",       "http://opt.set.work:5050/v1",  "gpt-5-mini",             "gpt-5-mini",             "key_copilotcli", 16000),
        ModelPreset("CopilotCLI GPT-5.4",          "http://opt.set.work:5050/v1",  "gpt-5.4",                "gpt-5.4",                "key_copilotcli", 16000),
        ModelPreset("CopilotCLI GPT-5.3-Codex",    "http://opt.set.work:5050/v1",  "gpt-5.3-codex",          "gpt-5.3-codex",          "key_copilotcli", 16000),
        ModelPreset("CopilotCLI Claude Sonnet 4.6","http://opt.set.work:5050/v1",  "claude-sonnet-4-6",      "claude-sonnet-4-6",      "key_copilotcli", 16000),
        ModelPreset("CopilotCLI Claude Opus 4.6",  "http://opt.set.work:5050/v1",  "claude-opus-4-6",        "claude-opus-4-6",        "key_copilotcli", 16000),
        ModelPreset("CopilotCLI Gemini 3.1 Pro",   "http://opt.set.work:5050/v1",  "gemini-3.1-pro",         "gemini-3.1-pro",         "key_copilotcli", 16000),
        // MiniMax：最大输出 128K
        ModelPreset("MiniMax M2.7",           "https://api.minimax.chat/v1",                           "MiniMax-M2.7",           "MiniMax-M2.7",           "key_minimax",  128000),
        ModelPreset("MiniMax M2.7 极速",       "https://api.minimax.chat/v1",                           "MiniMax-M2.7-highspeed", "MiniMax-M2.7-highspeed", "key_minimax",  128000),
        ModelPreset("MiniMax M2.5",           "https://api.minimax.chat/v1",                           "MiniMax-M2.5",           "MiniMax-M2.5",           "key_minimax",  128000),
        ModelPreset("MiniMax M2.5 极速",       "https://api.minimax.chat/v1",                           "MiniMax-M2.5-highspeed", "MiniMax-M2.5-highspeed", "key_minimax",  128000),
        ModelPreset("MiniMax M2.1",           "https://api.minimax.chat/v1",                           "MiniMax-M2.1",           "MiniMax-M2.1",           "key_minimax",  128000),
        ModelPreset("MiniMax M2",             "https://api.minimax.chat/v1",                           "MiniMax-M2",             "MiniMax-M2",             "key_minimax",  128000),
        // 小米 Mimo
        ModelPreset("小米 Mimo V2 Pro",        "https://api.xiaomimimo.com/v1",                         "mimo-v2-pro",            "mimo-v2-pro",            "key_mimo",      32000),
        ModelPreset("小米 Mimo V2 Flash",      "https://api.xiaomimimo.com/v1",                         "mimo-v2-flash",          "mimo-v2-flash",          "key_mimo",      16000),
        // OpenAI：最大输出 32768（GPT-4.1）
        ModelPreset("OpenAI GPT-4.1",         "https://api.openai.com/v1",                             "gpt-4.1",                "gpt-4.1",                "key_openai",    32768),
        ModelPreset("OpenAI GPT-4.1 Mini",    "https://api.openai.com/v1",                             "gpt-4.1-mini",           "gpt-4.1-mini",           "key_openai",    32768),
        ModelPreset("OpenAI GPT-4o",          "https://api.openai.com/v1",                             "gpt-4o",                 "gpt-4o",                 "key_openai",    16384),
        // DeepSeek：V4 Flash（原 deepseek-chat），V4 Pro 思考模式（原 deepseek-reasoner）
        // 旧模型名 deepseek-chat / deepseek-reasoner 将于 2026/07/24 弃用
        ModelPreset("DeepSeek V4 Flash",      "https://api.deepseek.com/v1",                           "deepseek-v4-flash",      "deepseek-v4-flash",      "key_deepseek",   8192),
        ModelPreset("DeepSeek V4 Pro (思考)",  "https://api.deepseek.com/v1",                           "deepseek-v4-pro",        "deepseek-v4-pro",        "key_deepseek",  32000, true),
        // 通义千问：Qwen3-Plus 32K，Turbo 8K
        ModelPreset("通义千问 Qwen3-Plus",     "https://dashscope.aliyuncs.com/compatible-mode/v1",     "qwen3-plus",             "qwen-vl-plus",           "key_qwen",      32000),
        ModelPreset("通义千问 Qwen3-Max",      "https://dashscope.aliyuncs.com/compatible-mode/v1",     "qwen3-max",              "qwen-vl-max",            "key_qwen",      32000),
        ModelPreset("通义千问 Qwen-Turbo",     "https://dashscope.aliyuncs.com/compatible-mode/v1",     "qwen-turbo",             "qwen-vl-plus",           "key_qwen",       8192),
        // Kimi：K2.5 256K 上下文，输出随上下文灵活
        ModelPreset("Kimi K2.5",              "https://api.moonshot.cn/v1",                            "moonshot-v1-auto",       "moonshot-v1-auto",       "key_kimi",      32000),
        ModelPreset("Kimi K2",                "https://api.moonshot.cn/v1",                            "moonshot-v1-128k",       "moonshot-v1-128k",       "key_kimi",      32000),
        // 智谱 GLM：GLM-4.7 最大输出 128K
        ModelPreset("智谱 GLM-4.7",           "https://open.bigmodel.cn/api/paas/v4",                  "glm-4.7",                "glm-4v",                 "key_glm",       128000),
        ModelPreset("智谱 GLM-4.5",           "https://open.bigmodel.cn/api/paas/v4",                  "glm-4.5",                "glm-4v",                 "key_glm",        32000),
        ModelPreset("智谱 GLM-4",             "https://open.bigmodel.cn/api/paas/v4",                  "glm-4",                  "glm-4v",                 "key_glm",         8192),
        // OpenRouter 免费模型（调用量前5名）
        ModelPreset("OpenRouter Llama 3.3 70B",    "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free",    "google/gemma-3-27b-it:free",                "key_openrouter",  8192),
        ModelPreset("OpenRouter Gemma 3 27B",      "https://openrouter.ai/api/v1", "google/gemma-3-27b-it:free",                "google/gemma-3-27b-it:free",                "key_openrouter",  8192),
        ModelPreset("OpenRouter Qwen3 Coder 480B", "https://openrouter.ai/api/v1", "qwen/qwen3-coder:free",                     "google/gemma-3-27b-it:free",                "key_openrouter", 16000),
        ModelPreset("OpenRouter GPT-OSS 120B",     "https://openrouter.ai/api/v1", "openai/gpt-oss-120b:free",                  "openai/gpt-oss-120b:free",                  "key_openrouter", 16000),
        ModelPreset("OpenRouter Hermes 3 405B",    "https://openrouter.ai/api/v1", "nousresearch/hermes-3-llama-3.1-405b:free", "google/gemma-3-27b-it:free",                "key_openrouter",  8192),
        // OpenRouter 免费模型（扩展）
        ModelPreset("OpenRouter Qwen3.6 Plus",     "https://openrouter.ai/api/v1", "qwen/qwen3.6-plus:free",                    "qwen/qwen3.6-plus:free",                    "key_openrouter", 16000),
        ModelPreset("OpenRouter Nemotron 3 Super", "https://openrouter.ai/api/v1", "nvidia/nemotron-3-super-120b-a12b:free",    "nvidia/nemotron-3-super-120b-a12b:free",    "key_openrouter", 16000),
        ModelPreset("OpenRouter Trinity Large",    "https://openrouter.ai/api/v1", "arcee-ai/trinity-large-preview:free",       "arcee-ai/trinity-large-preview:free",       "key_openrouter",  8192),
        ModelPreset("OpenRouter GLM 4.5 Air",      "https://openrouter.ai/api/v1", "z-ai/glm-4.5-air:free",                     "z-ai/glm-4.5-air:free",                     "key_openrouter",  8192),
        ModelPreset("OpenRouter Nemotron Nano 30B","https://openrouter.ai/api/v1", "nvidia/nemotron-3-nano-30b-a3b:free",       "nvidia/nemotron-3-nano-30b-a3b:free",       "key_openrouter",  8192),
        ModelPreset("OpenRouter MiniMax M2.5",     "https://openrouter.ai/api/v1", "minimax/minimax-m2.5:free",                 "minimax/minimax-m2.5:free",                 "key_openrouter", 16000),
        // Ollama 本地部署（默认 localhost:11434，Ollama 无需 API Key）
        ModelPreset("Ollama Llama 3.2",        "http://localhost:11434/v1", "llama3.2",       "llava",           "key_ollama",  8192),
        ModelPreset("Ollama Llama 3.1 8B",     "http://localhost:11434/v1", "llama3.1:8b",    "llava",           "key_ollama",  8192),
        ModelPreset("Ollama Qwen2.5 7B",       "http://localhost:11434/v1", "qwen2.5:7b",     "qwen2.5-vl:7b",   "key_ollama",  8192),
        ModelPreset("Ollama DeepSeek-R1 7B",   "http://localhost:11434/v1", "deepseek-r1:7b", "llava",           "key_ollama",  8192, true),
        ModelPreset("Ollama Mistral",          "http://localhost:11434/v1", "mistral",        "llava",           "key_ollama",  8192),
        ModelPreset("Ollama qwen3.6",             "http://localhost:11434/v1", "qwen3.6",           "qwen3.6",           "key_ollama",  8192),
        ModelPreset("Ollama Gemma4 4B",        "http://localhost:11434/v1", "gemma4:e4b",      "gemma4:e4b",       "key_ollama",  8192),
         ModelPreset("Ollama Gemma4 26B",        "http://localhost:11434/v1", "gemma4:26b",      "gemma4:26b",       "key_ollama",  8192),
        // scnet.cn（兼容 OpenAI 接口协议）
        ModelPreset("scnet Qwen3-235B",   "https://api.scnet.cn/api/llm/v1", "Qwen3-235B-A22B", "Qwen3-235B-A22B", "key_scnet", 32000),
        ModelPreset("scnet MiniMax-M2.5",      "https://api.scnet.cn/api/llm/v1", "MiniMax-M2.5",    "MiniMax-M2.5",    "key_scnet", 32000),
        // aiproxy 代理服务（opt.set.work:5001）
        ModelPreset("aiproxy openrouter",           "http://opt.set.work:5001/v1", "elephant-alpha",                                    "elephant-alpha",                                    "key_aiproxy", 32000),
        ModelPreset("aiproxy Gemini 2.0 Flash",     "http://opt.set.work:5001/v1", "google-ai-studio/gemini-2.0-flash",             "google-ai-studio/gemini-2.0-flash",             "key_aiproxy", 32000),
        ModelPreset("aiproxy Gemini 2.5 Pro",       "http://opt.set.work:5001/v1", "google-ai-studio/gemini-2.5-pro-preview-05-06", "google-ai-studio/gemini-2.5-pro-preview-05-06", "key_aiproxy", 32000),
        ModelPreset("aiproxy Gemini 1.5 Pro",       "http://opt.set.work:5001/v1", "google-ai-studio/gemini-1.5-pro-latest",        "google-ai-studio/gemini-1.5-pro-latest",        "key_aiproxy", 32000),
        ModelPreset("aiproxy GPT-4o",               "http://opt.set.work:5001/v1", "openai/gpt-4o",                                 "openai/gpt-4o",                                 "key_aiproxy", 16384),
        ModelPreset("aiproxy GPT-4.1",              "http://opt.set.work:5001/v1", "openai/gpt-4.1",                                "openai/gpt-4.1",                                "key_aiproxy", 32768),
        ModelPreset("aiproxy Claude 3.5 Sonnet",    "http://opt.set.work:5001/v1", "anthropic/claude-3-5-sonnet-20241022",          "anthropic/claude-3-5-sonnet-20241022",          "key_aiproxy", 32000),
        ModelPreset("aiproxy DeepSeek R1",          "http://opt.set.work:5001/v1", "deepseek/deepseek-r1",                          "deepseek/deepseek-r1",                          "key_aiproxy", 32000, true)
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireActivity().getSharedPreferences("smart_quiz_config", android.content.Context.MODE_PRIVATE)
        llmService = SmartQuizApplication.llmService

        loadConfig()
        binding.btnPreset.setOnClickListener { showPresetMenu(it) }
        binding.btnSaveConfig.setOnClickListener { saveConfig() }
        binding.btnToggleServer.setOnClickListener { toggleServer() }
        binding.btnResetPrompt.setOnClickListener {
            binding.etSystemPrompt.setText(llmService.getDefaultSystemPrompt())
        }

        // 监听模型名称变化，自动恢复对应模型保存的"合并提示词"开关状态
        binding.etModel.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val modelName = s?.toString()?.trim() ?: ""
                if (modelName.isNotEmpty() && prefs.contains("merge_prompts_$modelName")) {
                    binding.switchMergePrompt.isChecked = prefs.getBoolean("merge_prompts_$modelName", false)
                }
            }
        })

        binding.rowSystemPromptHeader.setOnClickListener { toggleSystemPromptExpand() }
        // 调试模式开关
        binding.switchDebugMode.isChecked = prefs.getBoolean("debug_mode", false)
        binding.switchDebugMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("debug_mode", isChecked).apply()
            Toast.makeText(requireContext(), if (isChecked) "调试模式已开启" else "调试模式已关闭", Toast.LENGTH_SHORT).show()
        }
        val versionName = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrDefault("1.0")
        binding.tvVersion.text = "v$versionName"
        binding.btnHelp.setOnClickListener { showHelpDialogPublic() }
        binding.cardHelp.setOnClickListener { showHelpDialogPublic() }
        binding.cardCheckUpdate.setOnClickListener {
            binding.tvUpdateBadge.visibility = android.view.View.GONE
            AppUpdateChecker.check(requireContext(), forceCheck = true)
        }
        setupDataManagement()
        refreshIpOptions()
    }

    override fun onResume() {
        super.onResume()
        refreshIpOptions()
        updateServerStatus()
    }

    private fun refreshIpOptions() {
        val container = binding.layoutIpOptions
        container.removeAllViews()
        val ipList = NetworkUtils.getAllLocalIpAddresses()
        if (ipList.isEmpty()) {
            val tv = android.widget.TextView(requireContext()).apply {
                text = "暂无可用 WiFi 网络"
                textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            }
            container.addView(tv)
            return
        }
        // 默认全选
        if (selectedIps.isEmpty()) {
            selectedIps.addAll(ipList.map { it.second })
        } else {
            // 移除已不存在的 IP，补充新 IP
            selectedIps.retainAll(ipList.map { it.second }.toSet())
            if (selectedIps.isEmpty()) selectedIps.addAll(ipList.map { it.second })
        }
        ipList.forEach { (label, ip) ->
            val cb = CheckBox(requireContext()).apply {
                text = label
                textSize = 14f
                isChecked = selectedIps.contains(ip)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                val pad = (4 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIps.add(ip) else selectedIps.remove(ip)
                    updateServerStatus()
                }
            }
            container.addView(cb)
        }
    }

    private fun loadConfig() {
        binding.etApiUrl.setText(prefs.getString("api_url", "http://opt.set.work:5050/v1"))
        // 根据当前选用的 model key 前缀加载对应的 api_key
        val currentKeyPref = prefs.getString("current_key_pref", "key_copilotcli") ?: "key_copilotcli"
        binding.etApiKey.setText(prefs.getString(currentKeyPref, ""))
        val savedModel = prefs.getString("model", "gpt-4o") ?: "gpt-4o"
        binding.etModel.setText(savedModel)
        binding.etVisionModel.setText(prefs.getString("vision_model", "gpt-4o"))
        binding.etPort.setText(prefs.getString("port", "8080"))
        val savedPrompt = prefs.getString("system_prompt", "") ?: ""
        binding.etSystemPrompt.setText(
            if (savedPrompt.isBlank()) llmService.getDefaultSystemPrompt() else savedPrompt
        )
        binding.switchMergePrompt.isChecked = prefs.getBoolean("merge_prompts_$savedModel", prefs.getBoolean("merge_prompts", false))
        applyConfig()
    }

    private fun applyConfig() {
        val promptText = binding.etSystemPrompt.text.toString().trim()
        val defaultPrompt = llmService.getDefaultSystemPrompt()
        val effectivePrompt = if (promptText == defaultPrompt) "" else promptText
        val maxTokens = prefs.getInt("max_tokens", 32000)
        val mergePrompts = binding.switchMergePrompt.isChecked
        llmService.updateConfig(
            apiKey       = binding.etApiKey.text.toString().trim(),
            baseUrl      = binding.etApiUrl.text.toString().trim(),
            model        = binding.etModel.text.toString().trim(),
            visionModel  = binding.etVisionModel.text.toString().trim(),
            systemPrompt = effectivePrompt,
            maxTokens    = maxTokens,
            mergePrompt  = mergePrompts
        )
    }

    private fun saveConfig() {
        val key = binding.etApiKey.text.toString().trim()
        val isOllama = binding.etApiUrl.text.toString().contains(":11434")
        if (key.isBlank() && !isOllama) {
            Toast.makeText(requireContext(), "请输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        val promptText = binding.etSystemPrompt.text.toString().trim()
        val defaultPrompt = llmService.getDefaultSystemPrompt()
        val promptToSave = if (promptText == defaultPrompt) "" else promptText
        val mergePrompts = binding.switchMergePrompt.isChecked
        val currentKeyPref = prefs.getString("current_key_pref", "key_minimax") ?: "key_minimax"
        val currModel = binding.etModel.text.toString().trim()
        prefs.edit()
            .putString("api_url",          binding.etApiUrl.text.toString().trim())
            .putString("api_key",          key)   // 兼容旧逻辑
            .putString(currentKeyPref,     key)   // 按模型分别存储
            .putString("model",            currModel)
            .putString("vision_model",     binding.etVisionModel.text.toString().trim())
            .putString("system_prompt",    promptToSave)
            .putBoolean("merge_prompts",   mergePrompts)
            .putBoolean("merge_prompts_$currModel", mergePrompts)
            .apply()
        applyConfig()
        Toast.makeText(requireContext(), "✅ 模型配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun showPresetMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        modelPresets.forEachIndexed { i, p -> popup.menu.add(0, i, i, p.name) }
        popup.setOnMenuItemClickListener { item ->
            val p = modelPresets[item.itemId]
            binding.etApiUrl.setText(p.baseUrl)
            binding.etModel.setText(p.textModel)
            binding.etVisionModel.setText(p.visionModel)
            // 切换到该预设对应的已存储 Key（若有则自动填入）
            val savedKey = prefs.getString(p.keyPrefKey, "") ?: ""
            binding.etApiKey.setText(savedKey)
            // 立即保存所有配置到 prefs，避免用户忘记点保存导致重启后配置不一致
            val savedMerge = prefs.getBoolean("merge_prompts_${p.textModel}", p.mergePrompt)
            binding.switchMergePrompt.isChecked = savedMerge
            prefs.edit()
                .putString("current_key_pref", p.keyPrefKey)
                .putInt("max_tokens", p.maxTokens)
                .putString("api_url",       p.baseUrl)
                .putString("model",         p.textModel)
                .putString("vision_model",  p.visionModel)
                .putString("api_key",       savedKey)
                .putString(p.keyPrefKey,    savedKey)
                .putBoolean("merge_prompts", savedMerge)
                .putBoolean("merge_prompts_${p.textModel}", savedMerge)
                .apply()
            applyConfig()
            val hint = when {
                p.keyPrefKey == "key_ollama" -> "，请确保本地 Ollama 服务已启动（可修改 URL 为实际 IP）"
                savedKey.isBlank()          -> "，请填写 API Key 并保存"
                else                        -> ""
            }
            Toast.makeText(requireContext(), "已切换到 ${p.name}$hint", Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
    }

    private fun toggleServer() {
        if (WebServerService.isRunning) stopServer() else startServer()
    }

    private fun startServer() {
        val port = binding.etPort.text.toString().toIntOrNull() ?: 8080

        if (port < 1024) {
            Toast.makeText(requireContext(), "端口号须 ≥ 1024，Android 系统不允许使用低端口", Toast.LENGTH_LONG).show()
            binding.etPort.setText("8080")
            return
        }

        prefs.edit().putString("port", port.toString()).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(requireContext(), perm) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requireActivity().requestPermissions(arrayOf(perm), 100)
            }
        }

        // 鸿蒙系统提示：需手动授权后台运行和局域网访问
        if (isHarmonyOs()) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("鸿蒙系统特别提示")
                .setMessage(
                    "鸿蒙系统默认会阻止应用监听局域网端口，可能导致其他设备无法访问 Web 服务。\n\n" +
                    "请按以下步骤授权：\n" +
                    "① 手机「设置」→「应用」→「拍书题库」→「电池」→ 开启「无限制」\n" +
                    "② 同页面「权限」→ 开启「局域网」（部分机型显示为「WLAN」）\n\n" +
                    "授权后重新启动 Web 服务即可正常使用。"
                )
                .setPositiveButton("去授权") { _, _ ->
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", requireContext().packageName, null)
                        }
                        startActivity(intent)
                    } catch (_: Exception) { }
                }
                .setNegativeButton("忽略，继续启动") { _, _ -> doStartServer(port) }
                .show()
            return
        }

        doStartServer(port)
    }

    private fun doStartServer(port: Int) {
        prefs.edit().putBoolean("server_was_running", true).apply()
        val intent = Intent(requireContext(), WebServerService::class.java).apply {
            putExtra(WebServerService.EXTRA_PORT, port)
        }
        requireContext().startForegroundService(intent)
        binding.root.postDelayed({ updateServerStatus() }, 600)
    }

    /** 检测是否为华为鸿蒙系统 */
    private fun isHarmonyOs(): Boolean = try {
        Class.forName("com.huawei.system.BuildEx")
            .getMethod("getOsBrand")
            .invoke(null)?.toString()?.equals("harmony", ignoreCase = true) == true
    } catch (_: Exception) {
        android.os.Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)
    }

    private fun stopServer() {
        prefs.edit().putBoolean("server_was_running", false).apply()
        requireContext().stopService(Intent(requireContext(), WebServerService::class.java))
        binding.root.postDelayed({ updateServerStatus() }, 300)
    }

    fun updateServerStatus() {
        if (!isAdded) return
        if (WebServerService.isRunning) {
            val port = WebServerService.currentPort
            val ips = selectedIps.ifEmpty {
                NetworkUtils.getAllLocalIpAddresses().map { it.second }.toSet()
            }
            val urls = ips.map { "http://$it:$port" }
            binding.btnToggleServer.text = "停止服务"
            binding.btnToggleServer.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.error)
            binding.tvServerStatus.text = "服务运行中"
            binding.tvServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.success_dark))
            binding.serverDot.background.setTint(ContextCompat.getColor(requireContext(), R.color.success))
            binding.tvServerUrl.visibility = View.VISIBLE
            binding.tvServerUrl.text = urls.joinToString("\n")
            binding.btnShareServer.visibility = View.VISIBLE
            binding.btnShareServer.setOnClickListener { showShareDialog(urls) }
            binding.serverStatusBanner.setBackgroundResource(R.drawable.bg_server_running)
        } else {
            binding.btnToggleServer.text = "启动服务"
            binding.btnToggleServer.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.primary)
            binding.tvServerStatus.text = "服务未启动"
            binding.tvServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            binding.serverDot.background.setTint(ContextCompat.getColor(requireContext(), R.color.text_hint))
            binding.tvServerUrl.visibility = View.GONE
            binding.btnShareServer.visibility = View.GONE
            binding.serverStatusBanner.setBackgroundResource(R.drawable.bg_server_status)
        }
    }

    private fun toggleSystemPromptExpand() {
        val isVisible = binding.layoutSystemPromptContent.visibility == View.VISIBLE
        binding.layoutSystemPromptContent.visibility = if (isVisible) View.GONE else View.VISIBLE
        binding.ivSystemPromptArrow.animate().rotation(if (isVisible) -90f else 0f).setDuration(200).start()
    }

    fun showHelpDialogPublic() = showHelpDialog()

    private fun showHelpDialog() {
        val dialog = android.app.Dialog(requireContext(), android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val webView = android.webkit.WebView(requireContext()).apply {
            settings.javaScriptEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            // JS Bridge：让 HTML 中的邮箱/网址链接通过系统 Intent 打开
            val fragmentRef = this@SettingsFragment
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun openUrl(url: String) {
                    val ctx = fragmentRef.context ?: return
                    val intent = when {
                        url.startsWith("mailto:") -> android.content.Intent(android.content.Intent.ACTION_SENDTO,
                            android.net.Uri.parse(url))
                        else -> android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url))
                    }
                    runCatching { ctx.startActivity(intent) }
                }
                @android.webkit.JavascriptInterface
                fun shareApk() {
                    fragmentRef.activity?.runOnUiThread { fragmentRef.shareApkFile() }
                }
            }, "Android")
        }
        // 顶部工具栏
        val toolbar = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 0)
            setBackgroundColor(android.graphics.Color.parseColor("#5C6BC0"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 160
            )
        }
        val tvTitle = android.widget.TextView(requireContext()).apply {
            text = "帮助文档"
            textSize = 17f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(20, 0, 0, 0)
        }
        val btnShare = android.widget.TextView(requireContext()).apply {
            text = "分享"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(24, 0, 8, 0)
            setOnClickListener { shareHelpDoc() }
        }
        val btnClose = android.widget.TextView(requireContext()).apply {
            text = "✕"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(24, 0, 24, 0)
            setOnClickListener { dialog.dismiss() }
        }
        toolbar.addView(tvTitle)
        toolbar.addView(btnShare)
        toolbar.addView(btnClose)

        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        root.addView(toolbar)
        root.addView(webView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        webView.loadUrl("file:///android_asset/web/help.html")
        dialog.setContentView(root)
        dialog.show()
    }

    private fun shareApkFile() {
        val toast = Toast.makeText(requireContext(), "正在准备安装包…", Toast.LENGTH_SHORT)
        toast.show()
        val ctx = requireContext().applicationContext
        val pkgName = ctx.packageName
        val cacheDir = ctx.cacheDir
        val srcPath = ctx.packageCodePath
        val act = activity
        Thread {
            try {
                val src = java.io.File(srcPath)
                val shareDir = java.io.File(cacheDir, "share")
                shareDir.mkdirs()
                val dest = java.io.File(shareDir, "pstk.apk")
                if (!dest.exists() || dest.length() != src.length()) {
                    src.copyTo(dest, overwrite = true)
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "$pkgName.fileprovider", dest)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "拍书题库 安装包")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(android.content.Intent.createChooser(intent, "分享拍书题库安装包")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                act?.runOnUiThread {
                    Toast.makeText(ctx, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun shareHelpDoc() {
        try {
            val html = requireContext().assets.open("web/help.html").bufferedReader().readText()
            val shareDir = java.io.File(requireContext().cacheDir, "share")
            shareDir.mkdirs()
            val file = java.io.File(shareDir, "拍书题库-帮助文档.html")
            file.writeText(html, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "拍书题库帮助文档")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "分享帮助文档"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShareDialog(urls: List<String>) {
        if (urls.isEmpty()) {
            Toast.makeText(requireContext(), "请先勾选至少一个 IP 地址", Toast.LENGTH_SHORT).show()
            return
        }
        val db = DatabaseHelper.getInstance(requireContext())
        val banks = db.getAllBanks()

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_share_server, null)

        val spinner = dialogView.findViewById<androidx.appcompat.widget.AppCompatSpinner>(R.id.spinnerBank)
        val qrContainer = dialogView.findViewById<LinearLayout>(R.id.layoutQrContainer)

        // 题库列表
        val bankNames = mutableListOf("AI智能练题系统") + banks.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, bankNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // 每个URL对应一张卡片位图
        val cardBitmaps = MutableList<android.graphics.Bitmap?>(urls.size) { null }

        // 动态为每个URL生成卡片视图
        data class CardViews(
            val tvTitle: android.widget.TextView,
            val ivQr: android.widget.ImageView,
            val tvUrl: android.widget.TextView,
            val btnSave: com.google.android.material.button.MaterialButton,
            val btnShare: com.google.android.material.button.MaterialButton
        )

        val dp = resources.displayMetrics.density
        val cardViews = urls.mapIndexed { index, url ->
            // IP 分隔线（第一张不加顶部间距）
            if (index > 0) {
                val divider = android.widget.TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    ).also { it.topMargin = (12 * dp).toInt(); it.bottomMargin = (12 * dp).toInt() }
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_stroke))
                }
                qrContainer.addView(divider)
            }

            // 外层卡片
            val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * dp).toInt() }
                cardElevation = 0f
                radius = 16 * dp
                strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
                strokeWidth = (1 * dp).toInt()
            }
            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }

            val tvTitle = android.widget.TextView(requireContext()).apply {
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (12 * dp).toInt() }
            }

            val ivQr = android.widget.ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (200 * dp).toInt(), (200 * dp).toInt()
                )
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            }

            val tvUrl = android.widget.TextView(requireContext()).apply {
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                text = url
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (8 * dp).toInt(); it.bottomMargin = (12 * dp).toInt() }
            }

            val btnRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val btnSave = com.google.android.material.button.MaterialButton(
                requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "保存图片"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f)
                    .also { it.marginEnd = (8 * dp).toInt() }
                setStrokeColorResource(R.color.primary)
                strokeWidth = (1 * dp).toInt()
                setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            }
            val btnShare = com.google.android.material.button.MaterialButton(requireContext()).apply {
                text = "分享"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f)
                    .also { it.marginStart = (8 * dp).toInt() }
            }

            btnRow.addView(btnSave)
            btnRow.addView(btnShare)
            inner.addView(tvTitle)
            inner.addView(ivQr)
            inner.addView(tvUrl)
            inner.addView(btnRow)
            card.addView(inner)
            qrContainer.addView(card)

            CardViews(tvTitle, ivQr, tvUrl, btnSave, btnShare)
        }

        fun refreshAllCards(bankName: String) {
            val cardTitle = if (bankName == "AI智能练题系统") "AI智能练题系统"
            else "${bankName}考试—AI智能练题系统"
            urls.forEachIndexed { index, url ->
                cardViews[index].tvTitle.text = cardTitle
                lifecycleScope.launch(Dispatchers.IO) {
                    val qr = QrCodeUtils.generate(url, size = 400,
                        fgColor = 0xFF3949AB.toInt(), bgColor = android.graphics.Color.WHITE)
                    val card = QrCodeUtils.generateShareCard(cardTitle, url)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            cardViews[index].ivQr.setImageBitmap(qr)
                            cardBitmaps[index]?.recycle()
                            cardBitmaps[index] = card
                        }
                    }
                }
            }
        }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) {
                refreshAllCards(bankNames[pos])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
        refreshAllCards(bankNames[0])

        // 绑定保存/分享按钮
        urls.forEachIndexed { index, url ->
            cardViews[index].btnSave.setOnClickListener {
                val bmp = cardBitmaps[index] ?: return@setOnClickListener
                lifecycleScope.launch(Dispatchers.IO) {
                    val uri = saveCardBitmap(bmp)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            if (uri != null) Toast.makeText(requireContext(), "✅ 图片已保存到相册", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(requireContext(), "保存失败，请检查权限", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            cardViews[index].btnShare.setOnClickListener {
                val bmp = cardBitmaps[index] ?: return@setOnClickListener
                lifecycleScope.launch(Dispatchers.IO) {
                    val uri = saveShareCardToMediaStore(bmp)
                    withContext(Dispatchers.Main) {
                        if (isAdded && uri != null) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_TEXT, url)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(shareIntent, "分享到"))
                            // 延迟清理临时图片（等待微信读取完成）
                            lifecycleScope.launch(Dispatchers.IO) {
                                kotlinx.coroutines.delay(30_000)
                                runCatching { requireContext().contentResolver.delete(uri, null, null) }
                            }
                        } else if (isAdded) {
                            Toast.makeText(requireContext(), "分享失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("分享在线练习入口")
            .setView(dialogView)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnDismissListener { cardBitmaps.forEach { it?.recycle() } }
        dialog.show()
    }

    /** 保存到相册（MediaStore） */
    private fun saveCardBitmap(bitmap: Bitmap): Uri? {
        return try {
            val filename = "SmartQuiz_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmartQuiz")
                }
                val uri = requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                uri?.let { requireContext().contentResolver.openOutputStream(it)?.use { os -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, os) } }
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SmartQuiz")
                dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 保存到 cache 目录，供 FileProvider 分享 */
    private fun saveTempCardBitmap(bitmap: Bitmap): Uri? {
        return try {
            val file = File(requireContext().cacheDir, "share_card.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 保存到 MediaStore Pictures（临时文件），返回公共媒体 URI。
     * 微信等第三方 App 无法读取 FileProvider URI，需使用此方式。
     */
    private fun saveShareCardToMediaStore(bitmap: Bitmap): Uri? {
        return try {
            val filename = "smartquiz_share_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmartQuiz")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = requireContext().contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
                requireContext().contentResolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                requireContext().contentResolver.update(uri, cv, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SmartQuiz")
                dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 数据管理 ====================

    private fun setupDataManagement() {
        binding.rowClearAll.setOnClickListener {
            showClearConfirmDialog(
                title = "清空所有数据",
                message = "此操作将删除所有学生账号、题库、题目、考试记录及报表数据。\n\n管理员账号将被保留。\n\n⚠️ 此操作不可撤销，请谨慎操作！",
                confirmText = "确认清空全部",
                toastMsg = "✅ 所有数据已清空"
            ) {
                val db = DatabaseHelper.getInstance(requireContext())
                db.clearAllData()
            }
        }
        binding.rowClearStudents.setOnClickListener {
            showClearConfirmDialog(
                title = "清空学生数据",
                message = "此操作将删除所有学生账号及其答题记录、错题记录、消息记录。\n\n题库与管理员数据不受影响。\n\n⚠️ 此操作不可撤销！",
                confirmText = "确认清空学生",
                toastMsg = "✅ 学生数据已清空"
            ) {
                val db = DatabaseHelper.getInstance(requireContext())
                db.clearStudentData()
            }
        }
        binding.rowClearExam.setOnClickListener {
            showClearConfirmDialog(
                title = "清空考试和报表数据",
                message = "此操作将删除所有考试记录、答题详情、已发布考试、错题及反馈数据。\n\n学生账号和题库不受影响。\n\n⚠️ 此操作不可撤销！",
                confirmText = "确认清空考试数据",
                toastMsg = "✅ 考试和报表数据已清空"
            ) {
                val db = DatabaseHelper.getInstance(requireContext())
                db.clearExamAndReportData()
            }
        }
        binding.rowClearBanks.setOnClickListener {
            showClearConfirmDialog(
                title = "清空题库数据",
                message = "此操作将删除所有题库及其全部题目，相关考试记录也将一并删除。\n\n学生账号不受影响。\n\n⚠️ 此操作不可撤销！",
                confirmText = "确认清空题库",
                toastMsg = "✅ 题库数据已清空"
            ) {
                val db = DatabaseHelper.getInstance(requireContext())
                db.clearBankData()
            }
        }

        binding.rowExportData.setOnClickListener { doExportData() }
        binding.rowImportData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("导入数据备份")
                .setMessage("导入将覆盖当前所有数据，建议先导出备份。\n\n请选择 .sqbak 备份文件继续。")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择文件") { _, _ ->
                    importFileLauncher.launch("*/*")
                }
                .show()
        }
        binding.rowImportDemo.setOnClickListener { showImportDemoDialog() }
    }

    /** 导入演示数据：非空时要求先备份 */
    internal fun showImportDemoDialog() {
        val db = DatabaseHelper.getInstance(requireContext())
        if (!db.isDatabaseEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("当前数据库不为空")
                .setMessage("导入演示数据前，建议先导出当前数据备份，以防重要数据丢失。\n\n请选择：")
                .setNegativeButton("取消", null)
                .setNeutralButton("先导出备份") { _, _ -> doExportData() }
                .setPositiveButton("直接覆盖导入") { _, _ ->
                    confirmInsertDemo(db)
                }
                .show()
                .also { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        ?.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                }
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("导入演示数据")
                .setMessage("将生成 2 个示例题库（每库 22 道题）、5 名演示学生账号（密码均为 123456）、2 场已完成的正式考试、多轮练习记录（每次 20 题）及错题、消息、反馈样本，方便快速体验所有功能。\n\n是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始导入") { _, _ ->
                    confirmInsertDemo(db)
                }
                .show()
        }
    }

    private fun confirmInsertDemo(db: DatabaseHelper) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.insertDemoData()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                Toast.makeText(requireContext(), "✅ 演示数据导入成功，演示账号密码均为 123456", Toast.LENGTH_LONG).show()
                (activity as? MainActivity)?.switchToBanks()
            }
        }
    }

    private fun doExportData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = DatabaseHelper.getInstance(requireContext())
                val json = db.exportToJson()
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "smartquiz_backup_$ts.sqbak"
                val cacheFile = File(requireContext().cacheDir, fileName)
                cacheFile.writeText(json, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    cacheFile
                )
                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "SmartQuiz 数据备份")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "导出备份文件"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "❌ 导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun doImportData(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.readText()
                    ?: throw Exception("无法读取文件")
                val db = DatabaseHelper.getInstance(requireContext())
                db.importFromJson(json)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "✅ 数据导入成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "❌ 导入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showClearConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        toastMsg: String,
        onConfirmed: () -> Unit
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmText) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    onConfirmed()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), toastMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
            .also { dialog ->
                // 将确认按钮标红以提示危险操作
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    ?.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            }
    }
}
