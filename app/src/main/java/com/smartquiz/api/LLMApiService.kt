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
package com.smartquiz.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.smartquiz.model.Question
import com.smartquiz.model.QuestionType
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 大模型API服务 - 支持OpenAI兼容接口（MiniMax / OpenAI / DeepSeek等）
 * 用于智能出题（纯文本 + 图片视觉识别）
 */
class LLMApiService(
    @Volatile private var apiKey: String = "",
    @Volatile private var baseUrl: String = "https://api.minimax.chat/v1",
    @Volatile private var model: String = "MiniMax-M2.7",
    @Volatile private var visionModel: String = "MiniMax-M2.7",
    @Volatile private var customSystemPrompt: String = "",
    @Volatile private var maxTokens: Int = 32000,
    @Volatile private var mergePrompt: Boolean = false
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(600, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun updateConfig(apiKey: String, baseUrl: String, model: String, visionModel: String = model, systemPrompt: String = "", maxTokens: Int = 32000, mergePrompt: Boolean = false) {
        this.apiKey = apiKey
        this.baseUrl = baseUrl.trimEnd('/')
        this.model = model
        this.visionModel = visionModel
        this.customSystemPrompt = systemPrompt
        this.maxTokens = maxTokens
        this.mergePrompt = mergePrompt
    }

    fun getDefaultSystemPrompt(): String = """你是一个专业的出题老师在中国教授知识。根据用户提供的课本内容，提取知识点并生成练习题。
【重要】如果用户提供的内容中包含了题目信息，请你判断并补充完整答案、解析、难度和知识点等内容，最终将结果转换成JSON格式。
【重要】你必须且只能输出一个纯JSON对象，禁止输出任何其他文字、解释、markdown标记。
【重要】JSON字符串值中不要使用中文引号""，只用普通的描述方式替代，除英语科目外，其他科目使用中文出题。

JSON格式如下：
{"questions":[{"type":1,"content":"题目内容","options":["A. 选项1","B. 选项2","C. 选项3","D. 选项4"],"answer":"A","explanation":"解析内容","difficulty":2,"knowledgePoint":"知识点名称","imageUrl":""}]}

type: 1=单选题 2=多选题 3=判断题 4=填空题 5=简答题
单选题answer为字母如A；多选题answer为多个字母如ABC；判断题options为["正确","错误"]，answer为"正确"或"错误"；填空题options为空数组[]；简答题options为空数组[]，answer为参考答案。
【难度要求】每个知识点必须生成5种难度的题目（difficulty分别为1、2、3、4、5），最高难度5为综合应用型题目，所有知识点的5个难度等级都要覆盖到。

【公式符号支持】系统使用 KaTeX 渲染数学、物理、化学公式，请在题目content、选项options、答案answer和解析explanation中使用 LaTeX 语法书写公式：
- 行内公式用单个美元符号包裹，例如：${'$'}x^2 + y^2 = r^2${'$'}
- 块级公式用双美元符号包裹，例如：${'$'}${'$'}\int_0^1 x^2 dx = \frac{1}{3}${'$'}${'$'}
- 数学常用：${'$'}\frac{a}{b}${'$'}分数、${'$'}\sqrt{x}${'$'}根号、${'$'}x^{n}${'$'}指数、${'$'}\sum_{i=1}^{n}${'$'}求和、${'$'}\lim_{x\to 0}${'$'}极限、${'$'}\vec{F}${'$'}向量、${'$'}\alpha \beta \gamma \theta \pi${'$'}希腊字母
- 物理常用：${'$'}F=ma${'$'}、${'$'}E=mc^2${'$'}、${'$'}v=\frac{\Delta x}{\Delta t}${'$'}、${'$'}\vec{F}${'$'}力向量、${'$'}\Omega${'$'}电阻、${'$'}\mu${'$'}摩擦系数、单位如${'$'}\text{m/s}^2${'$'}、${'$'}\text{kg}\cdot\text{m/s}${'$'}
- 化学常用：化学式直接写如H₂O或用${'$'}\text{H}_2\text{O}${'$'}、化学方程式用${'$'}\text{2H}_2 + \text{O}_2 \xrightarrow{\text{点燃}} \text{2H}_2\text{O}${'$'}、${'$'}\rightleftharpoons${'$'}可逆反应、${'$'}\uparrow${'$'}气体、${'$'}\downarrow${'$'}沉淀
请务必在数学、物理、化学题目中积极使用 LaTeX 公式，使题目显示专业规范。

【图片支持】如果题目需要配图（如几何图形、实验装置图、电路图、函数图像等），请在imageUrl字段填写图片的完整URL地址或base64格式（如 data:image/png;base64,iVBOR... ）。如果没有图片则留空字符串""。选项中如需图片，可在选项文本中使用 ![描述](图片URL或base64) 格式嵌入。"""

    fun isConfigured(): Boolean = apiKey.isNotBlank() || isOllamaEndpoint()
    private fun isOllamaEndpoint(): Boolean = baseUrl.contains(":11434")
    fun getModel(): String = model
    fun getVisionModel(): String = visionModel.ifBlank { model }

    /**
     * 判断模型是否为 DeepSeek 推理模型（需要传入 thinking 参数）
     * deepseek-v4-pro 及旧名 deepseek-reasoner 均属于推理模型
     */
    private fun isDeepSeekReasoner(m: String = model): Boolean =
        baseUrl.contains("api.deepseek.com") &&
        (m.contains("v4-pro") || m.contains("reasoner"))

    /** 将 DeepSeek 推理模型所需的额外参数注入 requestBody */
    private fun injectDeepSeekThinking(body: MutableMap<String, Any>, m: String = model) {
        if (isDeepSeekReasoner(m)) {
            body["thinking"] = mapOf("type" to "enabled")
            body["reasoning_effort"] = "high"
            // 推理模型不支持 temperature
            body.remove("temperature")
        }
    }

    /**
     * 根据文本内容生成题目
     */
    suspend fun generateQuestions(
        text: String,
        subject: String = "",
        questionCount: Int? = null,
        types: List<QuestionType> = listOf(
            QuestionType.SINGLE_CHOICE,
            QuestionType.TRUE_FALSE,
            QuestionType.FILL_BLANK
        ),
        extraPrompt: String = ""
    ): List<Question> {
        val typeDesc = types.joinToString("、") { it.label }
        val subjectHint = if (subject.isNotBlank()) "科目是「$subject」，" else ""

        // 防止超长OCR文本超出模型上下文，截取前15000字符
        val safeText = if (text.length > 15_000) text.take(15_000) else text

        val basePrompt = if (customSystemPrompt.isNotBlank()) customSystemPrompt else getDefaultSystemPrompt()
        val countHint = if (questionCount != null) "【严格要求】必须生成恰好${questionCount}道题，questions数组长度必须等于${questionCount}，不能多也不能少。" else "根据内容尽可能多地生成题目，不限数量。"
        val extraHint = if (extraPrompt.isNotBlank()) "\n补充要求：$extraPrompt" else ""
        val systemPrompt = "$basePrompt\n题目类型包括：$typeDesc\n$countHint$extraHint"

        // CopilotCLI代理(基于GitHub Copilot)：部分模型不支持temperature和max_tokens
        val isCopilotProxy = baseUrl.contains("opt.set.work:5050")
        val isNewModel = model.startsWith("gpt-5") || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")

        val messagesList = if (mergePrompt) {
            listOf(
                mapOf(
                    "role" to "user",
                    "content" to "【系统要求】\n$systemPrompt\n\n【用户输入】\n${subjectHint}课本内容如下：\n\n$safeText"
                )
            )
        } else {
            listOf(
                mapOf(
                    "role" to "system",
                    "content" to systemPrompt
                ),
                mapOf(
                    "role" to "user",
                    "content" to "${subjectHint}课本内容如下：\n\n$safeText"
                )
            )
        }

        val requestBody = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messagesList,
            "stream" to false
        )
        // temperature: CopilotCLI代理的新模型只支持默认值1，不传该参数
        if (!isCopilotProxy) {
            requestBody["temperature"] = 0.7
        }
        // max_tokens vs max_completion_tokens
        if (isCopilotProxy || isNewModel) {
            requestBody["max_completion_tokens"] = maxTokens
        } else {
            requestBody["max_tokens"] = maxTokens
        }
        // DeepSeek 推理模型需要 thinking + reasoning_effort
        injectDeepSeekThinking(requestBody)

        val responseText = callApi(requestBody)
        Log.d("SmartQuiz", "API response length: ${responseText.length}")
        val result = parseQuestions(responseText, types)
        // 兜底截断：若AI返回数量超出要求则截取
        return if (questionCount != null && result.size > questionCount) {
            Log.d("SmartQuiz", "AI返回${result.size}题，按要求截断至${questionCount}题")
            result.take(questionCount)
        } else result
    }

    /**
     * 根据图片直接生成题目（视觉模型）
     * 将图片以base64格式发送给视觉模型，由AI直接识别图片中的文字、公式、图表并生成题目。
     * 相比OCR+文本的方式，能准确识别数学公式、化学方程式、物理电路图等理工科内容。
     *
     * @param imageBase64List 图片的base64编码列表（不含data:前缀）
     * @param imageMimeTypes  每张图片对应的MIME类型（如image/jpeg, image/png）
     * @param subject 科目
     * @param questionCount 题目数量（null表示不限）
     * @param types 题目类型
     * @param extraPrompt 补充要求
     */
    suspend fun generateQuestionsFromImages(
        imageBase64List: List<String>,
        imageMimeTypes: List<String>,
        subject: String = "",
        questionCount: Int? = null,
        types: List<QuestionType> = listOf(
            QuestionType.SINGLE_CHOICE,
            QuestionType.TRUE_FALSE,
            QuestionType.FILL_BLANK
        ),
        extraPrompt: String = ""
    ): List<Question> {
        val typeDesc = types.joinToString("、") { it.label }
        val subjectHint = if (subject.isNotBlank()) "科目是「$subject」，" else ""

        val basePrompt = if (customSystemPrompt.isNotBlank()) customSystemPrompt else getDefaultSystemPrompt()
        val countHint = if (questionCount != null) "【严格要求】必须生成恰好${questionCount}道题，questions数组长度必须等于${questionCount}，不能多也不能少。" else "根据内容尽可能多地生成题目，不限数量。"
        val extraHint = if (extraPrompt.isNotBlank()) "\n补充要求：$extraPrompt" else ""
        val systemPrompt = "$basePrompt\n题目类型包括：$typeDesc\n$countHint$extraHint"

        // 构建多模态content
        val contentParts = mutableListOf<Map<String, Any>>()
        contentParts.add(mapOf(
            "type" to "text",
            "text" to (if (mergePrompt) "【系统要求】\n$systemPrompt\n\n【用户输入】\n" else "") + 
                      "${subjectHint}请仔细识别以下课本图片中的所有文字、数学公式、化学方程式、物理图表等内容，然后根据这些内容生成练习题。注意：图片中的公式符号请使用LaTeX语法准确还原。"
        ))
        imageBase64List.forEachIndexed { index, base64 ->
            val mimeType = imageMimeTypes.getOrElse(index) { "image/jpeg" }
            contentParts.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf(
                    "url" to "data:$mimeType;base64,$base64"
                )
            ))
        }

        val isCopilotProxy = baseUrl.contains("opt.set.work:5050")
        val useModel = visionModel.ifBlank { model }
        val isNewModel = useModel.startsWith("gpt-5") || useModel.startsWith("o1") || useModel.startsWith("o3") || useModel.startsWith("o4")

        val messagesList = if (mergePrompt) {
            listOf(
                mapOf(
                    "role" to "user",
                    "content" to contentParts
                )
            )
        } else {
            listOf(
                mapOf(
                    "role" to "system",
                    "content" to systemPrompt
                ),
                mapOf(
                    "role" to "user",
                    "content" to contentParts
                )
            )
        }

        val requestBody = mutableMapOf<String, Any>(
            "model" to useModel,
            "messages" to messagesList,
            "stream" to false
        )
        if (!isCopilotProxy) {
            requestBody["temperature"] = 0.7
        }
        if (isCopilotProxy || isNewModel) {
            requestBody["max_completion_tokens"] = maxTokens
        } else {
            requestBody["max_tokens"] = maxTokens
        }
        // DeepSeek 推理模型需要 thinking + reasoning_effort
        injectDeepSeekThinking(requestBody, useModel)

        Log.d("SmartQuiz", "Vision API: 发送 ${imageBase64List.size} 张图片，模型: $useModel")
        val responseText = callApi(requestBody)
        Log.d("SmartQuiz", "Vision API response length: ${responseText.length}")
        val result = parseQuestions(responseText, types)
        // 兜底截断：若AI返回数量超出要求则截取
        return if (questionCount != null && result.size > questionCount) {
            Log.d("SmartQuiz", "Vision API AI返回${result.size}题，按要求截断至${questionCount}题")
            result.take(questionCount)
        } else result
    }

    /**
     * 解析AI返回的题目JSON
     * 多级容错：完整解析 → 截断修复 → 逐题抢救
     * @param allowedTypes 允许的题型列表，不在列表中的题目将被过滤掉
     */
    private fun parseQuestions(
        responseText: String,
        allowedTypes: List<QuestionType> = QuestionType.entries.toList()
    ): List<Question> {
        val jsonStr = try {
            extractJson(responseText)
        } catch (e: Exception) {
            Log.e("SmartQuiz", "extractJson failed: ${e.message}")
            throw RuntimeException("解析题目失败: ${e.message}\n原始响应: ${responseText.take(500)}")
        }

        // 第一级：完整解析
        try {
            return filterByAllowedTypes(parseJsonArray(jsonStr), allowedTypes)
        } catch (e: Exception) {
            Log.w("SmartQuiz", "Full JSON parse failed (${e.message}), trying truncation repair…")
        }

        // 第二级：截断修复
        val repaired = repairTruncatedJson(jsonStr)
        try {
            val result = filterByAllowedTypes(parseJsonArray(repaired), allowedTypes)
            Log.d("SmartQuiz", "Repaired JSON parsed ${result.size} questions")
            return result
        } catch (e2: Exception) {
            Log.w("SmartQuiz", "Repaired JSON parse also failed (${e2.message}), trying per-object rescue…")
        }

        // 第三级：逐题抢救——用正则逐个提取 {...} 对象，能解析几道算几道
        val rescued = filterByAllowedTypes(rescueQuestions(jsonStr), allowedTypes)
        if (rescued.isNotEmpty()) {
            Log.d("SmartQuiz", "Rescued ${rescued.size} questions from malformed JSON")
            return rescued
        }

        throw RuntimeException("解析题目失败，AI返回的格式无法识别。\n共尝试了3种解析方式均失败。\n原始响应: ${responseText.take(500)}")
    }

    /**
     * 按允许的题型过滤题目列表，并对常见题型错误进行纠正：
     * - 判断题但选项不是["正确","错误"]时，修正选项
     * - 单选题答案含多个字母时，改为多选题（前提多选题在允许列表中）
     * - 简答题/填空题 options 不为空时清空选项
     * - 不在允许列表中的题型直接过滤掉
     */
    private fun filterByAllowedTypes(questions: List<Question>, allowedTypes: List<QuestionType>): List<Question> {
        if (allowedTypes.isEmpty()) return questions
        return questions.mapNotNull { q ->
            var corrected = q
            // 纠错：判断题选项不标准时修正
            if (corrected.type == QuestionType.TRUE_FALSE && corrected.options.size != 2) {
                corrected = corrected.copy(options = listOf("正确", "错误"))
            }
            // 纠错：单选题答案含多个字母时改为多选题
            if (corrected.type == QuestionType.SINGLE_CHOICE &&
                corrected.answer.trim().matches(Regex("[A-Za-z]{2,}")) &&
                QuestionType.MULTIPLE_CHOICE in allowedTypes) {
                corrected = corrected.copy(type = QuestionType.MULTIPLE_CHOICE)
            }
            // 纠错：简答题/填空题有选项时清空
            if (corrected.type == QuestionType.SHORT_ANSWER && corrected.options.isNotEmpty()) {
                corrected = corrected.copy(options = emptyList())
            }
            if (corrected.type == QuestionType.FILL_BLANK && corrected.options.isNotEmpty()) {
                corrected = corrected.copy(options = emptyList())
            }
            // 过滤：不在允许列表中的题型
            val effectiveType = corrected.type
            if (effectiveType in allowedTypes || corrected.type in allowedTypes) corrected else {
                Log.d("SmartQuiz", "过滤题型 ${corrected.type.label}（不在允许列表中）")
                null
            }
        }
    }

    /**
     * 逐题抢救：从JSON文本中提取每个 { ... } 对象块，逐个尝试解析
     */
    private fun rescueQuestions(json: String): List<Question> {
        val questions = mutableListOf<Question>()
        val arrayStart = json.indexOf('[')
        if (arrayStart < 0) return questions

        // 逐字符找平衡的 {} 块
        var depth = 0
        var objStart = -1
        var inStr = false
        var esc = false
        for (i in arrayStart until json.length) {
            val c = json[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inStr) { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (inStr) continue
            when (c) {
                '{' -> { if (depth == 0) objStart = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val objStr = json.substring(objStart, i + 1)
                        try {
                            val obj = JsonParser.parseString(objStr).asJsonObject
                            val typeVal = obj.get("type")
                            val type = if (typeVal != null && typeVal.isJsonPrimitive && typeVal.asJsonPrimitive.isNumber) {
                                QuestionType.fromCode(typeVal.asInt)
                            } else QuestionType.SINGLE_CHOICE
                            val options = if (obj.has("options") && obj.get("options").isJsonArray) {
                                obj.getAsJsonArray("options").map { it.asString }
                            } else emptyList()
                            val diffVal = obj.get("difficulty")
                            val difficulty = when {
                                diffVal == null -> 2
                                diffVal.isJsonPrimitive && diffVal.asJsonPrimitive.isNumber -> diffVal.asInt
                                else -> 2
                            }
                            val answer = obj.get("answer")?.asString ?: ""
                            // 多选题答案只有一个字母时，仍视为多选题
                            val finalType = type
                            val q = Question(
                                type = finalType,
                                content = obj.get("content")?.asString ?: "",
                                options = options,
                                answer = answer,
                                explanation = obj.get("explanation")?.asString ?: "",
                                difficulty = difficulty,
                                knowledgePoint = obj.get("knowledgePoint")?.asString ?: "",
                                imageUrl = obj.get("imageUrl")?.asString ?: ""
                            )
                            if (q.content.isNotBlank()) questions.add(q)
                        } catch (e: Exception) {
                            Log.w("SmartQuiz", "Rescue skip object at $objStart: ${e.message}")
                        }
                        objStart = -1
                    }
                }
            }
        }
        return questions
    }

    /** 修复截断的 JSON：找到最后一个完整的 } 对象边界，补上 ]} 关闭 */
    private fun repairTruncatedJson(json: String): String {
        // 找到 "questions": [ 之后，逐步搜索最后一个平衡的 } 位置
        val arrayStart = json.indexOf('[')
        if (arrayStart < 0) return json
        var depth = 0
        var lastCompleteObjEnd = -1
        var inString = false
        var escape = false
        for (i in arrayStart until json.length) {
            val c = json[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) lastCompleteObjEnd = i
                }
            }
        }
        return if (lastCompleteObjEnd > arrayStart) {
            json.substring(0, lastCompleteObjEnd + 1) + "]}"
        } else json
    }

    private fun parseJsonArray(jsonStr: String): List<Question> {
        val jsonObj = JsonParser.parseString(jsonStr).asJsonObject
        val questionsArray = jsonObj.getAsJsonArray("questions")

        return questionsArray.map { element ->
            val obj = element.asJsonObject
            val typeVal = obj.get("type")
            val type = if (typeVal.isJsonPrimitive && typeVal.asJsonPrimitive.isNumber) {
                QuestionType.fromCode(typeVal.asInt)
            } else {
                QuestionType.SINGLE_CHOICE
            }
            val options = if (obj.has("options") && obj.get("options").isJsonArray) {
                obj.getAsJsonArray("options").map { it.asString }
            } else {
                emptyList()
            }

            val diffVal = obj.get("difficulty")
            val difficulty = when {
                diffVal == null -> 2
                diffVal.isJsonPrimitive && diffVal.asJsonPrimitive.isNumber -> diffVal.asInt
                diffVal.isJsonPrimitive && diffVal.asJsonPrimitive.isString -> {
                    when (diffVal.asString.lowercase()) {
                        "easy" -> 1; "medium" -> 3; "hard" -> 5; else -> 2
                    }
                }
                else -> 2
            }

            val answer = obj.get("answer")?.asString ?: ""
            // 多选题答案只有一个字母时，仍视为多选题
            val finalType = type
            Question(
                type = finalType,
                content = obj.get("content")?.asString ?: "",
                options = options,
                answer = answer,
                explanation = obj.get("explanation")?.asString ?: "",
                difficulty = difficulty,
                knowledgePoint = obj.get("knowledgePoint")?.asString ?: "",
                imageUrl = obj.get("imageUrl")?.asString ?: ""
            )
        }.filter { it.content.isNotBlank() }
    }

    private fun extractJson(text: String): String {
        // 先去除 <think>...</think> 思维链内容（MiniMax M2.7等模型会返回）
        var cleaned = text
        val thinkPattern = Pattern.compile("<think>.*?</think>", Pattern.DOTALL)
        cleaned = thinkPattern.matcher(cleaned).replaceAll("").trim()

        // 尝试从markdown代码块中提取
        val codeBlockPattern = Pattern.compile("```(?:json)?\\s*\\n?(\\{.*\\})\\s*\\n?```", Pattern.DOTALL)
        val matcher = codeBlockPattern.matcher(cleaned)
        if (matcher.find()) {
            cleaned = matcher.group(1) ?: cleaned
        } else {
            // 尝试直接查找JSON对象
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1)
            }
        }

        // 修复LaTeX反斜杠与JSON转义冲突（\frac→\f被解析为换页符等）
        cleaned = fixLatexEscapesInJson(cleaned)

        // 智能修复中文标点：逐字符扫描，区分JSON结构位置和字符串值内容
        cleaned = fixChinesePunctuation(cleaned)

        return cleaned
    }

    /**
     * 修复AI返回JSON中LaTeX反斜杠与JSON转义序列的冲突。
     *
     * AI模型经常在JSON字符串中直接写 \frac, \text, \beta 等LaTeX命令而不加双转义，
     * 导致JSON解析器将 \f 解释为换页符(U+000C)、\t 解释为制表符、\b 解释为退格符等，
     * 最终丢失反斜杠（如 \frac 显示为 rac）。
     *
     * 策略：在JSON字符串值内部，将 \f, \t, \b, \r 统一双转义（这些控制符在题目文本中不会出现）；
     * 对 \n 做上下文检测，仅在后跟LaTeX命令时双转义（保留AI有意插入的换行）。
     */
    private fun fixLatexEscapesInJson(json: String): String {
        val sb = StringBuilder(json.length + 200)
        var inStr = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (!inStr) {
                if (c == '"') inStr = true
                sb.append(c)
                i++
                continue
            }
            // 在JSON字符串内部
            if (c == '"') { sb.append(c); inStr = false; i++; continue }
            if (c != '\\' || i + 1 >= json.length) { sb.append(c); i++; continue }

            val next = json[i + 1]
            when (next) {
                '\\' -> { sb.append("\\\\"); i += 2 }  // 已转义的反斜杠，保持
                '"'  -> { sb.append("\\\""); i += 2 }  // 转义引号，保持
                '/'  -> { sb.append("\\/"); i += 2 }    // 转义斜杠，保持
                'u'  -> { sb.append("\\u"); i += 2 }   // Unicode转义，保持

                // \f \t \b: 在题目文本中一定是LaTeX命令（\frac \text \theta \beta等），双转义
                'f', 't', 'b' -> { sb.append("\\\\").append(next); i += 2 }

                // \r: 回车符在题目中无意义，几乎一定是LaTeX命令（\right \rho \rangle等）
                'r' -> { sb.append("\\\\r"); i += 2 }

                // \n: 需要区分——AI可能用\n表示换行，也可能是LaTeX命令（\neq \nu \nabla等）
                'n' -> {
                    val ahead = if (i + 2 < json.length) json.substring(i + 2, minOf(i + 15, json.length)) else ""
                    if (ahead.matches(Regex("^(eq|abla|ot(?![a-z])|ewline|u(?![a-z])|i(?![a-z])|leq|geq|mid|otin|parallel|cong|eg(?![a-z])|exists|vDash|subseteq|supseteq|subset(?![e])|supset|less|Rightarrow|Leftarrow|Leftrightarrow|prec|succ|sqsubset|sqsupset|triangleleft|ormalsize|ormal).*", RegexOption.DOT_MATCHES_ALL))) {
                        sb.append("\\\\n")  // LaTeX命令，双转义
                    } else {
                        sb.append("\\n")    // 保留为真正的换行
                    }
                    i += 2
                }
                else -> { sb.append("\\").append(next); i += 2 }
            }
        }
        return sb.toString()
    }

    /**
     * 智能修复AI返回JSON中的中文标点问题。
     * 模型（尤其MiniMax M2.7）经常在JSON结构中混用中文标点：
     *   - 用 \u201C\u201D（""）代替 "
     *   - 用 \uFF0C（，）代替 , 分隔数组/对象
     *   - 用 \u3001（、）代替 ,
     * 策略：逐字符扫描，追踪是否在标准双引号包裹的字符串内：
     *   - 在字符串外遇到中文引号/逗号 → 替换为JSON标准符号
     *   - 在字符串内遇到的保持不动（属于内容）
     */
    private fun fixChinesePunctuation(json: String): String {
        val sb = StringBuilder(json.length)
        var inString = false
        var escape = false
        var i = 0
        while (i < json.length) {
            val c = json[i]

            if (escape) {
                sb.append(c)
                escape = false
                i++
                continue
            }

            if (inString) {
                when (c) {
                    '\\' -> { sb.append(c); escape = true }
                    '"' -> { sb.append(c); inString = false }
                    // 字符串内容中的中文引号，保留为普通文本（替换为安全字符避免JSON冲突）
                    '\u201C', '\u201D' -> sb.append("'")
                    '\u2018', '\u2019' -> sb.append("'")
                    else -> sb.append(c)
                }
                i++
                continue
            }

            // 不在字符串中
            when (c) {
                '"' -> { sb.append(c); inString = true }
                // 中文双引号出现在结构位置 → 当作JSON双引号
                '\u201C', '\u201D' -> { sb.append('"'); inString = true }
                // 中文单引号出现在结构位置 → 当作JSON双引号
                '\u2018', '\u2019' -> { sb.append('"'); inString = true }
                // 中文全角逗号/顿号出现在结构位置 → 当作逗号
                '\uFF0C' -> sb.append(',')
                '\u3001' -> sb.append(',')
                // 全角冒号 → 半角冒号
                '\uFF1A' -> sb.append(':')
                // 全角分号 → 半角逗号
                '\uFF1B' -> sb.append(',')
                // 全角方括号
                '\uFF3B' -> sb.append('[')
                '\uFF3D' -> sb.append(']')
                // 全角大括号
                '\uFF5B' -> sb.append('{')
                '\uFF5D' -> sb.append('}')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 通用文本生成：直接发送 prompt，返回 AI 原始文本（不做题目解析）。
     * 适用于姓名提取、文本摘要等非题目场景。
     */
    suspend fun callApiText(prompt: String): String {
        val isCopilotProxy = baseUrl.contains("opt.set.work:5050")
        val isNewModel = model.startsWith("gpt-5") || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")
        val messagesList = if (mergePrompt) {
            listOf(mapOf("role" to "user", "content" to prompt))
        } else {
            listOf(
                mapOf("role" to "system", "content" to "你是一个严格按格式输出的助手，只输出用户要求的内容，不输出任何多余文字。"),
                mapOf("role" to "user", "content" to prompt)
            )
        }
        val requestBody = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messagesList,
            "stream" to false
        )
        if (!isCopilotProxy) requestBody["temperature"] = 0.3
        if (isCopilotProxy || isNewModel) requestBody["max_completion_tokens"] = 4096
        else requestBody["max_tokens"] = 4096
        injectDeepSeekThinking(requestBody)
        return callApi(requestBody)
    }

    /**
     * 调用API
     */
    private suspend fun callApi(bodyMap: Map<String, Any>): String = suspendCancellableCoroutine { cont ->
        val jsonBody = gson.toJson(bodyMap)
        val url = "$baseUrl/chat/completions"
        Log.d("SmartQuiz", "API请求 → $url  模型: ${bodyMap["model"]}")
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        val isCopilotCLI = baseUrl.contains("opt.set.work:5050")
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) {
                    cont.resumeWithException(RuntimeException("网络请求失败($url): ${e.message}"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val friendlyMsg = parseApiError(response.code, bodyStr, isCopilotCLI)
                        cont.resumeWithException(RuntimeException(friendlyMsg))
                        return
                    }

                    val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
                    val message = jsonObj
                        .getAsJsonArray("choices")
                        .get(0).asJsonObject
                        .getAsJsonObject("message")
                    // 优先取 content；推理模型（如 Nemotron Nano）content 为 null，回退到 reasoning
                    val contentElem = message.get("content")
                    val content = if (contentElem != null && !contentElem.isJsonNull) {
                        contentElem.asString
                    } else {
                        message.get("reasoning")?.asString ?: ""
                    }

                    if (!cont.isCancelled) cont.resume(content)
                } catch (e: Exception) {
                    if (!cont.isCancelled) {
                        cont.resumeWithException(RuntimeException("解析响应失败: ${e.message}"))
                    }
                }
            }
        })
    }

    /**
     * 将API错误码和响应体翻译为用户友好的中文提示
     */
    private fun parseApiError(code: Int, body: String, isCopilotCLI: Boolean = false): String {
        val lower = body.lowercase()

        // 提取 CopilotCLI 的 error message 字段
        // 支持两种格式：
        //   OpenAI兼容: {"error":{"message":"..."}}
        //   简单格式:   {"error":"..."}
        fun extractCopilotMsg(): String {
            return try {
                val obj = JsonParser.parseString(body).asJsonObject
                val err = obj.get("error")
                if (err != null && err.isJsonObject) {
                    err.asJsonObject.get("message")?.asString ?: ""
                } else {
                    err?.asString ?: ""
                }
            } catch (_: Exception) { "" }
        }

        if (isCopilotCLI) {
            val msg = extractCopilotMsg()
            val msgLow = msg.lowercase()
            return when (code) {
                400 -> "请求参数错误，请检查模型名称是否正确。\n详情：${msg.ifBlank { body.take(200) }}"
                401 -> when {
                    msgLow.contains("expired") || lower.contains("expired") ->
                        "CopilotCLI API Key 已过期，请在设置中更新 API Key。"
                    msgLow.contains("disabled") || lower.contains("disabled") ->
                        "CopilotCLI 客户端已被禁用，请联系服务管理员。"
                    else ->
                        "CopilotCLI API Key 无效或未认证，请在设置中检查 API Key 是否正确。"
                }
                403 -> "管理员 Token 错误，无权访问该接口。"
                404 -> "请求的资源不存在，请检查模型名称或接口地址。"
                413 -> "请求内容超过 4MB 限制，请减少图片数量或内容长度后重试。"
                429 -> when {
                    msgLow.contains("daily") || msgLow.contains("token limit") || lower.contains("daily") -> {
                        // 尝试从 msg 提取用量，如 "Daily token limit reached (48320/50000)"
                        val usage = Regex("\\((\\d+)/(\\d+)\\)").find(msg)
                        if (usage != null) {
                            val (used, total) = usage.destructured
                            "今日 Token 用量已耗尽（已用 $used / 共 $total），额度将于 UTC 零点重置。"
                        } else {
                            "今日 Token 用量已耗尽，额度将于 UTC 零点（北京时间 08:00）重置。"
                        }
                    }
                    msgLow.contains("hour") || lower.contains("hour") ->
                        "每小时请求次数已达上限，请等待当前小时窗口结束后重试。"
                    else ->
                        "请求频率超限（每分钟最多 30 次），请等待约 1 分钟后重试。"
                }
                500 -> "CopilotCLI 服务器内部错误，请稍后重试。\n${msg.ifBlank { body.take(200) }}"
                502 -> "CopilotCLI 上游 AI 接口调用失败，可能是所选模型暂时不可用，请稍后重试或切换其他模型。"
                504 -> "CopilotCLI 上游 AI 接口请求超时，请稍后重试。"
                else -> "CopilotCLI 请求失败（错误码: $code）\n${msg.ifBlank { body.take(200) }}"
            }
        }

        return when {
            lower.contains("insufficient_balance") || lower.contains("insufficient balance") ->
                "API余额不足，请前往模型服务商充值后重试。\n（错误码: $code）"
            lower.contains("invalid_api_key") || lower.contains("invalid api key") ||
            lower.contains("invalid or inactive api key") || lower.contains("unauthorized") ->
                "API密钥无效或已过期，请在设置中检查API Key是否正确。\n（错误码: $code）"
            lower.contains("ratelimitreached") || lower.contains("rate_limit") || lower.contains("rate limit") ->
                "API请求过于频繁，请等待1分钟后再试。\n（错误码: $code）"
            lower.contains("unsupported_parameter") || lower.contains("unsupported parameter") ->
                "请求参数不兼容当前模型，请尝试切换其他模型。\n（错误码: $code）\n$body"
            lower.contains("model_not_found") || lower.contains("model not found") || lower.contains("does not exist") ->
                "所选模型不存在或不可用，请在设置中检查模型名称。\n（错误码: $code）"
            lower.contains("context_length") || lower.contains("too many tokens") || lower.contains("maximum context") ->
                "内容过长超出模型限制，请减少图片数量或内容长度后重试。\n（错误码: $code）"
            code == 401 -> "认证失败，请检查API Key是否正确。"
            code == 403 -> "没有权限访问该API，请检查账户权限。"
            code == 429 -> "请求被限流，请稍后再试。\n（可能是余额不足或调用频率过高）"
            code == 500 || code == 502 || code == 503 ->
                "API服务器暂时不可用（${code}），请稍后再试。\n$body"
            else -> "API请求失败（错误码: $code）\n$body"
        }
    }
}
