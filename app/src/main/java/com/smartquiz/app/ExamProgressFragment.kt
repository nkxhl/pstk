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

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.smartquiz.R
import com.smartquiz.databinding.FragmentExamProgressBinding
import com.smartquiz.db.DatabaseHelper

class ExamProgressFragment : Fragment() {

    companion object {
        private const val ARG_EXAM_ID = "examId"
        private const val ARG_BANK_NAME = "bankName"
        private const val ARG_TIME_MINUTES = "timeMinutes"

        fun newInstance(examId: Long, bankName: String, timeMinutes: Int): ExamProgressFragment {
            return ExamProgressFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_EXAM_ID, examId)
                    putString(ARG_BANK_NAME, bankName)
                    putInt(ARG_TIME_MINUTES, timeMinutes)
                }
            }
        }
    }

    private var _binding: FragmentExamProgressBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: DatabaseHelper

    private var examId: Long = 0L
    private var bankName: String = ""
    private var timeMinutes: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExamProgressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper.getInstance(requireContext())

        examId = arguments?.getLong(ARG_EXAM_ID) ?: 0L
        bankName = arguments?.getString(ARG_BANK_NAME) ?: ""
        timeMinutes = arguments?.getInt(ARG_TIME_MINUTES) ?: 0

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnRefresh.setOnClickListener { loadProgress() }

        loadProgress()
    }

    private fun loadProgress() {
        val progress = db.getExamProgress(examId)
        val now = System.currentTimeMillis()
        val onlineThreshold = 3 * 60 * 1000L

        val total = progress.size
        val completed = progress.count { it["isCompleted"] as Boolean }
        val online = progress.count {
            !(it["isCompleted"] as Boolean) &&
            (it["lastActiveAt"] as Long) > 0 &&
            (now - (it["lastActiveAt"] as Long)) < onlineThreshold
        }

        binding.tvTitle.text = "考试进度监控"
        binding.tvExamInfo.text = "题库：$bankName　时长：${timeMinutes}分钟"
        binding.tvTotalCount.text = total.toString()
        binding.tvCompletedCount.text = completed.toString()
        binding.tvOnlineCount.text = online.toString()

        binding.studentProgressContainer.removeAllViews()

        progress.forEach { p ->
            val isCompleted = p["isCompleted"] as Boolean
            val lastActive = p["lastActiveAt"] as Long
            val isOnline = !isCompleted && lastActive > 0 && (now - lastActive) < onlineThreshold
            val name = (p["realName"] as String).ifBlank { p["username"] as String }
            val cls = (p["className"] as String).ifBlank { "-" }
            val answered = p["answeredCount"] as Int

            val statusText = when {
                isCompleted -> "✅ 已交卷"
                isOnline    -> "🟢 在线"
                lastActive > 0 -> "⚪ 离线"
                else        -> "⬜ 未登录"
            }
            val statusColor = when {
                isCompleted -> R.color.success
                isOnline    -> R.color.primary
                else        -> R.color.text_hint
            }

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 48.dp()
                )
                setPadding(8.dp(), 0, 8.dp(), 0)
            }

            fun cell(text: String, weight: Float, colorRes: Int = R.color.text_primary, gravity: Int = android.view.Gravity.START) =
                TextView(requireContext()).apply {
                    this.text = text
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(requireContext(), colorRes))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                    this.gravity = gravity
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

            row.addView(cell(name, 2f))
            row.addView(cell(cls, 1.5f, R.color.text_secondary))
            row.addView(cell("${answered}题", 1f, R.color.text_primary, android.view.Gravity.CENTER))
            row.addView(cell(statusText, 1.5f, statusColor, android.view.Gravity.CENTER))

            binding.studentProgressContainer.addView(row)

            // 分割线
            binding.studentProgressContainer.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.divider))
            })
        }

        if (progress.isEmpty()) {
            binding.studentProgressContainer.addView(TextView(requireContext()).apply {
                text = "暂无学生数据"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                setPadding(0, 24.dp(), 0, 24.dp())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    private fun Int.dp() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
