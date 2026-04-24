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
package com.smartquiz.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import com.smartquiz.model.Question
import com.smartquiz.model.QuestionType
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

object PrintHelper {

    /**
     * 打印题目列表
     * @param context 上下文
     * @param title 试卷标题
     * @param questions 题目列表
     * @param showAnswer 是否打印答案和解析
     */
    fun printQuestions(context: Context, title: String, questions: List<Question>, showAnswer: Boolean) {
        val html = buildPrintHtml(title, questions, showAnswer)

        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        // 允许加载外部 CDN 资源（KaTeX CSS/JS）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 用轮询检测 KaTeX 渲染完成（JS 会设置 document.title = 'READY'）
                pollForReady(webView, context, title, 0)
            }
        }
        webView.loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "UTF-8", null)
    }

    private fun pollForReady(webView: WebView, context: Context, title: String, attempt: Int) {
        if (attempt > 15) {
            // 超时 7.5 秒仍未就绪，直接打印
            createPrintJob(context, webView, title)
            return
        }
        webView.evaluateJavascript("document.title") { value ->
            val t = value?.trim('"') ?: ""
            if (t == "READY") {
                createPrintJob(context, webView, title)
            } else {
                webView.postDelayed({ pollForReady(webView, context, title, attempt + 1) }, 500)
            }
        }
    }

    private fun createPrintJob(context: Context, webView: WebView, title: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "$title - 打印"
        val printAdapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(
            jobName,
            printAdapter,
            PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
        )
    }

    /**
     * 生成 PDF 并通过系统分享弹窗分享
     */
    fun sharePdfQuestions(context: Context, title: String, questions: List<Question>, showAnswer: Boolean) {
        val activity = context as? Activity
        if (activity == null) {
            Toast.makeText(context, "当前页面不支持生成分享 PDF", Toast.LENGTH_SHORT).show()
            return
        }

        val html = buildPrintHtml(title, questions, showAnswer)
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val density = context.resources.displayMetrics.density
        val pageWidthPx = (595 * density).roundToInt()
        val hostView = FrameLayout(context).apply {
            alpha = 0.01f
            layoutParams = FrameLayout.LayoutParams(pageWidthPx, 1)
            setBackgroundColor(Color.WHITE)
        }
        val webView = WebView(context)
        webView.setBackgroundColor(Color.WHITE)
        webView.setLayerType(WebView.LAYER_TYPE_SOFTWARE, null)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.layoutParams = FrameLayout.LayoutParams(pageWidthPx, 1)
        hostView.addView(webView)
        rootView.addView(hostView)
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(pageWidthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1, android.view.View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, pageWidthPx, 1)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pollForPdf(webView, hostView, rootView, context, title, 0)
            }
        }
        webView.loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "UTF-8", null)
    }

    private fun pollForPdf(
        webView: WebView,
        hostView: FrameLayout,
        rootView: ViewGroup,
        context: Context,
        title: String,
        attempt: Int
    ) {
        if (attempt > 20) {
            doWritePdf(webView, hostView, rootView, context, title)
            return
        }
        webView.evaluateJavascript("document.title") { value ->
            val t = value?.trim('"') ?: ""
            if (t == "READY") {
                webView.evaluateJavascript("Math.max(document.body.scrollHeight, document.documentElement.scrollHeight, document.body.offsetHeight, document.documentElement.offsetHeight)") { heightVal ->
                    val contentHeightPx = parseJsInt(heightVal)
                    doWritePdf(webView, hostView, rootView, context, title, contentHeightPx)
                }
            } else {
                webView.postDelayed({ pollForPdf(webView, hostView, rootView, context, title, attempt + 1) }, 500)
            }
        }
    }

    private fun doWritePdf(
        webView: WebView,
        hostView: FrameLayout,
        rootView: ViewGroup,
        context: Context,
        title: String,
        jsContentHeight: Int = 0
    ) {
        try {
            val shareDir = File(context.cacheDir, "share")
            shareDir.mkdirs()
            val pdfFile = File(shareDir, "${title}—拍书题库.pdf")

            val measuredContentHeight = (webView.contentHeight * webView.scale).roundToInt()
            val contentHeightPx = maxOf(jsContentHeight, measuredContentHeight, webView.height, 1)
            val targetHeight = contentHeightPx.coerceAtLeast(1)
            hostView.layoutParams = hostView.layoutParams.apply {
                width = webView.width.coerceAtLeast(1)
                height = targetHeight
            }
            webView.layoutParams = webView.layoutParams.apply {
                width = webView.width.coerceAtLeast(1)
                height = targetHeight
            }
            hostView.requestLayout()
            hostView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(webView.width.coerceAtLeast(1), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(targetHeight, android.view.View.MeasureSpec.EXACTLY)
            )
            hostView.layout(0, 0, webView.width.coerceAtLeast(1), targetHeight)
            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(webView.width.coerceAtLeast(1), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(targetHeight, android.view.View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, webView.width.coerceAtLeast(1), targetHeight)

            val exportPdf = {
                val pdfDocument = PrintedPdfDocument(
                    context,
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("share", "share", 300, 300))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
                )
                val firstPage = pdfDocument.startPage(0)
                val pageWidth = firstPage.info.pageWidth
                val pageHeight = firstPage.info.pageHeight
                pdfDocument.finishPage(firstPage)

                val scale = pageWidth.toFloat() / webView.width.coerceAtLeast(1).toFloat()
                val pageHeightInContent = (pageHeight / scale).roundToInt().coerceAtLeast(1)
                val totalPages = ceil(targetHeight.toDouble() / pageHeightInContent.toDouble()).toInt().coerceAtLeast(1)

                pdfDocument.close()

                val finalDocument = PrintedPdfDocument(
                    context,
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("share", "share", 300, 300))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
                )

                for (pageIndex in 0 until totalPages) {
                    val page = finalDocument.startPage(pageIndex)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    canvas.save()
                    canvas.scale(scale, scale)
                    canvas.translate(0f, -(pageIndex * pageHeightInContent).toFloat())
                    webView.draw(canvas)
                    canvas.restore()
                    finalDocument.finishPage(page)
                }

                FileOutputStream(pdfFile).use { finalDocument.writeTo(it) }
                finalDocument.close()

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "题库：$title")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "分享题库PDF「$title」"))
                rootView.removeView(hostView)
                webView.destroy()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                webView.postVisualStateCallback(System.currentTimeMillis(), object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        webView.postDelayed({ exportPdf() }, 200)
                    }
                })
            } else {
                webView.postDelayed({ exportPdf() }, 500)
            }
        } catch (e: Exception) {
            rootView.removeView(hostView)
            webView.destroy()
            Toast.makeText(context, "PDF 生成失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseJsInt(value: String?): Int {
        return value
            ?.trim()
            ?.trim('"')
            ?.substringBefore('.')
            ?.toIntOrNull()
            ?: 0
    }

    private fun buildPrintHtml(title: String, questions: List<Question>, showAnswer: Boolean): String {
        val grouped = questions.groupBy { it.type }
        val typeOrder = listOf(
            QuestionType.SINGLE_CHOICE,
            QuestionType.MULTIPLE_CHOICE,
            QuestionType.TRUE_FALSE,
            QuestionType.FILL_BLANK
        )

        val sb = StringBuilder()
        sb.append("""
<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"></script>
<style>
  @page { margin: 15mm 12mm 22mm 12mm;
    @bottom-left  { content: "试卷由 ©拍书题库 pstk.inc.work AI生成 — ${escapeHtml(title)}"; font-size:8pt; color:#000; font-family:sans-serif; }
    @bottom-right { content: counter(page) " / " counter(pages); font-size:8pt; color:#000; font-family:sans-serif; }
  }
  * { margin:0; padding:0; box-sizing:border-box; }
  body { font-family: "SimSun","Songti SC",serif; font-size:12pt; color:#000; line-height:1.7; }
  .title { text-align:center; font-size:18pt; font-weight:bold; margin-bottom:4pt; }
  .subtitle { text-align:center; font-size:10pt; color:#000; margin-bottom:14pt; }
  .section { font-size:13pt; font-weight:bold; margin:12pt 0 6pt 0; border-bottom:1px solid #000; padding-bottom:2pt; }
  .question { margin-bottom:10pt; page-break-inside:avoid; }
  .q-num { font-weight:bold; }
  .options { margin:2pt 0 0 2em; }
  .options-4col { display:grid; grid-template-columns:repeat(4,1fr); gap:1pt 4pt; }
  .options-2col { display:grid; grid-template-columns:repeat(2,1fr); gap:1pt 4pt; }
  .option-item { margin:1pt 0; }
  .answer-block { margin:3pt 0 0 2em; padding:4pt 8pt; background:#f5f5f5; border-left:3px solid #000; font-size:10pt; }
  .answer-label { font-weight:bold; color:#000; }
  .blank-line { display:inline-block; border-bottom:1px solid #000; width:80pt; margin:0 4pt; }
  .footer { display:flex; justify-content:space-between; align-items:center; font-size:8.5pt; color:#000; margin-top:24pt; border-top:1px solid #000; padding-top:6pt; }
</style>
</head><body>
<div class="title">${escapeHtml(title)}</div>
<div class="subtitle">共 ${questions.size} 题　　姓名：___________　　班级：___________　　日期：___________　　得分：___________</div>
""")

        var globalIndex = 1
        for (type in typeOrder) {
            val qs = grouped[type] ?: continue
            sb.append("""<div class="section">${sectionTitle(type, qs.size)}</div>""")
            for (q in qs) {
                sb.append("""<div class="question">""")
                sb.append("""<span class="q-num">${globalIndex}.</span> ${formatContent(q)}""")

                // 题目图片
                if (q.imageUrl.isNotBlank()) {
                    sb.append("""<div style="margin:4pt 0;text-align:center"><img src="${q.imageUrl.replace("&", "&amp;").replace("\"", "&quot;")}" style="max-width:80%;max-height:200pt;border:1px solid #ccc" onerror="this.style.display='none'"></div>""")
                }

                // 选项：根据最长选项字符数自动决定每行列数（节省版面）
                if (q.options.isNotEmpty()) {
                    val labels = listOf("A", "B", "C", "D", "E", "F", "G", "H")
                    val cleanOpts = q.options.map { opt ->
                        opt.trimStart().replace(Regex("^[A-Za-z][.、]\\s*"), "")
                    }
                    val maxLen = cleanOpts.maxOf { it.length }
                    // ≤8字 → 4列；≤18字 → 2列；其余 → 1列（每选项独占一行）
                    val colClass = when {
                        maxLen <= 8  -> "options options-4col"
                        maxLen <= 18 -> "options options-2col"
                        else         -> "options"
                    }
                    sb.append("""<div class="$colClass">""")
                    cleanOpts.forEachIndexed { i, cleanOpt ->
                        val label = labels.getOrElse(i) { "${i + 1}" }
                        sb.append("""<div class="option-item">${label}. ${formatOptionText(cleanOpt)}</div>""")
                    }
                    sb.append("</div>")
                }

                // 填空题画线
                if (q.type == QuestionType.FILL_BLANK && q.options.isEmpty()) {
                    if (!q.content.contains("_")) {
                        sb.append("""<div class="options">答：<span class="blank-line"></span></div>""")
                    }
                }

                // 答案与解析
                if (showAnswer) {
                    sb.append("""<div class="answer-block">""")
                    sb.append("""<span class="answer-label">答案：</span>${safeHtmlWithMath(q.answer)}""")
                    if (q.explanation.isNotBlank()) {
                        sb.append("""<br><span class="answer-label">解析：</span>${safeHtmlWithMath(q.explanation)}""")
                    }
                    sb.append("</div>")
                }

                sb.append("</div>")
                globalIndex++
            }
        }

        sb.append("""<div class="footer"><span>试卷由 ©拍书题库 pstk.inc.work AI生成 — ${escapeHtml(title)}</span><span id="pageNum"></span></div>""")
        sb.append("""
<script>
window.onload = function() {
  if (typeof renderMathInElement === 'function') {
    renderMathInElement(document.body, {
      delimiters: [
        {left: "\\[", right: "\\]", display: true},
        {left: "\\(", right: "\\)", display: false}
      ],
      throwOnError: false
    });
  }
  var el = document.getElementById('pageNum');
  if (el) {
    var bodyH = document.body.scrollHeight;
    var pageH = 1050;
    var total = Math.max(1, Math.ceil(bodyH / pageH));
    el.textContent = '共 ' + total + ' 页';
  }
  document.title = 'READY';
};
</script>
</body></html>""")
        return sb.toString()
    }

    private fun sectionTitle(type: QuestionType, count: Int): String {
        val label = when (type) {
            QuestionType.SINGLE_CHOICE -> "一、单项选择题"
            QuestionType.MULTIPLE_CHOICE -> "二、多项选择题"
            QuestionType.TRUE_FALSE -> "三、判断题"
            QuestionType.FILL_BLANK -> "四、填空题"
            QuestionType.SHORT_ANSWER -> "五、简答题"
        }
        return "$label（共 ${count} 题）"
    }

    private fun formatContent(q: Question): String {
        var text = safeHtmlWithMath(q.content)
        // 将下划线占位符转为填空线
        text = text.replace(Regex("_{2,}"), """<span class="blank-line"></span>""")
        return text
    }

    /** 格式化选项文本：保护公式不被 escapeHtml 破坏，同时支持 Markdown 图片 */
    private fun formatOptionText(opt: String): String {
        // 先提取 Markdown 图片，避免后续处理破坏 base64 data URI
        val imgs = mutableListOf<String>()
        val withoutImgs = opt.replace(Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")) { match ->
            val alt = match.groupValues[1]
            val url = match.groupValues[2]
            val ph = "\u0000IMG${imgs.size}\u0000"
            imgs.add("""<img src="$url" alt="${escapeHtml(alt)}" style="max-width:60%;max-height:120pt;vertical-align:middle;border:1px solid #ccc" onerror="this.style.display='none'">""")
            ph
        }
        var text = safeHtmlWithMath(withoutImgs)
        imgs.forEachIndexed { i, img -> text = text.replace("\u0000IMG$i\u0000", img) }
        return text
    }

    /**
     * 先占位保护公式，再对普通文本做 escapeHtml，最后还原公式。
     * 同时修复数据库旧数据中被 JSON 解析器误转义的 LaTeX 控制字符。
     */
    private fun safeHtmlWithMath(raw: String): String {
        // 1. 修复控制字符（旧数据中 \frac 被存成换页符等）
        var s = raw
            .replace("\u000C", "\\f")   // \f → \frac \forall 等
            .replace("\u0008", "\\b")   // \b → \beta \bar 等
            .replace("\t",     "\\t")   // \t → \text \theta 等
            .replace("\r",     "\\r")   // \r → \right \rho 等

        // \n 后跟 LaTeX 命令特征时恢复反斜杠
        s = s.replace(Regex("""\n(eq|abla|ot(?![a-z])|ewline|u(?![a-z])|i(?![a-z])|leq|geq|mid|otin|parallel|cong|eg(?![a-z])|exists|ormalsize|ormal|vDash|subseteq|supseteq|subset(?![e])|supset|less|Rightarrow|Leftarrow|Leftrightarrow|prec|succ|sqsubset|sqsupset)""")) {
            "\\" + it.groupValues[1]
        }

        // 2. 提取公式到占位符，避免 escapeHtml 破坏公式内容
        val formulas = mutableListOf<String>()

        // $$...$$ → \[...\]
        s = s.replace(Regex("""\$\$([\s\S]*?)\$\$""")) { mr ->
            val ph = "@@MATH${formulas.size}@@"
            formulas.add("\\[${mr.groupValues[1]}\\]")
            ph
        }
        // $...$ → \(...\)
        s = s.replace(Regex("""\$([^\$\n\r]+?)\$""")) { mr ->
            val ph = "@@MATH${formulas.size}@@"
            formulas.add("\\(${mr.groupValues[1]}\\)")
            ph
        }
        // 末尾未闭合的 $xxx → \(xxx\)
        s = s.replace(Regex("""\$([^\$\n\r]+)$""")) { mr ->
            val ph = "@@MATH${formulas.size}@@"
            formulas.add("\\(${mr.groupValues[1].trim()}\\)")
            ph
        }
        // 已是 \[...\] 格式
        s = s.replace(Regex("""\\\[([\s\S]*?)\\\]""")) { mr ->
            val ph = "@@MATH${formulas.size}@@"
            formulas.add("\\[${mr.groupValues[1]}\\]")
            ph
        }
        // 已是 \(...\) 格式（用 [\s\S]*? 支持括号内含括号）
        s = s.replace(Regex("""\\\(([\s\S]*?)\\\)""")) { mr ->
            val ph = "@@MATH${formulas.size}@@"
            formulas.add("\\(${mr.groupValues[1]}\\)")
            ph
        }

        // 3. 对非公式文本做 escapeHtml
        var result = escapeHtml(s)

        // 4. 还原公式（不经过 escapeHtml，保持原始 LaTeX）
        formulas.forEachIndexed { i, formula ->
            result = result.replace("@@MATH$i@@", formula)
        }
        return result
    }

    /** 仅转义普通文本的 HTML 特殊字符，不处理公式 */
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("\n", "<br>")
}
