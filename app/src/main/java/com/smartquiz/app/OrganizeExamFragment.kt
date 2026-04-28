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

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.smartquiz.R
import com.smartquiz.databinding.FragmentOrganizeExamBinding
import com.smartquiz.db.DatabaseHelper
import com.smartquiz.model.QuestionBank
import com.smartquiz.model.QuestionType
import com.smartquiz.model.User
import com.smartquiz.model.Question
import com.smartquiz.server.WebServerService
import com.smartquiz.util.PrintHelper
import com.smartquiz.util.OfflineExamHelper
import com.smartquiz.util.TimeUtils
import java.net.NetworkInterface
import java.util.Calendar

class OrganizeExamFragment : Fragment() {

    companion object {
        fun newInstance() = OrganizeExamFragment()
    }

    private var _binding: FragmentOrganizeExamBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: DatabaseHelper

    private var banks = listOf<QuestionBank>()
    private var allUsers = listOf<User>()
    private val selectedUserIds = mutableSetOf<Long>()
    private val selectedBankIds = mutableSetOf<Long>()   // 多选题库
    private val typeCountSeekBars = mutableMapOf<Int, SeekBar>()
    private val typeCountTextViews = mutableMapOf<Int, TextView>()
    private val typeScoreEditTexts = mutableMapOf<Int, EditText>()
    private var tvTotalScore: TextView? = null
    private var scheduledStartTime: Long = 0L
    private var isAutoSubmit: Boolean = false
    private var isPercentMode: Boolean = false  // 百分制模式
    private var bankTypeCountMap = mutableMapOf<Int, Int>()  // 当前题库各题型实际数量

    private val studentRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val studentRefreshRunnable = object : Runnable {
        override fun run() {
            refreshStudentListIfChanged()
            studentRefreshHandler.postDelayed(this, 10_000)
        }
    }

    private fun refreshStudentListIfChanged() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val newUsers = withContext(Dispatchers.IO) { db.getAllUsers() }
            if (_binding == null) return@launch
            val oldIds = allUsers.map { it.id }.toSet()
            val newIds = newUsers.map { it.id }.toSet()
            if (newIds != oldIds) {
                allUsers = newUsers
                selectedUserIds.retainAll(newIds)
                val classNames = withContext(Dispatchers.IO) { db.getDistinctClassNames() }
                if (_binding == null) return@launch
                setupStudentSelection(classNames)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOrganizeExamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper.getInstance(requireContext())

        val isBottomTab = parentFragmentManager.findFragmentByTag("exam") === this
        binding.btnBack.visibility = if (isBottomTab) View.GONE else View.VISIBLE
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnHelp.setOnClickListener { (activity as? MainActivity)?.showHelp() }

        setupTabs()
        setupTypeCountSettings()
        setupCommonSettings()
        binding.btnStartExam.setOnClickListener { publishExam() }
        // DB 查询放到后台线程，避免压测后启动时卡死主线程
        viewLifecycleOwner.lifecycleScope.launch {
            val loadedBanks = withContext(Dispatchers.IO) { db.getAllBanks() }
            val loadedUsers = withContext(Dispatchers.IO) { db.getAllUsers() }
            val classNames = withContext(Dispatchers.IO) { db.getDistinctClassNames() }
            if (_binding == null) return@launch
            banks = loadedBanks
            allUsers = loadedUsers
            setupBankSpinner(classNames)
            setupStudentSelection(classNames)
            loadExistingExams()
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次切换回本页面时重新加载题库和学生，确保数据与其他页面保持同步
        if (_binding != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val loadedBanks = withContext(Dispatchers.IO) { db.getAllBanks() }
                val loadedUsers = withContext(Dispatchers.IO) { db.getAllUsers() }
                val classNames = withContext(Dispatchers.IO) { db.getDistinctClassNames() }
                if (_binding == null) return@launch
                banks = loadedBanks
                allUsers = loadedUsers
                setupBankSpinner(classNames)
                setupStudentSelection(classNames)
            }
        }
        studentRefreshHandler.postDelayed(studentRefreshRunnable, 10_000)
    }

    override fun onPause() {
        super.onPause()
        studentRefreshHandler.removeCallbacks(studentRefreshRunnable)
    }

