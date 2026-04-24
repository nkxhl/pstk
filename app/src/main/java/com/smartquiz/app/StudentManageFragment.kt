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
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.smartquiz.R
import com.smartquiz.api.LLMApiService
import com.smartquiz.api.LocalOcrService
import com.smartquiz.databinding.FragmentStudentManageBinding
import com.smartquiz.db.DatabaseHelper
import com.smartquiz.model.User
import com.smartquiz.server.WebServerService
import com.smartquiz.util.ContentFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudentManageFragment : Fragment() {

    companion object {
        fun newInstance() = StudentManageFragment()

        /** 常用中国姓氏（含单姓和复姓） */
        private val CHINESE_SURNAMES = setOf(
            "赵","钱","孙","李","周","吴","郑","王","冯","陈","褚","卫","蒋","沈","韩","杨",
            "朱","秦","尤","许","何","吕","施","张","孔","曹","严","华","金","魏","陶","姜",
            "戚","谢","邹","喻","柏","水","窦","章","云","苏","潘","葛","奚","范","彭","郎",
            "鲁","韦","昌","马","苗","凤","花","方","俞","任","袁","柳","酆","鲍","史","唐",
            "费","廉","岑","薛","雷","贺","倪","汤","滕","殷","罗","毕","郝","邬","安","常",
            "乐","于","时","傅","皮","卞","齐","康","伍","余","元","卜","顾","孟","平","黄",
            "和","穆","萧","尹","姚","邵","湛","汪","祁","毛","禹","狄","米","贝","明","臧",
            "计","伏","成","戴","谈","宋","茅","庞","熊","纪","舒","屈","项","祝","董","梁",
            "杜","阮","蓝","闵","席","季","麻","强","贾","路","娄","危","江","童","颜","郭",
            "梅","盛","林","刁","钟","徐","邱","骆","高","夏","蔡","田","樊","胡","凌","霍",
            "虞","万","支","柯","昝","管","卢","莫","经","房","裘","缪","干","解","应","宗",
            "丁","宣","贲","邓","郁","单","杭","洪","包","诸","左","石","崔","吉","钮","龚",
            "程","嵇","邢","滑","裴","陆","荣","翁","荀","羊","於","惠","甄","曲","家","封",
            "芮","羿","储","靳","汲","邴","糜","松","井","段","富","巫","乌","焦","巴","弓",
            "牧","隗","山","谷","车","侯","宓","蓬","全","郗","班","仰","秋","仲","伊","宫",
            "宁","仇","栾","暴","甘","钭","厉","戎","祖","武","符","刘","景","詹","束","龙",
            "叶","幸","司","韶","郜","黎","蓟","溥","印","宿","白","怀","蒲","邰","从","鄂",
            "索","咸","籍","赖","卓","蔺","屠","蒙","池","乔","阴","胥","能","苍","双","闻",
            "莘","党","翟","谭","贡","劳","逄","姬","申","扶","堵","冉","宰","郦","雍","却",
            "璩","桑","桂","濮","牛","寿","通","边","扈","燕","冀","浦","尚","农","温","别",
            "庄","晏","柴","瞿","阎","充","慕","连","茹","习","宦","艾","鱼","容","向","古",
            "易","慎","戈","廖","庾","终","暨","居","衡","步","都","耿","满","弘","匡","国",
            "文","寇","广","禄","阙","东","欧","殳","沃","利","蔚","越","夔","隆","师","巩",
            "厍","聂","晁","勾","敖","融","冷","訾","辛","阚","那","简","饶","空","曾","毋",
            "沙","乜","养","鞠","须","丰","巢","关","蒯","相","查","后","荆","红","游","竺",
            "权","逯","盖","益","桓","公","万俟","司马","上官","欧阳","夏侯","诸葛","闻人",
            "东方","赫连","皇甫","尉迟","公羊","澹台","公冶","宗政","濮阳","淳于","单于",
            "太叔","申屠","公孙","仲孙","轩辕","令狐","钟离","宇文","长孙","慕容","鲜于",
            "闾丘","司徒","司空","亓官","司寇","仉","督","子车","颛孙","端木","巫马","公西",
            "漆雕","乐正","壤驷","公良","拓跋","夹谷","宰父","谷梁","晋","楚","闫","法",
            "汝","鄢","涂","钦","佘","牟","商","佟","卞","靖","蒲","邝","竹","奉","甄"
        )
    }

    private var _binding: FragmentStudentManageBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: DatabaseHelper
    private lateinit var ocrService: LocalOcrService
    private lateinit var llmService: LLMApiService

    private var selectedClassFilter: String? = null
    private var batchImageUris = mutableListOf<Uri>()
    private var currentBatchDialog: BottomSheetDialog? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            batchImageUris.clear()
            batchImageUris.addAll(uris)
            currentBatchDialog?.findViewById<TextView>(R.id.tvSelectedImages)?.apply {
                text = "已选择 ${uris.size} 张图片"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) imagePickerLauncher.launch("image/*")
        else Toast.makeText(requireContext(), "需要存储权限", Toast.LENGTH_SHORT).show()
    }

    // 在线用户ID集合（从WebServer获取）
    private fun getOnlineUserIds(): Set<Long> {
        return try {
            if (WebServerService.isRunning) {
                WebServerService.serverInstance?.getOnlineUserIds() ?: emptySet()
            } else emptySet()
        } catch (e: Exception) { emptySet() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStudentManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper.getInstance(requireContext())
        ocrService = LocalOcrService()
        llmService = SmartQuizApplication.llmService

        // 作为底部 tab 时隐藏返回按钮和"组织考试"按钮（考试有独立 tab）
        val isBottomTab = parentFragmentManager.backStackEntryCount == 0 ||
            parentFragmentManager.findFragmentByTag("students") === this
        binding.btnBack.visibility = if (isBottomTab) View.GONE else View.VISIBLE
        binding.btnOrganizeExam.visibility = if (isBottomTab) View.GONE else View.VISIBLE

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnHelp.setOnClickListener { (activity as? MainActivity)?.showHelp() }
        binding.btnAddStudent.setOnClickListener { showAddStudentDialog(null) }
        binding.btnBatchAdd.setOnClickListener { showBatchAddDialog() }
        binding.btnBroadcastMessage.setOnClickListener { showBroadcastMessageDialog() }
        binding.btnBatchGroup.setOnClickListener { showBatchGroupDialog() }
        binding.btnBatchDelete.setOnClickListener { showBatchDeleteDialog() }
        binding.btnOrganizeExam.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .add(R.id.contentFrame, OrganizeExamFragment.newInstance(), "organize_exam")
                .addToBackStack("organize_exam")
                .commit()
        }

        loadClassChips()
        loadStudentList()
    }

    private fun loadClassChips() {
        viewLifecycleOwner.lifecycleScope.launch {
            val classes = withContext(Dispatchers.IO) { db.getDistinctClassNames() }
            if (_binding == null) return@launch
                binding.chipGroupClass.removeAllViews()
            if (classes.isEmpty()) {
                binding.cardClassFilter.visibility = View.GONE
                return@launch
            }
        binding.cardClassFilter.visibility = View.VISIBLE

        val chipAll = Chip(requireContext()).apply {
            text = "全部"
            isCheckable = true
            isChecked = selectedClassFilter == null
            setOnClickListener {
                selectedClassFilter = null
                loadClassChips()
                loadStudentList()
            }
        }
        binding.chipGroupClass.addView(chipAll)

        classes.forEach { cn ->
            val chip = Chip(requireContext()).apply {
                text = cn
                isCheckable = true
                isChecked = selectedClassFilter == cn
                setOnClickListener {
                    selectedClassFilter = cn
                    loadClassChips()
                    loadStudentList()
                }
            }
            binding.chipGroupClass.addView(chip)
        }
    } // end launch
    } // end loadClassChips

    private var studentAdapter: StudentAdapter? = null

    fun loadStudentList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val allUsers = withContext(Dispatchers.IO) {
                if (selectedClassFilter != null)
                    db.getUsersByClassName(selectedClassFilter!!)
                else
                    db.getAllUsers()
            }
            if (_binding == null) return@launch
            val onlineIds = getOnlineUserIds()

            binding.tvStudentCount.text = "共 ${allUsers.size} 名学生"
            if (allUsers.isEmpty()) {
                binding.tvNoStudents.visibility = View.VISIBLE
                binding.studentListContainer.adapter = null
                return@launch
            }
            binding.tvNoStudents.visibility = View.GONE

            if (studentAdapter == null) {
                studentAdapter = StudentAdapter(
                    onEdit = { user -> showAddStudentDialog(user) },
                    onDelete = { user, displayName ->
                        AlertDialog.Builder(requireContext())
                            .setTitle("删除学生")
                            .setMessage("确定要删除「$displayName」吗？\n该学生的所有考试记录也将被删除。")
                            .setPositiveButton("删除") { _, _ ->
                                viewLifecycleOwner.lifecycleScope.launch {
                                    withContext(Dispatchers.IO) { db.deleteUser(user.id) }
                                    loadClassChips()
                                    loadStudentList()
                                    Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    },
                    onMessage = { user, displayName -> showSendMessageDialog(user, displayName) },
                    onViewRecord = { user, displayName ->
                        parentFragmentManager.beginTransaction()
                            .add(R.id.contentFrame, StudentExamRecordsFragment.newInstance(user.id, displayName), "student_exam_records")
                            .addToBackStack("student_exam_records")
                            .commit()
                    }
                )
                binding.studentListContainer.adapter = studentAdapter
            }
            studentAdapter!!.submitList(allUsers, onlineIds)
        }
    }

    /** RecyclerView Adapter，虚拟化渲染 4000+ 用户不 OOM */
    private inner class StudentAdapter(
        private val onEdit: (User) -> Unit,
        private val onDelete: (User, String) -> Unit,
        private val onMessage: (User, String) -> Unit,
        private val onViewRecord: (User, String) -> Unit
    ) : RecyclerView.Adapter<StudentAdapter.VH>() {

        private var users: List<User> = emptyList()
        private var onlineIds: Set<Long> = emptySet()

        fun submitList(newUsers: List<User>, newOnlineIds: Set<Long>) {
            users = newUsers
            onlineIds = newOnlineIds
            notifyDataSetChanged()
        }

        inner class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 6.dpToPx() }
                radius = 12f.dpToPxF()
                cardElevation = 0f
                strokeWidth = 1.dpToPx()
                strokeColor = ContextCompat.getColor(parent.context, R.color.card_stroke)
                setCardBackgroundColor(ContextCompat.getColor(parent.context, R.color.surface))
            }
            val item = LayoutInflater.from(parent.context).inflate(R.layout.item_student, card, false)
            card.addView(item)
            return VH(card)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val user = users[position]
            val card = holder.card
            val displayName = user.realName.ifBlank { user.nickname.ifBlank { user.username } }

            card.findViewById<TextView>(R.id.tvStudentName).text = displayName

            val metaParts = mutableListOf<String>()
            metaParts.add("@${user.username}")
            if (user.school.isNotBlank()) metaParts.add(user.school)
            if (user.className.isNotBlank()) metaParts.add(user.className)
            if (user.groupName.isNotBlank()) metaParts.add("[${user.groupName}]")
            card.findViewById<TextView>(R.id.tvStudentMeta).text = metaParts.joinToString(" · ")

            val viewOnline = card.findViewById<View>(R.id.viewOnline)
            viewOnline.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(card.context,
                    if (user.id in onlineIds) R.color.success else R.color.divider)
            )

            card.findViewById<View>(R.id.btnSendMessage).setOnClickListener { onMessage(user, displayName) }
            card.findViewById<View>(R.id.btnEditStudent).setOnClickListener { onEdit(user) }
            card.findViewById<View>(R.id.btnDeleteStudent).setOnClickListener { onDelete(user, displayName) }
            card.setOnClickListener { onViewRecord(user, displayName) }
        }

        override fun getItemCount() = users.size
    }

    private fun showAddStudentDialog(editUser: User?) {
        val isEdit = editUser != null
        val dialog = BottomSheetDialog(requireContext())
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_new_bank, null)
        // 复用 dialog_new_bank 布局的思路，但我们用代码构建表单
        dialog.dismiss()

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(if (isEdit) "编辑学生" else "添加学生")

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = 24.dpToPx()
            setPadding(pad, 16.dpToPx(), pad, 0)
        }

        val etUsername = EditText(requireContext()).apply {
            hint = "用户名（登录账号）"
            setText(editUser?.username ?: "")
            isEnabled = !isEdit
            if (isEdit) alpha = 0.6f
        }
        layout.addView(etUsername)

        val etRealName = EditText(requireContext()).apply { hint = "姓名"; setText(editUser?.realName ?: "") }
        layout.addView(etRealName)

        val etSchool = EditText(requireContext()).apply { hint = "学校"; setText(editUser?.school ?: "") }
        layout.addView(etSchool)

        val etClassName = EditText(requireContext()).apply { hint = "班级"; setText(editUser?.className ?: "") }
        layout.addView(etClassName)

        val etGroupName = EditText(requireContext()).apply { hint = "分组（可选）"; setText(editUser?.groupName ?: "") }
        layout.addView(etGroupName)

        val etPassword = EditText(requireContext()).apply {
            hint = if (isEdit) "新密码（留空不修改）" else "密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etPassword)

        builder.setView(layout)
        builder.setPositiveButton(if (isEdit) "保存" else "添加") { _, _ ->
            val username = etUsername.text.toString().trim()
            val realName = etRealName.text.toString().trim()
            val school = etSchool.text.toString().trim()
            val className = etClassName.text.toString().trim()
            val groupName = etGroupName.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (isEdit) {
                // 编辑时校验姓名和分组
                if (realName.isNotEmpty()) {
                    val err = ContentFilter.checkRegisterInput(editUser!!.username, realName, groupName)
                    if (err != null) {
                        Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                } else if (groupName.isNotEmpty() && ContentFilter.containsBannedWord(groupName)) {
                    Toast.makeText(requireContext(), "分组名含有违禁词汇，请重新填写", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                db.adminUpdateUser(editUser!!.id, realName, school, className, password.ifBlank { null }, groupName)
                Toast.makeText(requireContext(), "已更新", Toast.LENGTH_SHORT).show()
            } else {
                // 新增时完整校验
                val inputErr = ContentFilter.checkRegisterInput(username, realName, groupName)
                if (inputErr != null) {
                    Toast.makeText(requireContext(), inputErr, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val pwd = password.ifBlank { "123456" }
                val userId = db.registerUser(username, pwd, realName.ifBlank { username })
                if (userId < 0) {
                    Toast.makeText(requireContext(), "用户名已存在", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                db.adminUpdateUser(userId, realName, school, className, null, groupName)
                Toast.makeText(requireContext(), "已添加", Toast.LENGTH_SHORT).show()
            }
            loadClassChips()
            loadStudentList()
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun showSendMessageDialog(user: User, displayName: String) {
        val isOnline = user.id in getOnlineUserIds()
        val statusText = if (isOnline) "（🟢 在线，将立即收到）" else "（⚪ 离线，上线后收到）"

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = 24.dpToPx()
            setPadding(pad, 16.dpToPx(), pad, 0)
        }
        val tvStatus = TextView(requireContext()).apply {
            text = statusText
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(),
                if (isOnline) R.color.success else R.color.text_hint))
            setPadding(0, 0, 0, 8.dpToPx())
        }
        layout.addView(tvStatus)
        val etContent = EditText(requireContext()).apply {
            hint = "请输入消息内容..."
            minLines = 3
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(etContent)

        AlertDialog.Builder(requireContext())
            .setTitle("发送消息给「$displayName」")
            .setView(layout)
            .setPositiveButton("发送") { _, _ ->
                val content = etContent.text.toString().trim()
                if (content.isBlank()) {
                    Toast.makeText(requireContext(), "消息内容不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                db.sendMessage(user.id, content)
                Toast.makeText(requireContext(), "✅ 消息已发送", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatchGroupDialog() {
        val allUsers = if (selectedClassFilter != null)
            db.getUsersByClassName(selectedClassFilter!!)
        else
            db.getAllUsers()
        if (allUsers.isEmpty()) {
            Toast.makeText(requireContext(), "没有可分组的学生", Toast.LENGTH_SHORT).show()
            return
        }

        val checked = BooleanArray(allUsers.size) { false }
        val names = allUsers.map { u ->
            val displayName = u.realName.ifBlank { u.nickname.ifBlank { u.username } }
            val groupLabel = if (u.groupName.isNotBlank()) " [${u.groupName}]" else ""
            "$displayName$groupLabel (@${u.username})"
        }.toTypedArray()

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = 24.dpToPx()
            setPadding(pad, 16.dpToPx(), pad, 0)
        }

        // 组名输入
        val existingGroups = db.getDistinctGroupNames()
        val etGroupName = EditText(requireContext()).apply {
            hint = "输入组名（如：A组、第一组）"
            if (existingGroups.isNotEmpty()) {
                setHint("输入或选择组名：${existingGroups.joinToString("、")}")
            }
        }
        layout.addView(etGroupName)

        // 快速选组按钮行
        if (existingGroups.isNotEmpty()) {
            val tvHint = TextView(requireContext()).apply {
                text = "点击快速填入已有组名："
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                setPadding(0, 8.dpToPx(), 0, 4.dpToPx())
            }
            layout.addView(tvHint)
            val chipRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            existingGroups.forEach { gn ->
                val btn = com.google.android.material.button.MaterialButton(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = gn
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = 8.dpToPx() }
                    setOnClickListener { etGroupName.setText(gn) }
                }
                chipRow.addView(btn)
            }
            layout.addView(chipRow)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("🏷️ 批量分组")
            .setView(layout)
            .setPositiveButton("下一步") { _, _ ->
                val groupName = etGroupName.text.toString().trim()
                if (groupName.isBlank()) {
                    Toast.makeText(requireContext(), "请输入组名", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 选择学生
                AlertDialog.Builder(requireContext())
                    .setTitle("选择要加入「$groupName」的学生")
                    .setMultiChoiceItems(names, checked) { _, idx, isChecked ->
                        checked[idx] = isChecked
                    }
                    .setPositiveButton("确认分组") { _, _ ->
                        val selectedIds = allUsers.filterIndexed { idx, _ -> checked[idx] }.map { it.id }
                        if (selectedIds.isEmpty()) {
                            Toast.makeText(requireContext(), "未选择任何学生", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        db.batchSetGroupName(selectedIds, groupName)
                        loadStudentList()
                        Toast.makeText(requireContext(), "✅ 已将 ${selectedIds.size} 名学生设置为「$groupName」", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .setNeutralButton("全选") { dialog2, _ ->
                        checked.fill(true)
                        (dialog2 as AlertDialog).listView?.let { lv ->
                            for (i in 0 until lv.count) lv.setItemChecked(i, true)
                        }
                    }
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBroadcastMessageDialog() {
        val allUsers = if (selectedClassFilter != null)
            db.getUsersByClassName(selectedClassFilter!!)
        else
            db.getAllUsers()
        if (allUsers.isEmpty()) {
            Toast.makeText(requireContext(), "没有可发送的学生", Toast.LENGTH_SHORT).show()
            return
        }
        val onlineIds = getOnlineUserIds()
        val onlineCount = allUsers.count { it.id in onlineIds }
        val offlineCount = allUsers.size - onlineCount

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = 24.dpToPx()
            setPadding(pad, 16.dpToPx(), pad, 0)
        }
        val scopeText = if (selectedClassFilter != null) "「${selectedClassFilter}」班级" else "全部学生"
        val tvInfo = TextView(requireContext()).apply {
            text = "发送给${scopeText}共 ${allUsers.size} 人\n🟢 在线 $onlineCount 人（立即收到）\n⚪ 离线 $offlineCount 人（上线后收到）"
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setLineSpacing(4f.dpToPxF(), 1f)
            setPadding(0, 0, 0, 12.dpToPx())
        }
        layout.addView(tvInfo)
        val etContent = EditText(requireContext()).apply {
            hint = "请输入群发消息内容..."
            minLines = 3
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(etContent)

        AlertDialog.Builder(requireContext())
            .setTitle("📨 群发消息")
            .setView(layout)
            .setPositiveButton("发送") { _, _ ->
                val content = etContent.text.toString().trim()
                if (content.isBlank()) {
                    Toast.makeText(requireContext(), "消息内容不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val count = db.sendMessageToUsers(allUsers.map { it.id }, content)
                Toast.makeText(requireContext(), "✅ 已向 $count 名学生发送消息", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatchDeleteDialog() {
        val allUsers = if (selectedClassFilter != null)
            db.getUsersByClassName(selectedClassFilter!!)
        else
            db.getAllUsers()
        if (allUsers.isEmpty()) {
            Toast.makeText(requireContext(), "没有可删除的学生", Toast.LENGTH_SHORT).show()
            return
        }

        val checked = BooleanArray(allUsers.size) { false }
        val names = allUsers.map { u ->
            val displayName = u.realName.ifBlank { u.nickname.ifBlank { u.username } }
            val extra = buildString {
                if (u.school.isNotBlank()) append(" ${u.school}")
                if (u.className.isNotBlank()) append(" ${u.className}")
            }
            "$displayName (@${u.username})$extra"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("🗑️ 批量删除学生")
            .setMultiChoiceItems(names, checked) { _, idx, isChecked ->
                checked[idx] = isChecked
            }
            .setPositiveButton("删除所选") { _, _ ->
                val toDelete = allUsers.filterIndexed { idx, _ -> checked[idx] }
                if (toDelete.isEmpty()) {
                    Toast.makeText(requireContext(), "未选择任何学生", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("确认删除")
                    .setMessage("确定要删除选中的 ${toDelete.size} 名学生吗？\n该操作不可撤销，相关考试记录也将被删除。")
                    .setPositiveButton("确认删除") { _, _ ->
                        toDelete.forEach { db.deleteUser(it.id) }
                        loadClassChips()
                        loadStudentList()
                        Toast.makeText(requireContext(), "✅ 已删除 ${toDelete.size} 名学生", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("全选", null)
            .create()

        dialog.setOnShowListener {
            val neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            val listView = dialog.listView

            fun refreshNeutralButtonText() {
                neutralButton.text = if (checked.all { it }) "取消全选" else "全选"
            }

            neutralButton.setOnClickListener {
                val selectAll = checked.any { !it }
                for (i in checked.indices) {
                    checked[i] = selectAll
                    listView.setItemChecked(i, selectAll)
                }
                refreshNeutralButtonText()
            }

            listView.setOnItemClickListener { _, _, position, _ ->
                checked[position] = listView.isItemChecked(position)
                refreshNeutralButtonText()
            }

            refreshNeutralButtonText()
        }

        dialog.show()
    }

    private fun showBatchAddDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = 24.dpToPx()
            setPadding(pad, pad, pad, pad)
        }

        val tvTitle = TextView(requireContext()).apply {
            text = "AI识别名单批量添加"
            textSize = 18f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        layout.addView(tvTitle)

        val tvHint = TextView(requireContext()).apply {
            text = "拍照或选择相册中的名单图片，AI将自动识别姓名并批量创建学生账号。\n用户名=姓名拼音，默认密码统一设置。"
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            setPadding(0, 8.dpToPx(), 0, 16.dpToPx())
        }
        layout.addView(tvHint)

        val tvSelected = TextView(requireContext()).apply {
            id = R.id.tvSelectedImages
            text = "未选择图片"
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
        }
        layout.addView(tvSelected)

        val btnPick = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "📷 选择名单图片"
            setOnClickListener { pickImages() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 8.dpToPx() }
        }
        layout.addView(btnPick)

        // 学校和班级
        val etSchool = EditText(requireContext()).apply {
            hint = "学校"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 12.dpToPx() }
        }
        layout.addView(etSchool)

        val etClass = EditText(requireContext()).apply { hint = "班级" }
        layout.addView(etClass)

        val etPassword = EditText(requireContext()).apply {
            hint = "默认密码（留空则用123456）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etPassword)

        val tvProgress = TextView(requireContext()).apply {
            text = ""
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            setPadding(0, 8.dpToPx(), 0, 0)
        }
        layout.addView(tvProgress)

        val btnStart = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "🤖 开始识别并添加"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 16.dpToPx() }
        }
        layout.addView(btnStart)

        btnStart.setOnClickListener {
            if (batchImageUris.isEmpty()) {
                Toast.makeText(requireContext(), "请先选择图片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!llmService.isConfigured()) {
                Toast.makeText(requireContext(), "请先在设置中配置API密钥", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val school = etSchool.text.toString().trim()
            val className = etClass.text.toString().trim()
            val defaultPwd = etPassword.text.toString().trim().ifBlank { "123456" }

            btnStart.isEnabled = false
            tvProgress.text = "正在OCR识别..."

            lifecycleScope.launch {
                try {
                    val ocrText = withContext(Dispatchers.IO) {
                        ocrService.recognizeImages(requireContext(), batchImageUris)
                    }
                    tvProgress.text = "OCR完成，正在AI提取姓名..."

                    val names = withContext(Dispatchers.IO) {
                        extractNamesFromText(ocrText)
                    }

                    if (names.isEmpty()) {
                        tvProgress.text = "未识别到姓名，请检查图片内容"
                        btnStart.isEnabled = true
                        return@launch
                    }

                    tvProgress.text = "识别到 ${names.size} 个姓名，正在创建账号..."

                    val users = names.map { name ->
                        val username = toPinyin(name)
                        Triple(username, defaultPwd, name)
                    }

                    val created = withContext(Dispatchers.IO) {
                        val count = db.batchRegisterUsers(users)
                        // 设置学校班级
                        if (school.isNotBlank() || className.isNotBlank()) {
                            val allUsers = db.getAllUsers()
                            val newUserIds = allUsers.filter { u ->
                                names.any { n -> u.realName == n || u.username == toPinyin(n) }
                            }.map { it.id }
                            if (newUserIds.isNotEmpty()) {
                                db.batchSetSchoolClass(newUserIds, school, className)
                            }
                        }
                        count
                    }

                    tvProgress.text = "✅ 成功添加 $created 名学生（用户名=拼音，密码=$defaultPwd）"
                    loadClassChips()
                    loadStudentList()
                } catch (e: Exception) {
                    tvProgress.text = "❌ 失败: ${e.message}"
                    com.smartquiz.util.DebugHelper.copyErrorIfDebug(requireContext(), e, "AI识别名单")
                } finally {
                    btnStart.isEnabled = true
                }
            }
        }

        dialog.setContentView(layout)
        currentBatchDialog = dialog
        dialog.show()
    }

    private suspend fun extractNamesFromText(text: String): List<String> {
        if (!llmService.isConfigured()) return extractNamesDirectly(text)
        return try {
            val prompt = """从以下文字中提取所有学生姓名，只输出纯JSON数组，不要输出任何其他内容。
示例格式：["张三","李四","王五"]

文字内容：
${if (text.length > 8000) text.take(8000) else text}"""
            val raw = llmService.callApiText(prompt)
            // 从响应中提取JSON数组
            val arrayStart = raw.indexOf('[')
            val arrayEnd = raw.lastIndexOf(']')
            if (arrayStart >= 0 && arrayEnd > arrayStart) {
                val jsonArray = raw.substring(arrayStart, arrayEnd + 1)
                val list = com.google.gson.JsonParser.parseString(jsonArray).asJsonArray
                    .mapNotNull { it.asString?.trim() }
                    .filter { it.length in 2..5 && it.isNotBlank() }
                    .distinct()
                if (list.isNotEmpty()) return list
            }
            // AI返回非JSON数组格式，回退正则
            extractNamesDirectly(text)
        } catch (e: Exception) {
            android.util.Log.w("SmartQuiz", "AI name extraction failed, fallback to regex: ${e.message}")
            extractNamesDirectly(text)
        }
    }

    private suspend fun extractNamesDirectly(text: String): List<String> {
        val regex = Regex("[\\u4e00-\\u9fa5]{2,4}")
        val matches = regex.findAll(text).map { it.value }.toList()
        val commonWords = setOf("学校", "班级", "姓名", "序号", "编号", "成绩", "分数", "考试",
            "名单", "学号", "电话", "地址", "备注", "日期", "老师", "同学", "学生",
            "年级", "第一", "第二", "第三", "第四", "第五", "中学", "小学", "高中",
            "大学", "学院", "附属", "实验", "中心", "公司", "集团", "科技", "教育",
            "培训", "机构", "部门", "单位", "组织", "委员", "主任", "校长", "院长")
        return matches.filter { name ->
            name !in commonWords && name.first().toString() in CHINESE_SURNAMES
        }.distinct()
    }

    private fun toPinyin(name: String): String {
        // 简化的拼音转换：直接用汉字作用户名
        return name.lowercase().replace(" ", "")
    }

    private fun pickImages() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            imagePickerLauncher.launch("image/*")
        } else if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            imagePickerLauncher.launch("image/*")
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Float.dpToPxF(): Float = this * resources.displayMetrics.density

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
