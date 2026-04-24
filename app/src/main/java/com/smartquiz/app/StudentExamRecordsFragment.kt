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
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.smartquiz.R
import com.smartquiz.databinding.FragmentStudentExamRecordsBinding
import com.smartquiz.db.DatabaseHelper
import com.smartquiz.model.ExamRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StudentExamRecordsFragment : Fragment() {

	companion object {
		private const val ARG_USER_ID = "userId"
		private const val ARG_STUDENT_NAME = "studentName"

		fun newInstance(userId: Long, studentName: String): StudentExamRecordsFragment {
			return StudentExamRecordsFragment().apply {
				arguments = Bundle().apply {
					putLong(ARG_USER_ID, userId)
					putString(ARG_STUDENT_NAME, studentName)
				}
			}
		}
	}

	private var _binding: FragmentStudentExamRecordsBinding? = null
	private val binding get() = _binding!!
	private lateinit var db: DatabaseHelper
	private val mainHandler = Handler(Looper.getMainLooper())

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
	): View {
		_binding = FragmentStudentExamRecordsBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		db = DatabaseHelper.getInstance(requireContext())

		val userId = arguments?.getLong(ARG_USER_ID) ?: 0L
		val studentName = arguments?.getString(ARG_STUDENT_NAME) ?: "学生"

		binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
		binding.tvTitle.text = "${studentName} 的考试记录"
		binding.btnViewWrongQuestions.setOnClickListener {
			parentFragmentManager.beginTransaction()
				.add(R.id.contentFrame, StudentWrongQuestionsFragment.newInstance(userId, studentName), "student_wrong")
				.addToBackStack("student_wrong")
				.commit()
		}

		loadRecords(userId, studentName)
	}

	private fun loadRecords(userId: Long, studentName: String) {
		Thread {
			val records = db.getExamRecordsByUser(userId)
			mainHandler.post {
				if (_binding == null) return@post
				renderStats(records)
				renderList(records, studentName)
			}
		}.start()
	}

	private fun renderStats(records: List<ExamRecord>) {
		binding.tvStatExamCount.text = records.size.toString()
		if (records.isEmpty()) {
			binding.tvStatAvgScore.text = "--"
			binding.tvStatMaxScore.text = "--"
		} else {
			val avg = records.map { it.score }.average()
			val max = records.maxOf { it.score }
			binding.tvStatAvgScore.text = "%.0f".format(avg)
			binding.tvStatMaxScore.text = "%.0f".format(max)

			val avgColor = when {
				avg >= 80 -> ContextCompat.getColor(requireContext(), R.color.success_dark)
				avg >= 60 -> ContextCompat.getColor(requireContext(), R.color.warning)
				else      -> ContextCompat.getColor(requireContext(), R.color.error)
			}
			binding.tvStatAvgScore.setTextColor(avgColor)
		}
	}

	private fun renderList(records: List<ExamRecord>, studentName: String) {
		if (records.isEmpty()) {
			binding.tvNoRecords.visibility = View.VISIBLE
			binding.recordListContainer.visibility = View.GONE
			return
		}
		binding.tvNoRecords.visibility = View.GONE
		binding.recordListContainer.visibility = View.VISIBLE
		binding.recordListContainer.removeAllViews()

		val inflater = LayoutInflater.from(requireContext())
		val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
		val modeColors = listOf(
			ContextCompat.getColor(requireContext(), R.color.primary),
			ContextCompat.getColor(requireContext(), R.color.accent),
			ContextCompat.getColor(requireContext(), R.color.success)
		)

		records.forEachIndexed { index, record ->
			val row = inflater.inflate(R.layout.item_exam_record_row, binding.recordListContainer, false)

			// 模式标签（圆形背景）
			val tvMode = row.findViewById<TextView>(R.id.tvRecordMode)
			tvMode.text = record.modeLabel.take(2)
			val colorIdx = when (record.mode) {
				"exam"       -> 0
				"wrong_redo" -> 1
				else         -> 2
			}
			tvMode.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_icon_circle)
			tvMode.background.setTint(modeColors[colorIdx])

			// 题库名
			row.findViewById<TextView>(R.id.tvRecordBankName).text =
				record.bankName.ifBlank { "未知题库" }

			// 元信息：正确率 · 用时 · 时间
			val accuracy = if (record.totalQuestions > 0)
				record.correctCount * 100 / record.totalQuestions else 0
			val timeCost = formatTime(record.timeCostSeconds)
			val dateStr = dateFormat.format(Date(record.createdAt))
			row.findViewById<TextView>(R.id.tvRecordMeta).text =
				"正确率 $accuracy%  ·  用时 $timeCost  ·  $dateStr"

			// 得分及颜色
			val tvScore = row.findViewById<TextView>(R.id.tvRecordScore)
			tvScore.text = "%.0f".format(record.score)
			tvScore.setTextColor(
				ContextCompat.getColor(requireContext(), when {
					record.score >= 80 -> R.color.success_dark
					record.score >= 60 -> R.color.warning
					else               -> R.color.error
				})
			)

			// 分隔线（非最后一项）
			if (index < records.size - 1) {
				val divider = View(requireContext()).apply {
					layoutParams = LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT, 1
					).also { it.marginStart = 66.dpToPx(); it.marginEnd = 0 }
					setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.divider))
				}
				binding.recordListContainer.addView(divider)
			}

			// 点击查看答题详情
			row.setOnClickListener {
				Thread {
					val answers = db.getAnswersByExamRecord(record.id)
					mainHandler.post {
						if (_binding == null) return@post
						val frag = StudentAnswerDetailFragment.newInstance(studentName, record.score, answers)
						parentFragmentManager.beginTransaction()
							.add(R.id.contentFrame, frag, "exam_detail")
							.addToBackStack("exam_detail")
							.commit()
					}
				}.start()
			}

			binding.recordListContainer.addView(row)
		}
	}

	private fun formatTime(seconds: Int): String {
		return if (seconds < 60) "${seconds}秒"
		else "${seconds / 60}分${seconds % 60}秒"
	}

	private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
	}
}
