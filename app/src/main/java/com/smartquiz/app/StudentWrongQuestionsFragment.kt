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

import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.smartquiz.R
import com.smartquiz.databinding.FragmentStudentWrongQuestionsBinding
import com.smartquiz.db.DatabaseHelper

class StudentWrongQuestionsFragment : Fragment() {

	companion object {
		private const val ARG_USER_ID = "userId"
		private const val ARG_STUDENT_NAME = "studentName"

		fun newInstance(userId: Long, studentName: String) =
			StudentWrongQuestionsFragment().apply {
				arguments = Bundle().apply {
					putLong(ARG_USER_ID, userId)
					putString(ARG_STUDENT_NAME, studentName)
				}
			}
	}

	private var _binding: FragmentStudentWrongQuestionsBinding? = null
	private val binding get() = _binding!!
	private lateinit var db: DatabaseHelper
	private val mainHandler = Handler(Looper.getMainLooper())

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
	): View {
		_binding = FragmentStudentWrongQuestionsBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		db = DatabaseHelper.getInstance(requireContext())

		val userId = arguments?.getLong(ARG_USER_ID) ?: 0L
		val studentName = arguments?.getString(ARG_STUDENT_NAME) ?: "学生"

		binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
		binding.tvTitle.text = "${studentName} 的错题记录"

		loadWrongQuestions(userId)
	}

	private fun loadWrongQuestions(userId: Long) {
		Thread {
			val wrongList = db.getWrongQuestions(userId)
			mainHandler.post {
				if (_binding == null) return@post
				if (wrongList.isEmpty()) {
					binding.tvNoWrong.visibility = View.VISIBLE
					binding.listContainer.visibility = View.GONE
					return@post
				}
				binding.tvNoWrong.visibility = View.GONE
				binding.listContainer.visibility = View.VISIBLE
				binding.listContainer.removeAllViews()

				val inflater = LayoutInflater.from(requireContext())
				val errorColor = ContextCompat.getColor(requireContext(), R.color.error)

				wrongList.forEachIndexed { index, item ->
					val row = inflater.inflate(R.layout.item_wrong_question, binding.listContainer, false)
					val wrongCount = item["wrongCount"] as Int
					val content = (item["content"] as? String).orEmpty()
					val bankName = (item["bankName"] as? String).orEmpty()
					val answer = (item["answer"] as? String).orEmpty()
					val explanation = (item["explanation"] as? String).orEmpty()
					@Suppress("UNCHECKED_CAST")
					val options = item["options"] as? List<String> ?: emptyList()

					val tvWrongCount = row.findViewById<TextView>(R.id.tvWrongCount)
					tvWrongCount.text = wrongCount.toString()
					tvWrongCount.background.setTint(errorColor)

					row.findViewById<TextView>(R.id.tvQuestionContent).text = content
					row.findViewById<TextView>(R.id.tvWrongUsers).text = bankName

					val layoutDetail = row.findViewById<LinearLayout>(R.id.layoutDetail)
					val ivExpand = row.findViewById<ImageView>(R.id.ivExpand)
					layoutDetail.visibility = View.GONE
					ivExpand.rotation = -90f

					// 完整题目
					row.findViewById<TextView>(R.id.tvFullContent).text = content
					// 选项
					val optionLetters = listOf("A", "B", "C", "D", "E", "F")
					val optionsText = options.mapIndexed { i, opt ->
						"${optionLetters.getOrElse(i) { (i + 65).toChar().toString() }}. $opt"
					}.joinToString("\n")
					row.findViewById<TextView>(R.id.tvOptions).text = optionsText
					row.findViewById<TextView>(R.id.tvAnswer).text = answer
					val tvExplanation = row.findViewById<TextView>(R.id.tvExplanation)
					if (explanation.isBlank()) {
						tvExplanation.visibility = View.GONE
					} else {
						tvExplanation.visibility = View.VISIBLE
						tvExplanation.text = "解析：$explanation"
					}

					row.setOnClickListener {
						val isOpen = layoutDetail.visibility == View.VISIBLE
						layoutDetail.visibility = if (isOpen) View.GONE else View.VISIBLE
						ObjectAnimator.ofFloat(ivExpand, "rotation", if (isOpen) -90f else 0f)
							.setDuration(200).start()
					}

					// 分隔线（非最后）
					if (index < wrongList.size - 1) {
						val divider = View(requireContext()).apply {
							layoutParams = LinearLayout.LayoutParams(
								LinearLayout.LayoutParams.MATCH_PARENT, 1
							).also { it.marginStart = 68.dpToPx() }
							setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.divider))
						}
						binding.listContainer.addView(divider)
					}

					binding.listContainer.addView(row)
				}
			}
		}.start()
	}

	private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
	}
}
