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
import com.smartquiz.databinding.FragmentExamStatisticsBinding
import com.smartquiz.db.DatabaseHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExamStatisticsFragment : Fragment() {

    companion object {
        private const val ARG_EXAM_ID = "examId"
        private const val ARG_BANK_ID = "bankId"
        private const val ARG_BANK_NAME = "bankName"

        fun newInstance(examId: Long, bankId: Long, bankName: String): ExamStatisticsFragment {
            return ExamStatisticsFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_EXAM_ID, examId)
                    putLong(ARG_BANK_ID, bankId)
                    putString(ARG_BANK_NAME, bankName)
                }
            }
        }
    }

    private var _binding: FragmentExamStatisticsBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: DatabaseHelper

    private var examId: Long = 0L
    private var bankId: Long = 0L
    private var bankName: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExamStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper.getInstance(requireContext())

        examId = arguments?.getLong(ARG_EXAM_ID) ?: 0L
        bankId = arguments?.getLong(ARG_BANK_ID) ?: 0L
        bankName = arguments?.getString(ARG_BANK_NAME) ?: ""

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.tvTitle.text = "考试统计分析"

        loadStatistics()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadStatistics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                db.getExamDetailStatistics(examId, bankId)
            }
            if (_binding == null) return@launch

        val total = stats["totalStudents"] as Int
        val completed = stats["completedStudents"] as Int
        val avg = stats["avgScore"] as Double
        val max = stats["maxScore"] as Double
        val min = stats["minScore"] as Double
        val passRate = stats["passRate"] as Double
        val excellentRate = stats["excellentRate"] as Double
        val questionStats = stats["questionStats"] as List<Map<String, Any>>
        val studentDetails = stats["studentDetails"] as List<Map<String, Any>>

        // 总体统计
        binding.tvAvgScore.text = "%.1f".format(avg)
        binding.tvMaxScore.text = "%.0f".format(max)
        binding.tvMinScore.text = "%.0f".format(min)
        binding.tvPassRate.text = "%.0f%%".format(passRate * 100)
        binding.tvExcellentRate.text = "%.0f%%".format(excellentRate * 100)
        binding.tvExamMeta.text = "题库：$bankName　应考：${total}人　已交卷：${completed}人"

        // 学生成绩列表
        binding.studentScoreContainer.removeAllViews()
        if (studentDetails.isEmpty()) {
            binding.studentScoreContainer.addView(emptyHint("暂无成绩数据"))
        } else {
            studentDetails.forEachIndexed { index, s ->
                val name = (s["realName"] as? String).orEmpty().ifBlank { (s["username"] as? String).orEmpty().ifBlank { "未知用户" } }
                val cls = (s["className"] as? String).orEmpty().ifBlank { "-" }
                val score = s["score"] as Double
                val correct = s["correctCount"] as Int
                val totalQ = s["totalCount"] as Int
                val timeSec = s["timeCostSeconds"] as Int
                val mm = timeSec / 60; val ss = timeSec % 60
                val accuracyRate = if (totalQ > 0) correct.toDouble() / totalQ * 100 else 0.0

                val scoreColor = when {
                    score >= 90 -> R.color.success
                    score >= 60 -> R.color.primary
                    else        -> R.color.error
                }

                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(8.dp(), 10.dp(), 8.dp(), 10.dp())
                    isClickable = true; isFocusable = true
                    background = with(android.util.TypedValue()) {
                        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
                        ContextCompat.getDrawable(requireContext(), resourceId)
                    }
                }

                // 名次
                row.addView(TextView(requireContext()).apply {
                    text = "${index + 1}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                    layoutParams = LinearLayout.LayoutParams(24.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    gravity = android.view.Gravity.CENTER
                })
                row.addView(cell(name, 2f, R.color.text_primary))
                row.addView(cell(cls, 1.5f, R.color.text_secondary))
                row.addView(cell("%.0f".format(score), 1f, scoreColor, android.view.Gravity.CENTER))
                row.addView(cell("%.0f%%".format(accuracyRate), 1f, R.color.text_secondary, android.view.Gravity.CENTER))
                row.addView(cell("${mm}′${ss}″", 1.5f, R.color.text_hint, android.view.Gravity.CENTER))

                val answers = s["answers"] as List<Map<String, Any>>
                row.setOnClickListener {
                    val frag = StudentAnswerDetailFragment.newInstance(name, score, answers)
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.contentFrame, frag)
                        .addToBackStack(null)
                        .commit()
                }

                binding.studentScoreContainer.addView(row)
                binding.studentScoreContainer.addView(divider())
            }
        }

        // 各题得分率
        binding.questionStatsContainer.removeAllViews()
        if (questionStats.isEmpty()) {
            binding.questionStatsContainer.addView(emptyHint("暂无答题数据"))
        } else {
            questionStats.forEachIndexed { index, q ->
                val content = (q["content"] as String).let {
                    if (it.length > 40) it.take(40) + "…" else it
                }
                val correct = q["correctAnswers"] as Int
                val totalAns = q["totalAnswers"] as Int
                val rate = (q["scoreRate"] as Double) * 100

                val rateColor = when {
                    rate >= 80 -> R.color.success
                    rate >= 60 -> R.color.warning
                    else       -> R.color.error
                }

                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(8.dp(), 10.dp(), 8.dp(), 10.dp())
                }

                // 题号
                row.addView(TextView(requireContext()).apply {
                    text = "${index + 1}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                    layoutParams = LinearLayout.LayoutParams(36.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    gravity = android.view.Gravity.CENTER
                })
                // 题目内容
                row.addView(TextView(requireContext()).apply {
                    text = content
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                        it.marginStart = 8.dp()
                    }
                    maxLines = 2
                })
                // 得分率
                row.addView(TextView(requireContext()).apply {
                    text = "%.0f%%".format(rate)
                    textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(requireContext(), rateColor))
                    layoutParams = LinearLayout.LayoutParams(60.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    gravity = android.view.Gravity.CENTER
                })
                // 答对/总
                row.addView(TextView(requireContext()).apply {
                    text = "$correct/$totalAns"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                    layoutParams = LinearLayout.LayoutParams(60.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    gravity = android.view.Gravity.CENTER
                })

                binding.questionStatsContainer.addView(row)
                binding.questionStatsContainer.addView(divider())
            }
        }
    } // end launch
    } // end loadStatistics

    private fun cell(text: String, weight: Float, colorRes: Int, gravity: Int = android.view.Gravity.START) =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), colorRes))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            this.gravity = gravity
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun divider() = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.divider))
    }

    private fun emptyHint(msg: String) = TextView(requireContext()).apply {
        text = msg
        textSize = 14f
        gravity = android.view.Gravity.CENTER
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
        setPadding(0, 24.dp(), 0, 24.dp())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun Int.dp() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
