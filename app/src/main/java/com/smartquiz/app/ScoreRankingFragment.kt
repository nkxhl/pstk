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

import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.smartquiz.R
import com.smartquiz.databinding.FragmentScoreRankingBinding
import com.smartquiz.db.DatabaseHelper

class ScoreRankingFragment : Fragment() {

    companion object {
        fun newInstance() = ScoreRankingFragment()
    }

    private var _binding: FragmentScoreRankingBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: DatabaseHelper

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScoreRankingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper.getInstance(requireContext())
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        loadRanking()
    }

    private fun Int.dp(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics
    ).toInt()

    private fun loadRanking() {
        Thread {
            val ranking = db.getScoreRanking()
            mainHandler.post {
                if (_binding == null) return@post
                if (ranking.isEmpty()) {
                    binding.tvNoData.visibility = View.VISIBLE
                    return@post
                }
                binding.tvNoData.visibility = View.GONE
                buildRankingCards(ranking)
            }
        }.start()
    }

    private fun buildRankingCards(ranking: List<Map<String, Any>>) {
        val rankMedals = listOf("🥇", "🥈", "🥉")
        val avatarColors = listOf(
            ContextCompat.getColor(requireContext(), R.color.primary),
            ContextCompat.getColor(requireContext(), R.color.accent),
            ContextCompat.getColor(requireContext(), R.color.success),
            ContextCompat.getColor(requireContext(), R.color.warning),
            0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFF5C6BC0.toInt()
        )

        val inflater = LayoutInflater.from(requireContext())

        ranking.forEachIndexed { idx, user ->
            val userId = user["userId"] as Long
            val displayName = user["displayName"] as String
            val school = user["school"] as String
            val className = user["className"] as String
            val examCount = user["examCount"] as Int
            val totalQ = user["totalQuestions"] as Int
            val accuracy = user["accuracy"] as Double
            val rank = user["rank"] as Int

            // 卡片容器
            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 10.dp() }
                radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics)
                cardElevation = 0f
                strokeWidth = 1.dp()
                strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
                setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            }

            val cardInner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
            }

            // 头部行（排名 + 头像 + 姓名 + 正确率）
            val headerRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.attr.selectableItemBackground.let {
                    val typedValue = android.util.TypedValue()
                    requireContext().theme.resolveAttribute(it, typedValue, true)
                    typedValue.resourceId
                })
            }

            // 排名徽章
            val tvRank = TextView(requireContext()).apply {
                text = if (rank <= 3) rankMedals[rank - 1] else "$rank"
                textSize = if (rank <= 3) 22f else 16f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(36.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = android.view.Gravity.CENTER
            }
            headerRow.addView(tvRank)

            // 头像圆形
            val tvAvatar = TextView(requireContext()).apply {
                text = displayName.take(1).uppercase()
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                val size = 40.dp()
                layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = 12.dp() }
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_icon_circle)
                background?.setTint(avatarColors[idx % avatarColors.size])
            }
            headerRow.addView(tvAvatar)

            // 姓名 + 学校班级
            val infoCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(requireContext()).apply {
                text = displayName
                textSize = 15f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            infoCol.addView(tvName)
            val schoolInfo = listOf(school, className).filter { it.isNotBlank() }.joinToString(" · ")
            if (schoolInfo.isNotBlank()) {
                val tvSchool = TextView(requireContext()).apply {
                    text = schoolInfo
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                }
                infoCol.addView(tvSchool)
            }
            val tvMeta = TextView(requireContext()).apply {
                text = "考试${examCount}次 · 答题${totalQ}题"
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            }
            infoCol.addView(tvMeta)
            headerRow.addView(infoCol)

            // 正确率大字
            val accuracyCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = 8.dp() }
            }
            val accuracyColor = when {
                accuracy >= 80 -> ContextCompat.getColor(requireContext(), R.color.success_dark)
                accuracy >= 60 -> ContextCompat.getColor(requireContext(), R.color.warning)
                else           -> ContextCompat.getColor(requireContext(), R.color.error)
            }
            val tvAccuracy = TextView(requireContext()).apply {
                text = "%.1f%%".format(accuracy)
                textSize = 22f
                setTextColor(accuracyColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
            }
            val tvAccLabel = TextView(requireContext()).apply {
                text = "正确率"
                textSize = 11f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                gravity = android.view.Gravity.CENTER
            }
            accuracyCol.addView(tvAccuracy)
            accuracyCol.addView(tvAccLabel)
            headerRow.addView(accuracyCol)

            // 箭头
            val ivArrow = android.widget.ImageView(requireContext()).apply {
                setImageResource(android.R.drawable.arrow_down_float)
                layoutParams = LinearLayout.LayoutParams(20.dp(), 20.dp()).also { it.marginStart = 6.dp() }
                rotation = -90f
            }
            headerRow.addView(ivArrow)

            cardInner.addView(headerRow)

            // 题库详情展开区（懒加载，点击时才查询）
            val detailLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.background))
                setPadding(16.dp(), 0, 16.dp(), 12.dp())
            }
            cardInner.addView(detailLayout)

            // 点击头部展开/折叠，首次展开时懒加载题库详情
            headerRow.setOnClickListener {
                val isVisible = detailLayout.visibility == View.VISIBLE
                if (!isVisible && detailLayout.tag == null) {
                    Thread {
                        val bankDetails = db.getBankDetailForUser(userId)
                        mainHandler.post {
                            if (_binding == null) return@post
                            detailLayout.removeAllViews()
                            bankDetails.forEach { bank ->
                                val bName = bank["bankName"] as String
                                val bExams = bank["examCount"] as Int
                                val bTotal = bank["totalQuestions"] as Int
                                val bCorrect = bank["totalCorrect"] as Int
                                val bAcc = if (bTotal > 0) bCorrect * 100.0 / bTotal else 0.0
                                val bBest = bank["bestScore"] as Double
                                val bAvg = bank["avgScore"] as Double

                                val row = inflater.inflate(R.layout.item_bank_stat, detailLayout, false)
                                row.findViewById<TextView>(R.id.tvBankStatName).text = bName
                                row.findViewById<TextView>(R.id.tvBankStatExamCount).text = "考试 $bExams 次"
                                row.findViewById<TextView>(R.id.tvBankStatAccuracy).text = "正确率 ${"%.1f".format(bAcc)}%"
                                row.findViewById<TextView>(R.id.tvBankStatBest).text = "%.0f".format(bBest)
                                row.findViewById<TextView>(R.id.tvBankStatAvg).text = "%.0f".format(bAvg)
                                detailLayout.addView(row)
                            }
                            detailLayout.tag = true
                            detailLayout.visibility = View.VISIBLE
                            ivArrow.animate().rotation(0f).setDuration(200).start()
                        }
                    }.start()
                } else {
                    detailLayout.visibility = if (isVisible) View.GONE else View.VISIBLE
                    ivArrow.animate().rotation(if (isVisible) -90f else 0f).setDuration(200).start()
                }
            }

            card.addView(cardInner)
            binding.rankingContainer.addView(card)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
