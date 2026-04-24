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

import android.content.Context
import com.google.gson.Gson
import com.smartquiz.db.DatabaseHelper
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

/**
 * 抢答比赛统计服务器 - 老师端专用，运行在 8081 端口
 * 提供实时统计面板和比赛控制接口
 */
@Suppress("UNCHECKED_CAST")
class BuzzerWebServer(
    private val context: Context,
    port: Int = 8081
) : NanoHTTPD(port) {

    private val db = DatabaseHelper.getInstance(context)
    private val gson = Gson()

    // 请求体最大 4 MB，防止恶意请求导致 OOM
    private val MAX_BODY_SIZE = 4 * 1024 * 1024

    // handleGetStats 缓存：key = examId，value = Pair(响应时间戳, JSON字符串)
    private val statsCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, String>>()
    private val STATS_CACHE_TTL_MS = 1_000L // 1 秒缓存，降低高频轮询 DB 压力

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        // CORS 预检
        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                addHeader("Access-Control-Allow-Headers", "Content-Type")
            }
        }

        // 读取请求体
        val postBody: String = if (method == Method.POST) {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            // 拒绝超大请求体，防止恶意客户端触发 OOM
            if (contentLength > MAX_BODY_SIZE) {
                val r = jsonResponse(413, mapOf("error" to "请求体过大"))
                r.addHeader("Access-Control-Allow-Origin", "*")
                return r
            }
            if (contentLength > 0) {
                try {
                    val buf = ByteArray(contentLength)
                    var offset = 0
                    val input = session.inputStream
                    while (offset < contentLength) {
                        val read = input.read(buf, offset, contentLength - offset)
                        if (read < 0) break
                        offset += read
                    }
                    String(buf, 0, offset, Charsets.UTF_8)
                } catch (e: Exception) {
                    val bodyMap = mutableMapOf<String, String>()
                    try { session.parseBody(bodyMap) } catch (_: Exception) {}
                    bodyMap["postData"] ?: ""
                }
            } else ""
        } else ""

        val response = try {
            when {
                // 页面
                uri == "/" || uri == "/buzzer.html" -> serveAsset("web/buzzer.html", "text/html; charset=utf-8")
                uri.startsWith("/static/") -> serveStaticFile(uri)

                // 比赛列表（有效的抢答比赛）
                uri == "/api/buzzer/list" && method == Method.GET ->
                    handleGetBuzzerList()

                // 题目列表
                uri == "/api/buzzer/questions" && method == Method.GET ->
                    handleGetQuestions(session)

                // 综合统计（轮询用）
                uri == "/api/buzzer/stats" && method == Method.GET ->
                    handleGetStats(session)

                // 初始化/重置比赛
                uri == "/api/buzzer/init" && method == Method.POST ->
                    handleInitContest(postBody)

                // 切换当前题目
                uri == "/api/buzzer/question" && method == Method.POST ->
                    handleSetQuestion(postBody)

                // 结束比赛
                uri == "/api/buzzer/end" && method == Method.POST ->
                    handleEndContest(postBody)

                else -> jsonResponse(404, mapOf("error" to "接口不存在"))
            }
        } catch (e: Exception) {
            jsonResponse(500, mapOf("error" to "服务器错误: ${e.message}"))
        }

        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    // ==================== API 处理 ====================

    /** 获取所有抢答比赛列表（按创建时间倒序，包含进行中和已结束） */
    private fun handleGetBuzzerList(): Response {
        val exams = db.getAllAssignedExams()
        val buzzerExams = exams.filter { (it["examMode"] as? String) == "buzzer" }
        // 批量查询所有抢答比赛状态，消除 N+1
        val examIds = buzzerExams.mapNotNull { (it["id"] as? Long) }
        val stateMap = db.getBuzzerStateBatch(examIds)
        val result = buzzerExams.map { exam ->
            val examId = exam["id"] as Long
            val state = stateMap[examId]
            mapOf(
                "id" to examId,
                "bankName" to (exam["bankName"] as? String ?: ""),
                "totalUsers" to (exam["totalUsers"] as? Int ?: 0),
                "createdAt" to (exam["createdAt"] as? Long ?: 0L),
                "isStarted" to (state?.get("isStarted") as? Boolean ?: false),
                "isEnded" to (state?.get("isEnded") as? Boolean ?: false),
                "currentQuestionIndex" to (state?.get("currentQuestionIndex") as? Int ?: 0)
            )
        }
        return jsonResponse(200, mapOf("contests" to result))
    }

    /** 获取考试的题目列表 */
    private fun handleGetQuestions(session: IHTTPSession): Response {
        val examId = session.parameters["examId"]?.firstOrNull()?.toLongOrNull()
            ?: return jsonResponse(400, mapOf("error" to "缺少 examId"))
        val exam = db.getAssignedExamById(examId)
            ?: return jsonResponse(404, mapOf("error" to "考试不存在"))
        val questionIds = (exam["questionIds"] as? String) ?: ""
        val questions = if (questionIds.isNotBlank()) {
            val ids = questionIds.split(",").mapNotNull { it.trim().toLongOrNull() }
            db.getQuestionsByIds(ids)
        } else emptyList()

        val qList = questions.mapIndexed { idx, q ->
            mapOf(
                "index" to idx,
                "id" to q.id,
                "type" to q.type.code,
                "typeLabel" to q.type.label,
                "content" to q.content,
                "options" to q.options,
                "answer" to q.answer,
                "difficulty" to q.difficulty
            )
        }
        return jsonResponse(200, mapOf("questions" to qList, "total" to qList.size))
    }

    /** 综合统计数据包（教师面板轮询） */
    private fun handleGetStats(session: IHTTPSession): Response {
        val examId = session.parameters["examId"]?.firstOrNull()?.toLongOrNull()
            ?: return jsonResponse(400, mapOf("error" to "缺少 examId"))

        // 1 秒缓存：高频轮询直接返回缓存 JSON，减少 DB 压力
        val now = System.currentTimeMillis()
        val cached = statsCache[examId]
        if (cached != null && now - cached.first < STATS_CACHE_TTL_MS) {
            return newFixedLengthResponse(
                Response.Status.OK, "application/json", cached.second
            ).also { it.addHeader("X-Cache", "HIT") }
        }

        val exam = db.getAssignedExamById(examId)
            ?: return jsonResponse(404, mapOf("error" to "考试不存在"))

        // 获取题目列表
        val questionIds = (exam["questionIds"] as? String) ?: ""
        val questions = if (questionIds.isNotBlank()) {
            val ids = questionIds.split(",").mapNotNull { it.trim().toLongOrNull() }
            db.getQuestionsByIds(ids)
        } else emptyList()

        // 当前比赛状态
        val state = db.getBuzzerState(examId)
        val currentIndex = state?.get("currentQuestionIndex") as? Int ?: 0
        val isStarted = state?.get("isStarted") as? Boolean ?: false
        val isEnded = state?.get("isEnded") as? Boolean ?: false

        // 当前题目信息
        val currentQuestion: Map<String, Any> = if (currentIndex < questions.size) {
            val q = questions[currentIndex]
            mapOf(
                "index" to currentIndex,
                "id" to q.id,
                "type" to q.type.code,
                "typeLabel" to q.type.label,
                "content" to q.content,
                "options" to q.options,
                "answer" to q.answer,
                "difficulty" to q.difficulty
            )
        } else emptyMap()

        // 当前题目答题数量
        val answerCount = db.getBuzzerAnswerCount(examId, currentIndex)

        // 当前题目：答题最快且正确的学生排行
        val fastestCorrect = db.getBuzzerFastestCorrect(examId, currentIndex, 10)

        // 当前题目：各组正确率
        val groupAccuracy = db.getBuzzerGroupAccuracyForQuestion(examId, currentIndex)

        // 整体：学生正确率排行（按所有已答题目统计）
        val overallStudentRanking = db.getBuzzerOverallStudentAccuracy(examId)

        // 整体：组别正确率排行
        val overallGroupRanking = db.getBuzzerOverallGroupAccuracy(examId)

        // 整体：正确率最低的题目排行
        val questionIdsForWorst = questions.map { it.id }
        val worstQuestions = db.getBuzzerWorstQuestions(examId, questionIdsForWorst)

        val responseData = mapOf(
            "state" to mapOf(
                "examId" to examId,
                "bankName" to (exam["bankName"] as? String ?: ""),
                "currentQuestionIndex" to currentIndex,
                "totalQuestions" to questions.size,
                "isStarted" to isStarted,
                "isEnded" to isEnded
            ),
            "currentQuestion" to currentQuestion,
            "answerCount" to answerCount,
            "fastestCorrect" to fastestCorrect,
            "groupAccuracy" to groupAccuracy,
            "overallStudentRanking" to overallStudentRanking,
            "overallGroupRanking" to overallGroupRanking,
            "worstQuestions" to worstQuestions
        )
        val json = gson.toJson(responseData)
        // 写入缓存
        statsCache[examId] = Pair(System.currentTimeMillis(), json)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    /** 初始化或重置抢答比赛（从第0题开始） */
    private fun handleInitContest(body: String): Response {
        if (body.isBlank()) return jsonResponse(400, mapOf("error" to "请求体为空"))
        val params = gson.fromJson(body, Map::class.java) as? Map<String, Any>
            ?: return jsonResponse(400, mapOf("error" to "无效请求"))
        val examId = (params["examId"] as? Number)?.toLong()
            ?: return jsonResponse(400, mapOf("error" to "缺少 examId"))
        db.getAssignedExamById(examId)
            ?: return jsonResponse(404, mapOf("error" to "考试不存在"))
        // initBuzzerContest 内部已原子写入 current_question_index=0，无需再单独调用
        db.initBuzzerContest(examId)
        statsCache.remove(examId) // 状态已变更，使缓存失效
        return jsonResponse(200, mapOf("message" to "比赛已初始化，从第1题开始"))
    }

    /** 切换当前题目索引 */
    private fun handleSetQuestion(body: String): Response {
        if (body.isBlank()) return jsonResponse(400, mapOf("error" to "请求体为空"))
        val params = gson.fromJson(body, Map::class.java) as? Map<String, Any>
            ?: return jsonResponse(400, mapOf("error" to "无效请求"))
        val examId = (params["examId"] as? Number)?.toLong()
            ?: return jsonResponse(400, mapOf("error" to "缺少 examId"))
        val index = (params["index"] as? Number)?.toInt()
            ?: return jsonResponse(400, mapOf("error" to "缺少题目索引"))
        db.getAssignedExamById(examId)
            ?: return jsonResponse(404, mapOf("error" to "考试不存在"))
        db.setBuzzerCurrentQuestion(examId, index)
        statsCache.remove(examId) // 题目已切换，使缓存失效
        return jsonResponse(200, mapOf("message" to "已切换到第 ${index + 1} 题"))
    }

    /** 结束比赛 */
    private fun handleEndContest(body: String): Response {
        if (body.isBlank()) return jsonResponse(400, mapOf("error" to "请求体为空"))
        val params = gson.fromJson(body, Map::class.java) as? Map<String, Any>
            ?: return jsonResponse(400, mapOf("error" to "无效请求"))
        val examId = (params["examId"] as? Number)?.toLong()
            ?: return jsonResponse(400, mapOf("error" to "缺少 examId"))
        db.getAssignedExamById(examId)
            ?: return jsonResponse(404, mapOf("error" to "考试不存在"))
        // 原子性结束比赛并标记所有学生完成，避免两步调用中途崩溃造成状态不一致
        db.endBuzzerContestAndComplete(examId)
        statsCache.remove(examId) // 比赛已结束，使缓存失效
        return jsonResponse(200, mapOf("message" to "比赛已结束"))
    }

    // ==================== 工具方法 ====================

    private fun jsonResponse(code: Int, data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(
            Response.Status.lookup(code) ?: Response.Status.OK,
            "application/json",
            json
        )
    }

    private fun serveAsset(assetPath: String, mimeType: String): Response {
        return try {
            val inputStream = context.assets.open(assetPath)
            val bytes = inputStream.readBytes()
            inputStream.close()
            newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } catch (e: Exception) {
            jsonResponse(404, mapOf("error" to "页面不存在"))
        }
    }

    private fun serveStaticFile(uri: String): Response {
        val path = "web${uri.removePrefix("/static")}"
        val mimeType = when {
            uri.endsWith(".css") -> "text/css"
            uri.endsWith(".js") -> "application/javascript"
            uri.endsWith(".png") -> "image/png"
            uri.endsWith(".jpg") || uri.endsWith(".jpeg") -> "image/jpeg"
            uri.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        return serveAsset(path, mimeType)
    }
}
