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
import com.smartquiz.databinding.FragmentStudentAnswerDetailBinding

class StudentAnswerDetailFragment : Fragment() {

    companion object {
        private const val ARG_NAME = "studentName"
        private const val ARG_SCORE = "score"
        private const val ARG_ANSWERS = "answers"

        fun newInstance(studentName: String, score: Double, answers: List<Map<String, Any>>): StudentAnswerDetailFragment {
            return StudentAnswerDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NAME, studentName)
                    putDouble(ARG_SCORE, score)
                    // 将答题数据序列化为 ArrayList<Bundle>
                    val list = ArrayList<Bundle>()
                    answers.forEach { a ->
                        list.add(Bundle().apply {
                            putLong("questionId", (a["questionId"] as? Long) ?: 0L)
                            putString("content", a["content"] as? String ?: "")
                            putString("userAnswer", a["userAnswer"] as? String ?: "")
                            putBoolean("isCorrect", a["isCorrect"] as? Boolean ?: false)
                        })
                    }
                    putParcelableArrayList(ARG_ANSWERS, list)
                }
            }
        }
    }

    private var _binding: FragmentStudentAnswerDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStudentAnswerDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val studentName = arguments?.getString(ARG_NAME) ?: ""
        val score = arguments?.getDouble(ARG_SCORE) ?: 0.0
        @Suppress("DEPRECATION")
        val answerBundles = arguments?.getParcelableArrayList<Bundle>(ARG_ANSWERS) ?: arrayListOf()

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.tvTitle.text = "${studentName} 的答题详情"

        val correct = answerBundles.count { it.getBoolean("isCorrect") }
        val total = answerBundles.size
        binding.tvStudentInfo.text = "得分：${"%.0f".format(score)}分　正确：${correct}/${total}题"

        binding.answerContainer.removeAllViews()
        answerBundles.forEachIndexed { index, a ->
            val isCorrect = a.getBoolean("isCorrect")
            val content = a.getString("content") ?: ""
            val userAnswer = a.getString("userAnswer")?.ifBlank { "（未作答）" } ?: "（未作答）"

            val itemColor = if (isCorrect) R.color.success_light else R.color.error_light
            val textColor = if (isCorrect) R.color.success_dark else R.color.error

            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8.dp() }
                setBackgroundColor(ContextCompat.getColor(requireContext(), itemColor))
                setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            }

            // 题号 + 题目内容
            card.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 4.dp() }

                addView(TextView(requireContext()).apply {
                    text = if (isCorrect) "✅" else "❌"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = 6.dp() }
                })
                addView(TextView(requireContext()).apply {
                    text = "${index + 1}. $content"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setLineSpacing(0f, 1.4f)
                })
            })

            // 作答内容
            card.addView(TextView(requireContext()).apply {
                text = "作答：$userAnswer"
                textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), textColor))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = 22.dp() }
            })

            binding.answerContainer.addView(card)
        }

        if (answerBundles.isEmpty()) {
            binding.answerContainer.addView(TextView(requireContext()).apply {
                text = "暂无答题记录"
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
