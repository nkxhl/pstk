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
package com.smartquiz.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.smartquiz.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val gson = Gson()

    companion object {
        private const val DATABASE_NAME = "smart_quiz.db"
        private const val DATABASE_VERSION = 19

        // 表名
        private const val TABLE_BANKS = "question_banks"
        private const val TABLE_QUESTIONS = "questions"
        private const val TABLE_USERS = "users"
        private const val TABLE_EXAM_RECORDS = "exam_records"
        private const val TABLE_ANSWER_RECORDS = "answer_records"
        private const val TABLE_WRONG_QUESTIONS = "wrong_questions"
        private const val TABLE_FEEDBACK = "question_feedback"
        private const val TABLE_ASSIGNED_EXAM = "assigned_exams"
        private const val TABLE_STUDENT_MESSAGES = "student_messages"
        private const val TABLE_BUZZER_STATE = "buzzer_state"
        private const val TABLE_BUZZER_ANSWERS = "buzzer_answers"

        @Volatile
        private var instance: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
            }
        }

        fun hashPassword(password: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_BANKS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                subject TEXT DEFAULT '',
                description TEXT DEFAULT '',
                source_text TEXT DEFAULT '',
                question_count INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_QUESTIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bank_id INTEGER NOT NULL,
                type INTEGER NOT NULL DEFAULT 1,
                content TEXT NOT NULL,
                options TEXT DEFAULT '[]',
                answer TEXT NOT NULL,
                explanation TEXT DEFAULT '',
                difficulty INTEGER DEFAULT 1,
                knowledge_point TEXT DEFAULT '',
                image_url TEXT DEFAULT '',
                created_at INTEGER NOT NULL,
                FOREIGN KEY (bank_id) REFERENCES $TABLE_BANKS(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_USERS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                password TEXT NOT NULL,
                nickname TEXT DEFAULT '',
                real_name TEXT DEFAULT '',
                school TEXT DEFAULT '',
                class_name TEXT DEFAULT '',
                group_name TEXT DEFAULT '',
                phone TEXT DEFAULT '',
                email TEXT DEFAULT '',
                auth_token TEXT DEFAULT '',
                created_at INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_FEEDBACK (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                question_id INTEGER NOT NULL,
                bank_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                content TEXT NOT NULL,
                is_resolved INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (question_id) REFERENCES $TABLE_QUESTIONS(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_EXAM_RECORDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                bank_id INTEGER NOT NULL,
                bank_name TEXT DEFAULT '',
                total_questions INTEGER DEFAULT 0,
                correct_count INTEGER DEFAULT 0,
                score REAL DEFAULT 0,
                time_cost_seconds INTEGER DEFAULT 0,
                mode TEXT DEFAULT 'practice',
                assigned_exam_id INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (user_id) REFERENCES $TABLE_USERS(id),
                FOREIGN KEY (bank_id) REFERENCES $TABLE_BANKS(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_ANSWER_RECORDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exam_record_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                question_id INTEGER NOT NULL,
                user_answer TEXT DEFAULT '',
                is_correct INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (exam_record_id) REFERENCES $TABLE_EXAM_RECORDS(id),
                FOREIGN KEY (question_id) REFERENCES $TABLE_QUESTIONS(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_WRONG_QUESTIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                question_id INTEGER NOT NULL,
                bank_id INTEGER NOT NULL,
                wrong_count INTEGER DEFAULT 1,
                correct_count INTEGER DEFAULT 0,
                last_wrong_at INTEGER NOT NULL,
                is_resolved INTEGER DEFAULT 0,
                FOREIGN KEY (user_id) REFERENCES $TABLE_USERS(id),
                FOREIGN KEY (question_id) REFERENCES $TABLE_QUESTIONS(id),
                UNIQUE(user_id, question_id)
            )
        """)

        // 创建索引
        db.execSQL("CREATE INDEX idx_questions_bank ON $TABLE_QUESTIONS(bank_id)")
        db.execSQL("CREATE INDEX idx_exam_records_user ON $TABLE_EXAM_RECORDS(user_id)")
        db.execSQL("CREATE INDEX idx_wrong_questions_user ON $TABLE_WRONG_QUESTIONS(user_id)")
        db.execSQL("CREATE INDEX idx_answer_records_exam ON $TABLE_ANSWER_RECORDS(exam_record_id)")
        createAssignedExamTable(db)
        createStudentMessagesTable(db)
        createBuzzerTables(db)
        createPerformanceIndexes(db)
    }

    /** 高频查询复合索引，显著降低并发查询延迟 */
    private fun createPerformanceIndexes(db: SQLiteDatabase) {
        // 错题查询：getWrongQuestionIds 按 (user_id, bank_id) 过滤
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_wrong_user_bank ON $TABLE_WRONG_QUESTIONS(user_id, bank_id)")
        // 反馈聚合：getPendingFeedbackCountByBank / getBlockedQuestionIds 按 bank_id 分组
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_feedback_bank ON $TABLE_FEEDBACK(bank_id)")
        // 抢答排名：getBuzzerFastestCorrect 按 (exam_id, question_index, is_correct) 排序
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_buzzer_ans_lookup ON $TABLE_BUZZER_ANSWERS(assigned_exam_id, question_index, is_correct)")
        // 考试记录：assigned_exam_id 维度统计分析
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exam_records_assigned ON $TABLE_EXAM_RECORDS(assigned_exam_id)")
        // auth_token 索引：50并发时 getUserIdByToken 全表扫描改为索引查找，消除锁竞争
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_auth_token ON $TABLE_USERS(auth_token)")
    }

    private fun createStudentMessagesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_STUDENT_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                sender_name TEXT DEFAULT '老师',
                content TEXT NOT NULL,
                is_read INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (user_id) REFERENCES $TABLE_USERS(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_user ON $TABLE_STUDENT_MESSAGES(user_id, is_read)")
    }

    private fun createBuzzerTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_BUZZER_STATE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                assigned_exam_id INTEGER NOT NULL UNIQUE,
                current_question_index INTEGER DEFAULT 0,
                is_started INTEGER DEFAULT 0,
                is_ended INTEGER DEFAULT 0,
                started_at INTEGER DEFAULT 0,
                ended_at INTEGER DEFAULT 0,
                FOREIGN KEY (assigned_exam_id) REFERENCES $TABLE_ASSIGNED_EXAM(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_BUZZER_ANSWERS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                assigned_exam_id INTEGER NOT NULL,
                question_index INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                user_answer TEXT DEFAULT '',
                is_correct INTEGER DEFAULT 0,
                answered_at INTEGER NOT NULL,
                FOREIGN KEY (assigned_exam_id) REFERENCES $TABLE_ASSIGNED_EXAM(id) ON DELETE CASCADE,
                UNIQUE(assigned_exam_id, question_index, user_id)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_buzzer_answers_exam ON $TABLE_BUZZER_ANSWERS(assigned_exam_id, question_index)")
    }

    private fun createAssignedExamTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_ASSIGNED_EXAM (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bank_id INTEGER NOT NULL,
                bank_name TEXT DEFAULT '',
                limit_count INTEGER DEFAULT 0,
                time_minutes INTEGER DEFAULT 60,
                max_diff INTEGER DEFAULT 5,
                shuffle_opts INTEGER DEFAULT 1,
                shuffle_q INTEGER DEFAULT 1,
                question_types TEXT DEFAULT '',
                type_counts TEXT DEFAULT '',
                type_scores TEXT DEFAULT '',
                submit_before_end_minutes INTEGER DEFAULT 0,
                scheduled_start_time INTEGER DEFAULT 0,
                question_ids TEXT DEFAULT '',
                allow_download INTEGER DEFAULT 0,
                exam_mode TEXT DEFAULT 'online',
                auto_submit_at INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS assigned_exam_users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                assigned_exam_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                is_completed INTEGER DEFAULT 0,
                last_active_at INTEGER DEFAULT 0,
                answered_count INTEGER DEFAULT 0,
                force_submit INTEGER DEFAULT 0,
                UNIQUE(assigned_exam_id, user_id)
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 用户表增加扩展信息字段
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN real_name TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN school TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN class_name TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN phone TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN email TEXT DEFAULT ''")
            // 创建反馈表
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_FEEDBACK (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    question_id INTEGER NOT NULL,
                    bank_id INTEGER NOT NULL,
                    user_id INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    is_resolved INTEGER DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (question_id) REFERENCES $TABLE_QUESTIONS(id) ON DELETE CASCADE
                )
            """)
        }
        if (oldVersion < 3) {
            // 错题表增加答对次数字段
            db.execSQL("ALTER TABLE $TABLE_WRONG_QUESTIONS ADD COLUMN correct_count INTEGER DEFAULT 0")
        }
        if (oldVersion < 4) {
            createAssignedExamTable(db)
        }
        if (oldVersion < 5) {
            try { db.execSQL("ALTER TABLE $TABLE_ASSIGNED_EXAM ADD COLUMN question_types TEXT DEFAULT ''") } catch (_: Exception) {}
        }
        if (oldVersion < 6) {
            try { db.execSQL("ALTER TABLE $TABLE_ASSIGNED_EXAM ADD COLUMN type_counts TEXT DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_ASSIGNED_EXAM ADD COLUMN scheduled_start_time INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 7) {
            try { db.execSQL("ALTER TABLE assigned_exam_users ADD COLUMN last_active_at INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE assigned_exam_users ADD COLUMN answered_count INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 8) {
            try { db.execSQL("ALTER TABLE $TABLE_ASSIGNED_EXAM ADD COLUMN type_scores TEXT DEFAULT ''") } catch (_: Exception) {}
        }
        if (oldVersion < 9) {
            try { db.execSQL("ALTER TABLE $TABLE_EXAM_RECORDS ADD COLUMN assigned_exam_id INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 10) {
            try { db.execSQL("ALTER TABLE $TABLE_ASSIGNED_EXAM ADD COLUMN submit_before_end_minutes INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 11) {
            createStudentMessagesTable(db)
        }
        if (oldVersion < 12) {
            try { db.execSQL("ALTER TABLE $TABLE_ASSIGNED_EXAM ADD COLUMN question_ids TEXT DEFAULT ''") } catch (_: Exception) {}
        }
        if (oldVersion < 13) {
            try { db.execSQL("ALTER TABLE $TABLE_QUESTIONS ADD COLUMN image_url TEXT DEFAULT ''") } catch (_: Exception) {}
        }
        if (oldVersion < 14) {
            try { db.execSQL("ALTER TABLE $TABLE_ASSIGNED_EXAM ADD COLUMN allow_download INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 15) {
            try { db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN group_name TEXT DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_ASSIGNED_EXAM ADD COLUMN exam_mode TEXT DEFAULT 'online'") } catch (_: Exception) {}
            createBuzzerTables(db)
        }
        if (oldVersion < 16) {
            try { db.execSQL("ALTER TABLE $TABLE_ASSIGNED_EXAM ADD COLUMN auto_submit_at INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 17) {
            try { db.execSQL("ALTER TABLE assigned_exam_users ADD COLUMN force_submit INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 18) {
            // 持久化 token，APP 重启后客户端无需重新登录
            try { db.execSQL("ALTER TABLE $TABLE_USERS ADD COLUMN auth_token TEXT DEFAULT ''") } catch (_: Exception) {}
        }
        if (oldVersion < 19) {
            // auth_token 索引：消除并发登录时的全表扫描锁竞争
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_auth_token ON $TABLE_USERS(auth_token)") } catch (_: Exception) {}
        }
        // 无论从哪个版本升级，都确保性能索引存在
        createPerformanceIndexes(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 启用 WAL 模式：允许并发读写，显著提升多学生并发性能
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // 锁竞争等待 15 秒
        try { db.execSQL("PRAGMA busy_timeout = 15000") } catch (_: Exception) {}
        // WAL 下 synchronous=NORMAL 兼顾持久性与写入性能
        try { db.execSQL("PRAGMA synchronous = NORMAL") } catch (_: Exception) {}
        // WAL 自动检查点：累积 100 页时触发
        try { db.execSQL("PRAGMA wal_autocheckpoint = 100") } catch (_: Exception) {}
        // 压测后 WAL 文件可能很大，checkpoint 耗时较长，在后台线程异步清理，
        // 避免首次 getWritableDatabase() 卡死主线程/调用线程
        Thread {
            try { db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") } catch (_: Exception) {}
            try { db.execSQL("PRAGMA journal_mode = WAL") } catch (_: Exception) {}
        }.also { it.name = "db-wal-cleanup"; it.isDaemon = true; it.start() }
    }

    // ==================== 题库操作 ====================

    fun insertBank(bank: QuestionBank): Long {
        val values = ContentValues().apply {
            put("name", bank.name)
            put("subject", bank.subject)
            put("description", bank.description)
            put("source_text", bank.sourceText)
            put("question_count", bank.questionCount)
            put("created_at", bank.createdAt)
        }
        return writableDatabase.insert(TABLE_BANKS, null, values)
    }

    fun updateBankQuestionCount(bankId: Long, count: Int) {
        val values = ContentValues().apply { put("question_count", count) }
        writableDatabase.update(TABLE_BANKS, values, "id = ?", arrayOf(bankId.toString()))
    }

    fun getAllBanks(): List<QuestionBank> {
        val list = mutableListOf<QuestionBank>()
        readableDatabase.rawQuery("SELECT * FROM $TABLE_BANKS ORDER BY created_at DESC", null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToBank(cursor))
                }
            }
        return list
    }

    /**
     * 一次性批量获取所有题库的 pendingFeedback 数和 blockedCount，
     * 替代 handleGetBanks() 中的 N+1 查询（N 个题库各查 2 次 → 共 2 条聚合 SQL）。
     * 返回 Map<bankId, Pair<pendingFeedback, blockedCount>>
     */
    fun getBankMetaBatch(threshold: Int = 15): Map<Long, Pair<Int, Int>> {
        val result = mutableMapOf<Long, Pair<Int, Int>>()
        // 未解决的反馈数量（按 bank_id 分组）
        val pendingMap = mutableMapOf<Long, Int>()
        readableDatabase.rawQuery(
            "SELECT bank_id, COUNT(*) FROM $TABLE_FEEDBACK WHERE is_resolved=0 GROUP BY bank_id", null
        ).use { c -> while (c.moveToNext()) pendingMap[c.getLong(0)] = c.getInt(1) }
        // 被屏蔽题目数量（按 bank_id 分组，反馈次数 >= threshold）
        val blockedMap = mutableMapOf<Long, Int>()
        readableDatabase.rawQuery(
            """SELECT bank_id, COUNT(*) FROM (
                 SELECT bank_id, question_id FROM $TABLE_FEEDBACK
                 GROUP BY bank_id, question_id HAVING COUNT(*) >= $threshold
               ) GROUP BY bank_id""", null
        ).use { c -> while (c.moveToNext()) blockedMap[c.getLong(0)] = c.getInt(1) }
        // 合并：遍历所有出现过的 bank_id
        (pendingMap.keys + blockedMap.keys).distinct().forEach { bankId ->
            result[bankId] = Pair(pendingMap[bankId] ?: 0, blockedMap[bankId] ?: 0)
        }
        return result
    }

    fun getBank(id: Long): QuestionBank? {
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_BANKS WHERE id = ?", arrayOf(id.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursorToBank(cursor) else null
        }
    }

    fun deleteBank(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM $TABLE_ANSWER_RECORDS WHERE exam_record_id IN (SELECT id FROM $TABLE_EXAM_RECORDS WHERE bank_id = ?)",
                arrayOf(id)
            )
            db.delete(TABLE_EXAM_RECORDS, "bank_id = ?", arrayOf(id.toString()))
            db.delete(TABLE_WRONG_QUESTIONS, "bank_id = ?", arrayOf(id.toString()))
            db.delete(TABLE_FEEDBACK, "bank_id = ?", arrayOf(id.toString()))
            db.delete(TABLE_QUESTIONS, "bank_id = ?", arrayOf(id.toString()))
            db.delete(TABLE_BANKS, "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun cursorToBank(cursor: Cursor): QuestionBank = QuestionBank(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
        subject = cursor.getString(cursor.getColumnIndexOrThrow("subject")),
        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
        sourceText = cursor.getString(cursor.getColumnIndexOrThrow("source_text")),
        questionCount = cursor.getInt(cursor.getColumnIndexOrThrow("question_count")),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
    )

    // ==================== 题目操作 ====================

    fun insertQuestion(question: Question): Long {
        val values = ContentValues().apply {
            put("bank_id", question.bankId)
            put("type", question.type.code)
            put("content", question.content)
            put("options", gson.toJson(question.options))
            put("answer", question.answer)
            put("explanation", question.explanation)
            put("difficulty", question.difficulty)
            put("knowledge_point", question.knowledgePoint)
            put("image_url", question.imageUrl)
            put("created_at", question.createdAt)
        }
        return writableDatabase.insert(TABLE_QUESTIONS, null, values)
    }

    fun insertQuestions(questions: List<Question>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            questions.forEach { q ->
                val values = ContentValues().apply {
                    put("bank_id", q.bankId)
                    put("type", q.type.code)
                    put("content", q.content)
                    put("options", gson.toJson(q.options))
                    put("answer", q.answer)
                    put("explanation", q.explanation)
                    put("difficulty", q.difficulty)
                    put("knowledge_point", q.knowledgePoint)
                    put("image_url", q.imageUrl)
                    put("created_at", q.createdAt)
                }
                db.insert(TABLE_QUESTIONS, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 直接用 SQL COUNT(*) 获取题库题目数量，避免全量加载题目列表仅为取 size */
    fun getQuestionCount(bankId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_QUESTIONS WHERE bank_id = ?",
            arrayOf(bankId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 获取指定题库中各题型的题目数量，返回 typeCode -> count 映射 */
    fun getQuestionCountByType(bankId: Long): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        readableDatabase.rawQuery(
            "SELECT type, COUNT(*) as cnt FROM $TABLE_QUESTIONS WHERE bank_id = ? GROUP BY type",
            arrayOf(bankId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val typeCode = cursor.getInt(0)
                val count = cursor.getInt(1)
                result[typeCode] = count
            }
        }
        return result
    }

    fun getQuestionsByBank(bankId: Long): List<Question> {
        val list = mutableListOf<Question>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_QUESTIONS WHERE bank_id = ? ORDER BY id",
            arrayOf(bankId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToQuestion(cursor))
            }
        }
        return list
    }

    fun getQuestion(id: Long): Question? {
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_QUESTIONS WHERE id = ?", arrayOf(id.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursorToQuestion(cursor) else null
        }
    }

    fun getQuestionsByIds(ids: List<Long>): List<Question> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val map = mutableMapOf<Long, Question>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_QUESTIONS WHERE id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val q = cursorToQuestion(cursor)
                map[q.id] = q
            }
        }
        // 严格按传入 ID 顺序返回，保证教师端与学生端题目顺序完全一致
        return ids.mapNotNull { map[it] }
    }

    private fun cursorToQuestion(cursor: Cursor): Question {
        val optionsJson = cursor.getString(cursor.getColumnIndexOrThrow("options"))
        val optionsList: List<String> = try {
            gson.fromJson(optionsJson, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
        val imageUrlIdx = cursor.getColumnIndex("image_url")
        return Question(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            bankId = cursor.getLong(cursor.getColumnIndexOrThrow("bank_id")),
            type = QuestionType.fromCode(cursor.getInt(cursor.getColumnIndexOrThrow("type"))),
            content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
            options = optionsList,
            answer = cursor.getString(cursor.getColumnIndexOrThrow("answer")),
            explanation = cursor.getString(cursor.getColumnIndexOrThrow("explanation")),
            difficulty = cursor.getInt(cursor.getColumnIndexOrThrow("difficulty")),
            knowledgePoint = cursor.getString(cursor.getColumnIndexOrThrow("knowledge_point")),
            imageUrl = if (imageUrlIdx >= 0) (cursor.getString(imageUrlIdx) ?: "") else "",
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
        )
    }

    fun updateQuestion(question: Question) {
        val values = ContentValues().apply {
            put("type", question.type.code)
            put("content", question.content)
            put("options", gson.toJson(question.options))
            put("answer", question.answer)
            put("explanation", question.explanation)
            put("difficulty", question.difficulty)
            put("knowledge_point", question.knowledgePoint)
            put("image_url", question.imageUrl)
        }
        writableDatabase.update(TABLE_QUESTIONS, values, "id = ?", arrayOf(question.id.toString()))
    }

    fun deleteQuestion(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 先删除关联的答题记录和错题记录，避免外键约束冲突
            db.delete(TABLE_ANSWER_RECORDS, "question_id = ?", arrayOf(id.toString()))
            db.delete(TABLE_WRONG_QUESTIONS, "question_id = ?", arrayOf(id.toString()))
            db.delete(TABLE_QUESTIONS, "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ==================== 用户操作 ====================

    fun registerUser(username: String, password: String, nickname: String, groupName: String = ""): Long {
        val values = ContentValues().apply {
            put("username", username)
            put("password", hashPassword(password))
            put("nickname", nickname.ifEmpty { username })
            put("group_name", groupName)
            put("created_at", System.currentTimeMillis())
        }
        // 直接 INSERT，依赖 UNIQUE 约束防重复，避免先 SELECT 再 INSERT 的 TOCTOU 竞争
        return try {
            writableDatabase.insertOrThrow(TABLE_USERS, null, values)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            -1L // 用户名已存在
        }
    }

    /**
     * 注册并在同一事务内写入 token，减少 SQLite 写锁占用次数（从 2 次合并为 1 次）。
     * 高并发注册时减少锁竞争窗口。
     * 返回 (userId, token)，userId < 0 表示用户名已存在。
     */
    fun registerUserWithToken(username: String, password: String, nickname: String, groupName: String = "", token: String): Pair<Long, String> {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val values = ContentValues().apply {
                put("username", username)
                put("password", hashPassword(password))
                put("nickname", nickname.ifEmpty { username })
                put("group_name", groupName)
                put("auth_token", token)
                put("created_at", System.currentTimeMillis())
            }
            val userId = db.insertOrThrow(TABLE_USERS, null, values)
            db.setTransactionSuccessful()
            Pair(userId, token)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            Pair(-1L, "")
        } finally {
            db.endTransaction()
        }
    }

    fun loginUser(username: String, password: String): User? {
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_USERS WHERE username = ? AND password = ?",
            arrayOf(username, hashPassword(password))
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursorToUser(cursor) else null
        }
    }

    fun getUser(id: Long): User? {
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_USERS WHERE id = ?", arrayOf(id.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursorToUser(cursor) else null
        }
    }

    /** 持久化 token，APP 重启后可凭此恢复会话，无需重新登录 */
    fun saveUserToken(userId: Long, token: String) {
        val values = ContentValues().apply { put("auth_token", token) }
        writableDatabase.update(TABLE_USERS, values, "id=?", arrayOf(userId.toString()))
    }

    /** 根据 token 查找对应的 userId（APP 重启后会话重建使用） */
    fun getUserIdByToken(token: String): Long? {
        if (token.isBlank()) return null
        readableDatabase.rawQuery(
            "SELECT id FROM $TABLE_USERS WHERE auth_token = ?", arrayOf(token)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    /** 踢出用户时清除其持久化 token，防止旧 token 复活 */
    fun clearUserToken(userId: Long) {
        val values = ContentValues().apply { put("auth_token", "") }
        writableDatabase.update(TABLE_USERS, values, "id=?", arrayOf(userId.toString()))
    }

    private fun cursorToUser(cursor: Cursor): User = User(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        username = cursor.getString(cursor.getColumnIndexOrThrow("username")),
        password = cursor.getString(cursor.getColumnIndexOrThrow("password")),
        nickname = cursor.getString(cursor.getColumnIndexOrThrow("nickname")),
        realName = cursor.getString(cursor.getColumnIndexOrThrow("real_name")) ?: "",
        school = cursor.getString(cursor.getColumnIndexOrThrow("school")) ?: "",
        className = cursor.getString(cursor.getColumnIndexOrThrow("class_name")) ?: "",
        groupName = cursor.getColumnIndex("group_name").let { if (it >= 0) cursor.getString(it) ?: "" else "" },
        phone = cursor.getString(cursor.getColumnIndexOrThrow("phone")) ?: "",
        email = cursor.getString(cursor.getColumnIndexOrThrow("email")) ?: "",
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
    )

    fun updateUserProfile(userId: Long, realName: String, school: String, className: String, phone: String, email: String, groupName: String = "") {
        val values = ContentValues().apply {
            put("real_name", realName)
            put("school", school)
            put("class_name", className)
            put("phone", phone)
            put("email", email)
            put("group_name", groupName)
            // 同步更新 nickname 为 realName（若非空）
            if (realName.isNotBlank()) put("nickname", realName)
        }
        writableDatabase.update(TABLE_USERS, values, "id = ?", arrayOf(userId.toString()))
    }

    fun updateUserPassword(userId: Long, oldPassword: String, newPassword: String): Boolean {
        val hashed = hashPassword(oldPassword)
        var ok = false
        readableDatabase.rawQuery(
            "SELECT id FROM $TABLE_USERS WHERE id = ? AND password = ?",
            arrayOf(userId.toString(), hashed)
        ).use { cursor ->
            ok = cursor.moveToFirst()
        }
        if (!ok) return false
        val values = ContentValues().apply { put("password", hashPassword(newPassword)) }
        writableDatabase.update(TABLE_USERS, values, "id = ?", arrayOf(userId.toString()))
        return true
    }

    // ==================== 题目反馈 ====================

    fun addFeedback(questionId: Long, bankId: Long, userId: Long, content: String): Long {
        val values = ContentValues().apply {
            put("question_id", questionId)
            put("bank_id", bankId)
            put("user_id", userId)
            put("content", content)
            put("is_resolved", 0)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert(TABLE_FEEDBACK, null, values)
    }

    fun getFeedbackByBank(bankId: Long): List<QuestionFeedback> {
        val list = mutableListOf<QuestionFeedback>()
        readableDatabase.rawQuery(
            """SELECT f.*, COALESCE(NULLIF(u.real_name,''), NULLIF(u.nickname,''), u.username, '匿名') AS uname
               FROM $TABLE_FEEDBACK f
               LEFT JOIN $TABLE_USERS u ON f.user_id = u.id
               WHERE f.bank_id = ?
               ORDER BY f.is_resolved ASC, f.created_at DESC""",
            arrayOf(bankId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(QuestionFeedback(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    questionId = cursor.getLong(cursor.getColumnIndexOrThrow("question_id")),
                    bankId = cursor.getLong(cursor.getColumnIndexOrThrow("bank_id")),
                    userId = cursor.getLong(cursor.getColumnIndexOrThrow("user_id")),
                    username = cursor.getString(cursor.getColumnIndexOrThrow("uname")) ?: "匿名",
                    content = cursor.getString(cursor.getColumnIndexOrThrow("content")) ?: "",
                    isResolved = cursor.getInt(cursor.getColumnIndexOrThrow("is_resolved")) != 0,
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                ))
            }
        }
        return list
    }

    fun getPendingFeedbackCountByBank(bankId: Long): Int {
        var count = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_FEEDBACK WHERE bank_id = ? AND is_resolved = 0",
            arrayOf(bankId.toString())
        ).use { cursor ->
            cursor.moveToFirst()
            count = cursor.getInt(0)
        }
        return count
    }

    fun resolveFeedback(feedbackId: Long) {
        val values = ContentValues().apply { put("is_resolved", 1) }
        writableDatabase.update(TABLE_FEEDBACK, values, "id = ?", arrayOf(feedbackId.toString()))
    }

    /** 返回该题库中反馈次数 >= threshold 的题目ID集合（用于屏蔽） */
    fun getBlockedQuestionIds(bankId: Long, threshold: Int = 15): Set<Long> {
        val set = mutableSetOf<Long>()
        readableDatabase.rawQuery(
            """SELECT question_id FROM $TABLE_FEEDBACK
               WHERE bank_id = ?
               GROUP BY question_id
               HAVING COUNT(*) >= ?""",
            arrayOf(bankId.toString(), threshold.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) set.add(cursor.getLong(0))
        }
        return set
    }

    /** 返回题库中每道题的反馈次数 Map<questionId, count>（仅返回有反馈的题） */
    fun getFeedbackCountPerQuestion(bankId: Long): Map<Long, Int> {
        val map = mutableMapOf<Long, Int>()
        readableDatabase.rawQuery(
            """SELECT question_id, COUNT(*) AS cnt FROM $TABLE_FEEDBACK
               WHERE bank_id = ? GROUP BY question_id""",
            arrayOf(bankId.toString())
        ).use { cursor ->
            while (cursor.moveToNext())
                map[cursor.getLong(0)] = cursor.getInt(1)
        }
        return map
    }

    // ==================== 考试记录 ====================

    fun insertExamRecord(record: ExamRecord): Long {
        val values = ContentValues().apply {
            put("user_id", record.userId)
            put("bank_id", record.bankId)
            put("bank_name", record.bankName)
            put("total_questions", record.totalQuestions)
            put("correct_count", record.correctCount)
            put("score", record.score)
            put("time_cost_seconds", record.timeCostSeconds)
            put("mode", record.mode)
            put("assigned_exam_id", record.assignedExamId)
            put("created_at", record.createdAt)
        }
        return writableDatabase.insert(TABLE_EXAM_RECORDS, null, values)
    }

    fun getExamRecordsByUser(userId: Long): List<ExamRecord> {
        val list = mutableListOf<ExamRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_EXAM_RECORDS WHERE user_id = ? ORDER BY created_at DESC",
            arrayOf(userId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToExamRecord(cursor))
            }
        }
        return list
    }

    fun getUserStats(userId: Long): Map<String, Any> {
        val totalExams: Int
        val avgScore: Double
        val totalQuestions: Int

        readableDatabase.rawQuery(
            "SELECT COUNT(*) as cnt, AVG(score) as avg_s, SUM(total_questions) as total_q FROM $TABLE_EXAM_RECORDS WHERE user_id = ?",
            arrayOf(userId.toString())
        ).use { cursor ->
            cursor.moveToFirst()
            totalExams = cursor.getInt(0)
            avgScore = if (totalExams > 0) cursor.getDouble(1) else 0.0
            totalQuestions = cursor.getInt(2)
        }

        val wrongCount: Int
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_WRONG_QUESTIONS WHERE user_id = ? AND is_resolved = 0",
            arrayOf(userId.toString())
        ).use { cursor ->
            cursor.moveToFirst()
            wrongCount = cursor.getInt(0)
        }

        return mapOf(
            "totalExams" to totalExams,
            "avgScore" to String.format("%.1f", avgScore).toDouble(),
            "totalQuestions" to totalQuestions,
            "wrongCount" to wrongCount
        )
    }

    fun getAllExamRecords(): List<ExamRecord> {
        val list = mutableListOf<ExamRecord>()
        readableDatabase.rawQuery(
            """SELECT er.*, COALESCE(u.nickname, u.username, '') AS username
               FROM $TABLE_EXAM_RECORDS er
               LEFT JOIN $TABLE_USERS u ON er.user_id = u.id
               ORDER BY er.created_at DESC""", null
        ).use { cursor ->
            while (cursor.moveToNext()) list.add(cursorToExamRecord(cursor))
        }
        return list
    }

    /** APP端：清空指定题库的全部考试记录（含关联答题记录） */
    fun deleteExamRecordsByBank(bankId: Long) {
        writableDatabase.execSQL(
            "DELETE FROM $TABLE_ANSWER_RECORDS WHERE exam_record_id IN (SELECT id FROM $TABLE_EXAM_RECORDS WHERE bank_id = ?)",
            arrayOf(bankId)
        )
        writableDatabase.delete(TABLE_EXAM_RECORDS, "bank_id = ?", arrayOf(bankId.toString()))
    }

    /** 客户端：清空指定用户自己的全部考试记录（含关联答题记录） */
    fun deleteExamRecordsByUser(userId: Long) {
        writableDatabase.execSQL(
            "DELETE FROM $TABLE_ANSWER_RECORDS WHERE exam_record_id IN (SELECT id FROM $TABLE_EXAM_RECORDS WHERE user_id = ?)",
            arrayOf(userId)
        )
        writableDatabase.delete(TABLE_EXAM_RECORDS, "user_id = ?", arrayOf(userId.toString()))
    }

    fun getTotalWrongCount(): Int {
        var count = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_WRONG_QUESTIONS WHERE is_resolved = 0", null
        ).use { cursor ->
            cursor.moveToFirst()
            count = cursor.getInt(0)
        }
        return count
    }

    private fun cursorToExamRecord(cursor: Cursor): ExamRecord = ExamRecord(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        userId = cursor.getLong(cursor.getColumnIndexOrThrow("user_id")),
        bankId = cursor.getLong(cursor.getColumnIndexOrThrow("bank_id")),
        bankName = cursor.getString(cursor.getColumnIndexOrThrow("bank_name")),
        totalQuestions = cursor.getInt(cursor.getColumnIndexOrThrow("total_questions")),
        correctCount = cursor.getInt(cursor.getColumnIndexOrThrow("correct_count")),
        score = cursor.getDouble(cursor.getColumnIndexOrThrow("score")),
        timeCostSeconds = cursor.getInt(cursor.getColumnIndexOrThrow("time_cost_seconds")),
        mode = cursor.getString(cursor.getColumnIndexOrThrow("mode")),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
        username = cursor.getColumnIndex("username").let { if (it >= 0) cursor.getString(it) ?: "" else "" }
    )

    // ==================== 答题记录 ====================

    fun insertAnswerRecords(records: List<AnswerRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            records.forEach { r ->
                val values = ContentValues().apply {
                    put("exam_record_id", r.examRecordId)
                    put("user_id", r.userId)
                    put("question_id", r.questionId)
                    put("user_answer", r.userAnswer)
                    put("is_correct", if (r.isCorrect) 1 else 0)
                    put("created_at", r.createdAt)
                }
                db.insert(TABLE_ANSWER_RECORDS, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 查询指定考试记录的所有答题详情（含题目内容） */
    fun getAnswersByExamRecord(examRecordId: Long): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT ar.question_id, q.content, ar.user_answer, ar.is_correct
               FROM $TABLE_ANSWER_RECORDS ar
               LEFT JOIN $TABLE_QUESTIONS q ON ar.question_id = q.id
               WHERE ar.exam_record_id = ?
               ORDER BY ar.id""",
            arrayOf(examRecordId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(mapOf(
                    "questionId" to cursor.getLong(0),
                    "content"    to (cursor.getString(1) ?: ""),
                    "userAnswer" to (cursor.getString(2) ?: ""),
                    "isCorrect"  to (cursor.getInt(3) == 1)
                ))
            }
        }
        return results
    }

    // ==================== 错题操作 ====================

    /**
     * 事务性提交考试结果：将考试记录、答题记录、错题更新全部在一个事务内完成，
     * 保证并发时不会出现部分写入。
     * @param record 考试记录（examRecordId 为 0，函数内自动填充）
     * @param answers 答题记录列表
     * @param wrongUpdates 需要更新错题的操作列表（Triple: userId, questionId, bankId）
     * @param resolveUpdates 需要标记为已掌握的题目（Pair: userId, questionId）
     * @param incrementUpdates 需要累加答对次数的题目（Pair: userId, questionId, threshold）
     * @return 插入的 examRecord id
     */
    /**
     * 幂等检查：判断该用户是否已提交过某场指定考试的成绩。
     * 用于服务端防止网络重试或前端并发请求导致重复写入。
     */
    fun hasExamRecord(userId: Long, assignedExamId: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_EXAM_RECORDS WHERE user_id=? AND assigned_exam_id=?",
            arrayOf(userId.toString(), assignedExamId.toString())
        ).use { c -> return c.moveToFirst() && c.getInt(0) > 0 }
    }

    /**
     * 获取该用户在某场指定考试中的最近一条成绩摘要（用于幂等重复提交时返回已有结果）。
     */
    fun getExamRecordResult(userId: Long, assignedExamId: Long): Map<String, Any> {
        readableDatabase.rawQuery(
            """SELECT score, correct_count, total_questions, time_cost_seconds, created_at
               FROM $TABLE_EXAM_RECORDS WHERE user_id=? AND assigned_exam_id=?
               ORDER BY id DESC LIMIT 1""",
            arrayOf(userId.toString(), assignedExamId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return emptyMap()
            return mapOf(
                "score" to c.getDouble(0),
                "correctCount" to c.getInt(1),
                "totalQuestions" to c.getInt(2),
                "timeCostSeconds" to c.getInt(3),
                "createdAt" to c.getLong(4)
            )
        }
    }

     fun insertExamResult(
        record: ExamRecord,
        answers: List<AnswerRecord>,
        wrongUpdates: List<Triple<Long, Long, Long>>,
        resolveUpdates: List<Pair<Long, Long>>,
        incrementUpdates: List<Triple<Long, Long, Int>>
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 1. 插入考试记录
            val recordValues = ContentValues().apply {
                put("user_id", record.userId)
                put("bank_id", record.bankId)
                put("bank_name", record.bankName)
                put("total_questions", record.totalQuestions)
                put("correct_count", record.correctCount)
                put("score", record.score)
                put("time_cost_seconds", record.timeCostSeconds)
                put("mode", record.mode)
                put("assigned_exam_id", record.assignedExamId)
                put("created_at", record.createdAt)
            }
            val recordId = db.insert(TABLE_EXAM_RECORDS, null, recordValues)

            // 2. 插入答题记录
            answers.forEach { r ->
                val values = ContentValues().apply {
                    put("exam_record_id", recordId)
                    put("user_id", r.userId)
                    put("question_id", r.questionId)
                    put("user_answer", r.userAnswer)
                    put("is_correct", if (r.isCorrect) 1 else 0)
                    put("created_at", r.createdAt)
                }
                db.insert(TABLE_ANSWER_RECORDS, null, values)
            }

            // 3. 更新错题（原子 INSERT OR IGNORE + UPDATE，已在同一事务中）
            val now = System.currentTimeMillis()
            wrongUpdates.forEach { (userId, questionId, bankId) ->
                db.execSQL(
                    """INSERT OR IGNORE INTO $TABLE_WRONG_QUESTIONS
                       (user_id, question_id, bank_id, wrong_count, correct_count, last_wrong_at, is_resolved)
                       VALUES (?, ?, ?, 0, 0, ?, 0)""",
                    arrayOf(userId, questionId, bankId, now)
                )
                db.execSQL(
                    """UPDATE $TABLE_WRONG_QUESTIONS
                       SET wrong_count = wrong_count + 1,
                           correct_count = 0,
                           last_wrong_at = ?,
                           is_resolved = 0
                       WHERE user_id = ? AND question_id = ?""",
                    arrayOf(now, userId, questionId)
                )
            }

            // 4. 标记已掌握（错题重做模式）
            resolveUpdates.forEach { (userId, questionId) ->
                val values = ContentValues().apply { put("is_resolved", 1) }
                db.update(TABLE_WRONG_QUESTIONS, values,
                    "user_id = ? AND question_id = ?",
                    arrayOf(userId.toString(), questionId.toString()))
            }

            // 5. 累加答对次数并在达到阈值时自动标记已掌握（wrong_extend 模式）
            // 单条 UPDATE 原子完成：先累加 correct_count，然后用 CASE WHEN 一步判断是否达到阈值
            incrementUpdates.forEach { (userId, questionId, threshold) ->
                db.execSQL(
                    """UPDATE $TABLE_WRONG_QUESTIONS
                       SET correct_count = correct_count + 1,
                           is_resolved = CASE WHEN correct_count + 1 >= ? THEN 1 ELSE is_resolved END
                       WHERE user_id = ? AND question_id = ? AND is_resolved = 0""",
                    arrayOf(threshold, userId, questionId)
                )
            }

            db.setTransactionSuccessful()
            return recordId
        } finally {
            db.endTransaction()
        }
    }

    fun addOrUpdateWrongQuestion(userId: Long, questionId: Long, bankId: Long) {
        val db = writableDatabase
        // 原子操作：先尝试插入（冲突则忽略），再更新计数，避免并发 TOCTOU 问题
        db.execSQL(
            """INSERT OR IGNORE INTO $TABLE_WRONG_QUESTIONS
               (user_id, question_id, bank_id, wrong_count, correct_count, last_wrong_at, is_resolved)
               VALUES (?, ?, ?, 0, 0, ?, 0)""",
            arrayOf(userId, questionId, bankId, System.currentTimeMillis())
        )
        db.execSQL(
            """UPDATE $TABLE_WRONG_QUESTIONS
               SET wrong_count = wrong_count + 1,
                   correct_count = 0,
                   last_wrong_at = ?,
                   is_resolved = 0
               WHERE user_id = ? AND question_id = ?""",
            arrayOf(System.currentTimeMillis(), userId, questionId)
        )
    }

    fun resolveWrongQuestion(userId: Long, questionId: Long) {
        val values = ContentValues().apply { put("is_resolved", 1) }
        writableDatabase.update(
            TABLE_WRONG_QUESTIONS, values,
            "user_id = ? AND question_id = ?",
            arrayOf(userId.toString(), questionId.toString())
        )
    }

    /**
     * 错题模式答对时累加 correct_count，达到 threshold 次后自动标记为已掌握。
     * 同时重置 wrong_count（每次答错会重新累积）。
     * @return true 表示已达标自动 resolve
     */
    fun incrementCorrectCount(userId: Long, questionId: Long, threshold: Int = 2): Boolean {
        val db = writableDatabase
        // 原子自增，避免并发 TOCTOU
        db.execSQL(
            """UPDATE $TABLE_WRONG_QUESTIONS
               SET correct_count = correct_count + 1
               WHERE user_id = ? AND question_id = ? AND is_resolved = 0""",
            arrayOf(userId, questionId)
        )
        // 读取更新后的值，判断是否达到阈值
        val cursor = db.rawQuery(
            "SELECT id, correct_count FROM $TABLE_WRONG_QUESTIONS WHERE user_id = ? AND question_id = ? AND is_resolved = 0",
            arrayOf(userId.toString(), questionId.toString())
        )
        return try {
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val newCount = cursor.getInt(1)
                if (newCount >= threshold) {
                    val values = ContentValues().apply { put("is_resolved", 1) }
                    db.update(TABLE_WRONG_QUESTIONS, values, "id = ?", arrayOf(id.toString()))
                    true
                } else false
            } else false
        } finally {
            cursor.close()
        }
    }

    fun resetWrongQuestions(userId: Long, bankId: Long? = null) {
        val values = ContentValues().apply { put("is_resolved", 1) }
        val where = if (bankId != null) "user_id = ? AND bank_id = ? AND is_resolved = 0"
                    else "user_id = ? AND is_resolved = 0"
        val args = if (bankId != null) arrayOf(userId.toString(), bankId.toString())
                   else arrayOf(userId.toString())
        writableDatabase.update(TABLE_WRONG_QUESTIONS, values, where, args)
    }

    fun getWrongQuestions(userId: Long, bankId: Long? = null): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        val sql = StringBuilder("""
            SELECT w.*, q.content, q.type, q.options, q.answer, q.explanation, q.knowledge_point, q.image_url, b.name as bank_name
            FROM $TABLE_WRONG_QUESTIONS w
            JOIN $TABLE_QUESTIONS q ON w.question_id = q.id
            JOIN $TABLE_BANKS b ON w.bank_id = b.id
            WHERE w.user_id = ? AND w.is_resolved = 0
        """)
        val args = mutableListOf(userId.toString())
        if (bankId != null) {
            sql.append(" AND w.bank_id = ?")
            args.add(bankId.toString())
        }
        sql.append(" ORDER BY w.wrong_count DESC, w.last_wrong_at DESC")

        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val optionsJson = cursor.getString(cursor.getColumnIndexOrThrow("options"))
                val optionsList: List<String> = try {
                    gson.fromJson(optionsJson, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) { emptyList() }

                results.add(mapOf(
                    "id" to cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    "questionId" to cursor.getLong(cursor.getColumnIndexOrThrow("question_id")),
                    "bankId" to cursor.getLong(cursor.getColumnIndexOrThrow("bank_id")),
                    "bankName" to cursor.getString(cursor.getColumnIndexOrThrow("bank_name")),
                    "wrongCount" to cursor.getInt(cursor.getColumnIndexOrThrow("wrong_count")),
                    "content" to cursor.getString(cursor.getColumnIndexOrThrow("content")),
                    "type" to cursor.getInt(cursor.getColumnIndexOrThrow("type")),
                    "options" to optionsList,
                    "answer" to cursor.getString(cursor.getColumnIndexOrThrow("answer")),
                    "explanation" to cursor.getString(cursor.getColumnIndexOrThrow("explanation")),
                    "knowledgePoint" to cursor.getString(cursor.getColumnIndexOrThrow("knowledge_point")),
                    "imageUrl" to (cursor.getString(cursor.getColumnIndexOrThrow("image_url")) ?: "")
                ))
            }
        }
        return results
    }

    fun getWrongQuestionIds(userId: Long, bankId: Long? = null): List<Long> {
        val sql = StringBuilder("SELECT question_id FROM $TABLE_WRONG_QUESTIONS WHERE user_id = ? AND is_resolved = 0")
        val args = mutableListOf(userId.toString())
        if (bankId != null) {
            sql.append(" AND bank_id = ?")
            args.add(bankId.toString())
        }
        val ids = mutableListOf<Long>()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0))
            }
        }
        return ids
    }

    /** 根据知识点列表查询同题库中的题目（排除指定ids，用于错题模式扩展同知识点练习） */
    fun getQuestionsByKnowledgePoints(bankId: Long, knowledgePoints: List<String>, excludeIds: List<Long>): List<Question> {
        if (knowledgePoints.isEmpty()) return emptyList()
        val placeholders = knowledgePoints.joinToString(",") { "?" }
        val excludePlaceholders = if (excludeIds.isNotEmpty()) excludeIds.joinToString(",") { "?" } else "0"
        val sql = """
            SELECT * FROM $TABLE_QUESTIONS
            WHERE bank_id = ? AND knowledge_point IN ($placeholders)
            AND id NOT IN ($excludePlaceholders)
        """.trimIndent()
        val args = mutableListOf(bankId.toString()) + knowledgePoints + excludeIds.map { it.toString() }
        val list = mutableListOf<Question>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) list.add(cursorToQuestion(cursor))
        }
        return list
    }

    /**
     * 按题库汇总全局错题排名：每个题库的错题数（倒序）
     * 返回：bankId, bankName, wrongCount
     */
    fun getWrongRankingByBank(): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT w.bank_id,
                      COALESCE(b.name, w.bank_id || '') AS bank_name,
                      COUNT(DISTINCT w.question_id)     AS wrong_count
               FROM $TABLE_WRONG_QUESTIONS w
               LEFT JOIN $TABLE_BANKS b ON w.bank_id = b.id
               WHERE w.is_resolved = 0
               GROUP BY w.bank_id
               ORDER BY wrong_count DESC""", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(mapOf(
                    "bankId"     to cursor.getLong(cursor.getColumnIndexOrThrow("bank_id")),
                    "bankName"   to (cursor.getString(cursor.getColumnIndexOrThrow("bank_name")) ?: ""),
                    "wrongCount" to cursor.getInt(cursor.getColumnIndexOrThrow("wrong_count"))
                ))
            }
        }
        return results
    }

    /**
     * 指定题库下，按错误次数倒序列出错题及做错的人名
     * 返回每题：questionId, content, totalWrongCount, wrongUsers(List<String>)
     * 使用单条 JOIN + GROUP_CONCAT SQL 替代原来的 N+1 查询。
     */
    fun getWrongQuestionRankingForBank(bankId: Long): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT w.question_id,
                      q.content,
                      SUM(w.wrong_count) AS total_wrong,
                      GROUP_CONCAT(COALESCE(NULLIF(u.nickname,''), u.username, '匿名'), ', ') AS wrong_users
               FROM $TABLE_WRONG_QUESTIONS w
               JOIN $TABLE_QUESTIONS q ON w.question_id = q.id
               LEFT JOIN $TABLE_USERS u ON w.user_id = u.id
               WHERE w.bank_id = ? AND w.is_resolved = 0
               GROUP BY w.question_id
               ORDER BY total_wrong DESC""",
            arrayOf(bankId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val usersStr = cursor.getString(3) ?: ""
                val users = if (usersStr.isBlank()) emptyList() else usersStr.split(", ")
                results.add(mapOf(
                    "questionId"  to cursor.getLong(cursor.getColumnIndexOrThrow("question_id")),
                    "content"     to (cursor.getString(cursor.getColumnIndexOrThrow("content")) ?: ""),
                    "totalWrong"  to cursor.getInt(cursor.getColumnIndexOrThrow("total_wrong")),
                    "wrongUsers"  to users
                ))
            }
        }
        return results
    }

    // ==================== 报表汇总 ====================

    /**
     * 按用户分组，返回所有有考试记录的用户列表（含汇总统计）
     * 每项：userId, username, examCount, avgScore, totalQuestions, wrongCount
     */
    fun getUserReportSummaries(): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT er.user_id,
                      COALESCE(NULLIF(u.real_name,''), NULLIF(u.nickname,''), u.username, '匿名') AS username,
                      COALESCE(NULLIF(u.nickname,''), NULLIF(u.real_name,''), u.username, '匿名') AS nickname,
                      COUNT(er.id)                                                        AS exam_count,
                      AVG(CASE WHEN er.total_questions >= 20 THEN er.score END)           AS avg_score,
                      MAX(CASE WHEN er.total_questions >= 20 THEN er.score END)           AS max_score,
                      SUM(er.total_questions)                                             AS total_questions,
                      SUM(er.correct_count)                                              AS total_correct,
                      (SELECT COUNT(*) FROM $TABLE_WRONG_QUESTIONS w
                       WHERE w.user_id = er.user_id AND w.is_resolved = 0) AS wrong_count
               FROM $TABLE_EXAM_RECORDS er
               LEFT JOIN $TABLE_USERS u ON er.user_id = u.id
               GROUP BY er.user_id
               ORDER BY exam_count DESC, avg_score DESC""", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(mapOf(
                    "userId"         to cursor.getLong(cursor.getColumnIndexOrThrow("user_id")),
                    "username"       to (cursor.getString(cursor.getColumnIndexOrThrow("username")) ?: "匿名"),
                    "nickname"       to (cursor.getString(cursor.getColumnIndexOrThrow("nickname")) ?: "匿名"),
                    "examCount"      to cursor.getInt(cursor.getColumnIndexOrThrow("exam_count")),
                    "avgScore"       to cursor.getDouble(cursor.getColumnIndexOrThrow("avg_score")),
                    "maxScore"       to cursor.getDouble(cursor.getColumnIndexOrThrow("max_score")),
                    "totalQuestions" to cursor.getInt(cursor.getColumnIndexOrThrow("total_questions")),
                    "totalCorrect"   to cursor.getInt(cursor.getColumnIndexOrThrow("total_correct")),
                    "wrongCount"     to cursor.getInt(cursor.getColumnIndexOrThrow("wrong_count"))
                ))
            }
        }
        return results
    }

    /**
     * 指定用户按题库分组，返回每个题库的考试统计
     * 每项：bankId, bankName, examCount, avgScore, bestScore, totalQuestions
     */
    fun getBankStatsForUser(userId: Long): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT er.bank_id,
                      er.bank_name,
                      COUNT(er.id)            AS exam_count,
                      AVG(er.score)           AS avg_score,
                      MAX(er.score)           AS best_score,
                      SUM(er.total_questions) AS total_questions,
                      SUM(er.correct_count)   AS total_correct
               FROM $TABLE_EXAM_RECORDS er
               WHERE er.user_id = ?
               GROUP BY er.bank_id
               ORDER BY exam_count DESC""",
            arrayOf(userId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(mapOf(
                    "bankId"         to cursor.getLong(cursor.getColumnIndexOrThrow("bank_id")),
                    "bankName"       to (cursor.getString(cursor.getColumnIndexOrThrow("bank_name")) ?: ""),
                    "examCount"      to cursor.getInt(cursor.getColumnIndexOrThrow("exam_count")),
                    "avgScore"       to cursor.getDouble(cursor.getColumnIndexOrThrow("avg_score")),
                    "bestScore"      to cursor.getDouble(cursor.getColumnIndexOrThrow("best_score")),
                    "totalQuestions" to cursor.getInt(cursor.getColumnIndexOrThrow("total_questions")),
                    "totalCorrect"   to cursor.getInt(cursor.getColumnIndexOrThrow("total_correct"))
                ))
            }
        }
        return results
    }

    /**
     * 全局做题正确率排名：按用户正确率倒序，至少答过1题
     * 返回：rank, userId, username, totalQuestions, totalCorrect, accuracy, examCount, avgScore
     */
    fun getScoreRanking(): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT er.user_id,
                      COALESCE(NULLIF(u.real_name,''), NULLIF(u.nickname,''), u.username, '匿名') AS display_name,
                      COALESCE(u.school,'') AS school,
                      COALESCE(u.class_name,'') AS class_name,
                      COUNT(er.id)            AS exam_count,
                      SUM(er.total_questions) AS total_questions,
                      SUM(er.correct_count)   AS total_correct,
                      AVG(er.score)           AS avg_score
               FROM $TABLE_EXAM_RECORDS er
               LEFT JOIN $TABLE_USERS u ON er.user_id = u.id
               GROUP BY er.user_id
               HAVING SUM(er.total_questions) > 0
               ORDER BY (CAST(SUM(er.correct_count) AS REAL) / SUM(er.total_questions)) DESC, SUM(er.total_questions) DESC""",
            null
        ).use { cursor ->
            var rank = 1
            var prevAccuracy = -1.0
            var idx = 0
            while (cursor.moveToNext()) {
                idx++
                val totalQ = cursor.getInt(cursor.getColumnIndexOrThrow("total_questions"))
                val totalC = cursor.getInt(cursor.getColumnIndexOrThrow("total_correct"))
                val accuracy = if (totalQ > 0) totalC * 100.0 / totalQ else 0.0
                // 并列排名：正确率相同则名次相同，下一位跳过
                if (accuracy != prevAccuracy) {
                    rank = idx
                    prevAccuracy = accuracy
                }
                results.add(mapOf(
                    "rank"           to rank,
                    "userId"         to cursor.getLong(cursor.getColumnIndexOrThrow("user_id")),
                    "displayName"    to (cursor.getString(cursor.getColumnIndexOrThrow("display_name")) ?: "匿名"),
                    "school"         to (cursor.getString(cursor.getColumnIndexOrThrow("school")) ?: ""),
                    "className"      to (cursor.getString(cursor.getColumnIndexOrThrow("class_name")) ?: ""),
                    "examCount"      to cursor.getInt(cursor.getColumnIndexOrThrow("exam_count")),
                    "totalQuestions" to totalQ,
                    "totalCorrect"   to totalC,
                    "accuracy"       to accuracy,
                    "avgScore"       to cursor.getDouble(cursor.getColumnIndexOrThrow("avg_score"))
                ))
            }
        }
        return results
    }

    /**
     * 指定用户按题库分组的做题详情（用于排名点击展开）
     */
    fun getBankDetailForUser(userId: Long): List<Map<String, Any>> = getBankStatsForUser(userId)

    /**
     * 批量获取多个用户的题库做题详情，消除 handleGetScoreRanking 中的 N+1 查询。
     * 返回 Map<userId, List<bankStats>>
     */
    fun getBankDetailBatch(userIds: List<Long>): Map<Long, List<Map<String, Any>>> {
        if (userIds.isEmpty()) return emptyMap()
        val placeholders = userIds.joinToString(",") { "?" }
        val result = mutableMapOf<Long, MutableList<Map<String, Any>>>()
        readableDatabase.rawQuery(
            """SELECT er.user_id, er.bank_id, er.bank_name,
                      COUNT(er.id)            AS exam_count,
                      AVG(er.score)           AS avg_score,
                      MAX(er.score)           AS best_score,
                      SUM(er.total_questions) AS total_questions,
                      SUM(er.correct_count)   AS total_correct
               FROM $TABLE_EXAM_RECORDS er
               WHERE er.user_id IN ($placeholders)
               GROUP BY er.user_id, er.bank_id
               ORDER BY er.user_id, exam_count DESC""",
            userIds.map { it.toString() }.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uid = cursor.getLong(cursor.getColumnIndexOrThrow("user_id"))
                result.getOrPut(uid) { mutableListOf() }.add(mapOf(
                    "bankId"         to cursor.getLong(cursor.getColumnIndexOrThrow("bank_id")),
                    "bankName"       to (cursor.getString(cursor.getColumnIndexOrThrow("bank_name")) ?: ""),
                    "examCount"      to cursor.getInt(cursor.getColumnIndexOrThrow("exam_count")),
                    "avgScore"       to cursor.getDouble(cursor.getColumnIndexOrThrow("avg_score")),
                    "bestScore"      to cursor.getDouble(cursor.getColumnIndexOrThrow("best_score")),
                    "totalQuestions" to cursor.getInt(cursor.getColumnIndexOrThrow("total_questions")),
                    "totalCorrect"   to cursor.getInt(cursor.getColumnIndexOrThrow("total_correct"))
                ))
            }
        }
        return result
    }
    /** 获取所有用户列表 */
    fun getAllUsers(): List<User> {
        val list = mutableListOf<User>()
        readableDatabase.rawQuery("SELECT * FROM $TABLE_USERS ORDER BY class_name, real_name, created_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) list.add(cursorToUser(cursor))
        }
        return list
    }

    fun batchRegisterUsers(users: List<Triple<String, String, String>>): Int {
        var count = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            users.forEach { (username, password, realName) ->
                val exists = db.rawQuery("SELECT id FROM $TABLE_USERS WHERE username=?", arrayOf(username)).use { it.moveToFirst() }
                if (!exists) {
                    val values = android.content.ContentValues().apply {
                        put("username", username); put("password", hashPassword(password))
                        put("nickname", realName.ifEmpty { username }); put("real_name", realName)
                        put("created_at", System.currentTimeMillis())
                    }
                    if (db.insert(TABLE_USERS, null, values) > 0) count++
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    fun adminUpdateUser(userId: Long, realName: String, school: String, className: String, password: String?, groupName: String = "") {
        val values = android.content.ContentValues().apply {
            put("real_name", realName); put("nickname", realName.ifEmpty { "" })
            put("school", school); put("class_name", className)
            put("group_name", groupName)
            if (!password.isNullOrBlank()) put("password", hashPassword(password))
        }
        writableDatabase.update(TABLE_USERS, values, "id=?", arrayOf(userId.toString()))
    }

    fun deleteUser(userId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $TABLE_ANSWER_RECORDS WHERE exam_record_id IN (SELECT id FROM $TABLE_EXAM_RECORDS WHERE user_id=?)", arrayOf(userId))
            db.delete(TABLE_EXAM_RECORDS, "user_id=?", arrayOf(userId.toString()))
            db.delete(TABLE_WRONG_QUESTIONS, "user_id=?", arrayOf(userId.toString()))
            db.delete(TABLE_FEEDBACK, "user_id=?", arrayOf(userId.toString()))
            db.execSQL("DELETE FROM assigned_exam_users WHERE user_id=?", arrayOf(userId))
            db.delete(TABLE_USERS, "id=?", arrayOf(userId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 批量删除用户名以指定前缀开头的用户及其所有关联数据（用于压测后清理测试账号）。
     * @return 删除的用户数量
     */
    fun deleteUsersByUsernamePrefix(prefix: String): Int {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            // 用一条 SQL 级联删除，避免逐条循环
            db.execSQL("""
                DELETE FROM $TABLE_ANSWER_RECORDS
                WHERE exam_record_id IN (
                    SELECT id FROM $TABLE_EXAM_RECORDS
                    WHERE user_id IN (SELECT id FROM $TABLE_USERS WHERE username LIKE ?)
                )
            """.trimIndent(), arrayOf("$prefix%"))
            db.execSQL("DELETE FROM $TABLE_EXAM_RECORDS WHERE user_id IN (SELECT id FROM $TABLE_USERS WHERE username LIKE ?)", arrayOf("$prefix%"))
            db.execSQL("DELETE FROM $TABLE_WRONG_QUESTIONS WHERE user_id IN (SELECT id FROM $TABLE_USERS WHERE username LIKE ?)", arrayOf("$prefix%"))
            db.execSQL("DELETE FROM $TABLE_FEEDBACK WHERE user_id IN (SELECT id FROM $TABLE_USERS WHERE username LIKE ?)", arrayOf("$prefix%"))
            db.execSQL("DELETE FROM assigned_exam_users WHERE user_id IN (SELECT id FROM $TABLE_USERS WHERE username LIKE ?)", arrayOf("$prefix%"))
            val count = db.delete(TABLE_USERS, "username LIKE ?", arrayOf("$prefix%"))
            db.setTransactionSuccessful()
            count
        } finally {
            db.endTransaction()
        }
    }

    // ==================== 抢答比赛 ====================

    /** 初始化/重置抢答状态（考试创建后由发布逻辑调用） */
    fun initBuzzerContest(assignedExamId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 清除该比赛的所有答题记录
            db.delete(TABLE_BUZZER_ANSWERS, "assigned_exam_id=?", arrayOf(assignedExamId.toString()))
            // 重置学生完成状态，使客户端能重新检测到该考试
            val resetCv = ContentValues().apply { put("is_completed", 0) }
            db.update("assigned_exam_users", resetCv, "assigned_exam_id=?", arrayOf(assignedExamId.toString()))
            val cv = ContentValues().apply {
                put("assigned_exam_id", assignedExamId)
                put("current_question_index", 0)
                put("is_started", 0)
                put("is_ended", 0)
                put("started_at", 0L)
                put("ended_at", 0L)
            }
            db.insertWithOnConflict(TABLE_BUZZER_STATE, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 获取抢答状态 */
    fun getBuzzerState(assignedExamId: Long): Map<String, Any>? {
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_BUZZER_STATE WHERE assigned_exam_id=?",
            arrayOf(assignedExamId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return mapOf(
                "assignedExamId" to c.getLong(c.getColumnIndexOrThrow("assigned_exam_id")),
                "currentQuestionIndex" to c.getInt(c.getColumnIndexOrThrow("current_question_index")),
                "isStarted" to (c.getInt(c.getColumnIndexOrThrow("is_started")) == 1),
                "isEnded" to (c.getInt(c.getColumnIndexOrThrow("is_ended")) == 1),
                "startedAt" to c.getLong(c.getColumnIndexOrThrow("started_at")),
                "endedAt" to c.getLong(c.getColumnIndexOrThrow("ended_at"))
            )
        }
    }

    /** 批量获取多个考试的抢答状态，消除 handleGetBuzzerList() 中的 N+1 查询 */
    fun getBuzzerStateBatch(assignedExamIds: List<Long>): Map<Long, Map<String, Any>> {
        if (assignedExamIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, Map<String, Any>>()
        val placeholders = assignedExamIds.joinToString(",") { "?" }
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_BUZZER_STATE WHERE assigned_exam_id IN ($placeholders)",
            assignedExamIds.map { it.toString() }.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val eid = c.getLong(c.getColumnIndexOrThrow("assigned_exam_id"))
                result[eid] = mapOf(
                    "assignedExamId" to eid,
                    "currentQuestionIndex" to c.getInt(c.getColumnIndexOrThrow("current_question_index")),
                    "isStarted" to (c.getInt(c.getColumnIndexOrThrow("is_started")) == 1),
                    "isEnded" to (c.getInt(c.getColumnIndexOrThrow("is_ended")) == 1),
                    "startedAt" to c.getLong(c.getColumnIndexOrThrow("started_at")),
                    "endedAt" to c.getLong(c.getColumnIndexOrThrow("ended_at"))
                )
            }
        }
        return result
    }

    /** 设置当前题目索引并标记已开始 */
    fun setBuzzerCurrentQuestion(assignedExamId: Long, index: Int) {
        val now = System.currentTimeMillis()
        // 原子操作：若 started_at 尚未设置则同时记录开始时间，避免并发 TOCTOU
        writableDatabase.execSQL(
            """UPDATE $TABLE_BUZZER_STATE
               SET current_question_index = ?,
                   is_started = 1,
                   started_at = CASE WHEN started_at = 0 THEN ? ELSE started_at END
               WHERE assigned_exam_id = ?""",
            arrayOf(index, now, assignedExamId)
        )
    }

    /** 结束比赛 */
    fun endBuzzerContest(assignedExamId: Long) {
        val cv = ContentValues().apply {
            put("is_ended", 1)
            put("ended_at", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_BUZZER_STATE, cv, "assigned_exam_id=?", arrayOf(assignedExamId.toString()))
    }

    /** 原子性结束比赛并将所有学生标记为已完成，避免两步独立调用中途崩溃造成状态不一致 */
    fun endBuzzerContestAndComplete(assignedExamId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("is_ended", 1)
                put("ended_at", System.currentTimeMillis())
            }
            db.update(TABLE_BUZZER_STATE, cv, "assigned_exam_id=?", arrayOf(assignedExamId.toString()))
            val completeCv = ContentValues().apply { put("is_completed", 1) }
            db.update("assigned_exam_users", completeCv, "assigned_exam_id=?", arrayOf(assignedExamId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 客户端提交抢答答案 */
    fun submitBuzzerAnswer(assignedExamId: Long, questionIndex: Int, userId: Long, answer: String, isCorrect: Boolean): Boolean {
        val cv = ContentValues().apply {
            put("assigned_exam_id", assignedExamId)
            put("question_index", questionIndex)
            put("user_id", userId)
            put("user_answer", answer)
            put("is_correct", if (isCorrect) 1 else 0)
            put("answered_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_BUZZER_ANSWERS, null, cv,
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        ) > 0
    }

    /** 当前题目：按答对时间排序的最快学生排行榜 */
    fun getBuzzerFastestCorrect(assignedExamId: Long, questionIndex: Int, limit: Int = 10): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT ba.user_id, COALESCE(NULLIF(u.real_name,''), u.nickname, u.username, '匿名') AS display_name,
                      u.group_name, ba.answered_at
               FROM $TABLE_BUZZER_ANSWERS ba
               LEFT JOIN $TABLE_USERS u ON ba.user_id = u.id
               WHERE ba.assigned_exam_id=? AND ba.question_index=? AND ba.is_correct=1
               ORDER BY ba.answered_at ASC LIMIT ?""",
            arrayOf(assignedExamId.toString(), questionIndex.toString(), limit.toString())
        ).use { c ->
            var rank = 1
            while (c.moveToNext()) {
                results.add(mapOf(
                    "rank" to rank++,
                    "userId" to c.getLong(0),
                    "displayName" to (c.getString(1) ?: "匿名"),
                    "groupName" to (c.getString(2) ?: ""),
                    "answeredAt" to c.getLong(3)
                ))
            }
        }
        return results
    }

    /** 当前题目：各组正确率排行榜 */
    fun getBuzzerGroupAccuracyForQuestion(assignedExamId: Long, questionIndex: Int): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT COALESCE(NULLIF(u.group_name,''), '未分组') AS group_name,
                      COUNT(*) AS total, SUM(ba.is_correct) AS correct
               FROM $TABLE_BUZZER_ANSWERS ba
               LEFT JOIN $TABLE_USERS u ON ba.user_id = u.id
               WHERE ba.assigned_exam_id=? AND ba.question_index=?
               GROUP BY group_name
               ORDER BY (CAST(correct AS REAL)/total) DESC""",
            arrayOf(assignedExamId.toString(), questionIndex.toString())
        ).use { c ->
            var rank = 1
            while (c.moveToNext()) {
                val total = c.getInt(1)
                val correct = c.getInt(2)
                results.add(mapOf(
                    "rank" to rank++,
                    "groupName" to (c.getString(0) ?: "未分组"),
                    "total" to total,
                    "correct" to correct,
                    "accuracy" to if (total > 0) correct * 100 / total else 0
                ))
            }
        }
        return results
    }

    /** 整体：学生正确率排行（正确题数/总作答题数） */
    fun getBuzzerOverallStudentAccuracy(assignedExamId: Long): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT ba.user_id, COALESCE(NULLIF(u.real_name,''), u.nickname, u.username, '匿名') AS display_name,
                      u.group_name, COUNT(*) AS total, SUM(ba.is_correct) AS correct
               FROM $TABLE_BUZZER_ANSWERS ba
               LEFT JOIN $TABLE_USERS u ON ba.user_id = u.id
               WHERE ba.assigned_exam_id=?
               GROUP BY ba.user_id
               ORDER BY (CAST(correct AS REAL)/total) DESC, correct DESC""",
            arrayOf(assignedExamId.toString())
        ).use { c ->
            var rank = 1
            while (c.moveToNext()) {
                val total = c.getInt(3)
                val correct = c.getInt(4)
                results.add(mapOf(
                    "rank" to rank++,
                    "userId" to c.getLong(0),
                    "displayName" to (c.getString(1) ?: "匿名"),
                    "groupName" to (c.getString(2) ?: ""),
                    "total" to total,
                    "correct" to correct,
                    "accuracy" to if (total > 0) correct * 100 / total else 0
                ))
            }
        }
        return results
    }

    /** 整体：组别正确率排行 */
    fun getBuzzerOverallGroupAccuracy(assignedExamId: Long): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT COALESCE(NULLIF(u.group_name,''), '未分组') AS group_name,
                      COUNT(*) AS total, SUM(ba.is_correct) AS correct
               FROM $TABLE_BUZZER_ANSWERS ba
               LEFT JOIN $TABLE_USERS u ON ba.user_id = u.id
               WHERE ba.assigned_exam_id=?
               GROUP BY group_name
               ORDER BY (CAST(correct AS REAL)/total) DESC""",
            arrayOf(assignedExamId.toString())
        ).use { c ->
            var rank = 1
            while (c.moveToNext()) {
                val total = c.getInt(1)
                val correct = c.getInt(2)
                results.add(mapOf(
                    "rank" to rank++,
                    "groupName" to (c.getString(0) ?: "未分组"),
                    "total" to total,
                    "correct" to correct,
                    "accuracy" to if (total > 0) correct * 100 / total else 0
                ))
            }
        }
        return results
    }

    /** 整体：正确率最低的题目排行（包含参与者数量） */
    fun getBuzzerWorstQuestions(assignedExamId: Long, questionIds: List<Long>): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        if (questionIds.isEmpty()) return results

        // 构建 question_index -> question_id 映射，避免循环中再查 DB（消除 N+1）
        val indexToId = questionIds.mapIndexed { idx, id -> idx to id }.toMap()

        // 使用 IN 子句一次性拉取所有用到题目的内容预览
        val placeholders = questionIds.joinToString(",") { "?" }
        val contentMap = mutableMapOf<Long, String>()
        readableDatabase.rawQuery(
            "SELECT id, content FROM $TABLE_QUESTIONS WHERE id IN ($placeholders)",
            questionIds.map { it.toString() }.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                contentMap[c.getLong(0)] = c.getString(1)?.take(40) ?: ""
            }
        }

        readableDatabase.rawQuery(
            """SELECT ba.question_index, COUNT(*) AS total, SUM(ba.is_correct) AS correct
               FROM $TABLE_BUZZER_ANSWERS ba
               WHERE ba.assigned_exam_id=?
               GROUP BY ba.question_index
               ORDER BY (CAST(correct AS REAL)/total) ASC""",
            arrayOf(assignedExamId.toString())
        ).use { c ->
            var rank = 1
            while (c.moveToNext()) {
                val idx = c.getInt(0)
                val total = c.getInt(1)
                val correct = c.getInt(2)
                val preview = indexToId[idx]?.let { contentMap[it] ?: "" } ?: ""
                results.add(mapOf(
                    "rank" to rank++,
                    "questionIndex" to idx,
                    "questionPreview" to preview,
                    "total" to total,
                    "correct" to correct,
                    "accuracy" to if (total > 0) correct * 100 / total else 0
                ))
            }
        }
        return results
    }

    /** 当前题目参与/未参与答题人数（基于登录用户与答题记录对比） */
    fun getBuzzerAnswerCount(assignedExamId: Long, questionIndex: Int): Map<String, Int> {
        var answered = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT user_id) FROM $TABLE_BUZZER_ANSWERS WHERE assigned_exam_id=? AND question_index=?",
            arrayOf(assignedExamId.toString(), questionIndex.toString())
        ).use { c -> if (c.moveToFirst()) answered = c.getInt(0) }
        var total = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM assigned_exam_users WHERE assigned_exam_id=?",
            arrayOf(assignedExamId.toString())
        ).use { c -> if (c.moveToFirst()) total = c.getInt(0) }
        return mapOf("answered" to answered, "unanswered" to maxOf(0, total - answered), "total" to total)
    }

    /** 获取指定用户在某题的答题情况 */
    fun getBuzzerUserAnswer(assignedExamId: Long, questionIndex: Int, userId: Long): Map<String, Any>? {
        readableDatabase.rawQuery(
            "SELECT user_answer, is_correct, answered_at FROM $TABLE_BUZZER_ANSWERS WHERE assigned_exam_id=? AND question_index=? AND user_id=?",
            arrayOf(assignedExamId.toString(), questionIndex.toString(), userId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return mapOf(
                "answer" to (c.getString(0) ?: ""),
                "isCorrect" to (c.getInt(1) == 1),
                "answeredAt" to c.getLong(2)
            )
        }
    }

    /** 批量设置学生组别 */
    fun batchSetGroupName(userIds: List<Long>, groupName: String) {
        val db = writableDatabase; db.beginTransaction()
        try {
            userIds.forEach { uid ->
                val v = ContentValues().apply { put("group_name", groupName) }
                db.update(TABLE_USERS, v, "id=?", arrayOf(uid.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 获取所有组别名称 */
    fun getDistinctGroupNames(): List<String> {
        val list = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT group_name FROM $TABLE_USERS WHERE group_name != '' ORDER BY group_name", null
        ).use { c -> while (c.moveToNext()) list.add(c.getString(0)) }
        return list
    }

    // ==================== 批量设置学校班级 ====================

    fun batchSetSchoolClass(userIds: List<Long>, school: String, className: String) {
        val db = writableDatabase; db.beginTransaction()
        try {
            userIds.forEach { uid ->
                val v = android.content.ContentValues().apply { put("school", school); put("class_name", className) }
                db.update(TABLE_USERS, v, "id=?", arrayOf(uid.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun createAssignedExam(bankId: Long, bankName: String, limitCount: Int, timeMinutes: Int, maxDiff: Int, shuffleOpts: Boolean, shuffleQ: Boolean, questionTypes: String = "", typeCounts: String = "", typeScores: String = "", submitBeforeEndMinutes: Int = 0, scheduledStartTime: Long = 0, questionIds: String = "", examMode: String = "online", autoSubmitAt: Long = 0L): Long {
        val values = android.content.ContentValues().apply {
            put("bank_id", bankId); put("bank_name", bankName); put("limit_count", limitCount)
            put("time_minutes", timeMinutes); put("max_diff", maxDiff)
            put("shuffle_opts", if (shuffleOpts) 1 else 0); put("shuffle_q", if (shuffleQ) 1 else 0)
            put("question_types", questionTypes)
            put("type_counts", typeCounts)
            put("type_scores", typeScores)
            put("submit_before_end_minutes", submitBeforeEndMinutes)
            put("scheduled_start_time", scheduledStartTime)
            put("question_ids", questionIds)
            put("exam_mode", examMode)
            put("auto_submit_at", autoSubmitAt)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert(TABLE_ASSIGNED_EXAM, null, values)
    }

    fun assignExamToUsers(assignedExamId: Long, userIds: List<Long>) {
        val db = writableDatabase; db.beginTransaction()
        try {
            userIds.forEach { uid ->
                val v = android.content.ContentValues().apply {
                    put("assigned_exam_id", assignedExamId); put("user_id", uid); put("is_completed", 0)
                }
                db.insertWithOnConflict("assigned_exam_users", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * 查找最近一个处于活跃状态的在线考试（不依赖用户分配关系）。
     * 如果找到且该用户尚未被加入，则自动将其加入（open-join），
     * 使压力测试新注册用户也能参与已发布的考试。
     */
    fun getActiveAssignedExamOrAutoJoin(userId: Long): Map<String, Any>? {
        // 1. 先查已分配的考试
        val assigned = getActiveAssignedExam(userId)
        if (assigned != null) return assigned

        // 2. 查找最新一场 exam_mode='online' 且未结束的考试（auto_submit_at=0 或尚未到期）
        val now = System.currentTimeMillis()
        val row = readableDatabase.rawQuery(
            """SELECT * FROM $TABLE_ASSIGNED_EXAM
               WHERE exam_mode = 'online'
                 AND (auto_submit_at = 0 OR auto_submit_at > ?)
               ORDER BY created_at DESC LIMIT 1""", arrayOf(now.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            mapOf(
                "id"                    to c.getLong(c.getColumnIndexOrThrow("id")),
                "bankId"                to c.getLong(c.getColumnIndexOrThrow("bank_id")),
                "bankName"              to (c.getString(c.getColumnIndexOrThrow("bank_name")) ?: ""),
                "limitCount"            to c.getInt(c.getColumnIndexOrThrow("limit_count")),
                "timeMinutes"           to c.getInt(c.getColumnIndexOrThrow("time_minutes")),
                "maxDiff"               to c.getInt(c.getColumnIndexOrThrow("max_diff")),
                "shuffleOpts"           to (c.getInt(c.getColumnIndexOrThrow("shuffle_opts")) == 1),
                "shuffleQ"              to (c.getInt(c.getColumnIndexOrThrow("shuffle_q")) == 1),
                "questionTypes"         to (c.getString(c.getColumnIndexOrThrow("question_types")) ?: ""),
                "typeCounts"            to (c.getString(c.getColumnIndexOrThrow("type_counts")) ?: ""),
                "typeScores"            to (c.getColumnIndex("type_scores").let { if (it >= 0) c.getString(it) else "" } ?: ""),
                "submitBeforeEndMinutes" to (c.getColumnIndex("submit_before_end_minutes").let { if (it >= 0) c.getInt(it) else 0 }),
                "scheduledStartTime"    to c.getLong(c.getColumnIndexOrThrow("scheduled_start_time")),
                "questionIds"           to (c.getColumnIndex("question_ids").let { if (it >= 0) c.getString(it) else "" } ?: ""),
                "examMode"              to (c.getColumnIndex("exam_mode").let { if (it >= 0) c.getString(it) else "online" } ?: "online"),
                "autoSubmitAt"          to (c.getColumnIndex("auto_submit_at").let { if (it >= 0) c.getLong(it) else 0L })
            )
        } ?: return null

        // 3. 自动将用户加入该考试（CONFLICT_IGNORE 保证幂等）
        val examId = (row["id"] as Long)
        val v = android.content.ContentValues().apply {
            put("assigned_exam_id", examId)
            put("user_id", userId)
            put("is_completed", 0)
        }
        writableDatabase.insertWithOnConflict(
            "assigned_exam_users", null, v,
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        return row
    }

    fun getActiveAssignedExam(userId: Long): Map<String, Any>? {
        readableDatabase.rawQuery(
            """SELECT ae.* FROM $TABLE_ASSIGNED_EXAM ae
               JOIN assigned_exam_users aeu ON ae.id = aeu.assigned_exam_id
               WHERE aeu.user_id = ? AND aeu.is_completed = 0
               ORDER BY ae.created_at DESC LIMIT 1""", arrayOf(userId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return mapOf(
                "id" to cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                "bankId" to cursor.getLong(cursor.getColumnIndexOrThrow("bank_id")),
                "bankName" to (cursor.getString(cursor.getColumnIndexOrThrow("bank_name")) ?: ""),
                "limitCount" to cursor.getInt(cursor.getColumnIndexOrThrow("limit_count")),
                "timeMinutes" to cursor.getInt(cursor.getColumnIndexOrThrow("time_minutes")),
                "maxDiff" to cursor.getInt(cursor.getColumnIndexOrThrow("max_diff")),
                "shuffleOpts" to (cursor.getInt(cursor.getColumnIndexOrThrow("shuffle_opts")) == 1),
                "shuffleQ" to (cursor.getInt(cursor.getColumnIndexOrThrow("shuffle_q")) == 1),
                "questionTypes" to (cursor.getString(cursor.getColumnIndexOrThrow("question_types")) ?: ""),
                "typeCounts" to (cursor.getString(cursor.getColumnIndexOrThrow("type_counts")) ?: ""),
                "typeScores" to (cursor.getColumnIndex("type_scores").let { if (it >= 0) cursor.getString(it) else "" } ?: ""),
                "submitBeforeEndMinutes" to (cursor.getColumnIndex("submit_before_end_minutes").let { if (it >= 0) cursor.getInt(it) else 0 }),
                "scheduledStartTime" to cursor.getLong(cursor.getColumnIndexOrThrow("scheduled_start_time")),
                "questionIds" to (cursor.getColumnIndex("question_ids").let { if (it >= 0) cursor.getString(it) else "" } ?: ""),
                "examMode" to (cursor.getColumnIndex("exam_mode").let { if (it >= 0) cursor.getString(it) else "online" } ?: "online"),
                "autoSubmitAt" to (cursor.getColumnIndex("auto_submit_at").let { if (it >= 0) cursor.getLong(it) else 0L })
            )
        }
    }

    fun markAssignedExamCompleted(userId: Long, assignedExamId: Long) {
        val v = android.content.ContentValues().apply { put("is_completed", 1) }
        writableDatabase.update("assigned_exam_users", v, "user_id=? AND assigned_exam_id=?", arrayOf(userId.toString(), assignedExamId.toString()))
    }

    fun deleteAssignedExam(assignedExamId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("assigned_exam_users", "assigned_exam_id=?", arrayOf(assignedExamId.toString()))
            db.delete(TABLE_BUZZER_ANSWERS, "assigned_exam_id=?", arrayOf(assignedExamId.toString()))
            db.delete(TABLE_BUZZER_STATE, "assigned_exam_id=?", arrayOf(assignedExamId.toString()))
            db.delete(TABLE_ASSIGNED_EXAM, "id=?", arrayOf(assignedExamId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setExamAllowDownload(examId: Long, allow: Boolean) {
        val v = ContentValues().apply { put("allow_download", if (allow) 1 else 0) }
        writableDatabase.update(TABLE_ASSIGNED_EXAM, v, "id=?", arrayOf(examId.toString()))
    }

    fun getAssignedExamById(examId: Long): Map<String, Any>? {
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_ASSIGNED_EXAM WHERE id=?", arrayOf(examId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return mapOf(
                "id" to cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                "bankId" to cursor.getLong(cursor.getColumnIndexOrThrow("bank_id")),
                "bankName" to (cursor.getString(cursor.getColumnIndexOrThrow("bank_name")) ?: ""),
                "timeMinutes" to cursor.getInt(cursor.getColumnIndexOrThrow("time_minutes")),
                "typeScores" to (cursor.getColumnIndex("type_scores").let { if (it >= 0) cursor.getString(it) else "" } ?: ""),
                "questionIds" to (cursor.getColumnIndex("question_ids").let { if (it >= 0) cursor.getString(it) else "" } ?: ""),
                "allowDownload" to (cursor.getColumnIndex("allow_download").let { if (it >= 0) cursor.getInt(it) == 1 else false }),
                "examMode" to (cursor.getColumnIndex("exam_mode").let { if (it >= 0) cursor.getString(it) else "online" } ?: "online"),
                "createdAt" to cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
            )
        }
    }

    fun getDownloadableExams(): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_ASSIGNED_EXAM WHERE allow_download=1 ORDER BY created_at DESC", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(mapOf(
                    "id" to cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    "bankName" to (cursor.getString(cursor.getColumnIndexOrThrow("bank_name")) ?: ""),
                    "timeMinutes" to cursor.getInt(cursor.getColumnIndexOrThrow("time_minutes")),
                    "createdAt" to cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                ))
            }
        }
        return results
    }

    fun forceCompleteAllUsers(assignedExamId: Long) {
        val v = android.content.ContentValues().apply { put("is_completed", 1); put("force_submit", 1) }
        writableDatabase.update("assigned_exam_users", v, "assigned_exam_id=? AND is_completed=0", arrayOf(assignedExamId.toString()))
    }

    fun checkAndClearForceSubmit(userId: Long, assignedExamId: Long): Boolean {
        var flagged = false
        readableDatabase.rawQuery(
            "SELECT force_submit FROM assigned_exam_users WHERE user_id=? AND assigned_exam_id=?",
            arrayOf(userId.toString(), assignedExamId.toString())
        ).use { c ->
            if (c.moveToFirst()) flagged = c.getInt(0) == 1
        }
        if (flagged) {
            val v = android.content.ContentValues().apply { put("force_submit", 0) }
            writableDatabase.update("assigned_exam_users", v, "user_id=? AND assigned_exam_id=?", arrayOf(userId.toString(), assignedExamId.toString()))
        }
        return flagged
    }

    fun getExamStatistics(assignedExamId: Long, bankId: Long): Map<String, Any> {
        val userIds = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT user_id FROM assigned_exam_users WHERE assigned_exam_id=?", arrayOf(assignedExamId.toString())
        ).use { c -> while (c.moveToNext()) userIds.add(c.getLong(0)) }
        if (userIds.isEmpty()) return mapOf("totalStudents" to 0, "completedStudents" to 0, "avgScore" to 0.0, "maxScore" to 0, "minScore" to 0, "records" to emptyList<Any>())

        val placeholders = userIds.joinToString(",") { "?" }
        val args = userIds.map { it.toString() }.toTypedArray() + bankId.toString()
        val records = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT er.user_id, COALESCE(NULLIF(u.real_name,''), NULLIF(u.nickname,''), u.username, '匿名') AS real_name, u.username, er.score, er.correct_count, er.total_questions, er.time_cost_seconds
               FROM exam_records er LEFT JOIN $TABLE_USERS u ON er.user_id = u.id
               WHERE er.user_id IN ($placeholders) AND er.bank_id = ?
               ORDER BY er.score DESC""", args
        ).use { c ->
            while (c.moveToNext()) {
                records.add(mapOf(
                    "userId" to c.getLong(0),
                    "realName" to (c.getString(1) ?: ""),
                    "username" to (c.getString(2) ?: ""),
                    "score" to c.getInt(3),
                    "correctCount" to c.getInt(4),
                    "totalCount" to c.getInt(5),
                    "timeCost" to c.getInt(6)
                ))
            }
        }
        val scores = records.map { it["score"] as Int }
        val completedCount = records.map { it["userId"] }.distinct().size
        return mapOf(
            "totalStudents" to userIds.size,
            "completedStudents" to completedCount,
            "avgScore" to if (scores.isNotEmpty()) scores.average() else 0.0,
            "maxScore" to (scores.maxOrNull() ?: 0),
            "minScore" to (scores.minOrNull() ?: 0),
            "records" to records
        )
    }

    fun getDistinctClassNames(): List<String> {
        val list = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT class_name FROM $TABLE_USERS WHERE class_name != '' ORDER BY class_name", null
        ).use { cursor ->
            while (cursor.moveToNext()) list.add(cursor.getString(0))
        }
        return list
    }

    fun getUsersByClassName(className: String): List<User> {
        val list = mutableListOf<User>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_USERS WHERE class_name = ? ORDER BY real_name, username",
            arrayOf(className)
        ).use { cursor ->
            while (cursor.moveToNext()) list.add(cursorToUser(cursor))
        }
        return list
    }

    fun updateExamUserActivity(assignedExamId: Long, userId: Long, answeredCount: Int) {
        val v = ContentValues().apply {
            put("last_active_at", System.currentTimeMillis())
            put("answered_count", answeredCount)
        }
        writableDatabase.update("assigned_exam_users", v,
            "assigned_exam_id=? AND user_id=?", arrayOf(assignedExamId.toString(), userId.toString()))
    }

    fun getExamProgress(assignedExamId: Long): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT aeu.user_id, u.real_name, u.username, u.class_name,
                      aeu.is_completed, aeu.last_active_at, aeu.answered_count
               FROM assigned_exam_users aeu
               JOIN $TABLE_USERS u ON aeu.user_id = u.id
               WHERE aeu.assigned_exam_id = ?
               ORDER BY aeu.is_completed ASC, u.class_name, u.real_name, u.username""",
            arrayOf(assignedExamId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                results.add(mapOf(
                    "userId" to c.getLong(0),
                    "realName" to (c.getString(1) ?: ""),
                    "username" to (c.getString(2) ?: ""),
                    "className" to (c.getString(3) ?: ""),
                    "isCompleted" to (c.getInt(4) == 1),
                    "lastActiveAt" to c.getLong(5),
                    "answeredCount" to c.getInt(6)
                ))
            }
        }
        return results
    }

    fun getExamDetailStatistics(assignedExamId: Long, bankId: Long): Map<String, Any> {
        val userIds = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT user_id FROM assigned_exam_users WHERE assigned_exam_id=?",
            arrayOf(assignedExamId.toString())
        ).use { c -> while (c.moveToNext()) userIds.add(c.getLong(0)) }

        if (userIds.isEmpty()) return mapOf("totalStudents" to 0, "completedStudents" to 0,
            "avgScore" to 0.0, "maxScore" to 0, "minScore" to 0, "passRate" to 0.0,
            "excellentRate" to 0.0, "records" to emptyList<Any>(), "questionStats" to emptyList<Any>(),
            "studentDetails" to emptyList<Any>())

        // 按 assigned_exam_id 精确查询本场考试的成绩记录
        val records = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT er.id, er.user_id,
                      COALESCE(NULLIF(u.real_name,''), u.nickname, u.username, '未知用户') AS realName,
                      COALESCE(u.username, '未知') AS username, COALESCE(u.class_name, '') AS className,
                      er.score, er.correct_count, er.total_questions, er.time_cost_seconds
               FROM $TABLE_EXAM_RECORDS er LEFT JOIN $TABLE_USERS u ON er.user_id = u.id
               WHERE er.assigned_exam_id = ?
               ORDER BY er.score DESC""", arrayOf(assignedExamId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                records.add(mapOf(
                    "recordId" to c.getLong(0),
                    "userId" to c.getLong(1),
                    "realName" to (c.getString(2) ?: ""),
                    "username" to (c.getString(3) ?: ""),
                    "className" to (c.getString(4) ?: ""),
                    "score" to c.getDouble(5),
                    "correctCount" to c.getInt(6),
                    "totalCount" to c.getInt(7),
                    "timeCostSeconds" to c.getInt(8)
                ))
            }
        }

        val scores = records.map { it["score"] as Double }
        val completedCount = records.map { it["userId"] }.distinct().size
        val passCount = scores.count { it >= 60.0 }
        val excellentCount = scores.count { it >= 90.0 }

        // 每题得分率：从 answer_records 中统计
        val questionStats = mutableListOf<Map<String, Any>>()
        if (records.isNotEmpty()) {
            val recordIds = records.map { it["recordId"] as Long }
            val recPlaceholders = recordIds.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                """SELECT ar.question_id, q.content, q.type, COUNT(*) as total_ans,
                          SUM(ar.is_correct) as correct_ans
                   FROM $TABLE_ANSWER_RECORDS ar
                   JOIN $TABLE_QUESTIONS q ON ar.question_id = q.id
                   WHERE ar.exam_record_id IN ($recPlaceholders)
                   GROUP BY ar.question_id
                   ORDER BY (CAST(correct_ans AS REAL)/total_ans) ASC""",
                recordIds.map { it.toString() }.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) {
                    val total = c.getInt(3)
                    val correct = c.getInt(4)
                    questionStats.add(mapOf(
                        "questionId" to c.getLong(0),
                        "content" to (c.getString(1) ?: ""),
                        "typeCode" to c.getInt(2),
                        "totalAnswers" to total,
                        "correctAnswers" to correct,
                        "scoreRate" to if (total > 0) correct.toDouble() / total else 0.0
                    ))
                }
            }
        }

        // 每学生每题对错详情（批量拉取，消除 N+1）
        val studentDetails = mutableListOf<Map<String, Any>>()
        if (records.isNotEmpty()) {
            val allRecordIds = records.map { it["recordId"] as Long }
            val recPh2 = allRecordIds.joinToString(",") { "?" }
            // key = recordId
            val answersGrouped = mutableMapOf<Long, MutableList<Map<String, Any>>>()
            readableDatabase.rawQuery(
                """SELECT ar.exam_record_id, ar.question_id, q.content, ar.user_answer, ar.is_correct
                   FROM $TABLE_ANSWER_RECORDS ar
                   JOIN $TABLE_QUESTIONS q ON ar.question_id = q.id
                   WHERE ar.exam_record_id IN ($recPh2)
                   ORDER BY ar.exam_record_id, ar.id""",
                allRecordIds.map { it.toString() }.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) {
                    val rid = c.getLong(0)
                    answersGrouped.getOrPut(rid) { mutableListOf() }.add(mapOf(
                        "questionId" to c.getLong(1),
                        "content"    to (c.getString(2) ?: ""),
                        "userAnswer" to (c.getString(3) ?: ""),
                        "isCorrect"  to (c.getInt(4) == 1)
                    ))
                }
            }
            records.forEach { rec ->
                val rid = rec["recordId"] as Long
                studentDetails.add(rec + mapOf("answers" to (answersGrouped[rid] ?: emptyList<Any>())))
            }
        }

        return mapOf(
            "totalStudents" to userIds.size,
            "completedStudents" to completedCount,
            "avgScore" to if (scores.isNotEmpty()) scores.average() else 0.0,
            "maxScore" to (scores.maxOrNull() ?: 0.0),
            "minScore" to (scores.minOrNull() ?: 0.0),
            "passRate" to if (completedCount > 0) passCount.toDouble() / completedCount else 0.0,
            "excellentRate" to if (completedCount > 0) excellentCount.toDouble() / completedCount else 0.0,
            "records" to records,
            "questionStats" to questionStats,
            "studentDetails" to studentDetails
        )
    }

    fun getAllAssignedExams(): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            """SELECT ae.*, COUNT(aeu.id) AS total_users, SUM(aeu.is_completed) AS completed_users,
                      COALESCE(AVG(er.score), 0) AS avg_score, COALESCE(MAX(er.score), 0) AS max_score
               FROM $TABLE_ASSIGNED_EXAM ae
               LEFT JOIN assigned_exam_users aeu ON ae.id=aeu.assigned_exam_id
               LEFT JOIN $TABLE_EXAM_RECORDS er ON er.assigned_exam_id=ae.id
               GROUP BY ae.id ORDER BY ae.created_at DESC""", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(mapOf(
                    "id" to cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    "bankId" to cursor.getLong(cursor.getColumnIndexOrThrow("bank_id")),
                    "bankName" to (cursor.getString(cursor.getColumnIndexOrThrow("bank_name")) ?: ""),
                    "limitCount" to cursor.getInt(cursor.getColumnIndexOrThrow("limit_count")),
                    "timeMinutes" to cursor.getInt(cursor.getColumnIndexOrThrow("time_minutes")),
                    "maxDiff" to cursor.getInt(cursor.getColumnIndexOrThrow("max_diff")),
                    "shuffleOpts" to (cursor.getInt(cursor.getColumnIndexOrThrow("shuffle_opts")) == 1),
                    "shuffleQ" to (cursor.getInt(cursor.getColumnIndexOrThrow("shuffle_q")) == 1),
                    "typeCounts" to (cursor.getString(cursor.getColumnIndexOrThrow("type_counts")) ?: ""),
                    "typeScores" to (cursor.getString(cursor.getColumnIndexOrThrow("type_scores")) ?: ""),
                    "scheduledStartTime" to cursor.getLong(cursor.getColumnIndexOrThrow("scheduled_start_time")),
                    "totalUsers" to cursor.getInt(cursor.getColumnIndexOrThrow("total_users")),
                    "completedUsers" to cursor.getInt(cursor.getColumnIndexOrThrow("completed_users")),
                    "createdAt" to cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    "questionIds" to (cursor.getColumnIndex("question_ids").let { if (it >= 0) cursor.getString(it) else "" } ?: ""),
                    "allowDownload" to (cursor.getColumnIndex("allow_download").let { if (it >= 0) cursor.getInt(it) == 1 else false }),
                    "examMode" to (cursor.getColumnIndex("exam_mode").let { if (it >= 0) cursor.getString(it) else "online" } ?: "online"),
                    "autoSubmitAt" to (cursor.getColumnIndex("auto_submit_at").let { if (it >= 0) cursor.getLong(it) else 0L }),
                    "avgScore" to cursor.getDouble(cursor.getColumnIndexOrThrow("avg_score")),
                    "maxScore" to cursor.getDouble(cursor.getColumnIndexOrThrow("max_score"))
                ))
            }
        }
        return results
    }

    // ==================== 学生消息 ====================

    /** 发送消息给指定学生 */
    fun sendMessage(userId: Long, content: String, senderName: String = "老师"): Long {
        val values = ContentValues().apply {
            put("user_id", userId)
            put("sender_name", senderName)
            put("content", content)
            put("is_read", 0)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert(TABLE_STUDENT_MESSAGES, null, values)
    }

    /** 群发消息给多个学生 */
    fun sendMessageToUsers(userIds: List<Long>, content: String, senderName: String = "老师"): Int {
        val db = writableDatabase
        db.beginTransaction()
        var count = 0
        try {
            userIds.forEach { uid ->
                val values = ContentValues().apply {
                    put("user_id", uid)
                    put("sender_name", senderName)
                    put("content", content)
                    put("is_read", 0)
                    put("created_at", System.currentTimeMillis())
                }
                if (db.insert(TABLE_STUDENT_MESSAGES, null, values) > 0) count++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    /** 获取用户的未读消息 */
    fun getUnreadMessages(userId: Long): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_STUDENT_MESSAGES WHERE user_id=? AND is_read=0 ORDER BY created_at ASC",
            arrayOf(userId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(mapOf(
                    "id" to cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    "senderName" to (cursor.getString(cursor.getColumnIndexOrThrow("sender_name")) ?: "老师"),
                    "content" to (cursor.getString(cursor.getColumnIndexOrThrow("content")) ?: ""),
                    "createdAt" to cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                ))
            }
        }
        return results
    }

    /** 确认已读并删除消息 */
    fun markMessageReadAndDelete(messageId: Long) {
        writableDatabase.delete(TABLE_STUDENT_MESSAGES, "id=?", arrayOf(messageId.toString()))
    }

    // ==================== 题库导出/导入 ====================

    /** 将指定题库及其所有题目导出为 JSON 字符串 */
    fun exportBankToJson(bankId: Long): String? {
        val bank = getBank(bankId) ?: return null
        val questions = getQuestionsByBank(bankId)
        val exportData = mapOf(
            "format" to "SmartQuizBank",
            "version" to 1,
            "bank" to mapOf(
                "name" to bank.name,
                "subject" to bank.subject,
                "description" to bank.description,
                "sourceText" to bank.sourceText,
                "questionCount" to bank.questionCount,
                "createdAt" to bank.createdAt
            ),
            "questions" to questions.map { q ->
                mapOf(
                    "type" to q.type.code,
                    "content" to q.content,
                    "options" to q.options,
                    "answer" to q.answer,
                    "explanation" to q.explanation,
                    "difficulty" to q.difficulty,
                    "knowledgePoint" to q.knowledgePoint,
                    "imageUrl" to q.imageUrl
                )
            }
        )
        return gson.toJson(exportData)
    }

    /** 从 JSON 字符串导入题库和题目，返回新题库 ID；失败返回 -1 */
    fun importBankFromJson(json: String): Long {
        return try {
            val mapType = object : TypeToken<Map<String, Any>>() {}.type
            val data: Map<String, Any> = gson.fromJson(json, mapType)

            val format = data["format"] as? String
            if (format != "SmartQuizBank") return -1

            @Suppress("UNCHECKED_CAST")
            val bankMap = data["bank"] as? Map<String, Any> ?: return -1
            @Suppress("UNCHECKED_CAST")
            val questionsList = data["questions"] as? List<Map<String, Any>> ?: return -1

            val bank = QuestionBank(
                name = bankMap["name"] as? String ?: "导入的题库",
                subject = bankMap["subject"] as? String ?: "",
                description = bankMap["description"] as? String ?: "",
                sourceText = bankMap["sourceText"] as? String ?: "",
                questionCount = (bankMap["questionCount"] as? Double)?.toInt() ?: questionsList.size
            )

            val questions = questionsList.map { qMap ->
                @Suppress("UNCHECKED_CAST")
                val options = (qMap["options"] as? List<String>) ?: emptyList()
                Question(
                    bankId = 0L, // 占位，事务内填充
                    type = QuestionType.fromCode((qMap["type"] as? Double)?.toInt() ?: 1),
                    content = qMap["content"] as? String ?: "",
                    options = options,
                    answer = qMap["answer"] as? String ?: "",
                    explanation = qMap["explanation"] as? String ?: "",
                    difficulty = (qMap["difficulty"] as? Double)?.toInt() ?: 1,
                    knowledgePoint = qMap["knowledgePoint"] as? String ?: "",
                    imageUrl = qMap["imageUrl"] as? String ?: ""
                )
            }

            // 将 insertBank + insertQuestions + updateBankQuestionCount 包在同一事务中，
            // 避免中途崩溃产生空题库或题目数量不匹配
            val db = writableDatabase
            db.beginTransaction()
            try {
                val bankValues = ContentValues().apply {
                    put("name", bank.name)
                    put("subject", bank.subject)
                    put("description", bank.description)
                    put("source_text", bank.sourceText)
                    put("question_count", bank.questionCount)
                    put("created_at", bank.createdAt)
                }
                val bankId = db.insert(TABLE_BANKS, null, bankValues)
                if (bankId < 0) return -1

                questions.forEach { q ->
                    val qValues = ContentValues().apply {
                        put("bank_id", bankId)
                        put("type", q.type.code)
                        put("content", q.content)
                        put("options", gson.toJson(q.options))
                        put("answer", q.answer)
                        put("explanation", q.explanation)
                        put("difficulty", q.difficulty)
                        put("knowledge_point", q.knowledgePoint)
                        put("image_url", q.imageUrl)
                        put("created_at", q.createdAt)
                    }
                    db.insert(TABLE_QUESTIONS, null, qValues)
                }

                val countValues = ContentValues().apply { put("question_count", questions.size) }
                db.update(TABLE_BANKS, countValues, "id = ?", arrayOf(bankId.toString()))

                db.setTransactionSuccessful()
                bankId
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            -1
        }
    }

    // ==================== 数据清空 ====================

    /** 清空所有数据（管理员账号存于SharedPreferences，不受影响） */
    fun clearAllData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("assigned_exam_users", null, null)
            db.delete(TABLE_ASSIGNED_EXAM, null, null)
            db.delete(TABLE_ANSWER_RECORDS, null, null)
            db.delete(TABLE_EXAM_RECORDS, null, null)
            db.delete(TABLE_WRONG_QUESTIONS, null, null)
            db.delete(TABLE_FEEDBACK, null, null)
            db.delete(TABLE_STUDENT_MESSAGES, null, null)
            db.delete(TABLE_BUZZER_ANSWERS, null, null)
            db.delete(TABLE_BUZZER_STATE, null, null)
            db.delete(TABLE_USERS, null, null)
            db.delete(TABLE_QUESTIONS, null, null)
            db.delete(TABLE_BANKS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 清空学生数据（保留题库，管理员账号存于SharedPreferences，不受影响） */
    fun clearStudentData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("assigned_exam_users", null, null)
            db.delete(TABLE_ANSWER_RECORDS, null, null)
            db.delete(TABLE_EXAM_RECORDS, null, null)
            db.delete(TABLE_WRONG_QUESTIONS, null, null)
            db.delete(TABLE_FEEDBACK, null, null)
            db.delete(TABLE_STUDENT_MESSAGES, null, null)
            db.delete(TABLE_USERS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 清空考试和报表数据 */
    fun clearExamAndReportData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("assigned_exam_users", null, null)
            db.delete(TABLE_ASSIGNED_EXAM, null, null)
            db.delete(TABLE_ANSWER_RECORDS, null, null)
            db.delete(TABLE_EXAM_RECORDS, null, null)
            db.delete(TABLE_WRONG_QUESTIONS, null, null)
            db.delete(TABLE_FEEDBACK, null, null)
            db.delete(TABLE_BUZZER_ANSWERS, null, null)
            db.delete(TABLE_BUZZER_STATE, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 清空题库数据（同步删除依赖的考试记录） */
    fun clearBankData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("assigned_exam_users", null, null)
            db.delete(TABLE_ASSIGNED_EXAM, null, null)
            db.delete(TABLE_ANSWER_RECORDS, null, null)
            db.delete(TABLE_EXAM_RECORDS, null, null)
            db.delete(TABLE_WRONG_QUESTIONS, null, null)
            db.delete(TABLE_FEEDBACK, null, null)
            db.delete(TABLE_QUESTIONS, null, null)
            db.delete(TABLE_BANKS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ==================== 数据导出 / 导入 ====================

    /** 将所有表数据序列化为 JSON Map，返回 JSON 字符串 */
    fun exportToJson(): String {
        val db = readableDatabase
        val tables = listOf(
            TABLE_BANKS, TABLE_QUESTIONS, TABLE_USERS,
            TABLE_EXAM_RECORDS, TABLE_ANSWER_RECORDS, TABLE_WRONG_QUESTIONS,
            TABLE_FEEDBACK, TABLE_ASSIGNED_EXAM, TABLE_STUDENT_MESSAGES,
            TABLE_BUZZER_STATE, TABLE_BUZZER_ANSWERS, "assigned_exam_users"
        )
        val backup = mutableMapOf<String, List<Map<String, Any?>>>()
        for (table in tables) {
            val rows = mutableListOf<Map<String, Any?>>()
            try {
                val cursor = db.rawQuery("SELECT * FROM $table", null)
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val row = mutableMapOf<String, Any?>()
                        for (i in 0 until c.columnCount) {
                            row[c.getColumnName(i)] = when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                android.database.Cursor.FIELD_TYPE_FLOAT   -> c.getDouble(i)
                                android.database.Cursor.FIELD_TYPE_NULL    -> null
                                else                                        -> c.getString(i)
                            }
                        }
                        rows.add(row)
                    }
                }
            } catch (_: Exception) { /* 表不存在则跳过 */ }
            backup[table] = rows
        }
        return gson.toJson(mapOf("version" to DATABASE_VERSION, "tables" to backup))
    }

    /** 判断数据库是否为空（题库和用户表均无数据） */
    fun isDatabaseEmpty(): Boolean {
        val db = readableDatabase
        val bankCount = db.rawQuery("SELECT COUNT(*) FROM $TABLE_BANKS", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        val userCount = db.rawQuery("SELECT COUNT(*) FROM $TABLE_USERS", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        return bankCount == 0 && userCount == 0
    }

    /**
     * 插入演示数据：
     * - 2 个题库，每库 22 道题（单选/多选/判断/填空覆盖所有题型）
     * - 5 名学生账号（密码均为 123456）
     * - 2 场已发布考试（含参与记录与答题详情）
     * - 多轮练习记录（每次 ≥ 20 题，触发报表统计阈值）
     * - 错题、消息、反馈样本
     */
    fun insertDemoData() {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val day = 86400_000L
        db.beginTransaction()
        try {
            // ════════════════════════════════════════════════════════════
            // 题库 1：初中数学综合练习（22 题）
            // ════════════════════════════════════════════════════════════
            val bank1Id = db.insert(TABLE_BANKS, null, ContentValues().apply {
                put("name", "初中数学综合练习")
                put("subject", "数学")
                put("description", "涵盖有理数运算、一元一次方程、平面几何、函数基础等初中数学核心知识点，适合初一至初三学生阶段性测评。")
                put("source_text", "")
                put("question_count", 22)
                put("created_at", now - 14 * day)
            })

            // 辅助函数：插入一道题并返回其 ID
            fun q(bankId: Long, type: Int, content: String, opts: String, ans: String, exp: String, diff: Int, kp: String, ts: Long): Long =
                db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                    put("bank_id", bankId); put("type", type); put("content", content)
                    put("options", opts); put("answer", ans); put("explanation", exp)
                    put("difficulty", diff); put("knowledge_point", kp)
                    put("image_url", ""); put("created_at", ts)
                })

            val t1 = now - 14 * day
            // 单选 ×10
            val q1  = q(bank1Id,1,"计算：(-3)×(-4) = ?","""["6","12","-12","-6"]""","B","负负得正，3×4=12。",1,"有理数运算",t1)
            val q2  = q(bank1Id,1,"方程 2x + 6 = 0 的解是？","""["x=3","x=-3","x=6","x=-6"]""","B","2x=-6，x=-3。",1,"一元一次方程",t1)
            val q3  = q(bank1Id,1,"边长为 6 cm 的正方形，其周长是？","""["24cm","36cm","12cm","18cm"]""","A","周长=4×6=24 cm。",1,"平面图形",t1)
            val q4  = q(bank1Id,1,"|-7| 的值是？","""["7","-7","0","49"]""","A","绝对值是非负数，|-7|=7。",1,"有理数",t1)
            val q5  = q(bank1Id,1,"一次函数 y=2x+1 的图像经过哪个点？","""["(0,2)","(1,3)","(-1,0)","(2,5)"]""","B","x=1时 y=2×1+1=3，经过(1,3)。",2,"一次函数",t1)
            val q6  = q(bank1Id,1,"下列数中，最小的是？","""["-3","-1","0","2"]""","A","数轴上越靠左越小，-3最小。",1,"有理数大小比较",t1)
            val q7  = q(bank1Id,1,"整式 3x²y 的次数是？","""["2","3","5","1"]""","B","各变量指数之和：2+1=3。",2,"整式",t1)
            val q8  = q(bank1Id,1,"三角形内角和为？","""["90°","180°","270°","360°"]""","B","三角形三个内角之和恒为180°。",1,"三角形",t1)
            val q9  = q(bank1Id,1,"若 a:b = 2:3，b:c = 3:4，则 a:c = ?","""["1:2","2:4","2:3","1:4"]""","A","a:c = 2:4 = 1:2。",2,"比例",t1)
            val q10 = q(bank1Id,1,"下列运算正确的是？","""["3x+2y=5xy","3x²+2x²=5x²","3x+2x²=5x³","(3x)²=6x²"]""","B","同类项合并：3x²+2x²=5x²。",2,"整式运算",t1)
            // 多选 ×4
            val q11 = q(bank1Id,2,"下列哪些数是负数？（多选）","""["-5","0","3.14","-0.01"]""","AD","-5 和 -0.01 均小于0；0是非负非正数；3.14是正数。",1,"有理数",t1)
            val q12 = q(bank1Id,2,"以下哪些是一元一次方程？（多选）","""["2x+1=5","x²=4","3x-y=0","x/2=3"]""","AD","含一个未知数且未知数最高次数为1的方程。",2,"一元一次方程",t1)
            val q13 = q(bank1Id,2,"以下哪些角是锐角？（多选）","""["30°","90°","60°","120°"]""","AC","锐角：0°<角<90°，30°和60°满足。",1,"角的分类",t1)
            val q14 = q(bank1Id,2,"下列说法正确的是？（多选）","""["正数的绝对值是本身","零的绝对值是0","负数的绝对值是其相反数","绝对值可以是负数"]""","ABC","绝对值定义：|a|≥0，负数绝对值取其相反数（正数）。",2,"绝对值",t1)
            // 判断 ×4
            val q15 = q(bank1Id,3,"直角三角形三条边满足勾股定理 a²+b²=c²，其中 c 是斜边。","""["正确","错误"]""","A","勾股定理是直角三角形的基本定理，命题正确。",1,"勾股定理",t1)
            val q16 = q(bank1Id,3,"两个负数相加的结果一定是负数。","""["正确","错误"]""","A","两个负数相加，结果仍为负数，命题正确。",1,"有理数运算",t1)
            val q17 = q(bank1Id,3,"等腰三角形的两个底角一定相等。","""["正确","错误"]""","A","等腰三角形两腰相等，其底角也相等，命题正确。",1,"三角形",t1)
            val q18 = q(bank1Id,3,"所有的整数都是有理数。","""["正确","错误"]""","A","整数可表示为 n/1，属于有理数，命题正确。",1,"数的分类",t1)
            // 填空 ×4
            val q19 = q(bank1Id,4,"一个正方形的边长为 5 cm，它的面积是 ____ cm²。","""[]""","25","正方形面积=边长²=5²=25。",1,"图形面积",t1)
            val q20 = q(bank1Id,4,"解方程：3x - 9 = 0，x = ____。","""[]""","3","3x=9，x=3。",1,"一元一次方程",t1)
            val q21 = q(bank1Id,4,"数轴上，-3 和 2 之间的距离是 ____。","""[]""","5","距离=|(-3)-2|=|-5|=5。",1,"数轴",t1)
            val q22 = q(bank1Id,4,"若一个角的补角是 110°，则该角为 ____°。","""[]""","70","补角之和为180°，180°-110°=70°。",1,"角的计算",t1)

            val bank1Questions = listOf(q1,q2,q3,q4,q5,q6,q7,q8,q9,q10,q11,q12,q13,q14,q15,q16,q17,q18,q19,q20,q21,q22)

            // ════════════════════════════════════════════════════════════
            // 题库 2：Python 编程入门（22 题）
            // ════════════════════════════════════════════════════════════
            val bank2Id = db.insert(TABLE_BANKS, null, ContentValues().apply {
                put("name", "Python 编程入门")
                put("subject", "信息技术")
                put("description", "Python 基础语法、数据类型、条件判断、循环与函数，面向零基础编程学习者，兼顾信息学竞赛基础。")
                put("source_text", "")
                put("question_count", 22)
                put("created_at", now - 7 * day)
            })

            val t2 = now - 7 * day
            // 单选 ×10
            val p1  = q(bank2Id,1,"在 Python 中，哪个函数用于输出内容到控制台？","""["input()","print()","output()","show()"]""","B","print() 是 Python 内置的标准输出函数。",1,"基础函数",t2)
            val p2  = q(bank2Id,1,"Python 中列表（list）使用哪种括号？","""["()","{}","[]","<>"]""","C","列表使用方括号 []，如 [1, 2, 3]。",1,"数据类型",t2)
            val p3  = q(bank2Id,1,"以下哪个是 Python 的注释符号？","""["//","#","/*","--"]""","B","Python 单行注释以 # 开头。",1,"语法基础",t2)
            val p4  = q(bank2Id,1,"len('hello') 的返回值是？","""["4","5","6","hello"]""","B","字符串 'hello' 有 5 个字符。",1,"字符串操作",t2)
            val p5  = q(bank2Id,1,"Python 中整数除法运算符是？","""["/","//","%","**"]""","B","// 表示整除，结果取整数部分。",2,"运算符",t2)
            val p6  = q(bank2Id,1,"以下哪个关键字用于定义函数？","""["function","def","fun","define"]""","B","Python 使用 def 关键字定义函数。",1,"函数",t2)
            val p7  = q(bank2Id,1,"range(1, 5) 生成的序列是？","""["1,2,3,4,5","1,2,3,4","0,1,2,3,4","1,2,3"]""","B","range(start, stop) 包含 start 不含 stop。",1,"循环",t2)
            val p8  = q(bank2Id,1,"Python 中字典（dict）的键值对使用什么分隔？","""["=","->",":","=>"]""","C","字典格式为 {key: value}，用冒号分隔键和值。",1,"数据类型",t2)
            val p9  = q(bank2Id,1,"以下哪个表达式的结果为 True？","""["3 > 5","'a' == 'b'","len([]) == 0","10 % 3 == 0"]""","C","空列表长度为0，len([])==0 为 True。",2,"布尔运算",t2)
            val p10 = q(bank2Id,1,"Python 中用于捕获异常的语句是？","""["try...catch","try...except","error...handle","catch...throw"]""","B","Python 使用 try...except 进行异常处理。",2,"异常处理",t2)
            // 多选 ×4
            val p11 = q(bank2Id,2,"以下属于 Python 合法变量名的是？（多选）","""["my_var","2name","_count","for"]""","AC","变量名不能以数字开头，不能使用关键字。",2,"变量命名",t2)
            val p12 = q(bank2Id,2,"以下属于 Python 基本数据类型的是？（多选）","""["int","list","str","float"]""","ACD","int、str、float 是基本类型；list 是容器类型。",1,"数据类型",t2)
            val p13 = q(bank2Id,2,"以下哪些是 Python 的比较运算符？（多选）","""["==","!=",">=","=>"]""","ABC","Python 中没有 => 运算符，应使用 >=。",1,"运算符",t2)
            val p14 = q(bank2Id,2,"以下哪些关键字与条件判断有关？（多选）","""["if","elif","else","for"]""","ABC","if/elif/else 用于条件分支；for 用于循环。",1,"条件语句",t2)
            // 判断 ×4
            val p15 = q(bank2Id,3,"Python 是一种解释型编程语言。","""["正确","错误"]""","A","Python 代码由解释器逐行执行，属于解释型语言。",1,"语言特性",t2)
            val p16 = q(bank2Id,3,"Python 中 for 循环只能配合 range() 使用。","""["正确","错误"]""","B","for 可遍历任意可迭代对象，如列表、字符串等。",2,"循环结构",t2)
            val p17 = q(bank2Id,3,"Python 中列表是可变的（mutable）数据结构。","""["正确","错误"]""","A","列表元素可以增删改，是可变类型。",1,"数据类型",t2)
            val p18 = q(bank2Id,3,"Python 中 == 和 is 完全等价。","""["正确","错误"]""","B","== 比较值是否相等；is 比较是否为同一对象。",2,"运算符",t2)
            // 填空 ×4
            val p19 = q(bank2Id,4,"Python 中定义函数使用关键字 ____。","""[]""","def","使用 def 关键字定义函数，如 def my_func():。",1,"函数定义",t2)
            val p20 = q(bank2Id,4,"Python 中获取列表长度的函数是 ____()。","""[]""","len","len() 可返回列表、字符串、元组等的长度。",1,"内置函数",t2)
            val p21 = q(bank2Id,4,"print(2 ** 3) 的输出结果是 ____。","""[]""","8","** 是幂运算符，2³=8。",1,"运算符",t2)
            val p22 = q(bank2Id,4,"Python 中布尔类型的两个值是 True 和 ____。","""[]""","False","布尔类型只有 True 和 False 两个值（注意大写）。",1,"数据类型",t2)

            val bank2Questions = listOf(p1,p2,p3,p4,p5,p6,p7,p8,p9,p10,p11,p12,p13,p14,p15,p16,p17,p18,p19,p20,p21,p22)

            // ════════════════════════════════════════════════════════════
            // 学生账号（5 名，密码均为 123456）
            // ════════════════════════════════════════════════════════════
            val pwd = hashPassword("123456")
            fun user(uname: String, nick: String, real: String, cls: String, grp: String, ts: Long) =
                db.insert(TABLE_USERS, null, ContentValues().apply {
                    put("username", uname); put("password", pwd); put("nickname", nick)
                    put("real_name", real); put("school", "实验中学"); put("class_name", cls)
                    put("group_name", grp); put("phone", ""); put("email", ""); put("auth_token", "")
                    put("created_at", ts)
                })

            val u1 = user("demo_zhangsan", "张三", "张三", "初二(3)班", "A组", now - 10 * day)
            val u2 = user("demo_lisi",     "李四", "李四", "初二(3)班", "B组", now - 10 * day)
            val u3 = user("demo_wangwu",   "王五", "王五", "初二(4)班", "A组", now - 10 * day)
            val u4 = user("demo_zhaoliu",  "赵六", "赵六", "初二(4)班", "B组", now - 10 * day)
            val u5 = user("demo_sunqi",    "孙七", "孙七", "初三(1)班", "A组", now - 10 * day)

            // ════════════════════════════════════════════════════════════
            // 辅助：批量插入答题记录，返回正确题数
            // correctMask: true=答对，false=答错
            // ════════════════════════════════════════════════════════════
            fun insertAnswers(examId: Long, userId: Long, questions: List<Long>, correctMask: List<Boolean>, ts: Long) {
                questions.forEachIndexed { i, qid ->
                    db.insert(TABLE_ANSWER_RECORDS, null, ContentValues().apply {
                        put("exam_record_id", examId); put("user_id", userId)
                        put("question_id", qid)
                        put("user_answer", if (correctMask[i]) "A" else "D") // 简化：对=A 错=D
                        put("is_correct", if (correctMask[i]) 1 else 0)
                        put("created_at", ts)
                    })
                }
            }

            // ════════════════════════════════════════════════════════════
            // 练习记录（mode=practice，各学生多轮，每次 20 题）
            // ════════════════════════════════════════════════════════════
            // 练习用前 20 题
            val b1q20 = bank1Questions.take(20)
            val b2q20 = bank2Questions.take(20)

            fun practiceRecord(uid: Long, bid: Long, bname: String, total: Int, correct: Int, timeSec: Int, ts: Long): Long =
                db.insert(TABLE_EXAM_RECORDS, null, ContentValues().apply {
                    put("user_id", uid); put("bank_id", bid); put("bank_name", bname)
                    put("total_questions", total); put("correct_count", correct)
                    put("score", correct * 100.0 / total)
                    put("time_cost_seconds", timeSec); put("mode", "practice")
                    put("assigned_exam_id", 0); put("created_at", ts)
                })

            // 正确率 mask 生成（前 n 个为 true）
            fun mask(total: Int, correct: Int) = List(total) { it < correct }

            // 张三：数学 88%（20题×2轮），Python 85%
            val pr1a = practiceRecord(u1, bank1Id, "初中数学综合练习", 20, 18, 1080, now - 8 * day)
            insertAnswers(pr1a, u1, b1q20, mask(20,18), now - 8 * day)
            val pr1b = practiceRecord(u1, bank1Id, "初中数学综合练习", 20, 17, 960, now - 5 * day)
            insertAnswers(pr1b, u1, b1q20, mask(20,17), now - 5 * day)
            val pr1c = practiceRecord(u1, bank2Id, "Python 编程入门", 20, 17, 870, now - 3 * day)
            insertAnswers(pr1c, u1, b2q20, mask(20,17), now - 3 * day)

            // 李四：数学 70%，Python 65%
            val pr2a = practiceRecord(u2, bank1Id, "初中数学综合练习", 20, 14, 1320, now - 7 * day)
            insertAnswers(pr2a, u2, b1q20, mask(20,14), now - 7 * day)
            val pr2b = practiceRecord(u2, bank2Id, "Python 编程入门", 20, 13, 1140, now - 4 * day)
            insertAnswers(pr2b, u2, b2q20, mask(20,13), now - 4 * day)

            // 王五：数学 95%，Python 90%
            val pr3a = practiceRecord(u3, bank1Id, "初中数学综合练习", 20, 19, 720, now - 6 * day)
            insertAnswers(pr3a, u3, b1q20, mask(20,19), now - 6 * day)
            val pr3b = practiceRecord(u3, bank2Id, "Python 编程入门", 20, 18, 660, now - 2 * day)
            insertAnswers(pr3b, u3, b2q20, mask(20,18), now - 2 * day)

            // 赵六：数学 75%，Python 80%
            val pr4a = practiceRecord(u4, bank1Id, "初中数学综合练习", 20, 15, 1200, now - 6 * day)
            insertAnswers(pr4a, u4, b1q20, mask(20,15), now - 6 * day)
            val pr4b = practiceRecord(u4, bank2Id, "Python 编程入门", 20, 16, 900, now - 3 * day)
            insertAnswers(pr4b, u4, b2q20, mask(20,16), now - 3 * day)

            // 孙七：数学 60%，Python 70%
            val pr5a = practiceRecord(u5, bank1Id, "初中数学综合练习", 20, 12, 1500, now - 5 * day)
            insertAnswers(pr5a, u5, b1q20, mask(20,12), now - 5 * day)
            val pr5b = practiceRecord(u5, bank2Id, "Python 编程入门", 20, 14, 1200, now - 2 * day)
            insertAnswers(pr5b, u5, b2q20, mask(20,14), now - 2 * day)

            // ════════════════════════════════════════════════════════════
            // 已发布考试（assigned_exam）
            // ════════════════════════════════════════════════════════════
            val b1QIds = bank1Questions.take(20).joinToString(",", "[", "]")
            val b2QIds = bank2Questions.take(20).joinToString(",", "[", "]")

            // 考试 1：期中数学综合测验（已结束，7天前）
            val exam1Id = db.insert(TABLE_ASSIGNED_EXAM, null, ContentValues().apply {
                put("bank_id", bank1Id); put("bank_name", "初中数学综合练习")
                put("limit_count", 20); put("time_minutes", 60); put("max_diff", 5)
                put("shuffle_opts", 1); put("shuffle_q", 0)
                put("question_types", "1,2,3,4"); put("type_counts", "10,4,4,2")
                put("type_scores", "4,6,3,5"); put("submit_before_end_minutes", 0)
                put("scheduled_start_time", now - 7 * day)
                put("question_ids", b1QIds)
                put("allow_download", 0); put("exam_mode", "online")
                put("auto_submit_at", now - 6 * day)
                put("created_at", now - 8 * day)
            })

            // 考试 2：Python 编程基础测验（已结束，3天前）
            val exam2Id = db.insert(TABLE_ASSIGNED_EXAM, null, ContentValues().apply {
                put("bank_id", bank2Id); put("bank_name", "Python 编程入门")
                put("limit_count", 20); put("time_minutes", 45); put("max_diff", 5)
                put("shuffle_opts", 1); put("shuffle_q", 0)
                put("question_types", "1,2,3,4"); put("type_counts", "10,4,4,2")
                put("type_scores", "4,6,3,5"); put("submit_before_end_minutes", 0)
                put("scheduled_start_time", now - 3 * day)
                put("question_ids", b2QIds)
                put("allow_download", 0); put("exam_mode", "online")
                put("auto_submit_at", now - 2 * day - 12 * 3600_000L)
                put("created_at", now - 4 * day)
            })

            // 辅助：插入 assigned_exam_users + exam_record + answer_records
            fun assignedExamParticipation(
                uid: Long, examId: Long, bankId: Long, bankName: String,
                questions: List<Long>, correct: Int, timeSec: Int, ts: Long
            ) {
                val total = questions.size
                val score = correct * 100.0 / total
                // assigned_exam_users
                db.insertWithOnConflict("assigned_exam_users", null, ContentValues().apply {
                    put("assigned_exam_id", examId); put("user_id", uid)
                    put("is_completed", 1); put("last_active_at", ts)
                    put("answered_count", total); put("force_submit", 0)
                }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
                // exam_record
                val erId = db.insert(TABLE_EXAM_RECORDS, null, ContentValues().apply {
                    put("user_id", uid); put("bank_id", bankId); put("bank_name", bankName)
                    put("total_questions", total); put("correct_count", correct)
                    put("score", score); put("time_cost_seconds", timeSec)
                    put("mode", "exam"); put("assigned_exam_id", examId); put("created_at", ts)
                })
                // answer_records
                insertAnswers(erId, uid, questions, mask(total, correct), ts)
            }

            // 考试 1（数学）参与情况
            assignedExamParticipation(u1, exam1Id, bank1Id, "初中数学综合练习", b1q20, 17, 2640, now - 7 * day)  // 张三 85%
            assignedExamParticipation(u2, exam1Id, bank1Id, "初中数学综合练习", b1q20, 13, 3300, now - 7 * day)  // 李四 65%
            assignedExamParticipation(u3, exam1Id, bank1Id, "初中数学综合练习", b1q20, 19, 2160, now - 7 * day)  // 王五 95%
            assignedExamParticipation(u4, exam1Id, bank1Id, "初中数学综合练习", b1q20, 15, 2940, now - 7 * day)  // 赵六 75%
            assignedExamParticipation(u5, exam1Id, bank1Id, "初中数学综合练习", b1q20, 11, 3480, now - 7 * day)  // 孙七 55%

            // 考试 2（Python）参与情况
            assignedExamParticipation(u1, exam2Id, bank2Id, "Python 编程入门", b2q20, 16, 1980, now - 3 * day)  // 张三 80%
            assignedExamParticipation(u2, exam2Id, bank2Id, "Python 编程入门", b2q20, 14, 2460, now - 3 * day)  // 李四 70%
            assignedExamParticipation(u3, exam2Id, bank2Id, "Python 编程入门", b2q20, 18, 1620, now - 3 * day)  // 王五 90%
            assignedExamParticipation(u4, exam2Id, bank2Id, "Python 编程入门", b2q20, 17, 1800, now - 3 * day)  // 赵六 85%
            assignedExamParticipation(u5, exam2Id, bank2Id, "Python 编程入门", b2q20, 13, 2700, now - 3 * day)  // 孙七 65%

            // ════════════════════════════════════════════════════════════
            // 错题记录
            // ════════════════════════════════════════════════════════════
            fun wrongQ(uid: Long, qid: Long, bid: Long, wc: Int, ts: Long) {
                db.insertWithOnConflict(TABLE_WRONG_QUESTIONS, null, ContentValues().apply {
                    put("user_id", uid); put("question_id", qid); put("bank_id", bid)
                    put("wrong_count", wc); put("correct_count", 0)
                    put("last_wrong_at", ts); put("is_resolved", 0)
                }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            }
            wrongQ(u2, q5,  bank1Id, 2, now - 5 * day)
            wrongQ(u2, q7,  bank1Id, 3, now - 7 * day)
            wrongQ(u2, q10, bank1Id, 1, now - 7 * day)
            wrongQ(u5, q5,  bank1Id, 2, now - 5 * day)
            wrongQ(u5, q9,  bank1Id, 1, now - 7 * day)
            wrongQ(u5, q12, bank1Id, 2, now - 5 * day)
            wrongQ(u2, p5,  bank2Id, 2, now - 4 * day)
            wrongQ(u2, p9,  bank2Id, 1, now - 3 * day)
            wrongQ(u5, p10, bank2Id, 2, now - 2 * day)
            wrongQ(u5, p18, bank2Id, 1, now - 2 * day)

            // ════════════════════════════════════════════════════════════
            // 学生消息
            // ════════════════════════════════════════════════════════════
            fun msg(uid: Long, content: String, ts: Long) = db.insert(TABLE_STUDENT_MESSAGES, null, ContentValues().apply {
                put("user_id", uid); put("sender_name", "老师"); put("content", content)
                put("is_read", 0); put("created_at", ts)
            })
            msg(u1, "张三同学，期中数学测验成绩优秀，Python 测验也表现不错，继续保持！", now - 7 * day + 3600_000L)
            msg(u2, "李四同学，整式运算和比例部分有所欠缺，建议重做相关错题，期待你的进步。", now - 7 * day + 3600_000L)
            msg(u3, "王五同学，两次测验成绩均名列前茅，建议挑战更高难度的题库。", now - 3 * day + 3600_000L)
            msg(u4, "赵六同学，Python 测验表现良好，数学方面的多选题还需加强。", now - 3 * day + 3600_000L)
            msg(u5, "孙七同学，两次测验成绩有提升空间，课后请多刷错题本，有问题随时问老师。", now - 3 * day + 3600_000L)

            // ════════════════════════════════════════════════════════════
            // 题目反馈
            // ════════════════════════════════════════════════════════════
            db.insert(TABLE_FEEDBACK, null, ContentValues().apply {
                put("question_id", q7); put("bank_id", bank1Id); put("user_id", u2)
                put("content", "整式次数的计算方式能否再解释一下？我对'各变量指数之和'不太理解。")
                put("is_resolved", 0); put("created_at", now - 6 * day)
            })
            db.insert(TABLE_FEEDBACK, null, ContentValues().apply {
                put("question_id", p9); put("bank_id", bank2Id); put("user_id", u5)
                put("content", "这道题的选项 D 为什么是错的？10%3 不是等于 1 吗？")
                put("is_resolved", 1); put("created_at", now - 2 * day)
            })

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 插入最小演示数据（用于首次启动时无数据的场景） */
    fun insertMinimalDemoData() {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            // ── 题库 1：初中数学 ──────────────────────────────────────
            val bank1Id = db.insert(TABLE_BANKS, null, ContentValues().apply {
                put("name", "初中数学基础练习")
                put("subject", "数学")
                put("description", "涵盖整数运算、方程、几何基础等初中数学核心知识点，适合初一至初三学生课后练习。")
                put("source_text", "")
                put("question_count", 6)
                put("created_at", now - 7 * 86400_000L)
            })

            // 单选题 × 2
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank1Id)
                put("type", 1)
                put("content", "计算：(-3) × (-4) = ?")
                put("options", """["6","12","-12","-6"]""")
                put("answer", "B")
                put("explanation", "负数乘负数得正数，3×4=12，所以(-3)×(-4)=12。")
                put("difficulty", 1)
                put("knowledge_point", "整数运算")
                put("image_url", "")
                put("created_at", now - 7 * 86400_000L)
            })
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank1Id)
                put("type", 1)
                put("content", "方程 2x + 6 = 0 的解是？")
                put("options", """["x=3","x=-3","x=6","x=-6"]""")
                put("answer", "B")
                put("explanation", "2x = -6，x = -3。")
                put("difficulty", 1)
                put("knowledge_point", "一元一次方程")
                put("image_url", "")
                put("created_at", now - 7 * 86400_000L)
            })

            // 多选题 × 1
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank1Id)
                put("type", 2)
                put("content", "下列哪些数是负数？（多选）")
                put("options", """["-5","0","3.14","-0.01"]""")
                put("answer", "AD")
                put("explanation", "-5 和 -0.01 均小于 0，是负数；0 是非负非正；3.14 是正数。")
                put("difficulty", 1)
                put("knowledge_point", "有理数")
                put("image_url", "")
                put("created_at", now - 7 * 86400_000L)
            })

            // 判断题 × 2
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank1Id)
                put("type", 3)
                put("content", "直角三角形三条边满足勾股定理 a² + b² = c²，其中 c 是斜边。")
                put("options", """["正确","错误"]""")
                put("answer", "A")
                put("explanation", "勾股定理是直角三角形的基本定理，命题正确。")
                put("difficulty", 1)
                put("knowledge_point", "勾股定理")
                put("image_url", "")
                put("created_at", now - 7 * 86400_000L)
            })
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank1Id)
                put("type", 3)
                put("content", "两个负数相加的结果一定是负数。")
                put("options", """["正确","错误"]""")
                put("answer", "A")
                put("explanation", "两个负数均小于 0，相加结果仍小于 0，命题正确。")
                put("difficulty", 1)
                put("knowledge_point", "整数运算")
                put("image_url", "")
                put("created_at", now - 7 * 86400_000L)
            })

            // 填空题 × 1
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank1Id)
                put("type", 4)
                put("content", "一个正方形的边长为 5 cm，它的面积是 ____ cm²。")
                put("options", """[]""")
                put("answer", "25")
                put("explanation", "正方形面积 = 边长²= 5² = 25 cm²。")
                put("difficulty", 1)
                put("knowledge_point", "图形面积")
                put("image_url", "")
                put("created_at", now - 7 * 86400_000L)
            })

            // ── 题库 2：Python 编程入门 ────────────────────────────────
            val bank2Id = db.insert(TABLE_BANKS, null, ContentValues().apply {
                put("name", "Python 编程入门")
                put("subject", "信息技术")
                put("description", "Python 基础语法、数据类型、循环与函数，面向零基础编程学习者。")
                put("source_text", "")
                put("question_count", 6)
                put("created_at", now - 3 * 86400_000L)
            })

            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank2Id)
                put("type", 1)
                put("content", "在 Python 中，以下哪个函数用于输出内容到控制台？")
                put("options", """["input()","print()","output()","show()"]""")
                put("answer", "B")
                put("explanation", "print() 是 Python 内置的标准输出函数。")
                put("difficulty", 1)
                put("knowledge_point", "基础函数")
                put("image_url", "")
                put("created_at", now - 3 * 86400_000L)
            })
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank2Id)
                put("type", 1)
                put("content", "Python 中列表（list）使用哪种括号？")
                put("options", """["()","{}","[]","<>"]""")
                put("answer", "C")
                put("explanation", "列表使用方括号 []，如 [1, 2, 3]。")
                put("difficulty", 1)
                put("knowledge_point", "数据类型")
                put("image_url", "")
                put("created_at", now - 3 * 86400_000L)
            })
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank2Id)
                put("type", 2)
                put("content", "以下属于 Python 合法变量名的是？（多选）")
                put("options", """["my_var","2name","_count","for"]""")
                put("answer", "AC")
                put("explanation", "变量名不能以数字开头（2name不合法），不能是关键字（for不合法）。")
                put("difficulty", 2)
                put("knowledge_point", "变量命名")
                put("image_url", "")
                put("created_at", now - 3 * 86400_000L)
            })
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank2Id)
                put("type", 3)
                put("content", "Python 是一种解释型编程语言。")
                put("options", """["正确","错误"]""")
                put("answer", "A")
                put("explanation", "Python 代码由解释器逐行执行，属于解释型语言。")
                put("difficulty", 1)
                put("knowledge_point", "语言特性")
                put("image_url", "")
                put("created_at", now - 3 * 86400_000L)
            })
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank2Id)
                put("type", 3)
                put("content", "Python 中 for 循环必须配合 range() 函数使用。")
                put("options", """["正确","错误"]""")
                put("answer", "B")
                put("explanation", "for 循环可以遍历任意可迭代对象，如列表、字符串等，不限于 range()。")
                put("difficulty", 2)
                put("knowledge_point", "循环结构")
                put("image_url", "")
                put("created_at", now - 3 * 86400_000L)
            })
            db.insert(TABLE_QUESTIONS, null, ContentValues().apply {
                put("bank_id", bank2Id)
                put("type", 4)
                put("content", "Python 中定义函数使用关键字 ____。")
                put("options", """[]""")
                put("answer", "def")
                put("explanation", "使用 def 关键字定义函数，如 def my_func():。")
                put("difficulty", 1)
                put("knowledge_point", "函数定义")
                put("image_url", "")
                put("created_at", now - 3 * 86400_000L)
            })

            // ── 学生账号 ──────────────────────────────────────────────
            val pwd = hashPassword("123456")
            val user1Id = db.insert(TABLE_USERS, null, ContentValues().apply {
                put("username", "demo_zhangsan")
                put("password", pwd)
                put("nickname", "张三")
                put("real_name", "张三")
                put("school", "实验中学")
                put("class_name", "初二(3)班")
                put("group_name", "A组")
                put("phone", "")
                put("email", "")
                put("auth_token", "")
                put("created_at", now - 5 * 86400_000L)
            })
            val user2Id = db.insert(TABLE_USERS, null, ContentValues().apply {
                put("username", "demo_lisi")
                put("password", pwd)
                put("nickname", "李四")
                put("real_name", "李四")
                put("school", "实验中学")
                put("class_name", "初二(3)班")
                put("group_name", "B组")
                put("phone", "")
                put("email", "")
                put("auth_token", "")
                put("created_at", now - 5 * 86400_000L)
            })
            val user3Id = db.insert(TABLE_USERS, null, ContentValues().apply {
                put("username", "demo_wangwu")
                put("password", pwd)
                put("nickname", "王五")
                put("real_name", "王五")
                put("school", "实验中学")
                put("class_name", "初二(4)班")
                put("group_name", "A组")
                put("phone", "")
                put("email", "")
                put("auth_token", "")
                put("created_at", now - 5 * 86400_000L)
            })

            // ── 考试记录（每位学生对每个题库各一次） ────────────────
            // 张三 - 数学（5题，4题对，正确率80%）
            db.insert(TABLE_EXAM_RECORDS, null, ContentValues().apply {
                put("user_id", user1Id); put("bank_id", bank1Id); put("bank_name", "初中数学基础练习")
                put("total_questions", 5); put("correct_count", 4)
                put("score", 80.0); put("time_cost_seconds", 320); put("mode", "practice")
                put("assigned_exam_id", 0); put("created_at", now - 2 * 86400_000L)
            })
            // 张三 - Python（6题，5题对，正确率83%）
            db.insert(TABLE_EXAM_RECORDS, null, ContentValues().apply {
                put("user_id", user1Id); put("bank_id", bank2Id); put("bank_name", "Python 编程入门")
                put("total_questions", 6); put("correct_count", 5)
                put("score", 83.3); put("time_cost_seconds", 410); put("mode", "practice")
                put("assigned_exam_id", 0); put("created_at", now - 1 * 86400_000L)
            })
            // 李四 - 数学（5题，3题对，正确率60%）
            db.insert(TABLE_EXAM_RECORDS, null, ContentValues().apply {
                put("user_id", user2Id); put("bank_id", bank1Id); put("bank_name", "初中数学基础练习")
                put("total_questions", 5); put("correct_count", 3)
                put("score", 60.0); put("time_cost_seconds", 480); put("mode", "practice")
                put("assigned_exam_id", 0); put("created_at", now - 2 * 86400_000L)
            })
            // 王五 - 数学（5题，5题对，正确率100%）
            db.insert(TABLE_EXAM_RECORDS, null, ContentValues().apply {
                put("user_id", user3Id); put("bank_id", bank1Id); put("bank_name", "初中数学基础练习")
                put("total_questions", 5); put("correct_count", 5)
                put("score", 100.0); put("time_cost_seconds", 260); put("mode", "practice")
                put("assigned_exam_id", 0); put("created_at", now - 1 * 86400_000L)
            })

            // ── 错题记录 ──────────────────────────────────────────────
            // 李四 数学 题1 错了
            db.insert(TABLE_WRONG_QUESTIONS, null, ContentValues().apply {
                put("user_id", user2Id); put("question_id", bank1Id) // 用bank1Id第1题id近似，实际题目id由autoincrement生成，此处演示
                put("bank_id", bank1Id); put("wrong_count", 1); put("correct_count", 0)
                put("last_wrong_at", now - 2 * 86400_000L); put("is_resolved", 0)
            })

            // ── 学生消息 ──────────────────────────────────────────────
            db.insert(TABLE_STUDENT_MESSAGES, null, ContentValues().apply {
                put("user_id", user1Id); put("sender_name", "老师")
                put("content", "张三同学，你的数学基础不错，继续加油！建议多练习填空题和多选题。")
                put("is_read", 0); put("created_at", now - 86400_000L)
            })
            db.insert(TABLE_STUDENT_MESSAGES, null, ContentValues().apply {
                put("user_id", user2Id); put("sender_name", "老师")
                put("content", "李四同学，整数运算部分需要加强，建议回顾负数乘除法的规则。")
                put("is_read", 0); put("created_at", now - 86400_000L)
            })

            // ── 题目反馈 ──────────────────────────────────────────────
            db.insert(TABLE_FEEDBACK, null, ContentValues().apply {
                put("question_id", 1); put("bank_id", bank1Id); put("user_id", user2Id)
                put("content", "这道题的解析能不能写得更详细一点？我不太理解为什么是负负得正。")
                put("is_resolved", 0); put("created_at", now - 86400_000L)
            })

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 从 JSON 字符串导入数据（清空目标表后插入，在事务中完成） */
    fun importFromJson(json: String) {
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
        val root: Map<String, Any> = gson.fromJson(json, type)
        @Suppress("UNCHECKED_CAST")
        val tables = (root["tables"] as? Map<String, List<Map<String, Any?>>>) ?: return

        val db = writableDatabase
        db.beginTransaction()
        try {
            // 按依赖顺序清空（子表先清）
            val clearOrder = listOf(
                "assigned_exam_users", TABLE_BUZZER_ANSWERS, TABLE_BUZZER_STATE,
                TABLE_STUDENT_MESSAGES, TABLE_FEEDBACK, TABLE_WRONG_QUESTIONS,
                TABLE_ANSWER_RECORDS, TABLE_EXAM_RECORDS, TABLE_ASSIGNED_EXAM,
                TABLE_QUESTIONS, TABLE_BANKS, TABLE_USERS
            )
            for (t in clearOrder) {
                try { db.delete(t, null, null) } catch (_: Exception) {}
            }

            // 按依赖顺序插入（父表先插）
            val insertOrder = listOf(
                TABLE_BANKS, TABLE_QUESTIONS, TABLE_USERS,
                TABLE_ASSIGNED_EXAM, TABLE_EXAM_RECORDS, TABLE_ANSWER_RECORDS,
                TABLE_WRONG_QUESTIONS, TABLE_FEEDBACK, TABLE_STUDENT_MESSAGES,
                TABLE_BUZZER_STATE, TABLE_BUZZER_ANSWERS, "assigned_exam_users"
            )
            for (tableName in insertOrder) {
                val rows = tables[tableName] ?: continue
                for (row in rows) {
                    val cv = android.content.ContentValues()
                    for ((k, v) in row) {
                        when (v) {
                            null          -> cv.putNull(k)
                            is Double     -> {
                                // Gson 将所有数字解析为 Double，需区分整数列
                                if (v == kotlin.math.floor(v) && !v.isInfinite()) {
                                    cv.put(k, v.toLong())
                                } else {
                                    cv.put(k, v)
                                }
                            }
                            is String     -> cv.put(k, v)
                            else          -> cv.put(k, v.toString())
                        }
                    }
                    try { db.insertWithOnConflict(tableName, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) }
                    catch (_: Exception) {}
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
