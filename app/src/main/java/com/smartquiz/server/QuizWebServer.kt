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
import com.smartquiz.model.*
import com.smartquiz.util.ContentFilter
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

/**
 * 内置Web服务器 - 提供在线题库训练和考试服务
 */
@Suppress("UNCHECKED_CAST")
class QuizWebServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val db = DatabaseHelper.getInstance(context)
    private val gson = Gson()

    // 请求体最大允许 4 MB，防止恶意或异常大请求导致 OOM
    private val MAX_BODY_SIZE = 4 * 1024 * 1024

    // 用户会话管理 (token -> userId)，使用线程安全的并发集合
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 轻量级滑动窗口 Rate Limiter（基于令牌桶简化版）。
     * key = "$clientIp:$endpoint"，记录该 key 最近一次允许通过的时间戳。
     * 若距上次调用不足 minIntervalMs，则拒绝本次请求（返回 429）。
     * 使用 ConcurrentHashMap 保证线程安全；超过 2000 条时自动清理旧记录。
     */
    private val rateLimitMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun checkRateLimit(session: IHTTPSession, endpoint: String, minIntervalMs: Long): Response? {
        val ip = session.remoteIpAddress ?: "unknown"
        val key = "$ip:$endpoint"
        val now = System.currentTimeMillis()
        // 使用 compute() 原子读写，消除 TOCTOU 竞态
        var rejected = false
        rateLimitMap.compute(key) { _, last ->
            if (last != null && now - last < minIntervalMs) {
                rejected = true
                last // 保持原值不更新
            } else {
                now  // 允许通过，更新时间戳
            }
        }
        if (rejected) {
            return jsonResponse(429, mapOf("error" to "请求过于频繁，请稍后再试"))
        }
        // 超过 2000 条时清理超过 60 秒未使用的记录，防止内存无限增长
        if (rateLimitMap.size > 2000) {
            val expire = now - 60_000L
            rateLimitMap.entries.removeIf { it.value < expire }
        }
        return null
    }
    // 被踢出的token（用于区分"未登录"和"被踢出"），使用线程安全集合
    private val kickedTokens: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** 获取当前在线用户ID集合 */
    fun getOnlineUserIds(): Set<Long> = sessions.values.toSet()

    /** 踢出指定用户的旧会话，保留新token */
    private fun kickOldSessions(userId: Long, newToken: String) {
        val oldTokens = sessions.entries.filter { it.value == userId && it.key != newToken }.map { it.key }
        oldTokens.forEach { old ->
            sessions.remove(old)
            kickedTokens.add(old)
        }
        // 清除数据库中旧 token，重建新 token（不在此处写新 token，由调用方素 saveUserToken 完成）
        if (oldTokens.isNotEmpty()) db.clearUserToken(userId)
        // kickedTokens 超过500条时清理多余的（ConcurrentHashMap 迭代器安全）
        if (kickedTokens.size > 500) {
            val toRemove = kickedTokens.toList().take(kickedTokens.size - 200)
            kickedTokens.removeAll(toRemove.toSet())
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        // 处理CORS预检请求
        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
                addHeader("Access-Control-Max-Age", "86400")
            }
        }

        val response = try {
            // 读取请求体：先尝试 parseBody，若 postData 为空则直接读 inputStream
            val postBody: String = if (method == Method.POST || method == Method.PUT) {
                val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                // 拒绝超大请求体，防止恶意客户端触发 OOM
                if (contentLength > MAX_BODY_SIZE) {
                    return jsonResponse(413, mapOf("error" to "请求体过大")).also {
                        it.addHeader("Access-Control-Allow-Origin", "*")
                    }
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
                } else {
                    val bodyMap = mutableMapOf<String, String>()
                    try { session.parseBody(bodyMap) } catch (_: Exception) {}
                    bodyMap["postData"] ?: ""
                }
            } else ""

            when {
                // 静态页面：先检查 If-None-Match，命中则返回 304，节省大文件重传
                uri == "/" || uri == "/index.html" -> {
                    val clientEtag = session.headers["if-none-match"]
                    val serverEtag = "\"${"web/index.html".hashCode().toUInt()}\""
                    if (clientEtag != null && clientEtag == serverEtag)
                        newFixedLengthResponse(Response.Status.NOT_MODIFIED, null, "").apply { addHeader("ETag", serverEtag) }
                    else
                        serveAsset("web/index.html", "text/html; charset=utf-8")
                }
                uri.startsWith("/static/") -> {
                    val assetPath = "web${uri.removePrefix("/static")}"
                    val clientEtag = session.headers["if-none-match"]
                    val serverEtag = "\"${assetPath.hashCode().toUInt()}\""
                    if (clientEtag != null && clientEtag == serverEtag)
                        newFixedLengthResponse(Response.Status.NOT_MODIFIED, null, "").apply { addHeader("ETag", serverEtag) }
                    else
                        serveStaticFile(uri)
                }
                uri.startsWith("/katex/") -> {
                    val assetPath = "web$uri"
                    val clientEtag = session.headers["if-none-match"]
                    val serverEtag = "\"${assetPath.hashCode().toUInt()}\""
                    if (clientEtag != null && clientEtag == serverEtag)
                        newFixedLengthResponse(Response.Status.NOT_MODIFIED, null, "").apply { addHeader("ETag", serverEtag) }
                    else
                        serveKatexFile(uri)
                }

                // API路由
                uri == "/api/register" && method == Method.POST -> handleRegister(postBody)
                uri == "/api/login" && method == Method.POST -> handleLogin(postBody)
                uri == "/api/user/stats" -> handleUserStats(session)
                uri == "/api/user/profile" && method == Method.PUT -> handleUpdateProfile(session, postBody)
                uri == "/api/user/password" && method == Method.PUT -> handleUpdatePassword(session, postBody)
                uri == "/api/banks" && method == Method.GET -> handleGetBanks(session)
                uri.startsWith("/api/banks/") && uri.endsWith("/questions") ->
                    handleGetQuestions(uri, session)
                uri == "/api/exam/submit" && method == Method.POST -> handleSubmitExam(session, postBody)
                uri == "/api/exam/records" -> handleGetExamRecords(session)
                uri == "/api/exam/records/clear" && method == Method.POST -> handleClearExamRecords(session)
                uri == "/api/wrong-questions" -> handleGetWrongQuestions(session)
                uri == "/api/wrong-questions/resolve" && method == Method.POST ->
                    handleResolveWrong(session, postBody)
                uri == "/api/wrong-questions/reset" && method == Method.POST ->
                    handleResetWrong(session, postBody)
                uri == "/api/wrong-questions/ids" -> handleGetWrongIds(session)
                uri == "/api/ranking/score" -> handleGetScoreRanking(session)
                uri == "/api/assigned-exam/active" -> handleGetActiveAssignedExam(session)
                uri == "/api/assigned-exam/complete" && method == Method.POST -> handleCompleteAssignedExam(session, postBody)
                uri == "/api/assigned-exam/force-complete" && method == Method.POST -> handleForceCompleteExam(session, postBody)
                uri == "/api/assigned-exam/ping" && method == Method.POST -> handleExamPing(session, postBody)
                uri == "/api/buzzer/state" && method == Method.GET -> handleGetBuzzerState(session)
                uri == "/api/buzzer/answer" && method == Method.POST -> handleSubmitBuzzerAnswer(session, postBody)
                uri == "/api/messages/unread" && method == Method.GET -> handleGetUnreadMessages(session)
                uri == "/api/messages/read" && method == Method.POST -> handleMarkMessageRead(session, postBody)
                uri == "/api/messages/send" && method == Method.POST -> handleSendMessage(session, postBody)
                uri == "/api/feedback" && method == Method.POST -> handleAddFeedback(session, postBody)
                uri.startsWith("/api/feedback/bank/") && method == Method.GET -> handleGetFeedback(uri, session)
                uri.startsWith("/api/feedback/") && uri.endsWith("/resolve") && method == Method.PUT ->
                    handleResolveFeedback(uri, session)
                uri == "/api/offline-exams" && method == Method.GET -> handleGetOfflineExams(session)
                uri.startsWith("/api/offline-exams/") && uri.endsWith("/download") && method == Method.GET ->
                    handleDownloadOfflineExam(uri, session)
                uri.startsWith("/api/offline-exams/") && uri.endsWith("/view") && method == Method.GET ->
                    handleViewOfflineExam(uri, session)
                uri == "/api/admin/cleanup-test-users" && method == Method.POST -> handleCleanupTestUsers(postBody)
                uri.startsWith("/api/img/") && method == Method.GET -> handleServeImage(uri)

                else -> jsonResponse(404, mapOf("error" to "接口不存在"))
            }
        } catch (e: Exception) {
            jsonResponse(500, mapOf("error" to "服务器错误: ${e.message}"))
        }

        // 为所有API响应添加CORS头
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    // ==================== 认证相关 ====================

    private fun handleRegister(body: String): Response {
        val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val username = params["username"]?.toString() ?: ""
        val password = params["password"]?.toString() ?: ""
        val nickname = params["nickname"]?.toString() ?: ""
        val groupName = params["group"]?.toString() ?: ""

        if (username.length < 2 || password.length < 4) {
            return jsonResponse(400, mapOf("error" to "用户名至少2个字符，密码至少4个字符"))
        }

        // 格式与内容安全校验
        val inputError = ContentFilter.checkRegisterInput(username, nickname, groupName)
        if (inputError != null) {
            return jsonResponse(400, mapOf("error" to inputError))
        }

        val token = generateToken()
        // 注册与写入 token 合并为单次事务，减少 SQLite 写锁占用次数，降低高并发锁竞争
        val (userId, _) = db.registerUserWithToken(username, password, nickname, groupName, token)
        if (userId < 0) {
            return jsonResponse(400, mapOf("error" to "用户名已存在"))
        }

        sessions[token] = userId
        kickOldSessions(userId, token)
        return jsonResponse(200, mapOf(
            "message" to "注册成功",
            "token" to token,
            "user" to mapOf("id" to userId, "username" to username, "nickname" to nickname.ifEmpty { username }, "group" to groupName)
        ))
    }

    private fun handleLogin(body: String): Response {
        val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val username = params["username"]?.toString() ?: ""
        val password = params["password"]?.toString() ?: ""

        val user = db.loginUser(username, password)
            ?: return jsonResponse(401, mapOf("error" to "用户名或密码错误"))

        val token = generateToken()
        sessions[token] = user.id
        kickOldSessions(user.id, token)
        db.saveUserToken(user.id, token)
        return jsonResponse(200, mapOf(
            "message" to "登录成功",
            "token" to token,
            "user" to mapOf(
                "id" to user.id,
                "username" to user.username,
                "nickname" to user.nickname,
                "realName" to user.realName,
                "school" to user.school,
                "className" to user.className,
                "group" to user.groupName,
                "phone" to user.phone,
                "email" to user.email
            )
        ))
    }

    private fun getTokenFromSession(session: IHTTPSession): String? {
        return session.headers["authorization"]?.removePrefix("Bearer ")
            ?: session.parameters["token"]?.firstOrNull()
    }

    private fun getUserIdFromSession(session: IHTTPSession): Long? {
        val token = getTokenFromSession(session) ?: return null
        return sessions[token]
    }

    private fun requireAuth(session: IHTTPSession): Pair<Long, Response?> {
        val token = getTokenFromSession(session)
        if (token == null) {
            return Pair(0, jsonResponse(401, mapOf("error" to "请先登录")))
        }
        var userId = sessions[token]
        if (userId == null) {
            // 检查是否被踢出
            if (kickedTokens.remove(token)) {
                return Pair(0, jsonResponse(409, mapOf(
                    "error" to "您的账号已在其他设备登录，当前已被强制下线",
                    "kicked" to true
                )))
            }
            // APP 重启后内存会话丢失，尝试从数据库恢复会话
            val dbUserId = db.getUserIdByToken(token)
            if (dbUserId != null) {
                sessions[token] = dbUserId
                userId = dbUserId
            } else {
                return Pair(0, jsonResponse(401, mapOf("error" to "请先登录")))
            }
        }
        return Pair(userId, null)
    }

    // ==================== 题库相关 ====================

    private fun handleGetBanks(session: IHTTPSession): Response {
        // 题库列表需要登录才能查看，防止未授权客户端高频触发 N+1 查询
        val (_, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        val banks = db.getAllBanks()
        // 一次性批量获取全部题库的 pendingFeedback 和 blockedCount（2 条聚合 SQL 替代 2N 次查询）
        val metaBatch = db.getBankMetaBatch()
        val result = banks.map { bank ->
            val (pendingFeedback, blockedCount) = metaBatch[bank.id] ?: Pair(0, 0)
            mapOf(
                "id" to bank.id,
                "name" to bank.name,
                "subject" to bank.subject,
                "description" to bank.description,
                "questionCount" to bank.questionCount,
                "createdAt" to bank.createdAt,
                "pendingFeedback" to pendingFeedback,
                "blockedCount" to blockedCount
            )
        }
        return jsonResponse(200, mapOf("banks" to result))
    }

    private fun handleGetQuestions(uri: String, session: IHTTPSession): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        val bankId = uri.split("/").dropLast(1).last().toLongOrNull()
            ?: return jsonResponse(400, mapOf("error" to "无效的题库ID"))

        val mode = session.parameters["mode"]?.firstOrNull() ?: "all"  // all, random, wrong
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 0
        val maxDiff = session.parameters["maxDiff"]?.firstOrNull()?.toIntOrNull() ?: 5
        val typeFilter = session.parameters["types"]?.firstOrNull() ?: "" // 题型过滤，逗号分隔

        // 固定题目ID列表（考试模式下优先使用）
        val questionIdsParam = session.parameters["questionIds"]?.firstOrNull() ?: ""

        var questions = if (questionIdsParam.isNotBlank()) {
            // 有固定题目列表，直接按ID取题
            val ids = questionIdsParam.split(",").mapNotNull { it.trim().toLongOrNull() }
            val qs = db.getQuestionsByIds(ids)
            // 若客户端请求随机顺序，则打乱题目
            if (mode == "random") qs.shuffled() else qs
        } else {
            // 动态组卷逻辑
            // wrong_extend：错题 + 同知识点其他题，自动打乱
            when (mode) {
                "wrong", "wrong_extend" -> {
                    val wrongIds = db.getWrongQuestionIds(userId, bankId)
                    val wrongQuestions = db.getQuestionsByIds(wrongIds)
                    if (mode == "wrong_extend" && wrongQuestions.isNotEmpty()) {
                        val kps = wrongQuestions.mapNotNull { it.knowledgePoint.takeIf { k -> k.isNotBlank() } }.distinct()
                        val extendQuestions = db.getQuestionsByKnowledgePoints(bankId, kps, wrongIds)
                        (wrongQuestions + extendQuestions).distinctBy { it.id }
                    } else {
                        wrongQuestions
                    }
                }
                else -> db.getQuestionsByBank(bankId)
            }
        }

        // 仅在非固定题目模式下应用筛选
        if (questionIdsParam.isBlank()) {
            // 过滤反馈次数 >= 15 的被屏蔽题目
            val blockedIds = db.getBlockedQuestionIds(bankId)
            if (blockedIds.isNotEmpty()) {
                questions = questions.filter { it.id !in blockedIds }
            }

            // 按最高难度过滤
            if (maxDiff in 1..4) {
                questions = questions.filter { it.difficulty <= maxDiff }
            }

            // 按题型过滤
            if (typeFilter.isNotBlank()) {
                val allowedTypes = typeFilter.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                if (allowedTypes.isNotEmpty()) {
                    questions = questions.filter { it.type.code in allowedTypes }
                }
            }

            if (mode == "random" || mode == "wrong" || mode == "wrong_extend") {
                questions = questions.shuffled()
            }
            if (limit > 0 && questions.size > limit) {
                questions = questions.take(limit)
            }

            // 按题型分别限制数量 (typeCounts 格式: "1:5,2:3,3:2" -> 类型1取5题,类型2取3题...)
            val typeCounts = session.parameters["typeCounts"]?.firstOrNull() ?: ""
            if (typeCounts.isNotBlank()) {
                val countMap = mutableMapOf<Int, Int>()
                typeCounts.split(",").forEach { entry ->
                    val parts = entry.split(":")
                    if (parts.size == 2) {
                        val typeCode = parts[0].trim().toIntOrNull()
                        val count = parts[1].trim().toIntOrNull()
                        if (typeCode != null && count != null) countMap[typeCode] = count
                    }
                }
                if (countMap.isNotEmpty()) {
                    val grouped = questions.groupBy { it.type.code }
                    questions = countMap.flatMap { (typeCode, count) ->
                        val typeQuestions = grouped[typeCode] ?: emptyList()
                        if (count > 0 && typeQuestions.size > count) typeQuestions.take(count) else typeQuestions
                    }
                }
            }
        }

        val wrongIdSet = if (mode == "wrong_extend") db.getWrongQuestionIds(userId, bankId).toSet() else emptySet()

        // 只有明确的练习/错题模式（且非固定题目考试场景）才返回答案，防止客户端伪造 mode 参数获取答案
        val isPracticeMode = mode in setOf("wrong", "wrong_extend", "practice", "random", "all") && questionIdsParam.isBlank()

        val questionList = questions.map { q ->
            val base = mutableMapOf(
                "id" to q.id,
                "type" to q.type.code,
                "typeLabel" to q.type.label,
                "content" to q.content,
                "options" to q.options,
                "difficulty" to q.difficulty,
                "knowledgePoint" to q.knowledgePoint,
                "imageUrl" to q.imageUrl
            )
            // 仅练习/错题模式返回答案和解析（即时反馈），考试模式严禁返回
            if (isPracticeMode) {
                base["correctAnswer"] = q.answer
                base["explanation"] = q.explanation
            }
            // 标记是否为错题（用于错题扩展模式区分显示）
            if (mode == "wrong_extend") {
                base["isWrong"] = q.id in wrongIdSet
            }
            base
        }

        val bank = db.getBank(bankId)
        return jsonResponse(200, mapOf(
            "bankName" to (bank?.name ?: ""),
            "questions" to questionList,
            "total" to questionList.size
        ))
    }

    // ==================== 考试提交 ====================

    private fun handleSubmitExam(session: IHTTPSession, body: String): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val bankId = (params["bankId"] as? Number)?.toLong() ?: 0
        val timeCost = (params["timeCost"] as? Number)?.toInt() ?: 0
        val mode = params["mode"]?.toString() ?: "practice"
        val assignedExamId = (params["assignedExamId"] as? Number)?.toLong() ?: 0
        val answers = params["answers"] as? List<Map<String, Any>> ?: emptyList()
        // typeScores 格式: "1:2,2:3,3:2,4:2"  (typeCode:scorePerQuestion)
        // 百分制格式: "1:2,2:3,3:2,4:2|percent|rawTotal"
        val typeScoresStr = params["typeScores"]?.toString() ?: ""
        val isPercentMode = typeScoresStr.contains("|percent|")
        val rawTypeScoresStr = if (isPercentMode) typeScoresStr.substringBefore("|percent|") else typeScoresStr
        val rawTotalForPercent = if (isPercentMode) typeScoresStr.substringAfterLast("|").toIntOrNull() ?: 0 else 0
        val typeScoreMap: Map<Int, Int> = if (rawTypeScoresStr.isNotBlank()) {
            rawTypeScoresStr.split(",").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) parts[0].trim().toIntOrNull()?.let { code ->
                    parts[1].trim().toIntOrNull()?.let { score -> code to score }
                } else null
            }.toMap()
        } else emptyMap()

        if (answers.isEmpty()) {
            return jsonResponse(400, mapOf("error" to "没有答题记录"))
        }

        // 幂等检查：同一场指定考试（assignedExamId > 0）不允许重复提交
        // 防止网络重试或前端并发请求导致多条成绩记录
        if (assignedExamId > 0 && db.hasExamRecord(userId, assignedExamId)) {
            return jsonResponse(200, mapOf(
                "message" to "已提交",
                "result" to db.getExamRecordResult(userId, assignedExamId)
            ))
        }

        val bank = db.getBank(bankId)
            ?: return jsonResponse(400, mapOf("error" to "题库不存在"))

        var correctCount = 0
        var earnedScore = 0
        var totalPossibleScore = 0
        val answerRecords = mutableListOf<AnswerRecord>()
        val resultDetails = mutableListOf<Map<String, Any>>()
        // 收集需要事务内执行的错题操作，避免分散调用
        val wrongUpdates = mutableListOf<Triple<Long, Long, Long>>()
        val resolveUpdates = mutableListOf<Pair<Long, Long>>()
        val incrementUpdates = mutableListOf<Triple<Long, Long, Int>>()

        // 批量一次性查询所有题目，避免 N+1 数据库查询导致并发提交时阻塞崩溃
        val questionIds = answers.mapNotNull { (it["questionId"] as? Number)?.toLong() }
        val questionMap = db.getQuestionsByIds(questionIds).associateBy { it.id }

        answers.forEach { ans ->
            val questionId = (ans["questionId"] as? Number)?.toLong() ?: return@forEach
            val userAnswer = ans["answer"]?.toString() ?: ""
            val question = questionMap[questionId] ?: return@forEach

            // 该题型每题分值（有分值设置则用设置值，否则默认2分）
            val perScore = typeScoreMap[question.type.code] ?: 2
            totalPossibleScore += perScore

            val isCorrect = checkAnswer(question, userAnswer)
            val isSkipped = userAnswer.isBlank()
            if (isCorrect) {
                correctCount++
                earnedScore += perScore
                when (mode) {
                    "wrong_redo" -> resolveUpdates.add(Pair(userId, questionId))
                    "wrong_extend" -> incrementUpdates.add(Triple(userId, questionId, 2))
                }
            } else if (!isSkipped) {
                wrongUpdates.add(Triple(userId, questionId, bankId))
            }

            answerRecords.add(AnswerRecord(
                userId = userId,
                questionId = questionId,
                userAnswer = userAnswer,
                isCorrect = isCorrect
            ))

            resultDetails.add(mapOf(
                "questionId" to questionId,
                "content" to question.content,
                "type" to question.type.code,
                "typeLabel" to question.type.label,
                "options" to question.options,
                "userAnswer" to userAnswer,
                // 提交后始终返回正确答案和解析（考试结束后查看答案是正常需求）
                "correctAnswer" to question.answer,
                "explanation" to question.explanation,
                "isCorrect" to isCorrect,
                "isSkipped" to isSkipped,
                "knowledgePoint" to question.knowledgePoint,
                "imageUrl" to question.imageUrl
            ))
        }

        val totalQuestions = answers.size
        val answeredCount = answers.count { it["answer"]?.toString()?.isNotBlank() == true }
        // 有分值设置时用得分/满分，否则退回百分制
        val score = if (typeScoreMap.isNotEmpty() && totalPossibleScore > 0) {
            if (isPercentMode && rawTotalForPercent > 0) {
                // 百分制：按原始满分折算为100分
                (earnedScore.toDouble() / rawTotalForPercent) * 100.0
            } else {
                earnedScore.toDouble()
            }
        } else {
            if (answeredCount > 0) (correctCount.toDouble() / totalQuestions) * 100 else 0.0
        }
        val scoreTotal = if (isPercentMode) 100
            else if (typeScoreMap.isNotEmpty() && totalPossibleScore > 0) totalPossibleScore
            else 100

        val examRecord = ExamRecord(
            userId = userId,
            bankId = bankId,
            bankName = bank.name,
            totalQuestions = totalQuestions,
            correctCount = correctCount,
            score = score,
            timeCostSeconds = timeCost,
            mode = mode,
            assignedExamId = assignedExamId
        )

        // 所有写操作在单一事务中完成，保证原子性
        val recordId = db.insertExamResult(examRecord, answerRecords, wrongUpdates, resolveUpdates, incrementUpdates)

        return jsonResponse(200, mapOf(
            "message" to "提交成功",
            "result" to mapOf(
                "recordId" to recordId,
                "totalQuestions" to totalQuestions,
                "correctCount" to correctCount,
                "score" to String.format("%.1f", score).toDouble(),
                "scoreTotal" to scoreTotal,
                "timeCost" to timeCost,
                "mode" to mode,
                "details" to resultDetails
            )
        ))
    }

    private fun checkAnswer(question: Question, userAnswer: String): Boolean {
        val correct = question.answer.trim()
        val user = userAnswer.trim()

        return when (question.type) {
            QuestionType.SINGLE_CHOICE -> {
                user.equals(correct, ignoreCase = true)
            }
            QuestionType.MULTIPLE_CHOICE -> {
                val correctSet = correct.uppercase().toCharArray().sorted()
                val userSet = user.uppercase().toCharArray().sorted()
                correctSet == userSet
            }
            QuestionType.TRUE_FALSE -> {
                user == correct
            }
            QuestionType.FILL_BLANK -> {
                checkFillBlankAnswer(user, correct)
            }
            QuestionType.SHORT_ANSWER -> {
                checkShortAnswer(user, correct)
            }
        }
    }

    /**
     * 填空题智能判分：
     * 1. 支持多备选答案（用 | 或 ／ 分隔），任意一个匹配即正确
     * 2. 规范化后比较：去除标点、去除常见虚词助词、忽略大小写和空白
     * 3. 支持关键词无序匹配（答案词组顺序不同也算对）
     */
    private fun checkFillBlankAnswer(user: String, correct: String): Boolean {
        // 按备选答案分隔符拆分，任意一个匹配即正确
        val alternatives = correct.split(Regex("[|｜/／;；]")).map { it.trim() }.filter { it.isNotEmpty() }
        return alternatives.any { alt -> matchFillBlank(user, alt) }
    }

    private fun matchFillBlank(user: String, correct: String): Boolean {
        val u = normalizeFillBlank(user)
        val c = normalizeFillBlank(correct)
        if (u == c) return true
        // 关键词无序匹配：将答案按空格拆分为词组，判断用户答案包含所有关键词
        val cWords = c.split(" ").filter { it.isNotEmpty() }
        val uWords = u.split(" ").filter { it.isNotEmpty() }
        if (cWords.size > 1 && cWords.all { cw -> uWords.any { uw -> uw.contains(cw) || cw.contains(uw) } }) return true
        // 拼接后无空格比较
        return u.replace(" ", "") == c.replace(" ", "")
    }

    private fun normalizeFillBlank(text: String): String {
        // 去除中英文标点
        var s = text.replace(Regex("[\\pP\\p{S}！？。，、；：\u201c\u201d\u2018\u2019「」【】《》〈〉（）——…·~`!?.,;:\"'()\\[\\]{}<>@#%^&*+=|\\\\/_-]"), " ")
        // 去除常见助词、语气词
        s = s.replace(Regex("(的|地|得|了|着|过|吧|啊|嘛|呢|吗|嗯|哦|哈|喽|罢了|而已|之类|等等|等)"), "")
        // 合并多余空白，转小写
        return s.replace(Regex("\\s+"), " ").trim().lowercase()
    }

    /**
     * 简答题智能判分：
     * 1. 若标准答案为空，只要用户有作答即给分
     * 2. 支持多备选答案（用 | 分隔），任意一个匹配即正确
     * 3. 关键词匹配：用户答案包含标准答案中60%以上的关键词即给分
     * 4. 规范化后全文相似度（简单版）
     */
    private fun checkShortAnswer(user: String, correct: String): Boolean {
        if (user.isBlank()) return false
        if (correct.isBlank()) return true // 无标准答案，有作答即得分
        // 支持备选答案
        val alternatives = correct.split(Regex("[|｜]")).map { it.trim() }.filter { it.isNotEmpty() }
        return alternatives.any { alt -> matchShortAnswer(user, alt) }
    }

    private fun matchShortAnswer(user: String, correct: String): Boolean {
        val u = normalizeFillBlank(user)
        val c = normalizeFillBlank(correct)
        if (u == c) return true
        // 提取关键词（长度≥2的词）
        val cWords = c.split(Regex("\\s+")).filter { it.length >= 2 }
        if (cWords.isEmpty()) return u.isNotBlank()
        // 用户答案包含的关键词数量
        val uText = u.replace(" ", "")
        val matchedCount = cWords.count { kw -> uText.contains(kw) }
        // 包含60%以上关键词即给分
        return matchedCount.toDouble() / cWords.size >= 0.6
    }

    // ==================== 记录查询 ====================

    private fun handleGetExamRecords(session: IHTTPSession): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        val records = db.getExamRecordsByUser(userId).map { r ->
            mapOf(
                "id" to r.id,
                "bankName" to r.bankName,
                "totalQuestions" to r.totalQuestions,
                "correctCount" to r.correctCount,
                "score" to r.score,
                "timeCost" to r.timeCostSeconds,
                "mode" to r.mode,
                "modeLabel" to when(r.mode) {
                    "practice" -> "练习"
                    "exam" -> "考试"
                    "wrong_redo" -> "错题重练"
                    else -> r.mode
                },
                "createdAt" to r.createdAt
            )
        }
        return jsonResponse(200, mapOf("records" to records))
    }

    private fun handleClearExamRecords(session: IHTTPSession): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        db.deleteExamRecordsByUser(userId)
        return jsonResponse(200, mapOf("message" to "考试记录已清空"))
    }

    private fun handleUserStats(session: IHTTPSession): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        val stats = db.getUserStats(userId)
        return jsonResponse(200, mapOf("stats" to stats))
    }

    // ==================== 错题相关 ====================

    private fun handleGetWrongQuestions(session: IHTTPSession): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        val bankId = session.parameters["bankId"]?.firstOrNull()?.toLongOrNull()
        val wrongQuestions = db.getWrongQuestions(userId, bankId)
        return jsonResponse(200, mapOf("wrongQuestions" to wrongQuestions))
    }

    private fun handleGetWrongIds(session: IHTTPSession): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        val bankId = session.parameters["bankId"]?.firstOrNull()?.toLongOrNull()
        val ids = db.getWrongQuestionIds(userId, bankId)
        return jsonResponse(200, mapOf("wrongIds" to ids))
    }

    private fun handleResolveWrong(session: IHTTPSession, body: String): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val questionId = (params["questionId"] as? Number)?.toLong()
            ?: return jsonResponse(400, mapOf("error" to "缺少题目ID"))

        db.resolveWrongQuestion(userId, questionId)
        return jsonResponse(200, mapOf("message" to "已标记为已掌握"))
    }

    private fun handleResetWrong(session: IHTTPSession, body: String): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        val bankId: Long? = if (body.isNotBlank()) {
            runCatching {
                val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
                (params["bankId"] as? Number)?.toLong()
            }.getOrNull()
        } else null

        db.resetWrongQuestions(userId, bankId)
        return jsonResponse(200, mapOf("message" to "错题库已重置"))
    }

    // ==================== 用户资料 ====================

    private fun handleUpdateProfile(session: IHTTPSession, body: String): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        if (body.isBlank()) return jsonResponse(400, mapOf("error" to "请求体为空"))
        @Suppress("UNCHECKED_CAST")
        val params = gson.fromJson(body, Map::class.java) as? Map<String, Any>
            ?: return jsonResponse(400, mapOf("error" to "无效的请求格式"))
        val realName = params["realName"]?.toString() ?: ""
        val school = params["school"]?.toString() ?: ""
        val className = params["className"]?.toString() ?: ""
        val phone = params["phone"]?.toString() ?: ""
        val email = params["email"]?.toString() ?: ""
        val groupName = params["group"]?.toString() ?: ""

        db.updateUserProfile(userId, realName, school, className, phone, email, groupName)
        val user = db.getUser(userId) ?: return jsonResponse(404, mapOf("error" to "用户不存在"))
        return jsonResponse(200, mapOf(
            "message" to "资料已更新",
            "user" to mapOf(
                "id" to user.id,
                "username" to user.username,
                "nickname" to user.nickname,
                "realName" to user.realName,
                "school" to user.school,
                "className" to user.className,
                "group" to user.groupName,
                "phone" to user.phone,
                "email" to user.email
            )
        ))
    }

    private fun handleUpdatePassword(session: IHTTPSession, body: String): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        if (body.isBlank()) return jsonResponse(400, mapOf("error" to "请求体为空"))
        @Suppress("UNCHECKED_CAST")
        val params = gson.fromJson(body, Map::class.java) as? Map<String, Any>
            ?: return jsonResponse(400, mapOf("error" to "无效的请求格式"))
        val oldPassword = params["oldPassword"]?.toString() ?: ""
        val newPassword = params["newPassword"]?.toString() ?: ""

        if (newPassword.length < 4) return jsonResponse(400, mapOf("error" to "新密码至少4个字符"))
        val ok = db.updateUserPassword(userId, oldPassword, newPassword)
        return if (ok) jsonResponse(200, mapOf("message" to "密码已修改"))
               else jsonResponse(400, mapOf("error" to "原密码错误"))
    }

    // ==================== 题目反馈 ====================

    private fun handleAddFeedback(session: IHTTPSession, body: String): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp

        val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val questionId = (params["questionId"] as? Number)?.toLong()
            ?: return jsonResponse(400, mapOf("error" to "缺少题目ID"))
        val bankId = (params["bankId"] as? Number)?.toLong()
            ?: return jsonResponse(400, mapOf("error" to "缺少题库ID"))
        val content = params["content"]?.toString()?.trim() ?: ""
        if (content.isBlank()) return jsonResponse(400, mapOf("error" to "反馈内容不能为空"))

        db.addFeedback(questionId, bankId, userId, content)
        return jsonResponse(200, mapOf("message" to "反馈已提交"))
    }

    private fun handleGetFeedback(uri: String, session: IHTTPSession): Response {
        val (_, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        // /api/feedback/bank/{bankId}
        val bankId = uri.removePrefix("/api/feedback/bank/").toLongOrNull()
            ?: return jsonResponse(400, mapOf("error" to "无效的题库ID"))
        val list = db.getFeedbackByBank(bankId).map { f ->
            mapOf(
                "id" to f.id,
                "questionId" to f.questionId,
                "bankId" to f.bankId,
                "userId" to f.userId,
                "username" to f.username,
                "content" to f.content,
                "isResolved" to f.isResolved,
                "createdAt" to f.createdAt
            )
        }
        return jsonResponse(200, mapOf("feedbacks" to list))
    }

    private fun handleResolveFeedback(uri: String, session: IHTTPSession): Response {
        val (_, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        // /api/feedback/{id}/resolve
        val feedbackId = uri.removePrefix("/api/feedback/").removeSuffix("/resolve").toLongOrNull()
            ?: return jsonResponse(400, mapOf("error" to "无效的反馈ID"))
        db.resolveFeedback(feedbackId)
        return jsonResponse(200, mapOf("message" to "已标记为已处理"))
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

    /**
     * 服务静态 asset 文件。
     * - 小文件（≤ ASSET_CACHE_THRESHOLD）用内存缓存，多次请求复用同一个 ByteArray，避免重复 readBytes。
     * - 大文件（katex.min.js / index.html 等）直接以 InputStream 流式传输，
     *   不整体读入内存，彻底避免多学生并发时的 OOM 崩溃。
     * - 所有文件均附带 Cache-Control 和 ETag，让浏览器缓存，减少重复请求。
     */
    private val assetCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val ASSET_CACHE_THRESHOLD = 32 * 1024  // ≤ 32 KB 才缓存

    /** 提供题目图片（存储在应用私有目录 question_images/） */
    private fun handleServeImage(uri: String): Response {
        val fileName = uri.removePrefix("/api/img/").replace("..", "").replace("/", "")
        if (fileName.isBlank()) return jsonResponse(400, mapOf("error" to "无效文件名"))
        val file = java.io.File(context.filesDir, "question_images/$fileName")
        if (!file.exists() || !file.isFile) return jsonResponse(404, mapOf("error" to "图片不存在"))
        val mimeType = when {
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".gif") -> "image/gif"
            fileName.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
        val fis = java.io.FileInputStream(file)
        return newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length()).apply {
            addHeader("Cache-Control", "public, max-age=86400")
        }
    }

    private fun serveAsset(assetPath: String, mimeType: String): Response {
        return try {
            // ETag 用路径的 hashCode，足够区分不同文件（重启后自动失效）
            val etag = "\"${assetPath.hashCode().toUInt()}\""

            val cached = assetCache[assetPath]
            if (cached != null) {
                // 命中内存缓存：直接用 ByteArrayInputStream，零拷贝
                return newFixedLengthResponse(
                    Response.Status.OK, mimeType,
                    ByteArrayInputStream(cached), cached.size.toLong()
                ).apply {
                    addHeader("Cache-Control", "public, max-age=86400")
                    addHeader("ETag", etag)
                }
            }

            val inputStream = context.assets.open(assetPath)
            val fileSize = inputStream.available()

            if (fileSize in 1..ASSET_CACHE_THRESHOLD) {
                // 小文件：读入内存并缓存，复用
                val bytes = inputStream.readBytes()
                inputStream.close()
                assetCache[assetPath] = bytes
                newFixedLengthResponse(
                    Response.Status.OK, mimeType,
                    ByteArrayInputStream(bytes), bytes.size.toLong()
                ).apply {
                    addHeader("Cache-Control", "public, max-age=86400")
                    addHeader("ETag", etag)
                }
            } else {
                // 大文件：流式传输，不整体读入内存，彻底避免 OOM
                // fileSize 可能为 0（assets.available() 有时返回 0），此时用 chunked 模式
                if (fileSize > 0) {
                    newFixedLengthResponse(
                        Response.Status.OK, mimeType,
                        inputStream, fileSize.toLong()
                    ).apply {
                        addHeader("Cache-Control", "public, max-age=86400")
                        addHeader("ETag", etag)
                    }
                } else {
                    // available() 返回 0 时降级：读字节但不缓存，仅一次分配
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    newFixedLengthResponse(
                        Response.Status.OK, mimeType,
                        ByteArrayInputStream(bytes), bytes.size.toLong()
                    ).apply {
                        addHeader("Cache-Control", "public, max-age=86400")
                        addHeader("ETag", etag)
                    }
                }
            }
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
            uri.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }
        return serveAsset(path, mimeType)
    }

    private fun serveKatexFile(uri: String): Response {
        val path = "web$uri"   // /katex/xxx → web/katex/xxx
        val mimeType = when {
            uri.endsWith(".css")   -> "text/css; charset=utf-8"
            uri.endsWith(".js")    -> "application/javascript; charset=utf-8"
            uri.endsWith(".woff2") -> "font/woff2"
            uri.endsWith(".woff")  -> "font/woff"
            uri.endsWith(".ttf")   -> "font/ttf"
            else -> "application/octet-stream"
        }
        return serveAsset(path, mimeType)
    }

    private fun handleGetScoreRanking(session: IHTTPSession): Response {
        val (_, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val ranking = db.getScoreRanking()
        // 一次批量查询所有用户的题库详情，替代 N 次独立 DB 调用
        val userIds = ranking.mapNotNull { it["userId"] as? Long }
        val bankDetailMap = db.getBankDetailBatch(userIds)
        val result = ranking.map { user ->
            val userId = user["userId"] as Long
            user + mapOf("bankDetails" to (bankDetailMap[userId] ?: emptyList<Any>()))
        }
        return jsonResponse(200, mapOf("ranking" to result))
    }

    private fun handleGetActiveAssignedExam(session: IHTTPSession): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val exam = db.getActiveAssignedExamOrAutoJoin(userId)
        val totalScore = if (exam != null) exam + mapOf(
            "forcedMode" to true,
            "typeCounts" to (exam["typeCounts"] ?: ""),
            "typeScores" to (exam["typeScores"] ?: ""),
            "submitBeforeEndMinutes" to (exam["submitBeforeEndMinutes"] ?: 0),
            "scheduledStartTime" to (exam["scheduledStartTime"] ?: 0L),
            "autoSubmitAt" to (exam["autoSubmitAt"] ?: 0L)
        ) else null
        return jsonResponse(200, mapOf("assignedExam" to totalScore))
    }

    private fun handleCompleteAssignedExam(session: IHTTPSession, body: String): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val assignedExamId = (params["assignedExamId"] as? Number)?.toLong() ?: return jsonResponse(400, mapOf("error" to "缺少参数"))
        db.markAssignedExamCompleted(userId, assignedExamId)
        return jsonResponse(200, mapOf("message" to "已标记完成"))
    }

    private fun handleForceCompleteExam(session: IHTTPSession, body: String): Response {
        val (_, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val assignedExamId = (params["assignedExamId"] as? Number)?.toLong() ?: return jsonResponse(400, mapOf("error" to "缺少参数"))
        db.forceCompleteAllUsers(assignedExamId)
        return jsonResponse(200, mapOf("message" to "已强制交卷"))
    }

    private fun handleExamPing(session: IHTTPSession, body: String): Response {
        // 心跳最短间隔 3 秒，防止前端异常情况下高频请求耗尽线程
        checkRateLimit(session, "exam_ping", 3_000L)?.let { return it }
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        var assignedExamId = 0L
        if (body.isNotBlank()) {
            runCatching {
                val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
                assignedExamId = (params["assignedExamId"] as? Number)?.toLong() ?: 0
                val answeredCount = (params["answeredCount"] as? Number)?.toInt() ?: 0
                if (assignedExamId > 0) db.updateExamUserActivity(assignedExamId, userId, answeredCount)
            }
        }
        // 检查是否被强制交卷
        val forceSubmit = if (assignedExamId > 0) db.checkAndClearForceSubmit(userId, assignedExamId) else false
        return jsonResponse(200, mapOf("message" to "pong", "forceSubmit" to forceSubmit))
    }

    // ==================== 学生消息 ====================

    private fun handleGetUnreadMessages(session: IHTTPSession): Response {
        // 消息轮询最短间隔 10 秒，前端设定 15 秒，允许一定容忍
        checkRateLimit(session, "msg_unread", 10_000L)?.let { return it }
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val messages = db.getUnreadMessages(userId)
        return jsonResponse(200, mapOf("messages" to messages))
    }

    private fun handleMarkMessageRead(session: IHTTPSession, body: String): Response {
        val (_, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val messageId = (params["messageId"] as? Number)?.toLong()
            ?: return jsonResponse(400, mapOf("error" to "缺少消息ID"))
        db.markMessageReadAndDelete(messageId)
        return jsonResponse(200, mapOf("message" to "已确认"))
    }

    private fun handleSendMessage(session: IHTTPSession, body: String): Response {
        // 必须携带有效 token，防止未鉴权用户随意推送消息
        val (_, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val content = params["content"]?.toString()?.trim()
            ?: return jsonResponse(400, mapOf("error" to "消息内容不能为空"))
        val senderName = params["senderName"]?.toString() ?: "老师"
        val userIds = (params["userIds"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }
            ?: return jsonResponse(400, mapOf("error" to "缺少目标用户"))
        if (userIds.isEmpty()) return jsonResponse(400, mapOf("error" to "缺少目标用户"))
        val count = db.sendMessageToUsers(userIds, content, senderName)
        return jsonResponse(200, mapOf("message" to "已发送 $count 条消息", "count" to count))
    }

    // ==================== 抢答比赛客户端接口 ====================

    /** 获取当前抢答比赛状态（客户端轮询） */
    private fun handleGetBuzzerState(session: IHTTPSession): Response {
        // 抢答状态轮询最短间隔 1 秒（前端 2 秒轮询，给予 1 倍容忍）
        checkRateLimit(session, "buzzer_state", 1_000L)?.let { return it }
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val assignedExamId = session.parameters["examId"]?.firstOrNull()?.toLongOrNull()
            ?: return jsonResponse(400, mapOf("error" to "缺少考试ID"))
        val state = db.getBuzzerState(assignedExamId)
            ?: return jsonResponse(404, mapOf("error" to "比赛不存在"))
        val qIndex = state["currentQuestionIndex"] as Int
        val userAnswer = db.getBuzzerUserAnswer(assignedExamId, qIndex, userId)

        // 获取当前题目信息
        val exam = db.getAssignedExamById(assignedExamId)
        val questionIds = (exam?.get("questionIds") as? String)
            ?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
        val currentQuestion: Map<String, Any>? = questionIds.getOrNull(qIndex)?.let { qId ->
            db.getQuestion(qId)?.let { q ->
                mapOf(
                    "id" to q.id,
                    "type" to q.type.code,
                    "content" to q.content,
                    "options" to q.options
                    // correctAnswer 不下发客户端，答题正确性由服务端 handleSubmitBuzzerAnswer 判断
                )
            }
        }

        return jsonResponse(200, mapOf(
            "state" to state,
            "currentQuestion" to (currentQuestion ?: emptyMap<String, Any>()),
            "totalQuestions" to questionIds.size,
            "hasAnswered" to (userAnswer != null),
            "myAnswer" to (userAnswer?.get("answer") ?: ""),
            "myIsCorrect" to (userAnswer?.get("isCorrect") ?: false)
        ))
    }

    /** 客户端提交抢答题目答案 */
    private fun handleSubmitBuzzerAnswer(session: IHTTPSession, body: String): Response {
        val (userId, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val params = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val assignedExamId = (params["examId"] as? Number)?.toLong()
            ?: return jsonResponse(400, mapOf("error" to "缺少考试ID"))
        val questionIndex = (params["questionIndex"] as? Number)?.toInt()
            ?: return jsonResponse(400, mapOf("error" to "缺少题目索引"))
        val answer = params["answer"]?.toString() ?: ""
        // 校验当前题目索引必须匹配
        val state = db.getBuzzerState(assignedExamId)
            ?: return jsonResponse(404, mapOf("error" to "比赛不存在"))
        if ((state["isEnded"] as Boolean)) return jsonResponse(400, mapOf("error" to "比赛已结束"))
        if (!(state["isStarted"] as Boolean)) return jsonResponse(400, mapOf("error" to "比赛尚未开始"))
        if ((state["currentQuestionIndex"] as Int) != questionIndex)
            return jsonResponse(400, mapOf("error" to "题目已切换，不可重复作答"))
        // 获取题目校验答案
        val exam = db.getAssignedExamById(assignedExamId)
            ?: return jsonResponse(404, mapOf("error" to "考试不存在"))
        val questionIds = (exam["questionIds"] as? String)?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
        val question = questionIds.getOrNull(questionIndex)?.let { db.getQuestion(it) }
            ?: return jsonResponse(404, mapOf("error" to "题目不存在"))
        val isCorrect = checkAnswer(question, answer)
        val saved = db.submitBuzzerAnswer(assignedExamId, questionIndex, userId, answer, isCorrect)
        return if (saved) jsonResponse(200, mapOf("message" to "答案已提交", "isCorrect" to isCorrect))
               else jsonResponse(400, mapOf("error" to "已经作答过该题"))
    }

    private val secureRandom = java.security.SecureRandom()

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        // Base64 URL-safe 编码（无填充），产生 32 字符的密码学安全 token
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    // ==================== 离线考试下载 ====================

    private fun handleGetOfflineExams(session: IHTTPSession): Response {
        val (_, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val list = db.getDownloadableExams()
        return jsonResponse(200, mapOf("exams" to list))
    }

    private fun handleViewOfflineExam(uri: String, session: IHTTPSession): Response {
        val (_, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val examId = uri.removePrefix("/api/offline-exams/").removeSuffix("/view").toLongOrNull()
            ?: return jsonResponse(400, mapOf("error" to "无效的考试ID"))
        val exam = db.getAssignedExamById(examId)
            ?: return jsonResponse(404, mapOf("error" to "考试不存在"))
        if (exam["allowDownload"] as? Boolean != true) return jsonResponse(403, mapOf("error" to "该考试不允许下载"))

        val questionIds = (exam["questionIds"] as? String) ?: ""
        val questions = if (questionIds.isNotBlank()) {
            val ids = questionIds.split(",").mapNotNull { it.trim().toLongOrNull() }
            db.getQuestionsByIds(ids)
        } else {
            db.getQuestionsByBank(exam["bankId"] as Long)
        }
        if (questions.isEmpty()) return jsonResponse(404, mapOf("error" to "该考试暂无题目"))

        val html = com.smartquiz.util.OfflineExamHelper.buildOfflineHtml(
            context = context,
            title = exam["bankName"] as String,
            questions = questions,
            timeMinutes = exam["timeMinutes"] as Int,
            typeScores = (exam["typeScores"] as? String) ?: ""
        )
        val bytes = html.toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
            ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun handleDownloadOfflineExam(uri: String, session: IHTTPSession): Response {
        val (_, errResp) = requireAuth(session)
        if (errResp != null) return errResp
        val examId = uri.removePrefix("/api/offline-exams/").removeSuffix("/download").toLongOrNull()
            ?: return jsonResponse(400, mapOf("error" to "无效的考试ID"))
        val exam = db.getAssignedExamById(examId)
            ?: return jsonResponse(404, mapOf("error" to "考试不存在"))
        if (exam["allowDownload"] as? Boolean != true) return jsonResponse(403, mapOf("error" to "该考试不允许下载"))

        val questionIds = (exam["questionIds"] as? String) ?: ""
        val questions = if (questionIds.isNotBlank()) {
            val ids = questionIds.split(",").mapNotNull { it.trim().toLongOrNull() }
            db.getQuestionsByIds(ids)
        } else {
            db.getQuestionsByBank(exam["bankId"] as Long)
        }
        if (questions.isEmpty()) return jsonResponse(404, mapOf("error" to "该考试暂无题目"))

        val html = com.smartquiz.util.OfflineExamHelper.buildOfflineHtml(
            context = context,
            title = exam["bankName"] as String,
            questions = questions,
            timeMinutes = exam["timeMinutes"] as Int,
            typeScores = (exam["typeScores"] as? String) ?: ""
        )
        val bytes = html.toByteArray(Charsets.UTF_8)
        val fileName = "${exam["bankName"]}\u2014\u62cd\u4e66\u9898\u5e93.html"
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
            ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Expose-Headers", "Content-Disposition")
        }
    }

    /**
     * 批量删除测试用户（用户名前缀匹配）。
     * POST /api/admin/cleanup-test-users  body: {"prefix":"stu"}
     * 无需鉴权，仅限局域网内管理员操作。
     */
    private fun handleCleanupTestUsers(body: String): Response {
        val json = gson.fromJson(body, Map::class.java) as? Map<String, Any> ?: emptyMap()
        val prefix = (json["prefix"] as? String)?.trim() ?: ""
        if (prefix.length < 2) {
            return jsonResponse(400, mapOf("error" to "prefix 至少需要 2 个字符，防止误删"))
        }
        val count = db.deleteUsersByUsernamePrefix(prefix)
        return jsonResponse(200, mapOf("message" to "已删除 $count 个测试用户", "deleted" to count))
    }
}
