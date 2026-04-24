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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smartquiz.R
import java.text.Collator
import java.util.Locale
import com.smartquiz.databinding.FragmentReportBinding
import com.smartquiz.db.DatabaseHelper

class ReportFragment : Fragment() {

    private enum class SortMode { AVG_SCORE, MAX_SCORE, NAME }

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: DatabaseHelper
    private var sortMode = SortMode.AVG_SCORE
    private var userSummaries: List<Map<String, Any>> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var adapter: UserSummaryAdapter? = null

    private inner class UserSummaryAdapter(
        private var items: List<Map<String, Any>>
    ) : RecyclerView.Adapter<UserSummaryAdapter.VH>() {

        private val avatarColors by lazy {
            listOf(
                ContextCompat.getColor(requireContext(), R.color.primary),
                ContextCompat.getColor(requireContext(), R.color.accent),
                ContextCompat.getColor(requireContext(), R.color.success),
                ContextCompat.getColor(requireContext(), R.color.warning),
                0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFF5C6BC0.toInt()
            )
        }

        fun submitList(newItems: List<Map<String, Any>>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvAvatar: TextView = itemView.findViewById(R.id.tvUserAvatar)
            val tvName: TextView = itemView.findViewById(R.id.tvUserName)
            val tvMeta: TextView = itemView.findViewById(R.id.tvUserMeta)
            val tvAvgScore: TextView = itemView.findViewById(R.id.tvUserAvgScore)
            val tvMaxScore: TextView = itemView.findViewById(R.id.tvUserMaxScore)
            val layoutBankStats: LinearLayout = itemView.findViewById(R.id.layoutBankStats)
            val ivArrow: ImageView = itemView.findViewById(R.id.ivUserArrow)
            val rowHeader: View = itemView.findViewById(R.id.rowUserHeader)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_user_summary, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val user = items[position]
            val userId = user["userId"] as Long
            val username = (user["username"] as? String).orEmpty().ifBlank { "未知用户" }
            val nickname = (user["nickname"] as? String).orEmpty().ifBlank { username }
            val examCount = user["examCount"] as Int
            val avgScore = user["avgScore"] as Double
            val maxScore = (user["maxScore"] as? Double) ?: 0.0
            val totalQuestions = user["totalQuestions"] as Int
            val totalCorrect = user["totalCorrect"] as Int
            val userWrongCount = user["wrongCount"] as Int

            holder.tvAvatar.text = nickname.take(1).uppercase()
            holder.tvAvatar.background.setTint(avatarColors[position % avatarColors.size])
            holder.tvName.text = nickname

            val accuracy = if (totalQuestions > 0) totalCorrect * 100 / totalQuestions else 0
            holder.tvMeta.text = "考试 $examCount 次 · 答题 $totalQuestions 题 · 正确率 $accuracy% · 错题 $userWrongCount 道"

            val scoreColor = when {
                avgScore >= 80 -> ContextCompat.getColor(requireContext(), R.color.success_dark)
                avgScore >= 60 -> ContextCompat.getColor(requireContext(), R.color.warning)
                else           -> ContextCompat.getColor(requireContext(), R.color.error)
            }
            holder.tvAvgScore.text = "%.0f".format(avgScore)
            holder.tvAvgScore.setTextColor(scoreColor)
            holder.tvMaxScore.text = "%.0f".format(maxScore)

            holder.layoutBankStats.visibility = View.GONE
            holder.ivArrow.rotation = -90f
            holder.layoutBankStats.tag = null

            // 点击整行跳转考试记录
            holder.rowHeader.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .add(R.id.contentFrame, StudentExamRecordsFragment.newInstance(userId, nickname), "student_exam_records")
                    .addToBackStack("student_exam_records")
                    .commit()
            }

            // 点击箭头展开/收起题库统计
            holder.ivArrow.setOnClickListener {
                val isVisible = holder.layoutBankStats.visibility == View.VISIBLE
                if (!isVisible && holder.layoutBankStats.tag == null) {
                    Thread {
                        val bankStats = db.getBankStatsForUser(userId)
                        mainHandler.post {
                            if (_binding == null) return@post
                            holder.layoutBankStats.removeAllViews()
                            val inf = LayoutInflater.from(requireContext())
                            bankStats.forEach { bankStat ->
                                val bankRow = inf.inflate(R.layout.item_bank_stat, holder.layoutBankStats, false)
                                bankRow.findViewById<TextView>(R.id.tvBankStatName).text = bankStat["bankName"] as String
                                val bExamCount = bankStat["examCount"] as Int
                                bankRow.findViewById<TextView>(R.id.tvBankStatExamCount).text = "考试 $bExamCount 次"
                                val bTotal = bankStat["totalQuestions"] as Int
                                val bCorrect = bankStat["totalCorrect"] as Int
                                val bAccuracy = if (bTotal > 0) bCorrect * 100 / bTotal else 0
                                bankRow.findViewById<TextView>(R.id.tvBankStatAccuracy).text = "正确率 $bAccuracy%"
                                val bestScore = bankStat["bestScore"] as Double
                                val bAvgScore = bankStat["avgScore"] as Double
                                bankRow.findViewById<TextView>(R.id.tvBankStatBest).text = "%.0f".format(bestScore)
                                bankRow.findViewById<TextView>(R.id.tvBankStatAvg).text = "%.0f".format(bAvgScore)
                                holder.layoutBankStats.addView(bankRow)
                            }
                            holder.layoutBankStats.tag = true
                            holder.layoutBankStats.visibility = View.VISIBLE
                            holder.ivArrow.animate().rotation(0f).setDuration(200).start()
                        }
                    }.start()
                } else {
                    holder.layoutBankStats.visibility = if (isVisible) View.GONE else View.VISIBLE
                    holder.ivArrow.animate().rotation(if (isVisible) -90f else 0f).setDuration(200).start()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper.getInstance(requireContext())
        adapter = UserSummaryAdapter(emptyList())
        binding.recordListContainer.apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
        }
        binding.recordListContainer.adapter = adapter
        binding.btnRefreshReport.setOnClickListener { loadReport() }
        binding.btnHelp.setOnClickListener { (activity as? MainActivity)?.showHelp() }
        binding.btnStudentManage.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .add(R.id.contentFrame, StudentManageFragment(), "student_manage")
                .addToBackStack("student_manage").commit()
        }
        binding.btnSortAvg.setOnClickListener { setSortMode(SortMode.AVG_SCORE) }
        binding.btnSortMax.setOnClickListener { setSortMode(SortMode.MAX_SCORE) }
        binding.btnSortName.setOnClickListener { setSortMode(SortMode.NAME) }
        loadReport()
    }

