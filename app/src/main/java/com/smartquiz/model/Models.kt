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
package com.smartquiz.model

/**
 * 题目类型枚举
 */
enum class QuestionType(val code: Int, val label: String) {
    SINGLE_CHOICE(1, "单选题"),
    MULTIPLE_CHOICE(2, "多选题"),
    TRUE_FALSE(3, "判断题"),
    FILL_BLANK(4, "填空题"),
    SHORT_ANSWER(5, "简答题");

    companion object {
        fun fromCode(code: Int): QuestionType =
            entries.firstOrNull { it.code == code } ?: SINGLE_CHOICE
    }
}

/**
 * 题目数据模型
 */
data class Question(
    val id: Long = 0,
    val bankId: Long = 0,
    val type: QuestionType = QuestionType.SINGLE_CHOICE,
    val content: String = "",
    val options: List<String> = emptyList(),     // 选项列表 (选择题用)
    val answer: String = "",                      // 正确答案
    val explanation: String = "",                  // 解析
    val difficulty: Int = 1,                       // 难度 1-5
    val knowledgePoint: String = "",               // 知识点
    val imageUrl: String = "",                     // 题目图片URL
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 题库数据模型
 */
data class QuestionBank(
    val id: Long = 0,
    val name: String = "",
    val subject: String = "",                     // 科目
    val description: String = "",
    val sourceText: String = "",                  // OCR识别的原文
    val questionCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 考试模式枚举
 */
enum class ExamMode(val code: String, val label: String) {
    ONLINE("online", "在线考试"),
    OFFLINE("offline", "离线考试"),
    BUZZER("buzzer", "抢答比赛");

    companion object {
        fun fromCode(code: String): ExamMode =
            entries.firstOrNull { it.code == code } ?: ONLINE
    }
}

/**
 * 用户数据模型
 */
data class User(
    val id: Long = 0,
    val username: String = "",
    val password: String = "",                    // SHA-256哈希
    val nickname: String = "",
    val realName: String = "",                    // 姓名
    val school: String = "",                      // 学校
    val className: String = "",                   // 班级
    val groupName: String = "",                   // 组别（抢答比赛用）
    val phone: String = "",                       // 手机号
    val email: String = "",                       // 邮箱
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 题目错误反馈
 */
data class QuestionFeedback(
    val id: Long = 0,
    val questionId: Long = 0,
    val bankId: Long = 0,
    val userId: Long = 0,
    val username: String = "",
    val content: String = "",
    val isResolved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 考试记录
 */
data class ExamRecord(
    val id: Long = 0,
    val userId: Long = 0,
    val bankId: Long = 0,
    val bankName: String = "",
    val totalQuestions: Int = 0,
    val correctCount: Int = 0,
    val score: Double = 0.0,                      // 分数
    val timeCostSeconds: Int = 0,                 // 用时(秒)
    val mode: String = "practice",                // practice/exam/wrong_redo
    val assignedExamId: Long = 0,                 // 关联的指定考试ID
    val createdAt: Long = System.currentTimeMillis(),
    val username: String = ""                     // 用户名（JOIN查询时填充）
) {
    val modeLabel: String get() = when (mode) {
        "exam"       -> "考试"
        "wrong_redo" -> "错题重练"
        else         -> "练习"
    }
}

/**
 * 答题记录(单题)
 */
data class AnswerRecord(
    val id: Long = 0,
    val examRecordId: Long = 0,
    val userId: Long = 0,
    val questionId: Long = 0,
    val userAnswer: String = "",
    val isCorrect: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 错题记录
 */
data class WrongQuestion(
    val id: Long = 0,
    val userId: Long = 0,
    val questionId: Long = 0,
    val bankId: Long = 0,
    val wrongCount: Int = 1,                      // 错误次数
    val lastWrongAt: Long = System.currentTimeMillis(),
    val isResolved: Boolean = false               // 是否已掌握
)
