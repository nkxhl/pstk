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

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.smartquiz.R
import com.smartquiz.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var banksFragment: BanksFragment
    private lateinit var reportFragment: ReportFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var studentManageFragment: StudentManageFragment
    private lateinit var organizeExamFragment: OrganizeExamFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        banksFragment         = BanksFragment()
        reportFragment        = ReportFragment()
        settingsFragment      = SettingsFragment()
        studentManageFragment = StudentManageFragment()
        organizeExamFragment  = OrganizeExamFragment()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.contentFrame, banksFragment,         "banks")
                .add(R.id.contentFrame, reportFragment,        "report")
                .add(R.id.contentFrame, settingsFragment,      "settings")
                .add(R.id.contentFrame, studentManageFragment, "students")
                .add(R.id.contentFrame, organizeExamFragment,  "exam")
                .hide(reportFragment)
                .hide(settingsFragment)
                .hide(studentManageFragment)
                .hide(organizeExamFragment)
                .commit()
        } else {
            banksFragment         = supportFragmentManager.findFragmentByTag("banks")    as BanksFragment
            reportFragment        = supportFragmentManager.findFragmentByTag("report")   as ReportFragment
            settingsFragment      = supportFragmentManager.findFragmentByTag("settings") as SettingsFragment
            studentManageFragment = (supportFragmentManager.findFragmentByTag("students") as? StudentManageFragment) ?: StudentManageFragment()
            organizeExamFragment  = (supportFragmentManager.findFragmentByTag("exam")     as? OrganizeExamFragment)  ?: OrganizeExamFragment()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_banks -> {
                    showFragment("banks")
                    banksFragment.refreshBankList()
                    true
                }
                R.id.nav_students -> {
                    showFragment("students")
                    studentManageFragment.loadStudentList()
                    true
                }
                R.id.nav_exam -> {
                    showFragment("exam")
                    true
                }
                R.id.nav_report -> {
                    showFragment("report")
                    reportFragment.loadReport()
                    true
                }
                R.id.nav_settings -> {
                    showFragment("settings")
                    settingsFragment.updateServerStatus()
                    true
                }
                else -> false
            }
        }

        // 返回栈变化时恢复底部导航可见性
        supportFragmentManager.addOnBackStackChangedListener {
            val hasBackStack = supportFragmentManager.backStackEntryCount > 0
            binding.bottomNav.visibility =
                if (hasBackStack) android.view.View.GONE else android.view.View.VISIBLE
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // 处理启动时的分享 intent
        handleIncomingIntent(intent)

        // 首次安装：数据库为空时自动询问是否导入演示数据
        val prefs = getSharedPreferences("smart_quiz_config", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("demo_prompt_shown", false)) {
            prefs.edit().putBoolean("demo_prompt_shown", true).apply()
            binding.root.postDelayed({
                val db = com.smartquiz.db.DatabaseHelper.getInstance(this)
                if (db.isDatabaseEmpty()) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("👋 欢迎使用拍书题库！")
                        .setMessage("检测到您是第一使用应用，是否导入演示数据？\n演示数据包含 2 个示例题库（每库 22 道题）、5 名演示学生账号（密码均为 123456）、2 场正式考试（含完整答题记录）、多轮练习及错题样本，方便您快速了解所有功能。\n\n后续您可以在设置中的‘数据管理—>清空所有数据’来清空演示数据。")
                        .setNegativeButton("跳过") { _, _ -> }
                        .setPositiveButton("导入演示数据") { _, _ ->
                            settingsFragment.showImportDemoDialog()
                        }
                        .show()
                }
            }, 600)
        }

        // 启动后延迟 3 秒静默检查更新，避免影响首屏加载
        binding.root.postDelayed({
            AppUpdateChecker.check(this)
        }, 3000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
        if (uri == null) return

        // 判断文件类型：图片/PDF/WORD/TXT/MD → 询问生成题库；.sqb → 导入题库
        val mime = contentResolver.getType(uri) ?: ""
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val isDocument = mime.startsWith("image/") ||
                mime == "application/pdf" ||
                mime == "application/msword" ||
                mime.startsWith("application/vnd.openxmlformats") ||
                mime == "text/plain" || mime == "text/markdown" ||
                name.endsWith(".txt") || name.endsWith(".md") ||
                name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx")

        binding.root.post {
            if (isDocument) {
                showSharedDocumentDialog(uri)
            } else {
                confirmAndImportBank(uri)
            }
        }
    }

    /** 收到图片/PDF/WORD/TXT/MD 分享时，询问新建题库还是追加到已有题库 */
    private fun showSharedDocumentDialog(uri: Uri) {
        showFragment("banks")
        binding.bottomNav.selectedItemId = R.id.nav_banks

        AlertDialog.Builder(this)
            .setTitle("生成题库")
            .setMessage("收到一个文件，是否用它生成题目？")
            .setPositiveButton("新建题库") { _, _ ->
                banksFragment.showGenerateDialogWithUri(uri)
            }
            .setNeutralButton("添加到已有题库") { _, _ ->
                pickBankAndGenerate(uri)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 列出所有题库让用户选择，然后追加生成题目 */
    private fun pickBankAndGenerate(uri: Uri) {
        val db = com.smartquiz.db.DatabaseHelper(this)
        val banks = db.getAllBanks()
        if (banks.isEmpty()) {
            // 没有题库则直接新建
            banksFragment.showGenerateDialogWithUri(uri)
            return
        }
        val names = banks.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择题库")
            .setItems(names) { _, which ->
                val bank = banks[which]
                banksFragment.showGenerateDialogWithUri(uri, bank.id, bank.name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmAndImportBank(uri: Uri) {
        // 切换到题库标签
        showFragment("banks")
        binding.bottomNav.selectedItemId = R.id.nav_banks

        AlertDialog.Builder(this)
            .setTitle("导入题库")
            .setMessage("收到一个题库文件，是否导入到系统中？")
            .setPositiveButton("导入") { _, _ ->
                banksFragment.importBankFromUri(uri)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showHelp() {
        settingsFragment.showHelpDialogPublic()
    }

    fun switchToBanks() {
        binding.bottomNav.selectedItemId = R.id.nav_banks
        showFragment("banks")
        banksFragment.refreshBankList()
    }

    private fun showFragment(tag: String) {
        val tx = supportFragmentManager.beginTransaction()
        listOf(
            supportFragmentManager.findFragmentByTag("banks"),
            supportFragmentManager.findFragmentByTag("report"),
            supportFragmentManager.findFragmentByTag("settings"),
            supportFragmentManager.findFragmentByTag("students"),
            supportFragmentManager.findFragmentByTag("exam")
        ).forEach { f ->
            if (f != null) {
                if (f.tag == tag) tx.show(f) else tx.hide(f)
            }
        }
        tx.commit()
    }
}