    private fun setupTabs() {
        if (binding.tabLayout.tabCount == 0) {
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText("发布考试"))
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText("考试列表"))
        }
        binding.publishPage.visibility = View.VISIBLE
        binding.listPage.visibility = View.GONE

        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                if ((tab?.position ?: 0) == 0) {
                    binding.publishPage.visibility = View.VISIBLE
                    binding.listPage.visibility = View.GONE
                } else {
                    binding.publishPage.visibility = View.GONE
                    binding.listPage.visibility = View.VISIBLE
                    loadExistingExams()
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun setupBankSpinner(classNames: List<String> = emptyList()) {
        if (banks.isEmpty()) {
            binding.tvBankSelect.text = "暂无题库"
            return
        }
        // 默认选中第一个题库
        if (selectedBankIds.isEmpty()) {
            selectedBankIds.add(banks[0].id)
        }
        updateBankSelectLabel()
        binding.tvBankSelect.setOnClickListener { showBankMultiSelectDialog() }
    }

    /** 刷新题库选择按钮的显示文字，并重新合并各题型数量更新滑块 */
    private fun updateBankSelectLabel() {
        val selected = banks.filter { it.id in selectedBankIds }
        binding.tvBankSelect.text = if (selected.isEmpty()) "点击选择题库（可多选）"
        else selected.joinToString(" + ") { "${it.name}(${it.questionCount}题)" }
        // 更新默认考试名称：用第一个题库名
        if (selected.isNotEmpty()) {
            binding.etExamName.setText(selected.joinToString("+") { it.name })
        }
        // 合并多个题库各题型数量后更新滑块
        viewLifecycleOwner.lifecycleScope.launch {
            val merged = mutableMapOf<Int, Int>()
            for (bank in selected) {
                val counts = withContext(Dispatchers.IO) { db.getQuestionCountByType(bank.id) }
                counts.forEach { (type, cnt) -> merged[type] = (merged[type] ?: 0) + cnt }
            }
            if (_binding == null) return@launch
            bankTypeCountMap.clear()
            bankTypeCountMap.putAll(merged)
            updateSeekBarMaxByBank()
        }
    }

    /** 弹出多选题库对话框 */
    private fun showBankMultiSelectDialog() {
        val names = banks.map { "${it.name} (${it.questionCount}题)" }.toTypedArray()
        val checked = banks.map { it.id in selectedBankIds }.toBooleanArray()
        AlertDialog.Builder(requireContext())
            .setTitle("选择题库（可多选）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) selectedBankIds.add(banks[which].id)
                else selectedBankIds.remove(banks[which].id)
            }
            .setPositiveButton("确定") { _, _ ->
                if (selectedBankIds.isEmpty() && banks.isNotEmpty()) {
                    selectedBankIds.add(banks[0].id)
                }
                updateBankSelectLabel()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 根据题库各题型实际数量更新滑块最大值和当前值 */
    private fun updateSeekBarMaxByBank() {
        QuestionType.entries.forEach { type ->
            val maxCount = bankTypeCountMap[type.code] ?: 0
            val seek = typeCountSeekBars[type.code] ?: return@forEach
            val tvVal = typeCountTextViews[type.code] ?: return@forEach
            seek.max = maxCount
            if (seek.progress > maxCount) {
                seek.progress = maxCount
                tvVal.text = if (maxCount == 0) "不考" else "${maxCount}题"
            }
            // 若该题型题库中没有题，自动设为0
            if (maxCount == 0 && seek.progress != 0) {
                seek.progress = 0
                tvVal.text = "不考"
            }
        }
        updateTotalScore()
    }

    private fun setupStudentSelection(classNames: List<String> = emptyList()) {
        binding.chipGroupClassSelect.removeAllViews()
        classNames.forEach { cn ->
            val chip = Chip(requireContext()).apply {
                text = cn
                isCheckable = true
                setOnCheckedChangeListener { _, checked ->
                    val ids = db.getUsersByClassName(cn).map { it.id }
                    if (checked) selectedUserIds.addAll(ids) else selectedUserIds.removeAll(ids.toSet())
                    refreshStudentChecks()
                    updateSelectedCount()
                }
            }
            binding.chipGroupClassSelect.addView(chip)
        }
        binding.btnSelectAll.setOnClickListener {
            if (selectedUserIds.size == allUsers.size) selectedUserIds.clear()
            else { selectedUserIds.clear(); selectedUserIds.addAll(allUsers.map { it.id }) }
            refreshStudentChecks()
            updateSelectedCount()
        }
        refreshStudentChecks()
        updateSelectedCount()
    }

    private fun refreshStudentChecks() {
        binding.studentCheckContainer.removeAllViews()
        // 用户数量大时分批渲染，避免一次性创建大量 View 卡死主线程
        val rowSize = 5
        val chunks = allUsers.chunked(rowSize)
        var index = 0
        fun renderNextBatch() {
            if (_binding == null) return
            val end = minOf(index + 10, chunks.size) // 每批渲染 10 组（50 人）
            for (i in index until end) {
                val group = chunks[i]
                val col = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(0, 0, 8.dp(), 0) }
                }
                group.forEach { user ->
                    val name = user.realName.ifBlank { user.nickname.ifBlank { user.username } }
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    val cb = CheckBox(requireContext()).apply {
                        isChecked = user.id in selectedUserIds
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedUserIds.add(user.id) else selectedUserIds.remove(user.id)
                            updateSelectedCount()
                        }
                    }
                    val tv = TextView(requireContext()).apply {
                        text = name
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(2.dp(), 0, 8.dp(), 0)
                    }
                    row.addView(cb)
                    row.addView(tv)
                    row.setOnClickListener { cb.isChecked = !cb.isChecked }
                    col.addView(row)
                }
                binding.studentCheckContainer.addView(col)
            }
            index = end
            if (index < chunks.size) {
                binding.studentCheckContainer.postDelayed(::renderNextBatch, 16L)
            }
        }
        renderNextBatch()
    }

    private fun updateSelectedCount() {
        binding.tvSelectedCount.text = "已选 ${selectedUserIds.size} 人"
        binding.btnSelectAll.text =
            if (selectedUserIds.isNotEmpty() && selectedUserIds.size == allUsers.size) "取消全选" else "全选"
    }

        // 默认各题型数量和分值
    private val defaultCounts = mapOf(1 to 15, 2 to 10, 3 to 10, 4 to 10)
    private val defaultScores = mapOf(1 to 2, 2 to 3, 3 to 2, 4 to 2)

    private fun setupTypeCountSettings() {
        binding.typeCountContainer.removeAllViews()
        typeCountSeekBars.clear()
        typeCountTextViews.clear()
        typeScoreEditTexts.clear()

        // 表头行
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 6.dp())
        }
        header.addView(TextView(requireContext()).apply {
            text = "题型"; textSize = 12f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(72.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        header.addView(TextView(requireContext()).apply {
            text = "数量"; textSize = 12f; gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(requireContext()).apply {
            text = "分值/题"; textSize = 12f; gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(72.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        binding.typeCountContainer.addView(header)

        QuestionType.entries.forEach { type ->
            val defaultCount = defaultCounts[type.code] ?: 10
            val defaultScore = defaultScores[type.code] ?: 2

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 8.dp())
            }
            val tvLabel = TextView(requireContext()).apply {
                text = type.label; textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(72.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val tvVal = TextView(requireContext()).apply {
                text = "${defaultCount}题"; textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(42.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val seek = SeekBar(requireContext()).apply {
                max = 60  // 初始最大值，切换题库后会更新
                progress = defaultCount
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                        tvVal.text = if (p == 0) "不考" else "${p}题"
                        updateTotalScore()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            // 分值输入容器（EditText + "分" 标签）
            val scoreContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(72.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val etScore = EditText(requireContext()).apply {
                setText(defaultScore.toString())
                textSize = 14f; gravity = android.view.Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(42.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(4.dp(), 2.dp(), 4.dp(), 2.dp())
                // 加底线边框
                val gd = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(1.dp(), ContextCompat.getColor(requireContext(), R.color.primary))
                    cornerRadius = 6f.dpF()
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                background = gd
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { updateTotalScore() }
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                })
            }
            val tvUnit = TextView(requireContext()).apply {
                text = "分"; textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                setPadding(3.dp(), 0, 0, 0)
            }
            scoreContainer.addView(etScore)
            scoreContainer.addView(tvUnit)

            row.addView(tvLabel)
            row.addView(seek)
            row.addView(tvVal)
            row.addView(scoreContainer)
            typeCountSeekBars[type.code] = seek
            typeCountTextViews[type.code] = tvVal
            typeScoreEditTexts[type.code] = etScore
            binding.typeCountContainer.addView(row)
        }

        // 总分显示行（含百分制复选框）
        val totalRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp(), 0, 0)
        }
        val cbPercent = CheckBox(requireContext()).apply {
            text = "百分制"
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            isChecked = isPercentMode
            setOnCheckedChangeListener { _, checked ->
                isPercentMode = checked
                updateTotalScore()
            }
        }
        val tvTotal = TextView(requireContext()).apply {
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            setPadding(8.dp(), 0, 0, 0)
        }
        tvTotalScore = tvTotal
        totalRow.addView(cbPercent)
        totalRow.addView(tvTotal)
        binding.typeCountContainer.addView(totalRow)
        updateTotalScore()
    }

    private fun updateTotalScore() {
        var total = 0
        QuestionType.entries.forEach { type ->
            val count = typeCountSeekBars[type.code]?.progress ?: 0
            val score = typeScoreEditTexts[type.code]?.text?.toString()?.toIntOrNull() ?: 0
            total += count * score
        }
        if (isPercentMode) {
            tvTotalScore?.text = "试卷总分：100 分（百分制）"
        } else {
            tvTotalScore?.text = "试卷总分：${total} 分"
        }
    }

    private fun setupCommonSettings() {
        // 考试模式切换时更新描述
        binding.rgExamMode.setOnCheckedChangeListener { _, checkedId ->
            binding.tvExamModeDesc.text = when (checkedId) {
                R.id.rbModeOnline -> "学生登录后进入强制考试模式，题目顺序和选项顺序随机。"
                R.id.rbModeOffline -> "生成试卷文件，将 HTML 文件分发给学生在线下使用。"
                R.id.rbModeBuzzer -> "全班同步显示相同题目，老师通过统计页（8081端口）控制题目进度，实时查看抢答统计。"
                else -> ""
            }
        }
        binding.seekTimeMinutes.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                binding.tvTimeMinutes.text = "${p}分钟"
                // 可交卷时间不能超过考试时长
                if (binding.seekSubmitBefore.progress > p) {
                    binding.seekSubmitBefore.progress = p
                }
                binding.seekSubmitBefore.max = p
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        binding.seekSubmitBefore.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                binding.tvSubmitBefore.text = if (p == 0) "不允许交卷" else "最后${p}分钟"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        // 初始化可交卷的max为考试时长
        binding.seekSubmitBefore.max = binding.seekTimeMinutes.progress
        binding.seekMaxDiff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { binding.tvMaxDiff.text = "⭐$p" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        binding.btnPickTime.setOnClickListener { pickScheduledTime() }
        binding.btnClearTime.setOnClickListener {
            scheduledStartTime = 0L
            binding.tvScheduledTime.text = "立即开考"
        }
        binding.chkAutoSubmit.setOnCheckedChangeListener { _, checked -> isAutoSubmit = checked }
    }

    private fun pickScheduledTime() {
        val now = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, y, m, d ->
            TimePickerDialog(requireContext(), { _, hh, mm ->
                val cal = Calendar.getInstance().apply { set(y, m, d, hh, mm, 0); set(Calendar.MILLISECOND, 0) }
                scheduledStartTime = cal.timeInMillis
                binding.tvScheduledTime.text = TimeUtils.formatTime(scheduledStartTime)
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun publishExam() {
        if (banks.isEmpty()) { Toast.makeText(requireContext(), "没有可用的题库", Toast.LENGTH_SHORT).show(); return }
        if (selectedBankIds.isEmpty()) { Toast.makeText(requireContext(), "请选择至少一个题库", Toast.LENGTH_SHORT).show(); return }

        val selectedBanks = banks.filter { it.id in selectedBankIds }
        val primaryBank = selectedBanks.first()
        val bankName = selectedBanks.joinToString("+") { it.name }
        val examTitle = binding.etExamName.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: bankName
        val timeMinutes = binding.seekTimeMinutes.progress
        val submitBeforeEnd = binding.seekSubmitBefore.progress
        val maxDiff = binding.seekMaxDiff.progress
        val typeCounts = QuestionType.entries.joinToString(",") { t -> "${t.code}:${typeCountSeekBars[t.code]?.progress ?: 0}" }
        val typeScores = QuestionType.entries.joinToString(",") { t -> "${t.code}:${typeScoreEditTexts[t.code]?.text?.toString()?.toIntOrNull() ?: 2}" }
        val questionTypes = QuestionType.entries.joinToString(",") { it.code.toString() }
        val limitCount = typeCountSeekBars.values.sumOf { it.progress }.takeIf { it > 0 } ?: 0
        val rawTotalScore = QuestionType.entries.sumOf { t ->
            (typeCountSeekBars[t.code]?.progress ?: 0) * (typeScoreEditTexts[t.code]?.text?.toString()?.toIntOrNull() ?: 2)
        }
        // 百分制：总分固定100，typeScores附加":percent"标记告知服务端按比例折算
        val totalScore = if (isPercentMode) 100 else rawTotalScore
        val finalTypeScores = if (isPercentMode) "$typeScores|percent|$rawTotalScore" else typeScores

        when (binding.rgExamMode.checkedRadioButtonId) {
            R.id.rbModeOffline ->
                publishOfflineExam(primaryBank, bankName, examTitle, timeMinutes, maxDiff, typeCounts, finalTypeScores, totalScore)
            R.id.rbModeBuzzer ->
                publishBuzzerContest(primaryBank, bankName, examTitle, timeMinutes, maxDiff, typeCounts, finalTypeScores, totalScore)
            else ->
                publishOnlineExam(primaryBank, bankName, examTitle, timeMinutes, submitBeforeEnd, maxDiff, typeCounts, finalTypeScores, questionTypes, limitCount, totalScore)
        }
    }

    private fun publishOnlineExam(primaryBank: QuestionBank, bankName: String, examTitle: String, timeMinutes: Int, submitBeforeEnd: Int, maxDiff: Int, typeCounts: String, typeScores: String, questionTypes: String, limitCount: Int, totalScore: Int) {
        if (selectedUserIds.isEmpty()) { Toast.makeText(requireContext(), "请至少选择一名学生", Toast.LENGTH_SHORT).show(); return }
        val scheduleText = if (scheduledStartTime > 0L) TimeUtils.formatTime(scheduledStartTime) else "立即开考"
        val autoSubmitAt: Long = if (isAutoSubmit && timeMinutes > 0) {
            val startBase = if (scheduledStartTime > 0L) scheduledStartTime else System.currentTimeMillis()
            startBase + timeMinutes * 60 * 1000L
        } else 0L

        AlertDialog.Builder(requireContext())
            .setTitle("确认发布在线考试")
            .setMessage("题库：${bankName}\n学生：${selectedUserIds.size} 人\n考试时长：${timeMinutes}分钟\n可交卷：${if (submitBeforeEnd > 0) "最后${submitBeforeEnd}分钟" else "不允许手动交卷"}\n最高难度：⭐${maxDiff}\n开考时间：${scheduleText}${if (isAutoSubmit) "\n到时自动交卷：是" else ""}\n试卷总分：${totalScore}分\n\n学生登录后将进入强制考试模式。")
            .setPositiveButton("发布") { _, _ ->
                val fixedQuestions = buildExamQuestionsMulti(selectedBankIds.toList(), maxDiff, typeCounts)
                val questionIds = fixedQuestions.joinToString(",") { it.id.toString() }

                val examId = db.createAssignedExam(
                    bankId = primaryBank.id, bankName = bankName, limitCount = limitCount,
                    timeMinutes = timeMinutes, maxDiff = maxDiff, shuffleOpts = true, shuffleQ = true,
                    questionTypes = questionTypes, typeCounts = typeCounts, typeScores = typeScores,
                    submitBeforeEndMinutes = submitBeforeEnd, scheduledStartTime = scheduledStartTime,
                    questionIds = questionIds, autoSubmitAt = autoSubmitAt
                )
                db.assignExamToUsers(examId, selectedUserIds.toList())
                Toast.makeText(requireContext(), "✅ 考试已发布，${selectedUserIds.size} 名学生登录后将进入考试", Toast.LENGTH_LONG).show()
                loadExistingExams()
                binding.tabLayout.getTabAt(1)?.select()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun publishOfflineExam(primaryBank: QuestionBank, bankName: String, examTitle: String, timeMinutes: Int, maxDiff: Int, typeCounts: String, typeScores: String, totalScore: Int) {
        val fixedQuestions = buildExamQuestionsMulti(selectedBankIds.toList(), maxDiff, typeCounts)
        if (fixedQuestions.isEmpty()) {
            Toast.makeText(requireContext(), "没有符合条件的题目", Toast.LENGTH_SHORT).show()
            return
        }
        OfflineExamHelper.shareOfflineExam(
            context = requireContext(),
            title = examTitle,
            questions = fixedQuestions,
            timeMinutes = timeMinutes,
            typeScores = typeScores
        )
    }

    private fun publishBuzzerContest(primaryBank: QuestionBank, bankName: String, examTitle: String, timeMinutes: Int, maxDiff: Int, typeCounts: String, typeScores: String, totalScore: Int) {
        if (selectedUserIds.isEmpty()) {
            Toast.makeText(requireContext(), "请至少选择一名学生", Toast.LENGTH_SHORT).show()
            return
        }
        // 抢答比赛：题目顺序和选项顺序固定，不打乱
        val fixedQuestions = buildBuzzerQuestionsMulti(selectedBankIds.toList(), maxDiff, typeCounts)
        if (fixedQuestions.isEmpty()) {
            Toast.makeText(requireContext(), "没有符合条件的题目", Toast.LENGTH_SHORT).show()
            return
        }
        val questionIds = fixedQuestions.joinToString(",") { it.id.toString() }
        val questionTypes = QuestionType.entries.joinToString(",") { it.code.toString() }
        val limitCount = fixedQuestions.size

        val ipText = getLocalIpAddress()?.let { "http://$it:8081" } ?: "稀 IP 未知"

        AlertDialog.Builder(requireContext())
            .setTitle("確认发布抢答比赛")
            .setMessage(
                "题库：${bankName}\n" +
                "题目数：${fixedQuestions.size} 题\n" +
                "学生：${selectedUserIds.size} 人\n\n" +
                "抢答比赛中所有学生看到相同题目\n" +
                "老师通过以下地址查看统计：\n$ipText"
            )
            .setPositiveButton("发布") { _, _ ->
                val examId = db.createAssignedExam(
                    bankId = primaryBank.id, bankName = bankName, limitCount = limitCount,
                    timeMinutes = timeMinutes, maxDiff = maxDiff,
                    shuffleOpts = false, shuffleQ = false,
                    questionTypes = questionTypes, typeCounts = typeCounts,
                    typeScores = typeScores, submitBeforeEndMinutes = 0,
                    scheduledStartTime = 0L, questionIds = questionIds,
                    examMode = "buzzer"
                )
                db.initBuzzerContest(examId)
                db.assignExamToUsers(examId, selectedUserIds.toList())
                Toast.makeText(
                    requireContext(),
                    "🏆 抢答比赛已发布！老师统计页：$ipText",
                    Toast.LENGTH_LONG
                ).show()
                loadExistingExams()
                binding.tabLayout.getTabAt(1)?.select()
            }
            .setNegativeButton("取消", null).show()
    }

    /** 抢答比赛组卷：随机抽取题目，抽完后按 ID 升序固定顺序，确保每个学生看到相同题目 */
    private fun buildBuzzerQuestions(bankId: Long, maxDiff: Int, typeCounts: String): List<Question> {
        var questions = db.getQuestionsByBank(bankId)
        val blockedIds = db.getBlockedQuestionIds(bankId)
        if (blockedIds.isNotEmpty()) questions = questions.filter { it.id !in blockedIds }
        if (maxDiff in 1..4) questions = questions.filter { it.difficulty <= maxDiff }
        // 随机打乱用于抽题
        questions = questions.shuffled()
        if (typeCounts.isNotBlank()) {
            val countMap = mutableMapOf<Int, Int>()
            typeCounts.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val typeCode = parts[0].trim().toIntOrNull()
                    val count = parts[1].trim().toIntOrNull()
                    if (typeCode != null && count != null && count > 0) countMap[typeCode] = count
                }
            }
            if (countMap.isNotEmpty()) {
                val grouped = questions.groupBy { it.type.code }
                questions = countMap.flatMap { (typeCode, count) ->
                    val typeQuestions = grouped[typeCode] ?: emptyList()
                    if (typeQuestions.size > count) typeQuestions.take(count) else typeQuestions
                }
            }
        }
        // 抽取后按 ID 升序，确保全班题目顺序一致
        return questions.sortedBy { it.id }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    /** 按考试筛选参数从题库中组卷，返回固定的题目列表 */
    private fun buildExamQuestions(bankId: Long, maxDiff: Int, typeCounts: String): List<Question> {
        var questions = db.getQuestionsByBank(bankId)

        // 过滤被屏蔽题目
        val blockedIds = db.getBlockedQuestionIds(bankId)
        if (blockedIds.isNotEmpty()) {
            questions = questions.filter { it.id !in blockedIds }
        }

        // 按最高难度过滤
        if (maxDiff in 1..4) {
            questions = questions.filter { it.difficulty <= maxDiff }
        }

        // 随机打乱
        questions = questions.shuffled()

        // 按题型数量分组截取
        if (typeCounts.isNotBlank()) {
            val countMap = mutableMapOf<Int, Int>()
            typeCounts.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val typeCode = parts[0].trim().toIntOrNull()
                    val count = parts[1].trim().toIntOrNull()
                    if (typeCode != null && count != null && count > 0) countMap[typeCode] = count
                }
            }
            if (countMap.isNotEmpty()) {
                val grouped = questions.groupBy { it.type.code }
                questions = countMap.flatMap { (typeCode, count) ->
                    val typeQuestions = grouped[typeCode] ?: emptyList()
                    if (typeQuestions.size > count) typeQuestions.take(count) else typeQuestions
                }
            }
        }

        return questions
    }

    /** 多题库组卷（在线/离线考试）：从多个题库合并后随机抽题 */
    private fun buildExamQuestionsMulti(bankIds: List<Long>, maxDiff: Int, typeCounts: String): List<Question> {
        if (bankIds.isEmpty()) return emptyList()
        if (bankIds.size == 1) return buildExamQuestions(bankIds[0], maxDiff, typeCounts)
        // 合并多题库题目，去重（不同题库可能有重复 id，但通常不会）
        var questions = bankIds.flatMap { bid ->
            var qs = db.getQuestionsByBank(bid)
            val blocked = db.getBlockedQuestionIds(bid)
            if (blocked.isNotEmpty()) qs = qs.filter { it.id !in blocked }
            qs
        }.distinctBy { it.id }
        if (maxDiff in 1..4) questions = questions.filter { it.difficulty <= maxDiff }
        questions = questions.shuffled()
        if (typeCounts.isNotBlank()) {
            val countMap = mutableMapOf<Int, Int>()
            typeCounts.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val typeCode = parts[0].trim().toIntOrNull()
                    val count = parts[1].trim().toIntOrNull()
                    if (typeCode != null && count != null && count > 0) countMap[typeCode] = count
                }
            }
            if (countMap.isNotEmpty()) {
                val grouped = questions.groupBy { it.type.code }
                questions = countMap.flatMap { (typeCode, count) ->
                    val typeQuestions = grouped[typeCode] ?: emptyList()
                    if (typeQuestions.size > count) typeQuestions.take(count) else typeQuestions
                }
            }
        }
        return questions
    }

    /** 多题库抢答组卷：合并后随机抽取，抽完后按 ID 升序固定顺序，确保全班题目一致 */
    private fun buildBuzzerQuestionsMulti(bankIds: List<Long>, maxDiff: Int, typeCounts: String): List<Question> {
        if (bankIds.isEmpty()) return emptyList()
        if (bankIds.size == 1) return buildBuzzerQuestions(bankIds[0], maxDiff, typeCounts)
        var questions = bankIds.flatMap { bid ->
            var qs = db.getQuestionsByBank(bid)
            val blocked = db.getBlockedQuestionIds(bid)
            if (blocked.isNotEmpty()) qs = qs.filter { it.id !in blocked }
            qs
        }.distinctBy { it.id }
        if (maxDiff in 1..4) questions = questions.filter { it.difficulty <= maxDiff }
        // 随机打乱用于抽题
        questions = questions.shuffled()
        if (typeCounts.isNotBlank()) {
            val countMap = mutableMapOf<Int, Int>()
            typeCounts.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val typeCode = parts[0].trim().toIntOrNull()
                    val count = parts[1].trim().toIntOrNull()
                    if (typeCode != null && count != null && count > 0) countMap[typeCode] = count
                }
            }
            if (countMap.isNotEmpty()) {
                val grouped = questions.groupBy { it.type.code }
                questions = countMap.flatMap { (typeCode, count) ->
                    val typeQuestions = grouped[typeCode] ?: emptyList()
                    if (typeQuestions.size > count) typeQuestions.take(count) else typeQuestions
                }
            }
        }
        // 抽取后按 ID 升序，确保全班题目顺序一致
        return questions.sortedBy { it.id }
    }

    private fun loadExistingExams() {
        val exams = db.getAllAssignedExams()
        binding.examListContainer.removeAllViews()
        if (exams.isEmpty()) { binding.tvNoExams.visibility = View.VISIBLE; return }
        binding.tvNoExams.visibility = View.GONE

        exams.forEach { exam ->
            val examId = exam["id"] as Long
            val bankId = exam["bankId"] as Long
            val bankName = exam["bankName"] as String
            val totalUsers = exam["totalUsers"] as Int
            val completedUsers = (exam["completedUsers"] as? Int) ?: 0
            val avgScore = (exam["avgScore"] as? Double) ?: 0.0
            val maxScore = (exam["maxScore"] as? Double) ?: 0.0
            val timeMinutes = exam["timeMinutes"] as Int
            val createdAt = exam["createdAt"] as Long
            val scheduledStart = (exam["scheduledStartTime"] as? Long) ?: 0L
            val isFinished = totalUsers > 0 && completedUsers >= totalUsers
            val examQuestionIds = (exam["questionIds"] as? String) ?: ""
            val examTypeScores = (exam["typeScores"] as? String) ?: ""
            val allowDownload = (exam["allowDownload"] as? Boolean) ?: false
            val typeCounts = (exam["typeCounts"] as? String) ?: ""
            val maxDiff = (exam["maxDiff"] as? Int) ?: 5

            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = 8.dp() }
                radius = 12f.dpF(); cardElevation = 0f; strokeWidth = 1.dp()
                strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
                setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (isFinished) showExamDetailStatistics(examId, bankId, bankName)
                    else showExamProgress(examId, bankName, timeMinutes)
                }
            }
            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            }

            // 标题行
            val titleRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(requireContext()).apply {
                text = "📝 $bankName"
                textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // 考试模式标签
            val examModeLabel = when (exam["examMode"] as? String) {
                "buzzer" -> "🏆 抢答比赛"
                "offline" -> "📄 离线"
                else -> if (isFinished) "已结束" else "进行中"
            }
            val modeColor = when (exam["examMode"] as? String) {
                "buzzer" -> R.color.warning
                else -> if (isFinished) R.color.success else R.color.warning
            }
            titleRow.addView(TextView(requireContext()).apply {
                text = examModeLabel
                textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(), modeColor))
            })
            inner.addView(titleRow)

            // 元信息
            inner.addView(TextView(requireContext()).apply {
                text = buildString {
                    append("${timeMinutes}分钟 · 完成 $completedUsers/$totalUsers · ${TimeUtils.formatTime(createdAt)}")
                    if (scheduledStart > 0L) append("\n开考：${TimeUtils.formatTime(scheduledStart)}")
                    if (completedUsers > 0) append("\n均分 ${"%.1f".format(avgScore)} · 最高分 ${"%.0f".format(maxScore)}")
                }
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                setPadding(0, 4.dp(), 0, 8.dp())
            })

            // ── 辅助函数：创建等宽无边框按钮 ─────────────────────────────────
            fun makeBtn(label: String, color: Int, weight: Float = 1f, onClick: () -> Unit): TextView {
                return TextView(requireContext()).apply {
                    text = label
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(color)
                    background = null
                    setPadding(4.dp(), 8.dp(), 4.dp(), 8.dp())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                    setOnClickListener { onClick() }
                }
            }
            val colorPrimary = ContextCompat.getColor(requireContext(), R.color.primary)
            val colorWarning = ContextCompat.getColor(requireContext(), R.color.warning)
            val colorError   = ContextCompat.getColor(requireContext(), R.color.error)
            val colorGray    = android.graphics.Color.parseColor("#AAAAAA")

            // ── 行1：📊进度/统计  ⚡强制交卷（进行中）  🔄重考 ─────────────
            val actionRow1 = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = 8.dp() }
            }
            actionRow1.addView(makeBtn(
                if (!isFinished) "📊 查看进度" else "📈 统计分析", colorPrimary
            ) {
                if (!isFinished) showExamProgress(examId, bankName, timeMinutes)
                else showExamDetailStatistics(examId, bankId, bankName)
            })
            // 抢答比赛模式额外显示统计页按鑰
            if ((exam["examMode"] as? String) == "buzzer") {
                val ip = getLocalIpAddress()
                actionRow1.addView(makeBtn("🏆 抢答统计", colorWarning) {
                    val url = if (ip != null) "http://$ip:8081" else "http://localhost:8081"
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url))
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), "统计页地址: $url", Toast.LENGTH_LONG).show()
                    }
                })
            } else if (!isFinished) {
                actionRow1.addView(makeBtn("⚡ 强制交卷", colorWarning) {
                    AlertDialog.Builder(requireContext()).setTitle("强制交卷")
                        .setMessage("确定将所有未交卷学生强制交卷吗？")
                        .setPositiveButton("确定") { _, _ ->
                            db.forceCompleteAllUsers(examId)
                            Toast.makeText(requireContext(), "已强制交卷", Toast.LENGTH_SHORT).show()
                            loadExistingExams()
                        }.setNegativeButton("取消", null).show()
                })
            } else {
                // 考试已结束时，占位保持等宽
                actionRow1.addView(android.view.View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            actionRow1.addView(makeBtn("🔄 重考", colorPrimary) {
                showRestartExamDialog(examId, bankId, bankName, timeMinutes, maxDiff, typeCounts, examTypeScores)
            })

            // ── 行2：🗑删除  🖨打印  📤离线分享  📥开放/禁止下载 ─────────────
            val actionRow2 = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = 2.dp() }
            }
            actionRow2.addView(makeBtn("🗑 删除", colorError) {
                AlertDialog.Builder(requireContext()).setTitle("删除考试").setMessage("确定要删除这场考试吗？")
                    .setPositiveButton("删除") { _, _ -> db.deleteAssignedExam(examId); loadExistingExams() }
                    .setNegativeButton("取消", null).show()
            })
            actionRow2.addView(makeBtn("🖨 打印", colorGray) {
                val questions = if (examQuestionIds.isNotBlank()) {
                    val ids = examQuestionIds.split(",").mapNotNull { it.trim().toLongOrNull() }
                    db.getQuestionsByIds(ids)
                } else {
                    db.getQuestionsByBank(bankId)
                }
                if (questions.isEmpty()) {
                    Toast.makeText(requireContext(), "该考试暂无题目", Toast.LENGTH_SHORT).show()
                    return@makeBtn
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("打印试卷")
                    .setMessage("「${bankName}」共 ${questions.size} 道题")
                    .setPositiveButton("仅题目") { _, _ ->
                        PrintHelper.printQuestions(requireContext(), bankName, questions, showAnswer = false)
                    }
                    .setNeutralButton("含答案解析") { _, _ ->
                        PrintHelper.printQuestions(requireContext(), bankName, questions, showAnswer = true)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            })
            actionRow2.addView(makeBtn("📤 离线分享", colorGray) {
                val questions = if (examQuestionIds.isNotBlank()) {
                    val ids = examQuestionIds.split(",").mapNotNull { it.trim().toLongOrNull() }
                    db.getQuestionsByIds(ids)
                } else {
                    db.getQuestionsByBank(bankId)
                }
                if (questions.isEmpty()) {
                    Toast.makeText(requireContext(), "该考试暂无题目", Toast.LENGTH_SHORT).show()
                    return@makeBtn
                }
                OfflineExamHelper.shareOfflineExam(
                    context = requireContext(),
                    title = bankName,
                    questions = questions,
                    timeMinutes = timeMinutes,
                    typeScores = examTypeScores
                )
            })
            actionRow2.addView(makeBtn(
                if (allowDownload) "📥 禁止下载" else "📥 开放下载",
                if (allowDownload) colorWarning else colorGray
            ) {
                val newAllow = !allowDownload
                db.setExamAllowDownload(examId, newAllow)
                Toast.makeText(requireContext(),
                    if (newAllow) "已开启下载，学生可在客户端下载离线考试" else "已关闭下载",
                    Toast.LENGTH_SHORT).show()
                loadExistingExams()
            })

            inner.addView(actionRow1)
            inner.addView(actionRow2)

            card.addView(inner)
            binding.examListContainer.addView(card)
        }
    }

    private fun showExamProgress(assignedExamId: Long, bankName: String, timeMinutes: Int) {
        val frag = ExamProgressFragment.newInstance(assignedExamId, bankName, timeMinutes)
        parentFragmentManager.beginTransaction()
            .replace(R.id.contentFrame, frag)
            .addToBackStack(null)
            .commit()
    }

    private fun showExamDetailStatistics(assignedExamId: Long, bankId: Long, bankName: String) {
        val frag = ExamStatisticsFragment.newInstance(assignedExamId, bankId, bankName)
        parentFragmentManager.beginTransaction()
            .replace(R.id.contentFrame, frag)
            .addToBackStack(null)
            .commit()
    }

    private fun showRestartExamDialog(
        origExamId: Long, bankId: Long, bankName: String,
        timeMinutes: Int, maxDiff: Int, typeCounts: String, typeScores: String
    ) {
        // 重新加载学生列表，允许修改
        allUsers = db.getAllUsers()
        selectedUserIds.clear()
        // 保留原考试学生选择，从数据库读取原参与学生
        val origProgress = db.getExamProgress(origExamId)
        origProgress.forEach { selectedUserIds.add(it["userId"] as Long) }

        val scrollView = ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 12.dp(), 20.dp(), 8.dp())
        }
        scrollView.addView(container)

        // 说明文字
        container.addView(TextView(requireContext()).apply {
            text = "将以相同题目重新发起一场考试，可重新选择参考学生和开考时间。"
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setPadding(0, 0, 0, 10.dp())
        })

        // ── 学生选择区（默认折叠，支持班级筛选） ────────────────────────
        // 标题行：「选择参考学生」+ 已选人数 + 展开/折叠按钮
        val tvStudentTitle = TextView(requireContext()).apply {
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        }
        val tvCount = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
        }
        fun updateStudentTitle() {
            tvStudentTitle.text = "选择参考学生（已选 ${selectedUserIds.size} 人）"
            tvCount.text = selectedUserIds.joinToString("、") { uid ->
                allUsers.firstOrNull { it.id == uid }?.let { u ->
                    u.realName.ifBlank { u.username }
                } ?: uid.toString()
            }.let { names -> if (names.length > 40) names.take(40) + "…" else names }
        }
        updateStudentTitle()

        val studentExpandContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE   // 默认折叠
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 6.dp() }
        }

        // 收集所有班级
        val allClasses = mutableListOf<String>()
        allClasses.add("全部")
        for (u in allUsers) {
            val cls = u.className.trim()
            if (cls.isNotEmpty() && !allClasses.contains(cls)) allClasses.add(cls)
        }
        var currentClassFilter = "全部"
        @Suppress("UNUSED_VALUE")

        // 班级筛选行（仅有多个班级时显示）
        val classFilterRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 4.dp() }
        }

        // 学生列表容器（可被刷新）
        val studentListContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun rebuildStudentList(classFilter: String) {
            studentListContainer.removeAllViews()
            val filtered = if (classFilter == "全部") allUsers
                           else allUsers.filter { it.className.trim() == classFilter }
            val colSize = 3
            var i = 0
            while (i < filtered.size) {
                val rowLayout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val groupEnd = minOf(i + colSize, filtered.size)
                for (j in i until groupEnd) {
                    val user = filtered[j]
                    val name = user.realName.ifBlank { user.username }
                    val cell = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .also { it.setMargins(0, 2.dp(), 4.dp(), 2.dp()) }
                    }
                    val cb = android.widget.CheckBox(requireContext()).apply {
                        isChecked = user.id in selectedUserIds
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedUserIds.add(user.id) else selectedUserIds.remove(user.id)
                            updateStudentTitle()
                        }
                    }
                    val tv = TextView(requireContext()).apply {
                        text = name
                        textSize = 13f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                        setPadding(2.dp(), 0, 0, 0)
                    }
                    cell.addView(cb); cell.addView(tv)
                    cell.setOnClickListener { cb.isChecked = !cb.isChecked }
                    rowLayout.addView(cell)
                }
                // 补齐不足 colSize 的空格
                repeat(colSize - (groupEnd - i)) {
                    rowLayout.addView(android.view.View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
                studentListContainer.addView(rowLayout)
                i += colSize
            }
        }
        rebuildStudentList("全部")

        // 构建班级筛选按钮（有多个班级才显示）
        if (allClasses.size > 1) {
            val colorPrimary = ContextCompat.getColor(requireContext(), R.color.primary)
            val colorGray = android.graphics.Color.parseColor("#AAAAAA")
            val classBtnList = mutableListOf<TextView>()
            for (cls in allClasses) {
                val btn = TextView(requireContext()).apply {
                    text = cls
                    textSize = 12f
                    setTextColor(if (cls == "全部") colorPrimary else colorGray)
                    background = android.graphics.drawable.GradientDrawable().also { gd ->
                        gd.setStroke(1.dp(), if (cls == "全部") colorPrimary else colorGray)
                        gd.cornerRadius = 16.dp().toFloat()
                    }
                    setPadding(10.dp(), 3.dp(), 10.dp(), 3.dp())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = 6.dp() }
                    setOnClickListener {
                        currentClassFilter = cls
                        for (b in classBtnList) {
                            b.setTextColor(colorGray)
                            (b.background as? android.graphics.drawable.GradientDrawable)?.setStroke(1.dp(), colorGray)
                        }
                        setTextColor(colorPrimary)
                        (background as? android.graphics.drawable.GradientDrawable)?.setStroke(1.dp(), colorPrimary)
                        rebuildStudentList(cls)
                    }
                }
                classBtnList.add(btn)
                classFilterRow.addView(btn)
            }
            studentExpandContainer.addView(classFilterRow)
        }
        studentExpandContainer.addView(studentListContainer)

        // 标题行：点击展开/折叠
        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = with(android.util.TypedValue()) {
                android.util.TypedValue().also {
                    requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }
                null
            }
            setPadding(0, 6.dp(), 0, 6.dp())
        }
        val tvArrow = TextView(requireContext()).apply {
            text = "▶"
            textSize = 11f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            setPadding(0, 0, 6.dp(), 0)
        }
        var expanded = false
        headerRow.addView(tvStudentTitle)
        headerRow.addView(android.view.View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        headerRow.addView(tvArrow)
        headerRow.setOnClickListener {
            expanded = !expanded
            studentExpandContainer.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
            tvArrow.text = if (expanded) "▼" else "▶"
        }

        container.addView(headerRow)
        container.addView(tvCount)
        container.addView(studentExpandContainer)

        // 开考时间选择
        container.addView(TextView(requireContext()).apply {
            text = "开考时间"; textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setPadding(0, 12.dp(), 0, 4.dp())
        })
        var restartScheduledTime = 0L
        val tvScheduled = TextView(requireContext()).apply {
            text = "立即开考"; textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
        }
        val btnPickTime = android.widget.Button(requireContext()).apply {
            text = "选择时间"
            textSize = 13f
            setOnClickListener {
                val now = Calendar.getInstance()
                DatePickerDialog(requireContext(), { _, y, m, d ->
                    TimePickerDialog(requireContext(), { _, hh, mm ->
                        val cal = Calendar.getInstance().apply { set(y, m, d, hh, mm, 0) }
                        restartScheduledTime = cal.timeInMillis
                        tvScheduled.text = com.smartquiz.util.TimeUtils.formatTime(restartScheduledTime)
                    }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        val btnClearTime = android.widget.Button(requireContext()).apply {
            text = "立即开考"
            textSize = 13f
            setOnClickListener { restartScheduledTime = 0L; tvScheduled.text = "立即开考" }
        }
        val timeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        timeRow.addView(tvScheduled)
        timeRow.addView(btnPickTime)
        timeRow.addView(btnClearTime)
        container.addView(timeRow)

        AlertDialog.Builder(requireContext())
            .setTitle("重新考试：$bankName")
            .setView(scrollView)
            .setPositiveButton("发布") { _, _ ->
                if (selectedUserIds.isEmpty()) {
                    Toast.makeText(requireContext(), "请至少选择一名学生", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 获取原考试题目
                val origExam = db.getAssignedExamById(origExamId)
                val origQuestionIds = (origExam?.get("questionIds") as? String) ?: ""
                val questionIds: String
                val questions = if (origQuestionIds.isNotBlank()) {
                    val ids = origQuestionIds.split(",").mapNotNull { it.trim().toLongOrNull() }
                    val qs = db.getQuestionsByIds(ids)
                    questionIds = origQuestionIds
                    qs
                } else {
                    val qs = buildExamQuestions(bankId, maxDiff, typeCounts)
                    questionIds = qs.joinToString(",") { it.id.toString() }
                    qs
                }
                val questionTypes = QuestionType.entries.joinToString(",") { it.code.toString() }
                val newExamId = db.createAssignedExam(
                    bankId = bankId, bankName = bankName,
                    limitCount = questions.size,
                    timeMinutes = timeMinutes, maxDiff = maxDiff,
                    shuffleOpts = true, shuffleQ = true,
                    questionTypes = questionTypes,
                    typeCounts = typeCounts, typeScores = typeScores,
                    submitBeforeEndMinutes = 0,
                    scheduledStartTime = restartScheduledTime,
                    questionIds = questionIds
                )
                db.assignExamToUsers(newExamId, selectedUserIds.toList())
                Toast.makeText(requireContext(),
                    "✅ 重新考试已发布，${selectedUserIds.size} 名学生登录后将进入考试",
                    Toast.LENGTH_LONG).show()
                loadExistingExams()
            }
            .setNegativeButton("取消", null).show()
    }
    private fun Int.dp(): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics).toInt()
    private fun Float.dpF(): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, resources.displayMetrics)

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}