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

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.smartquiz.R
import com.smartquiz.databinding.FragmentBankDetailBinding
import com.smartquiz.db.DatabaseHelper
import com.smartquiz.model.Question
import com.smartquiz.model.QuestionBank
import com.smartquiz.model.QuestionFeedback
import com.smartquiz.model.QuestionType
import com.smartquiz.util.OfflineExamHelper
import com.smartquiz.util.PrintHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class BankDetailFragment : Fragment() {

    companion object {
        private const val ARG_BANK_ID = "bank_id"

        fun newInstance(bankId: Long): BankDetailFragment {
            return BankDetailFragment().apply {
                arguments = Bundle().apply { putLong(ARG_BANK_ID, bankId) }
            }
        }
    }

    private var _binding: FragmentBankDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: DatabaseHelper
    private var bankId: Long = -1L
    private var bank: QuestionBank? = null
    // 追踪当前加载协程，确保新加载开始时取消旧的，避免并发修改 View 树崩溃
    private var loadJob: Job? = null
    // 编辑对话框中当前图片URL（null=未变更，""=已删除）
    private var pendingImageUrl: String? = null
    private var pickImageCallback: ((String) -> Unit)? = null
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val savedPath = saveImageToPrivateDir(uri)
            if (savedPath != null) {
                pickImageCallback?.invoke(savedPath)
            } else {
                android.widget.Toast.makeText(requireContext(), "图片保存失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBankDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper.getInstance(requireContext())
        bankId = arguments?.getLong(ARG_BANK_ID) ?: -1L

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnAddMoreQuestions.setOnClickListener {
            val banksFragment = parentFragmentManager.findFragmentByTag("banks") as? BanksFragment
            parentFragmentManager.popBackStack()
            banksFragment?.showGenerateDialogPublic(bankId, bank?.name)
        }

        binding.btnClearExamRecords.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清空考试记录")
                .setMessage("确定要清空「${bank?.name ?: "本题库"}」的所有考试记录吗？\n此操作不可撤销。")
                .setPositiveButton("清空") { _, _ ->
                    db.deleteExamRecordsByBank(bankId)
                    android.widget.Toast.makeText(requireContext(), "考试记录已清空", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnFeedback.setOnClickListener {
            showFeedbackDialog()
        }

        binding.btnPrintQuestions.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val questions = withContext(Dispatchers.IO) { db.getQuestionsByBank(bankId) }
                if (_binding == null) return@launch
                if (questions.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "暂无题目可打印", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("打印题目")
                    .setMessage("「${bank?.name ?: "题库"}」共 ${questions.size} 道题")
                    .setPositiveButton("仅题目") { _, _ ->
                        PrintHelper.printQuestions(requireContext(), bank?.name ?: "题库", questions, showAnswer = false)
                    }
                    .setNeutralButton("含答案解析") { _, _ ->
                        PrintHelper.printQuestions(requireContext(), bank?.name ?: "题库", questions, showAnswer = true)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        binding.btnShareOfflineBank.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val questions = withContext(Dispatchers.IO) { db.getQuestionsByBank(bankId) }
                if (_binding == null) return@launch
                if (questions.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "暂无题目可分享", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                OfflineExamHelper.shareOfflineExam(
                    context = requireContext(),
                    title = bank?.name ?: "题库",
                    questions = questions,
                    timeMinutes = 0,
                    typeScores = ""
                )
            }
        }

        binding.btnSharePdf.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val questions = withContext(Dispatchers.IO) { db.getQuestionsByBank(bankId) }
                if (_binding == null) return@launch
                if (questions.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "暂无题目可生成PDF", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("分享PDF")
                    .setMessage("「${bank?.name ?: "题库"}」共 ${questions.size} 道题")
                    .setPositiveButton("仅题目") { _, _ ->
                        PrintHelper.sharePdfQuestions(requireContext(), bank?.name ?: "题库", questions, showAnswer = false)
                    }
                    .setNeutralButton("含答案解析") { _, _ ->
                        PrintHelper.sharePdfQuestions(requireContext(), bank?.name ?: "题库", questions, showAnswer = true)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        loadBankDetail()
    }

    fun loadBankDetail() {
        // 取消上一次还未完成的加载，防止并发修改 View 树导致崩溃
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val (loadedBank, loadedQuestions, pendingCount) = withContext(Dispatchers.IO) {
                val b = db.getBank(bankId)
                val qs = if (b != null) db.getQuestionsByBank(bankId) else emptyList()
                val pending = if (b != null) db.getPendingFeedbackCountByBank(bankId) else 0
                Triple(b, qs, pending)
            }
            if (_binding == null) return@launch
            bank = loadedBank ?: return@launch
            binding.tvBankDetailName.text = bank!!.name
            val subject = bank!!.subject
            if (subject.isNotBlank()) {
                binding.tvBankDetailSubject.text = subject
                binding.tvBankDetailSubject.visibility = View.VISIBLE
            } else {
                binding.tvBankDetailSubject.visibility = View.GONE
            }
            if (bank!!.description.isNotBlank()) {
                binding.tvBankDetailDesc.text = bank!!.description
                binding.tvBankDetailDesc.visibility = View.VISIBLE
            } else {
                binding.tvBankDetailDesc.visibility = View.GONE
            }

            binding.tvBankDetailCount.text = "${loadedQuestions.size} 道题"
            binding.tvBankDetailTotalCount.text = loadedQuestions.size.toString()

            // 反馈徽章
            if (pendingCount > 0) {
                binding.btnFeedback.text = "⚠️ $pendingCount"
                binding.btnFeedback.visibility = View.VISIBLE
            } else {
                binding.btnFeedback.visibility = View.GONE
            }

            binding.questionListContainer.removeAllViews()
            if (loadedQuestions.isEmpty()) {
            binding.emptyQuestions.visibility = View.VISIBLE
            return@launch
        }
        binding.emptyQuestions.visibility = View.GONE

        val inflater = LayoutInflater.from(requireContext())
        val primaryColor = ContextCompat.getColor(requireContext(), R.color.primary)
        val textSecondaryColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val optPad = (6 * resources.displayMetrics.density).toInt()

        // 分批渲染：每批 20 条后 yield，避免主线程长时间阻塞触发 ANR
        loadedQuestions.chunked(20).forEachIndexed { chunkIdx, chunk ->
            chunk.forEachIndexed { localIdx, question ->
                val idx = chunkIdx * 20 + localIdx
                if (_binding == null) return@launch
                val item = inflater.inflate(R.layout.item_question, binding.questionListContainer, false)

                val indexTv = item.findViewById<TextView>(R.id.tvQuestionIndex)
                indexTv.text = (idx + 1).toString()
                indexTv.background.setTint(primaryColor)

                item.findViewById<TextView>(R.id.tvQuestionType).text = question.type.label
                item.findViewById<TextView>(R.id.tvDifficulty).text =
                    "难度 ${"★".repeat(question.difficulty)}${"☆".repeat(5 - question.difficulty)}"
                item.findViewById<TextView>(R.id.tvQuestionContent).text = question.content
                item.findViewById<TextView>(R.id.tvAnswer).text = question.answer

                // 选项
                val layoutOptions = item.findViewById<LinearLayout>(R.id.layoutOptions)
                if (question.options.isNotEmpty()) {
                    layoutOptions.visibility = View.VISIBLE
                    question.options.forEach { opt ->
                        val tv = TextView(requireContext()).apply {
                            text = opt
                            textSize = 13f
                            setTextColor(textSecondaryColor)
                            setPadding(0, optPad / 2, 0, optPad / 2)
                        }
                        layoutOptions.addView(tv)
                    }
                }

                // 解析
                val tvExpl = item.findViewById<TextView>(R.id.tvExplanation)
                if (question.explanation.isNotBlank()) {
                    tvExpl.text = "解析：${question.explanation}"
                    tvExpl.visibility = View.VISIBLE
                }

                // 编辑
                item.findViewById<MaterialButton>(R.id.btnEditQuestion).setOnClickListener {
                    showEditDialog(question)
                }

                // 删除
                item.findViewById<MaterialButton>(R.id.btnDeleteQuestion).setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("删除题目")
                        .setMessage("确定要删除这道题目吗？")
                        .setPositiveButton("删除") { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    db.deleteQuestion(question.id)
                                    db.updateBankQuestionCount(bankId, db.getQuestionCount(bankId))
                                }
                                loadBankDetail()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }

                binding.questionListContainer.addView(item)
            }
            // 每批渲染完后暂让主线程处理其他事件，避免 ANR
            yield()
        }
    } // end launch
    } // end loadBankDetail

    /** 将 imageUrl （/api/img/xxx）加载到 ImageView */
    private fun loadImageIntoView(imageUrl: String, iv: ImageView) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val fileName = imageUrl.removePrefix("/api/img/")
                    val file = java.io.File(requireContext().filesDir, "question_images/$fileName")
                    if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null
                } catch (e: Exception) { null }
            }
            if (_binding == null) return@launch
            if (bitmap != null) iv.setImageBitmap(bitmap) else iv.setImageDrawable(null)
        }
    }

    /**
     * 将用户选择的图片复制到应用私有目录
     */
    private fun saveImageToPrivateDir(uri: Uri): String? {
        return try {
            val ext = when (requireContext().contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val fileName = "img_${System.currentTimeMillis()}.$ext"
            val dir = java.io.File(requireContext().filesDir, "question_images")
            dir.mkdirs()
            val dest = java.io.File(dir, fileName)
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            "/api/img/$fileName"
        } catch (e: Exception) {
            null
        }
    }

    private fun showEditDialog(question: Question) {
        val dialog = BottomSheetDialog(requireContext(), R.style.ThemeOverlay_App_BottomSheetDialog)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_question, null)
        dialog.setContentView(dialogView)

        val etContent = dialogView.findViewById<TextInputEditText>(R.id.etEditContent)
        val etAnswer = dialogView.findViewById<TextInputEditText>(R.id.etEditAnswer)
        val etExpl = dialogView.findViewById<TextInputEditText>(R.id.etEditExplanation)
        val layoutEditOptions = dialogView.findViewById<LinearLayout>(R.id.layoutEditOptions)
        val ivEditImage = dialogView.findViewById<ImageView>(R.id.ivEditImage)
        val btnPickImage = dialogView.findViewById<MaterialButton>(R.id.btnPickImage)
        val btnRemoveImage = dialogView.findViewById<MaterialButton>(R.id.btnRemoveImage)

        etContent.setText(question.content)
        etAnswer.setText(question.answer)
        etExpl.setText(question.explanation)

        // 初始化图片区域
        var currentImageUrl = question.imageUrl
        fun refreshImageArea() {
            if (currentImageUrl.isNotBlank()) {
                ivEditImage.visibility = View.VISIBLE
                btnRemoveImage.visibility = View.VISIBLE
                // imageUrl可能是 /api/img/xxx，通过本地HTTP服务器加载
                val displayUrl = currentImageUrl
                loadImageIntoView(displayUrl, ivEditImage)
            } else {
                ivEditImage.visibility = View.GONE
                btnRemoveImage.visibility = View.GONE
            }
        }
        refreshImageArea()

        pickImageCallback = { path ->
            currentImageUrl = path
            refreshImageArea()
        }
        btnPickImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        btnRemoveImage.setOnClickListener {
            currentImageUrl = ""
            refreshImageArea()
        }

        // 选项编辑（选择题）
        val optionInputs = mutableListOf<TextInputEditText>()
        if (question.options.isNotEmpty()) {
            layoutEditOptions.visibility = View.VISIBLE
            val labels = listOf("A", "B", "C", "D", "E", "F")
            question.options.forEachIndexed { index, opt ->
                val til = TextInputLayout(requireContext(), null,
                    com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { lp -> lp.bottomMargin = (8 * resources.displayMetrics.density).toInt() }
                    hint = "选项 ${labels.getOrElse(index) { (index + 1).toString() }}"
                }
                val et = TextInputEditText(til.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setText(opt)
                    textSize = 14f
                }
                til.addView(et)
                layoutEditOptions.addView(til)
                optionInputs.add(et)
            }
        }

        dialogView.findViewById<MaterialButton>(R.id.btnEditCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<MaterialButton>(R.id.btnEditSave).setOnClickListener {
            val newContent = etContent.text.toString().trim()
            val newAnswer = etAnswer.text.toString().trim()
            if (newContent.isBlank() || newAnswer.isBlank()) {
                android.widget.Toast.makeText(requireContext(), "题目和答案不能为空", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val newOptions = if (optionInputs.isNotEmpty()) {
                optionInputs.map { it.text.toString() }
            } else {
                question.options
            }
            val updated = question.copy(
                content = newContent,
                answer = newAnswer,
                explanation = etExpl.text.toString().trim(),
                options = newOptions,
                imageUrl = currentImageUrl
            )
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.updateQuestion(updated) }
                dialog.dismiss()
                loadBankDetail()
            }
        }

        dialog.show()
    }

    private fun showFeedbackDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (feedbacks, questions) = withContext(Dispatchers.IO) {
                val fb = db.getFeedbackByBank(bankId)
                val qs = db.getQuestionsByBank(bankId).associateBy { it.id }
                Pair(fb, qs)
            }
            if (_binding == null) return@launch
            if (feedbacks.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "暂无待处理反馈", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val items = feedbacks.map { f ->
                val resolved = if (f.isResolved) "✅" else "⚠️"
                val qContent = questions[f.questionId]?.content?.take(20) ?: "题目#${f.questionId}"
                "$resolved [${f.username}] $qContent\n${f.content}"
            }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("题目错误反馈（${feedbacks.count { !it.isResolved }} 条待处理）")
                .setItems(items) { _, which ->
                    val feedback = feedbacks[which]
                    val question = questions[feedback.questionId]
                    if (question != null) {
                        if (!feedback.isResolved) {
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                db.resolveFeedback(feedback.id)
                            }
                        }
                        showEditDialog(question)
                        loadBankDetail()
                    } else {
                        android.widget.Toast.makeText(requireContext(), "题目已删除", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
