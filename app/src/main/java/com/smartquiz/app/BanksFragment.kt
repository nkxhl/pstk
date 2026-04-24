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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.smartquiz.R
import com.smartquiz.api.LLMApiService
import com.smartquiz.api.LocalOcrService
import com.smartquiz.databinding.FragmentBanksBinding
import com.smartquiz.db.DatabaseHelper
import com.smartquiz.model.QuestionBank
import com.smartquiz.model.QuestionType
import com.smartquiz.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class BanksFragment : Fragment() {

    companion object {
        private const val TAG = "BanksFragment"

        private val SUBJECTS = arrayOf(
            "语文", "数学", "英语", "物理", "化学", "生物", "历史", "地理", "政治",
            "道德与法治", "科学", "信息技术", "体育", "音乐", "美术",
            "数学（小学）", "语文（小学）", "品德与社会",
            "机械基础", "电工基础", "电子技术", "计算机应用", "会计基础",
            "经济政治与社会", "职业道德", "哲学与人生"
        )
    }

    private var _binding: FragmentBanksBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: DatabaseHelper
    private lateinit var llmService: LLMApiService
    private val ocrService = LocalOcrService()

    // 识别模式：false=普通文本(OCR)  true=图表公式(视觉模型)
    private var isVisionMode = false

    // 当前 dialog 状态
    private var dialogImageUris = mutableListOf<Uri>()
    private var currentDialog: BottomSheetDialog? = null
    private var addToExistingBankId: Long = -1L  // -1 = 新建, >0 = 追加到已有题库

    // 拍照临时文件 Uri
    private var cameraPhotoUri: Uri? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            dialogImageUris.clear()
            dialogImageUris.addAll(uris)
            updatePickerLabel()
        }
    }

    // 选择任意类型文件（支持图片/PDF/WORD/TXT/MD）
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            dialogImageUris.clear()
            dialogImageUris.addAll(uris)
            // 持久化权限（部分设备需要）
            uris.forEach { uri ->
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            updatePickerLabel()
        }
    }

    // 拍单张照片
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraPhotoUri?.let { uri ->
                dialogImageUris.add(uri)
                updatePickerLabel()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) launchFilePicker()
        else Toast.makeText(requireContext(), "需要存储权限", Toast.LENGTH_SHORT).show()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) launchCamera()
        else Toast.makeText(requireContext(), "需要相机权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBanksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper.getInstance(requireContext())
        llmService = SmartQuizApplication.llmService

        binding.btnNewBank.setOnClickListener { showGenerateDialog(-1L, null) }
        binding.btnHelp.setOnClickListener { (activity as? MainActivity)?.showHelp() }
        refreshBankList()
    }

    fun refreshBankList() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 后台线程读取数据库，避免压测后数据库繁忙时卡死主线程
            val banks = withContext(Dispatchers.IO) { db.getAllBanks() }

            if (_binding == null) return@launch
            binding.tvBankCount.text = "${banks.size} 个题库"
            binding.bankListContainer.removeAllViews()

            if (banks.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                return@launch
            }
            binding.emptyState.visibility = View.GONE

            val inflater = LayoutInflater.from(requireContext())
            banks.forEach { bank ->
                // 后台查询每个题库的反馈统计
                val pendingFeedback = withContext(Dispatchers.IO) { db.getPendingFeedbackCountByBank(bank.id) }
                val blockedCount = withContext(Dispatchers.IO) { db.getBlockedQuestionIds(bank.id).size }

                if (_binding == null) return@launch
                val item = inflater.inflate(R.layout.item_bank, binding.bankListContainer, false)

                item.findViewById<TextView>(R.id.tvBankName).text = bank.name

                val subjectTv = item.findViewById<TextView>(R.id.tvBankSubject)
                if (bank.subject.isNotBlank()) {
                    subjectTv.text = bank.subject
                    subjectTv.visibility = View.VISIBLE
                } else {
                    subjectTv.visibility = View.GONE
                }
                item.findViewById<TextView>(R.id.tvBankCount).text = "${bank.questionCount} 道题"
                item.findViewById<TextView>(R.id.tvBankTime).text = TimeUtils.formatTime(bank.createdAt)

                val layoutStats = item.findViewById<android.view.View>(R.id.layoutFeedbackStats)
                val tvPending = item.findViewById<TextView>(R.id.tvPendingFeedback)
                val tvBlocked = item.findViewById<TextView>(R.id.tvBlockedCount)
                if (pendingFeedback > 0 || blockedCount > 0) {
                    layoutStats.visibility = View.VISIBLE
                    if (pendingFeedback > 0) {
                        tvPending.text = "⚠️ ${pendingFeedback}条反馈"
                        tvPending.visibility = View.VISIBLE
                    }
                    if (blockedCount > 0) {
                        tvBlocked.text = "🚫 ${blockedCount}题屏蔽"
                        tvBlocked.visibility = View.VISIBLE
                    }
                }

                // 点击整个 item 进入题库详情
                item.setOnClickListener {
                    openBankDetail(bank.id)
                }

                // 分享题库
                item.findViewById<MaterialButton>(R.id.btnShareBank).setOnClickListener {
                    shareBank(bank)
                }

                // 继续添加题目
                item.findViewById<MaterialButton>(R.id.btnAddQuestions).setOnClickListener {
                    showGenerateDialog(bank.id, bank.name)
                }

                // 删除题库
                item.findViewById<MaterialButton>(R.id.btnDeleteBank).setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("删除题库")
                        .setMessage("确定要删除「${bank.name}」及其所有题目吗？")
                        .setPositiveButton("删除") { _, _ ->
                            db.deleteBank(bank.id)
                            refreshBankList()
                            Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }

                binding.bankListContainer.addView(item)
            }
        }
    }

    private fun openBankDetail(bankId: Long) {
        val detail = BankDetailFragment.newInstance(bankId)
        parentFragmentManager.beginTransaction()
            .add(R.id.contentFrame, detail, "bank_detail_$bankId")
            .addToBackStack("bank_detail")
            .commit()
        // 隐藏底部导航
        activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
            ?.visibility = View.GONE
    }

    /** 供 BankDetailFragment 回调使用 */
    fun showGenerateDialogPublic(bankId: Long, bankName: String?) = showGenerateDialog(bankId, bankName)

    /** 由 MainActivity 调用：携带外部分享的 URI 预填后打开生成对话框 */
    fun showGenerateDialogWithUri(uri: Uri, bankId: Long = -1L, bankName: String? = null) {
        dialogImageUris.clear()
        dialogImageUris.add(uri)
        showGenerateDialog(bankId, bankName)
    }

    /**
     * 弹出生成题目的 BottomSheet
     * @param bankId  -1 = 新建题库；>0 = 追加题目到已有题库
     * @param bankName 已有题库名（追加模式下使用）
     */
    private fun showGenerateDialog(bankId: Long, bankName: String?) {
        addToExistingBankId = bankId
        // 每次打开对话框时清空上次残留的图片（外部分享场景由 showGenerateDialogWithUri 在调用前预填）
        dialogImageUris.clear()
        cameraPhotoUri = null
        isVisionMode = false

        val dialog = BottomSheetDialog(requireContext(), R.style.ThemeOverlay_App_BottomSheetDialog)
        currentDialog = dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_bank, null)
        dialog.setContentView(dialogView)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val tilBankName = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilBankName)
        val etBankName = dialogView.findViewById<TextInputEditText>(R.id.etBankName)
        val etSubject = dialogView.findViewById<AutoCompleteTextView>(R.id.etSubject)
        val etCount = dialogView.findViewById<TextInputEditText>(R.id.etQuestionCount)
        val etExtraPrompt = dialogView.findViewById<TextInputEditText>(R.id.etExtraPrompt)
        val cbTypeSingle = dialogView.findViewById<CheckBox>(R.id.cbTypeSingle)
        val cbTypeMultiple = dialogView.findViewById<CheckBox>(R.id.cbTypeMultiple)
        val cbTypeTrueFalse = dialogView.findViewById<CheckBox>(R.id.cbTypeTrueFalse)
        val cbTypeFillBlank = dialogView.findViewById<CheckBox>(R.id.cbTypeFillBlank)
        val cbTypeShortAnswer = dialogView.findViewById<CheckBox>(R.id.cbTypeShortAnswer)
        val layoutPicker = dialogView.findViewById<View>(R.id.layoutImagePicker)
        val layoutCamera = dialogView.findViewById<View>(R.id.layoutCamera)
        val rgInputMode = dialogView.findViewById<RadioGroup>(R.id.rgInputMode)
        val tvModeHint = dialogView.findViewById<TextView>(R.id.tvModeHint)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmGenerate)

        // 科目下拉列表
        val subjectAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, SUBJECTS)
        etSubject.setAdapter(subjectAdapter)
        etSubject.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) etSubject.showDropDown() }

        // 模式切换
        rgInputMode.setOnCheckedChangeListener { _, checkedId ->
            isVisionMode = (checkedId == R.id.rbModeVision)
            tvModeHint.text = if (isVisionMode)
                "图表公式：文件直接发送给AI视觉模型，可精准识别公式/图表，速度较慢"
            else
                "普通文本：先OCR识别再生成，速度快；适合文字为主的课本"
        }

        if (bankId > 0) {
            tvTitle.text = "继续添加题目"
            etBankName.setText(bankName)
            tilBankName.isEnabled = false
            btnConfirm.text = "添加题目"
        } else {
            tvTitle.text = "新建题库"
            tilBankName.isEnabled = true
        }

        layoutPicker.setOnClickListener { checkPermissionsAndPick() }
        layoutCamera.setOnClickListener { checkCameraAndLaunch() }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val name = if (bankId > 0) bankName ?: "" else etBankName.text.toString().trim()
            val subject = etSubject.text.toString().trim()
            val rawCount = etCount.text.toString().toIntOrNull() ?: 20
            val count = rawCount.coerceIn(1, 50)
            if (rawCount > 50) {
                etCount.setText("50")
                Toast.makeText(requireContext(), "题目数量最多50题，已自动调整", Toast.LENGTH_SHORT).show()
            }
            val extraPrompt = etExtraPrompt.text.toString().trim()
            val selectedTypes = mutableListOf<QuestionType>().apply {
                if (cbTypeSingle.isChecked) add(QuestionType.SINGLE_CHOICE)
                if (cbTypeMultiple.isChecked) add(QuestionType.MULTIPLE_CHOICE)
                if (cbTypeTrueFalse.isChecked) add(QuestionType.TRUE_FALSE)
                if (cbTypeFillBlank.isChecked) add(QuestionType.FILL_BLANK)
                if (cbTypeShortAnswer.isChecked) add(QuestionType.SHORT_ANSWER)
            }.ifEmpty {
                listOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE)
            }

            if (name.isBlank() && bankId < 0) {
                tilBankName.error = "请输入题库名称"
                return@setOnClickListener
            }
            if (dialogImageUris.isEmpty()) {
                Toast.makeText(requireContext(), "请先选择文件或拍照", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!llmService.isConfigured()) {
                Toast.makeText(requireContext(), "请先在「设置」页面配置 API Key", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            startGeneratePipeline(
                targetBankId = bankId,
                newBankName = name,
                subject = subject,
                questionCount = count,
                extraPrompt = extraPrompt,
                fileUris = dialogImageUris.toList(),
                visionMode = isVisionMode,
                questionTypes = selectedTypes
            )
        }

        dialog.show()
    }

    private fun updatePickerLabel() {
        val count = dialogImageUris.size
        currentDialog?.findViewById<TextView>(R.id.tvSelectedImages)?.apply {
            text = "已选择 $count 个文件"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
        }
    }

    private fun checkPermissionsAndPick() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        val need = perms.any { ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED }
        if (need) permissionLauncher.launch(perms) else launchFilePicker()
    }

    private fun launchFilePicker() {
        // 支持图片、PDF、WORD、TXT、MD等所有文本/文档格式
        filePickerLauncher.launch(arrayOf(
            "image/*",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
            "text/*"
        ))
    }

    private fun checkCameraAndLaunch() {
        val camPerm = Manifest.permission.CAMERA
        val storagePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

        val permsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(requireContext(), camPerm) != PackageManager.PERMISSION_GRANTED)
            permsNeeded.add(camPerm)
        if (ContextCompat.checkSelfPermission(requireContext(), storagePerm) != PackageManager.PERMISSION_GRANTED)
            permsNeeded.add(storagePerm)

        if (permsNeeded.isNotEmpty()) {
            cameraPermissionLauncher.launch(permsNeeded.toTypedArray())
        } else {
            launchCamera()
        }
    }

    private fun launchCamera() {
        val photoFile = File(requireContext().cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            photoFile
        )
        cameraPhotoUri = uri
        cameraLauncher.launch(uri)
    }

    private fun startGeneratePipeline(
        targetBankId: Long,
        newBankName: String,
        subject: String,
        questionCount: Int?,
        extraPrompt: String,
        fileUris: List<Uri>,
        visionMode: Boolean,
        questionTypes: List<QuestionType> = listOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE)
    ) {
        val modeLabel = if (visionMode) "图表公式模式" else "普通文本模式"
        val modelName = if (visionMode) llmService.getVisionModel() else llmService.getModel()
        showProgress(true, "正在准备文件… [$modeLabel]")

        lifecycleScope.launch {
            try {
                // Step 1: 确定题库 ID
                updateProgress("准备题库… [$modeLabel]")
                val bankId = if (targetBankId > 0) {
                    targetBankId
                } else {
                    val autoName = newBankName.ifBlank { "题库_${System.currentTimeMillis() / 1000}" }
                    val bank = QuestionBank(
                        name = autoName, subject = subject,
                        description = "从 ${fileUris.size} 个文件中AI生成",
                        sourceText = ""
                    )
                    withContext(Dispatchers.IO) { db.insertBank(bank) }
                }

                val questions = if (visionMode) {
                    // ===== 图表公式模式：文件直接发送给视觉模型 =====
                    updateProgress("正在处理文件（${fileUris.size} 个）… [图表公式模式]")
                    val imageData = withContext(Dispatchers.IO) {
                        fileUris.mapNotNull { uri -> encodeFileToBase64ForVision(uri) }
                    }
                    if (imageData.isEmpty()) throw RuntimeException("没有可处理的图片文件，图表公式模式仅支持图片格式")
                    val base64List = imageData.map { it.first }
                    val mimeTypes = imageData.map { it.second }

                    val countHint = if (questionCount != null) "生成 $questionCount 道题" else "自动出题"
                    updateProgress("AI 视觉识别中… [$countHint]\n模式：图表公式  模型：$modelName")

                    withContext(Dispatchers.IO) {
                        llmService.generateQuestionsFromImages(
                            imageBase64List = base64List,
                            imageMimeTypes = mimeTypes,
                            subject = subject,
                            questionCount = questionCount,
                            types = questionTypes,
                            extraPrompt = extraPrompt
                        )
                    }
                } else {
                    // ===== 普通文本模式：提取文本后交给文本模型 =====
                    updateProgress("正在提取文本内容（${fileUris.size} 个文件）… [普通文本模式]")
                    val extractedText = withContext(Dispatchers.IO) {
                        extractTextFromFiles(fileUris)
                    }
                    if (extractedText.isBlank()) throw RuntimeException("未能从文件中提取到任何文本内容")
                    Log.d(TAG, "提取文本长度: ${extractedText.length} 字符")

                    val countHint = if (questionCount != null) "生成 $questionCount 道题" else "自动出题"
                    updateProgress("AI 生成题目中… [$countHint]\n模式：普通文本  模型：$modelName")

                    withContext(Dispatchers.IO) {
                        llmService.generateQuestions(
                            text = extractedText,
                            subject = subject,
                            questionCount = questionCount,
                            types = questionTypes,
                            extraPrompt = extraPrompt
                        )
                    }
                }

                Log.d(TAG, "AI returned ${questions.size} questions")

                // Step 3: 保存
                updateProgress("正在保存题目…")
                val withBank = questions.map { it.copy(bankId = bankId) }
                withContext(Dispatchers.IO) {
                    db.insertQuestions(withBank)
                    val totalCount = db.getQuestionsByBank(bankId).size
                    db.updateBankQuestionCount(bankId, totalCount)
                }

                showProgress(false)
                Toast.makeText(
                    requireContext(),
                    "✅ 成功生成 ${withBank.size} 道题目！",
                    Toast.LENGTH_LONG
                ).show()
                refreshBankList()

            } catch (e: Exception) {
                Log.e(TAG, "Pipeline failed", e)
                showProgress(false)
                com.smartquiz.util.DebugHelper.copyErrorIfDebug(requireContext(), e, "题库生成")
                AlertDialog.Builder(requireContext())
                    .setTitle("处理失败")
                    .setMessage(e.message ?: "未知错误")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    /**
     * 从各类文件中提取纯文本：
     * - 图片：使用 OCR 识别
     * - PDF：提取文字层（PdfRenderer只能渲染，直接用 Android PdfRenderer → bitmap → OCR）
     * - DOCX：解析 word/document.xml
     * - TXT/MD：直接读取
     */
    private suspend fun extractTextFromFiles(uris: List<Uri>): String {
        val context = requireContext()
        val sb = StringBuilder()
        uris.forEachIndexed { index, uri ->
            if (index > 0) sb.append("\n\n---\n\n")
            val mime = context.contentResolver.getType(uri) ?: ""
            val text = when {
                mime.startsWith("image/") -> {
                    // 图片 → OCR
                    try { ocrService.recognizeImage(context, uri) }
                    catch (e: Exception) { Log.w(TAG, "OCR失败: $uri", e); "" }
                }
                mime == "application/pdf" -> {
                    // PDF → 每页渲染为 bitmap → OCR
                    extractTextFromPdf(uri)
                }
                mime == "application/msword" ||
                mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                    // DOCX → 解析XML
                    extractTextFromDocx(uri)
                }
                else -> {
                    // TXT / MD / 其他文本类型 → 直接读取
                    try {
                        context.contentResolver.openInputStream(uri)?.use { it.reader(Charsets.UTF_8).readText() } ?: ""
                    } catch (e: Exception) { Log.w(TAG, "读取文本失败: $uri", e); "" }
                }
            }
            sb.append(text)
        }
        return sb.toString()
    }

    /**
     * PDF文件：用 android.graphics.pdf.PdfRenderer 将每页渲染成 bitmap，再 OCR
     * - 最多处理 50 页（避免超长PDF卡顿）
     * - 渲染分辨率 3x，提升 OCR 识别率
     * - Bitmap 先填充白色背景，避免透明导致识别失败
     */
    private suspend fun extractTextFromPdf(uri: Uri): String {
        val context = requireContext()
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext ""
                pfd.use { fd ->
                    val renderer = android.graphics.pdf.PdfRenderer(fd)
                    val pageCount = minOf(renderer.pageCount, 50) // 最多处理50页
                    for (i in 0 until pageCount) {
                        val page = renderer.openPage(i)
                        // 3x 分辨率，提升 OCR 识别率
                        val scale = 3
                        val bmp = Bitmap.createBitmap(
                            page.width * scale, page.height * scale,
                            Bitmap.Config.ARGB_8888
                        )
                        // 先填充白色背景（PDF 透明背景会导致 OCR 识别黑屏）
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        page.close()

                        val tempFile = File(context.cacheDir, "pdf_p${i}_${System.currentTimeMillis()}.jpg")
                        tempFile.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        bmp.recycle()

                        val tempUri = Uri.fromFile(tempFile)
                        try {
                            val pageText = ocrService.recognizeImage(context, tempUri)
                            if (pageText.isNotBlank()) {
                                if (sb.isNotEmpty()) sb.append("\n")
                                sb.append("【第${i + 1}页】\n")
                                sb.append(pageText)
                            }
                        } finally {
                            tempFile.delete()
                        }
                    }
                    renderer.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "PDF处理失败", e)
            }
            sb.toString()
        }
    }

    /**
     * DOCX文件：解压ZIP，读取 word/document.xml，提取纯文本
     */
    private fun extractTextFromDocx(uri: Uri): String {
        val context = requireContext()
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return ""
            val bytes = inputStream.use { it.readBytes() }
            val zip = java.util.zip.ZipInputStream(bytes.inputStream())
            var text = ""
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xmlContent = zip.readBytes().toString(Charsets.UTF_8)
                    // 用正则提取所有<w:t>标签内容
                    val pattern = Regex("<w:t[^>]*>([^<]*)</w:t>")
                    text = pattern.findAll(xmlContent).map { it.groupValues[1] }.joinToString(" ")
                    break
                }
                entry = zip.nextEntry
            }
            zip.close()
            text
        } catch (e: Exception) {
            Log.w(TAG, "DOCX处理失败", e)
            ""
        }
    }

    /**
     * 将图片类型文件编码为 base64 供视觉模型使用（仅支持图片）
     */
    private fun encodeFileToBase64ForVision(uri: Uri): Pair<String, String>? {
        val mime = requireContext().contentResolver.getType(uri) ?: ""
        if (!mime.startsWith("image/")) return null  // 图表公式模式只处理图片
        return try { encodeImageToBase64(uri) } catch (e: Exception) { null }
    }

    /**
     * 将图片Uri转换为base64编码
     * 自动压缩大图以适配API传输限制（目标单张不超过1MB base64）
     * @return Pair<base64字符串, MIME类型>
     */
    private fun encodeImageToBase64(uri: Uri): Pair<String, String> {
        val context = requireContext()
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw RuntimeException("无法读取图片")
        val originalBytes = inputStream.use { it.readBytes() }

        // 检测MIME类型
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"

        // 如果图片较小（<500KB），直接编码
        if (originalBytes.size < 500 * 1024) {
            return Pair(Base64.encodeToString(originalBytes, Base64.NO_WRAP), mimeType)
        }

        // 大图需要压缩：解码 → 缩放 → 重新编码为JPEG
        val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
            ?: throw RuntimeException("图片解码失败")

        // 计算缩放比例，长边不超过2048像素
        val maxSide = 2048
        val scale = if (bitmap.width > maxSide || bitmap.height > maxSide) {
            maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        } else 1f

        val scaledBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        if (scaledBitmap !== bitmap) scaledBitmap.recycle()
        bitmap.recycle()

        val compressedBytes = outputStream.toByteArray()
        Log.d(TAG, "图片压缩: ${originalBytes.size / 1024}KB → ${compressedBytes.size / 1024}KB")
        return Pair(Base64.encodeToString(compressedBytes, Base64.NO_WRAP), "image/jpeg")
    }

    private fun showProgress(show: Boolean, message: String = "") {
        activity?.runOnUiThread {
            binding.cardProgress.visibility = if (show) View.VISIBLE else View.GONE
            if (message.isNotBlank()) binding.tvProgress.text = message
        }
    }

    private fun updateProgress(message: String) {
        activity?.runOnUiThread { binding.tvProgress.text = message }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 导出题库为 .sqb 文件并通过系统分享发送 */
    private fun shareBank(bank: QuestionBank) {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    db.exportBankToJson(bank.id)
                } ?: run {
                    Toast.makeText(requireContext(), "导出题库失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val fileName = "${bank.name}.sqb"
                val shareDir = File(requireContext().cacheDir, "share")
                shareDir.mkdirs()
                val file = File(shareDir, fileName)
                withContext(Dispatchers.IO) {
                    file.outputStream().use { fos ->
                        GZIPOutputStream(fos).use { gzip ->
                            gzip.write(json.toByteArray(Charsets.UTF_8))
                        }
                    }
                }

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "题库分享：${bank.name}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "分享题库「${bank.name}」"))
            } catch (e: Exception) {
                Log.e(TAG, "Share bank failed", e)
                Toast.makeText(requireContext(), "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 从外部 Uri 导入题库文件 */
    fun importBankFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        try {
                            GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        } catch (e: java.util.zip.ZipException) {
                            // 兼容未压缩的旧文件
                            requireContext().contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        }
                    }
                }
                if (json.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "无法读取文件内容", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val bankId = withContext(Dispatchers.IO) {
                    db.importBankFromJson(json)
                }

                if (bankId > 0) {
                    Toast.makeText(requireContext(), "✅ 题库导入成功！", Toast.LENGTH_LONG).show()
                    refreshBankList()
                } else {
                    Toast.makeText(requireContext(), "导入失败：文件格式不正确", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import bank failed", e)
                Toast.makeText(requireContext(), "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
