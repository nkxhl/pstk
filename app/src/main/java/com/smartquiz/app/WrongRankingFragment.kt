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
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.smartquiz.R
import com.smartquiz.databinding.FragmentWrongRankingBinding
import com.smartquiz.db.DatabaseHelper

class WrongRankingFragment : Fragment() {

    companion object {
        fun newInstance() = WrongRankingFragment()
    }

    private var _binding: FragmentWrongRankingBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: DatabaseHelper

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWrongRankingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper.getInstance(requireContext())

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        loadRanking()
    }

    private fun Int.dp(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics
    ).toInt()

    private fun loadRanking() {
        val inflater = LayoutInflater.from(requireContext())
        val bankRanking = db.getWrongRankingByBank()

        if (bankRanking.isEmpty()) {
            binding.tvNoWrong.visibility = View.VISIBLE
            return
        }

        binding.tvNoWrong.visibility = View.GONE

        bankRanking.forEach { bankItem ->
            val bankId = bankItem["bankId"] as Long
            val bankName = bankItem["bankName"] as String
            val wrongCount = bankItem["wrongCount"] as Int

            // ---- 题库卡片 ----
            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 12.dp() }
                radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics)
                cardElevation = 0f
                strokeWidth = 1.dp()
                strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
                setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            }

            val cardInner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val pad = 16.dp()
                setPadding(pad, pad, pad, pad)
            }

            // 题库标题行
            val headerRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvBankName = TextView(requireContext()).apply {
                text = bankName
                textSize = 16f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvBankCount = TextView(requireContext()).apply {
                text = "共 $wrongCount 道错题"
                textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
            }

            headerRow.addView(tvBankName)
            headerRow.addView(tvBankCount)

            // 分割线
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.topMargin = 8.dp() }
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_stroke))
            }

            // 错题列表容器
            val questionsContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = 8.dp() }
            }

            val questions = db.getWrongQuestionRankingForBank(bankId)
            questions.forEach { qItem ->
                val content = qItem["content"] as String
                val totalWrong = qItem["totalWrong"] as Int
                @Suppress("UNCHECKED_CAST")
                val wrongUsers = qItem["wrongUsers"] as List<String>

                val questionView = inflater.inflate(
                    R.layout.item_wrong_question, questionsContainer, false
                )

                // 错误次数徽章
                val tvWrongCount = questionView.findViewById<TextView>(R.id.tvWrongCount)
                tvWrongCount.text = totalWrong.toString()
                val badgeColor = when {
                    totalWrong >= 5 -> ContextCompat.getColor(requireContext(), R.color.error)
                    totalWrong >= 3 -> ContextCompat.getColor(requireContext(), R.color.warning)
                    else            -> ContextCompat.getColor(requireContext(), R.color.primary)
                }
                tvWrongCount.background.setTint(badgeColor)

                // 题目摘要（2行截断）
                questionView.findViewById<TextView>(R.id.tvQuestionContent).text = content

                // 做错人名摘要
                questionView.findViewById<TextView>(R.id.tvWrongUsers).text =
                    "做错的人：${wrongUsers.joinToString("、")}"

                // 展开/折叠详情
                val layoutDetail = questionView.findViewById<LinearLayout>(R.id.layoutDetail)
                val ivExpand = questionView.findViewById<ImageView>(R.id.ivExpand)
                var isExpanded = false

                // 填充展开区域
                questionView.findViewById<TextView>(R.id.tvFullContent).text = content

                // 通过 questionId 获取完整题目信息
                val questionId = qItem["questionId"] as Long
                val question = db.getQuestion(questionId)
                if (question != null) {
                    val tvOptions = questionView.findViewById<TextView>(R.id.tvOptions)
                    if (question.options.isNotEmpty()) {
                        val labels = listOf("A", "B", "C", "D", "E", "F")
                        tvOptions.text = question.options.mapIndexed { i, opt ->
                            "${labels.getOrElse(i) { (i + 65).toChar().toString() }}. $opt"
                        }.joinToString("\n")
                        tvOptions.visibility = View.VISIBLE
                    } else {
                        tvOptions.visibility = View.GONE
                    }
                    questionView.findViewById<TextView>(R.id.tvAnswer).text = question.answer
                    val tvExplanation = questionView.findViewById<TextView>(R.id.tvExplanation)
                    if (question.explanation.isNotBlank()) {
                        tvExplanation.text = "解析：${question.explanation}"
                        tvExplanation.visibility = View.VISIBLE
                    } else {
                        tvExplanation.visibility = View.GONE
                    }
                }

                questionView.findViewById<TextView>(R.id.tvWrongUsersDetail).text =
                    "做错的人：${wrongUsers.joinToString("、")}"

                questionView.setOnClickListener {
                    isExpanded = !isExpanded
                    layoutDetail.visibility = if (isExpanded) View.VISIBLE else View.GONE
                    ObjectAnimator.ofFloat(
                        ivExpand, "rotation",
                        if (isExpanded) -90f else 90f,
                        if (isExpanded) 90f else -90f
                    ).setDuration(200).start()
                }

                questionsContainer.addView(questionView)
            }

            cardInner.addView(headerRow)
            cardInner.addView(divider)
            cardInner.addView(questionsContainer)
            card.addView(cardInner)
            binding.bankRankingContainer.addView(card)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