    private fun setSortMode(mode: SortMode) {
        sortMode = mode
        updateSortButtons()
        renderUserList()
    }

    private fun updateSortButtons() {
        val primary = ContextCompat.getColor(requireContext(), R.color.primary)
        val strokeDefault = ContextCompat.getColor(requireContext(), R.color.card_stroke)
        listOf(
            binding.btnSortAvg to SortMode.AVG_SCORE,
            binding.btnSortMax to SortMode.MAX_SCORE,
            binding.btnSortName to SortMode.NAME
        ).forEach { (btn, mode) ->
            val selected = sortMode == mode
            btn.setTextColor(if (selected) primary else ContextCompat.getColor(requireContext(), R.color.text_secondary))
            btn.strokeColor = android.content.res.ColorStateList.valueOf(if (selected) primary else strokeDefault)
            btn.strokeWidth = if (selected) 2 else 1
        }
    }

    fun loadReport() {
        Thread {
            val ranking = db.getScoreRanking()
            val records = db.getAllExamRecords()
            val wrongCount = db.getTotalWrongCount()
            val summaries = db.getUserReportSummaries()
            mainHandler.post {
                if (_binding == null) return@post
                binding.tvStatBanks.text = ranking.size.toString()
                binding.tvStatExams.text = records.size.toString()
                binding.tvStatQuestions.text = records.sumOf { it.totalQuestions }.toString()
                binding.tvStatUsers.text = records.map { it.userId }.distinct().size.toString()
                binding.tvStatWrong.text = wrongCount.toString()
                binding.cardWrong.setOnClickListener {
                    parentFragmentManager.beginTransaction()
                        .add(R.id.contentFrame, WrongRankingFragment.newInstance(), "wrong_ranking")
                        .addToBackStack("wrong_ranking").commit()
                }
                binding.cardScoreRanking.setOnClickListener {
                    parentFragmentManager.beginTransaction()
                        .add(R.id.contentFrame, ScoreRankingFragment.newInstance(), "score_ranking")
                        .addToBackStack("score_ranking").commit()
                }
                userSummaries = summaries
                updateSortButtons()
                if (userSummaries.isEmpty()) {
                    binding.tvNoRecords.visibility = View.VISIBLE
                    adapter?.submitList(emptyList())
                } else {
                    binding.tvNoRecords.visibility = View.GONE
                    renderUserList()
                }
            }
        }.start()
    }

    private fun sortedSummaries(): List<Map<String, Any>> = when (sortMode) {
        SortMode.AVG_SCORE -> userSummaries.sortedByDescending { it["avgScore"] as Double }
        SortMode.MAX_SCORE -> userSummaries.sortedByDescending { it["maxScore"] as Double }
        SortMode.NAME      -> {
            val collator = Collator.getInstance(Locale.CHINA)
            userSummaries.sortedWith { a, b ->
                collator.compare(
                    (a["nickname"] as? String).orEmpty(),
                    (b["nickname"] as? String).orEmpty()
                )
            }
        }
    }

    private fun renderUserList() {
        adapter?.submitList(sortedSummaries())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}