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

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import com.smartquiz.model.Question
import com.smartquiz.model.QuestionType
import java.io.File

object OfflineExamHelper {

    /**
     * 生成离线考试 HTML 文件并通过系统分享发送
     * @param context 上下文
     * @param title 考试标题
     * @param questions 固定题目列表
     * @param timeMinutes 考试时长（分钟），0=不限时
     * @param typeScores 题型分值映射字符串，如 "1:2,2:3,3:2,4:2"
     */
    fun shareOfflineExam(
        context: Context,
        title: String,
        questions: List<Question>,
        timeMinutes: Int = 0,
        typeScores: String = ""
    ) {
        if (questions.isEmpty()) {
            Toast.makeText(context, "没有题目可供生成", Toast.LENGTH_SHORT).show()
            return
        }
        val html = buildOfflineHtml(context, title, questions, timeMinutes, typeScores)
        val shareDir = File(context.cacheDir, "share")
        shareDir.mkdirs()
        val file = File(shareDir, "${title}—拍书题库.html")
        file.writeText(html, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "离线考试：$title")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享离线考试「$title」"))
    }

    fun buildOfflineHtml(
        context: Context,
        title: String,
        questions: List<Question>,
        timeMinutes: Int,
        typeScores: String
    ): String {
        // 读取并内联 KaTeX 资源（JS/CSS/字体全部内联，彻底无需网络）
        val katexJs = readAssetText(context, "web/katex/katex.min.js")
        val autoRenderJs = readAssetText(context, "web/katex/auto-render.min.js")
        // 将 CSS 中的字体 url(fonts/xxx.woff2) 替换为 base64 data URI
        val katexCssRaw = readAssetText(context, "web/katex/katex.min.css")
        val katexCss = katexCssRaw.replace(Regex("""url\(fonts/([^)]+\.woff2)\)""")) { mr ->
            val fontName = mr.groupValues[1]
            val b64 = readAssetBase64(context, "web/katex/fonts/$fontName")
            "url(data:font/woff2;base64,$b64)"
        }

        // 解析分值映射
        val scoreMap = mutableMapOf<Int, Int>()
        typeScores.split(",").forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val tc = parts[0].trim().toIntOrNull()
                val sc = parts[1].trim().toIntOrNull()
                if (tc != null && sc != null) scoreMap[tc] = sc
            }
        }

        // 按题型分组 JSON
        val questionsJson = buildString {
            append("[")
            questions.forEachIndexed { i, q ->
                if (i > 0) append(",")
                append("{")
                append("\"id\":${q.id},")
                append("\"type\":${q.type.code},")
                append("\"content\":${jsonStr(q.content)},")
                append("\"options\":[")
                q.options.forEachIndexed { oi, opt ->
                    if (oi > 0) append(",")
                    append(jsonStr(opt))
                }
                append("],")
                // 答案与解析用 Base64 编码，防止直接阅读源代码获取答案
                append("\"_ca\":${jsonStr(Base64.encodeToString(q.answer.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))},")
                append("\"_ex\":${jsonStr(Base64.encodeToString(q.explanation.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))},")
                append("\"difficulty\":${q.difficulty},")
                append("\"knowledgePoint\":${jsonStr(q.knowledgePoint)},")
                append("\"imageUrl\":${jsonStr(q.imageUrl)}") 
                append("}")
            }
            append("]")
        }

        val totalScore = questions.groupBy { it.type.code }.entries.sumOf { (tc, qs) ->
            qs.size * (scoreMap[tc] ?: 2)
        }

        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>${esc(title)} - 离线考试</title>
<style>${katexCss}</style>
<style>
*{margin:0;padding:0;-webkit-box-sizing:border-box;box-sizing:border-box;-ms-touch-action:manipulation;touch-action:manipulation}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;background:#F7F7FC;color:#1A1A2E;line-height:1.6;min-height:100vh}
.header{background:#6C63FF;color:#fff;padding:20px;text-align:center;z-index:100;-webkit-box-shadow:0 2px 12px rgba(0,0,0,0.15);box-shadow:0 2px 12px rgba(0,0,0,0.15)}
.header h1{font-size:20px;margin-bottom:4px}
.header .sub{font-size:13px;opacity:0.85}
.quiz-bar{display:-webkit-box;display:-webkit-flex;display:flex;-webkit-box-orient:horizontal;-webkit-box-direction:normal;-webkit-box-align:center;-webkit-align-items:center;align-items:center;-webkit-box-pack:justify;-webkit-justify-content:space-between;justify-content:space-between;background:#6C63FF;color:#fff;padding:10px 16px;position:-webkit-sticky;position:sticky;top:0;z-index:100;-webkit-box-shadow:0 2px 8px rgba(0,0,0,0.15);box-shadow:0 2px 8px rgba(0,0,0,0.15)}
.quiz-bar-left{-webkit-box-flex:1;-webkit-flex:1;flex:1;font-size:14px;font-weight:600}
.quiz-bar-right{display:-webkit-box;display:-webkit-flex;display:flex;-webkit-box-align:center;-webkit-align-items:center;align-items:center;-webkit-flex-shrink:0;flex-shrink:0}
.quiz-bar-right>*+*{margin-left:8px}
.qb-timer{font-size:16px;font-weight:700;white-space:nowrap}
.qb-timer.warning{color:#FFD700;-webkit-animation:pulse 1s infinite;animation:pulse 1s infinite}
.qb-btn{display:inline-block;padding:5px 12px;-webkit-border-radius:6px;border-radius:6px;font-size:13px;font-weight:600;border:1px solid rgba(255,255,255,0.5);background:rgba(255,255,255,0.18);color:#fff;cursor:pointer}
.qb-btn-submit{background:rgba(46,213,115,0.85);border-color:transparent}
.container{max-width:800px;margin:0 auto;padding:0 16px 80px}
.card{background:#fff;-webkit-border-radius:14px;border-radius:14px;padding:20px;margin:16px 0;-webkit-box-shadow:0 4px 16px rgba(108,99,255,0.08);box-shadow:0 4px 16px rgba(108,99,255,0.08)}
.card-title{font-size:18px;font-weight:600;margin-bottom:12px;padding-bottom:8px;border-bottom:2px solid #E8E6FF}
.progress-bar{width:100%;height:6px;background:#e0e0e0;-webkit-border-radius:3px;border-radius:3px;margin:8px 0}
.progress-fill{height:100%;background:#6C63FF;-webkit-border-radius:3px;border-radius:3px;-webkit-transition:width 0.3s;transition:width 0.3s}
.q-dots{display:-webkit-box;display:-webkit-flex;display:flex;-webkit-flex-wrap:nowrap;flex-wrap:nowrap;overflow-x:auto;-webkit-overflow-scrolling:touch;margin:12px 0;padding-bottom:4px;cursor:grab}
.q-dots::-webkit-scrollbar{display:none}
.q-dot{width:28px;height:28px;min-width:28px;-webkit-border-radius:50%;border-radius:50%;background:#e0e0e0;color:#6E7191;display:-webkit-box;display:-webkit-flex;display:flex;-webkit-box-align:center;-webkit-align-items:center;align-items:center;-webkit-box-pack:center;-webkit-justify-content:center;justify-content:center;font-size:11px;cursor:pointer;-webkit-transition:all 0.2s;transition:all 0.2s;margin-right:6px}
.q-dot.current{background:#6C63FF;color:#fff;-webkit-transform:scale(1.15);transform:scale(1.15)}
.q-dot.answered{background:#E8E6FF;color:#6C63FF}
.q-dot.correct-dot{background:#2ED573;color:#fff}
.q-dot.wrong-dot{background:#FF4757;color:#fff}
.question-card{background:#fff;-webkit-border-radius:14px;border-radius:14px;padding:20px;margin-bottom:16px;-webkit-box-shadow:0 4px 16px rgba(108,99,255,0.08);box-shadow:0 4px 16px rgba(108,99,255,0.08)}
.question-meta{display:-webkit-box;display:-webkit-flex;display:flex;-webkit-flex-wrap:wrap;flex-wrap:wrap;-webkit-box-align:center;-webkit-align-items:center;align-items:center;margin-bottom:12px}
.question-meta>*+*{margin-left:8px;margin-top:4px}
.tag{display:inline-block;padding:2px 10px;-webkit-border-radius:20px;border-radius:20px;font-size:12px;font-weight:500}
.tag-type{background:#E8E6FF;color:#6C63FF}
.tag-diff{background:#FFF3E0;color:#FF9800}
.question-content{font-size:16px;margin-bottom:16px;line-height:1.8}
.options{list-style:none;margin:0;padding:0}
.option-item{display:-webkit-box;display:-webkit-flex;display:flex;-webkit-box-align:center;-webkit-align-items:center;align-items:center;padding:12px 16px;margin-bottom:8px;-webkit-border-radius:10px;border-radius:10px;border:2px solid #e0e0e0;cursor:pointer;-webkit-transition:all 0.2s;transition:all 0.2s}
.option-item.selected{border-color:#6C63FF;background:#E8E6FF}
.option-item.correct{border-color:#2ED573;background:#E8F5E9}
.option-item.wrong{border-color:#FF4757;background:#FFEBEE}
.option-radio{width:20px;height:20px;-webkit-border-radius:50%;border-radius:50%;border:2px solid #ccc;margin-right:12px;-webkit-flex-shrink:0;flex-shrink:0;-webkit-transition:all 0.2s;transition:all 0.2s}
.option-item.selected .option-radio{border-color:#6C63FF;background:#6C63FF;-webkit-box-shadow:inset 0 0 0 3px #fff;box-shadow:inset 0 0 0 3px #fff}
.option-item.correct .option-radio{border-color:#2ED573;background:#2ED573;-webkit-box-shadow:inset 0 0 0 3px #fff;box-shadow:inset 0 0 0 3px #fff}
.option-item.wrong .option-radio{border-color:#FF4757;background:#FF4757;-webkit-box-shadow:inset 0 0 0 3px #fff;box-shadow:inset 0 0 0 3px #fff}
.option-checkbox{width:20px;height:20px;-webkit-border-radius:4px;border-radius:4px;border:2px solid #ccc;margin-right:12px;-webkit-flex-shrink:0;flex-shrink:0;-webkit-transition:all 0.2s;transition:all 0.2s}
.option-item.selected .option-checkbox{border-color:#6C63FF;background:#6C63FF;-webkit-box-shadow:inset 0 0 0 3px #fff;box-shadow:inset 0 0 0 3px #fff}
.fill-input{width:100%;padding:12px 16px;border:2px solid #e0e0e0;-webkit-border-radius:8px;border-radius:8px;font-size:15px;outline:none;background:#fff;color:#1A1A2E;-webkit-appearance:none;appearance:none;-webkit-box-sizing:border-box;box-sizing:border-box}
.fill-input:focus{border-color:#6C63FF;outline:none}
.btn{display:inline-block;padding:10px 20px;-webkit-border-radius:10px;border-radius:10px;font-size:14px;font-weight:600;border:none;cursor:pointer;-webkit-transition:all 0.2s;transition:all 0.2s;text-align:center}
.btn-primary{background:#6C63FF;color:#fff}
.btn-success{background:#2ED573;color:#fff}
.btn-warning{background:#FFA502;color:#fff}
.btn-outline{background:transparent;color:#6C63FF;border:2px solid #6C63FF}
.btn-block{width:100%;display:block}
.btn-group{display:-webkit-box;display:-webkit-flex;display:flex;-webkit-flex-wrap:wrap;flex-wrap:wrap;margin:-4px}
.btn-group>*{margin:4px}
.question-nav{display:-webkit-box;display:-webkit-flex;display:flex;-webkit-box-pack:justify;-webkit-justify-content:space-between;justify-content:space-between;margin-top:12px}
.result-score{text-align:center;padding:20px 0}
.score-circle{width:100px;height:100px;-webkit-border-radius:50%;border-radius:50%;display:-webkit-box;display:-webkit-flex;display:flex;-webkit-box-orient:vertical;-webkit-flex-direction:column;flex-direction:column;-webkit-box-align:center;-webkit-align-items:center;align-items:center;-webkit-box-pack:center;-webkit-justify-content:center;justify-content:center;margin:0 auto;font-size:26px;font-weight:700;color:#fff}
.score-high{background:#2ED573}
.score-mid{background:#FFA502}
.score-low{background:#FF4757}
.result-stats{display:-webkit-box;display:-webkit-flex;display:flex;-webkit-box-pack:distribute;-webkit-justify-content:space-around;justify-content:space-around;margin:20px 0;text-align:center}
.stat-value{font-size:24px;font-weight:700}
.stat-label{font-size:12px;color:#6E7191;margin-top:4px}
.detail-item{padding:12px;margin-bottom:8px;-webkit-border-radius:8px;border-radius:8px;border:1px solid #e0e0e0}
.correct-item{border-left:4px solid #2ED573}
.wrong-item{border-left:4px solid #FF4757}
.detail-answer{margin-top:8px;padding:8px 12px;background:#f8f8f8;-webkit-border-radius:6px;border-radius:6px;font-size:13px}
.toast{position:fixed;bottom:80px;left:10%;width:80%;background:rgba(0,0,0,0.8);color:#fff;padding:10px 24px;-webkit-border-radius:20px;border-radius:20px;font-size:14px;opacity:0;-webkit-transition:opacity 0.3s;transition:opacity 0.3s;z-index:9999;pointer-events:none;text-align:center}
.toast.show{opacity:1}
.info-form{margin-top:16px}
.info-form .form-group{margin-bottom:12px}
.info-form label{display:block;font-size:14px;font-weight:500;margin-bottom:4px}
.info-form input{width:100%;padding:10px 14px;border:2px solid #e0e0e0;-webkit-border-radius:8px;border-radius:8px;font-size:15px;outline:none;-webkit-appearance:none;appearance:none;background:#fff;color:#1A1A2E;-webkit-box-sizing:border-box;box-sizing:border-box}
.info-form input:focus{border-color:#6C63FF;outline:none}
.practice-explain{margin-top:12px;padding:12px 14px;background:#F0EFFF;-webkit-border-radius:8px;border-radius:8px;font-size:14px;line-height:1.6;color:#1A1A2E;border-left:4px solid #6C63FF}
@-webkit-keyframes pulse{0%,100%{opacity:1}50%{opacity:0.5}}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:0.5}}
</style>
</head>
<body>
<div class="header" id="infoHeader">
  <h1>${esc(title)}</h1>
  <div class="sub">共 ${questions.size} 题 · 离线练习 &amp; 考试</div>
</div>
<div class="quiz-bar" id="quizBar" style="display:none">
  <div class="quiz-bar-left" id="progressText">第 1/${questions.size} 题</div>
  <div class="quiz-bar-right">
    <span class="qb-timer" id="timerDisplay">00:00</span>
    <button type="button" class="qb-btn qb-btn-submit" id="btnSubmit" onclick="confirmSubmit()">交卷</button>
  </div>
</div>
<div id="toast" class="toast"></div>
<div id="confirmMask" style="display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:9998;-webkit-tap-highlight-color:transparent">
  <div style="position:absolute;top:50%;left:50%;width:80%;max-width:320px;background:#fff;-webkit-border-radius:14px;border-radius:14px;-webkit-transform:translate(-50%,-50%);transform:translate(-50%,-50%);overflow:hidden;-webkit-box-shadow:0 8px 32px rgba(0,0,0,0.18);box-shadow:0 8px 32px rgba(0,0,0,0.18)">
    <div style="padding:20px 20px 12px;font-size:15px;color:#1A1A2E;text-align:center" id="confirmMsg"></div>
    <div style="display:-webkit-box;display:-webkit-flex;display:flex;border-top:1px solid #e0e0e0">
      <button type="button" id="confirmCancel" onclick="hideConfirm()" style="-webkit-box-flex:1;-webkit-flex:1;flex:1;padding:14px;font-size:15px;border:none;background:transparent;color:#6E7191;cursor:pointer;border-right:1px solid #e0e0e0">取消</button>
      <button type="button" id="confirmOk" onclick="doConfirmOk()" style="-webkit-box-flex:1;-webkit-flex:1;flex:1;padding:14px;font-size:15px;border:none;background:transparent;color:#6C63FF;font-weight:600;cursor:pointer">确定</button>
    </div>
  </div>
</div>
<div class="container">
  <div id="modePage">
    <div class="card">
      <div class="card-title">🎯 选择训练模式</div>
      <p style="color:#6E7191;font-size:14px;margin-bottom:16px">请选择要使用的模式，两种模式均可多次使用</p>
      <div style="display:-webkit-box;display:-webkit-flex;display:flex;gap:12px">
        <button type="button" onclick="selectMode('practice')" style="-webkit-box-flex:1;-webkit-flex:1;flex:1;padding:20px 12px;background:#E8F5E9;border:2px solid #2ED573;-webkit-border-radius:14px;border-radius:14px;cursor:pointer;text-align:center;color:#1A1A2E">
          <div style="font-size:32px;margin-bottom:8px">📖</div>
          <div style="font-weight:700;font-size:16px;margin-bottom:4px">练习模式</div>
          <div style="font-size:12px;color:#6E7191">答题后立即显示<br>解析和正确答案</div>
        </button>
        <button type="button" onclick="selectMode('exam')" style="-webkit-box-flex:1;-webkit-flex:1;flex:1;padding:20px 12px;background:#E8E6FF;border:2px solid #6C63FF;-webkit-border-radius:14px;border-radius:14px;cursor:pointer;text-align:center;color:#1A1A2E">
          <div style="font-size:32px;margin-bottom:8px">📝</div>
          <div style="font-weight:700;font-size:16px;margin-bottom:4px">考试模式</div>
          <div style="font-size:12px;color:#6E7191">答完所有题目<br>交卷后显示成绩</div>
        </button>
      </div>
    </div>
  </div>
  <div id="infoPage" style="display:none">
    <div class="card">
      <div class="card-title">考生信息</div>
      <div class="info-form">
        <div class="form-group">
          <label>姓名</label>
          <input type="text" id="infoName" placeholder="请输入姓名">
        </div>
        <div class="form-group">
          <label>班级</label>
          <input type="text" id="infoClass" placeholder="请输入班级">
        </div>
      </div>
      <button type="button" class="btn btn-primary btn-block" onclick="startExam()" style="margin-top:16px;width:100%">开始考试</button>
    </div>
  </div>
  <div id="quizPage" style="display:none">
    <div class="progress-bar"><div class="progress-fill" id="progressBar"></div></div>
    <div class="q-dots" id="questionDots"></div>
    <div id="questionArea"></div>
    <div class="question-nav">
      <button type="button" class="btn btn-outline" id="btnPrev" onclick="goQ(-1)">上一题</button>
      <button type="button" class="btn btn-primary" id="btnNext" onclick="goQ(1)">下一题</button>
    </div>
  </div>
  <div id="resultPage" style="display:none"></div>
</div>
<script>
var QUESTIONS = $questionsJson;
var TIME_LIMIT = ${timeMinutes * 60};
var TYPE_SCORES = ${buildScoreMapJson(scoreMap)};
var TITLE = ${jsonStr(title)};
// 运行时解码答案（答案在源码中以Base64存储，防止直接查看源代码获取答案）
(function(){
  function _b64d(s){try{return decodeURIComponent(escape(atob(s)));}catch(e){return s||'';}}
  for(var i=0;i<QUESTIONS.length;i++){
    QUESTIONS[i].correctAnswer=_b64d(QUESTIONS[i]._ca||'');
    QUESTIONS[i].explanation=_b64d(QUESTIONS[i]._ex||'');
    delete QUESTIONS[i]._ca; delete QUESTIONS[i]._ex;
  }
})();
var answers = {};
var practiceRevealed = {};
var MODE = 'exam';
var currentIndex = 0;
var startTime = 0;
var elapsed = 0;
var timerInterval = null;
var studentName = '';
var studentClass = '';
var confirmOkFn = null;

function showToast(msg) {
  var t = document.getElementById('toast');
  if (!t) { alert(msg); return; }
  t.innerHTML = msg;
  t.className = 'toast show';
  setTimeout(function(){ t.className = 'toast'; }, 2500);
}

function selectMode(mode) {
  MODE = mode;
  document.getElementById('modePage').style.display = 'none';
  if (mode === 'exam') {
    var btnS = document.getElementById('btnSubmit');
    if (btnS) btnS.innerHTML = '交卷';
    document.getElementById('infoPage').style.display = 'block';
  } else {
    document.getElementById('infoHeader').style.display = 'none';
    answers = {}; practiceRevealed = {}; currentIndex = 0;
    startTime = (new Date()).getTime(); elapsed = 0;
    if (timerInterval) clearInterval(timerInterval);
    timerInterval = setInterval(updateTimer, 1000);
    document.getElementById('quizBar').style.display = '';
    var btnS2 = document.getElementById('btnSubmit');
    if (btnS2) btnS2.innerHTML = '结束练习';
    document.getElementById('quizPage').style.display = 'block';
    renderDots(); renderQuestion();
  }
}
function confirmMulti(qId) { if (MODE==='practice'&&!practiceRevealed[qId]) { practiceRevealed[qId]=true; renderDots(); renderQuestion(); } }
function confirmFill(qId) { if (MODE==='practice'&&!practiceRevealed[qId]) { practiceRevealed[qId]=true; renderDots(); renderQuestion(); } }

function startExam() {
  var nameEl = document.getElementById('infoName');
  var classEl = document.getElementById('infoClass');
  studentName = nameEl ? nameEl.value.replace(/^\s+|\s+${'$'}/g, '') : '';
  studentClass = classEl ? classEl.value.replace(/^\s+|\s+${'$'}/g, '') : '';
  if (!studentName) { showToast('请输入姓名'); return; }
  document.getElementById('infoPage').style.display = 'none';
  document.getElementById('infoHeader').style.display = 'none';
  document.getElementById('quizBar').style.display = '';
  document.getElementById('quizPage').style.display = 'block';
  answers = {};
  currentIndex = 0;
  startTime = (new Date()).getTime();
  elapsed = 0;
  if (timerInterval) clearInterval(timerInterval);
  timerInterval = setInterval(updateTimer, 1000);
  renderDots();
  renderQuestion();
}

function updateTimer() {
  elapsed = Math.floor(((new Date()).getTime() - startTime) / 1000);
  var display = document.getElementById('timerDisplay');
  if (!display) return;
  var m, s;
  if (TIME_LIMIT > 0) {
    var left = TIME_LIMIT - elapsed;
    if (left < 0) left = 0;
    m = Math.floor(left / 60); s = left % 60;
    display.innerHTML = (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
    display.className = left <= 60 ? 'qb-timer warning' : 'qb-timer';
    if (left <= 0) { clearInterval(timerInterval); doSubmit(); }
  } else {
    m = Math.floor(elapsed / 60); s = elapsed % 60;
    display.innerHTML = (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
  }
}

function initDotsDrag() {
  var el = document.getElementById('questionDots');
  if (!el || el._dragInited) return;
  el._dragInited = true;
  var down = false, startX = 0, scrollLeft = 0;
  el.addEventListener('mousedown', function(e) { down = true; startX = e.pageX; scrollLeft = el.scrollLeft; el.style.cursor = 'grabbing'; e.preventDefault(); });
  document.addEventListener('mouseup', function() { if (!down) return; down = false; el.style.cursor = ''; });
  document.addEventListener('mousemove', function(e) { if (!down) return; el.scrollLeft = scrollLeft - (e.pageX - startX); });
}

function renderDots() {
  var el = document.getElementById('questionDots');
  if (!el) return;
  var html = '';
  for (var i = 0; i < QUESTIONS.length; i++) {
    var cls = 'q-dot';
    if (i === currentIndex) cls += ' current';
    else if (MODE==='practice'&&practiceRevealed[QUESTIONS[i].id]) {
      var _pa=answers[QUESTIONS[i].id]||'',_pc=QUESTIONS[i].correctAnswer||'',_qt=QUESTIONS[i].type,_pok=false;
      if(_qt===2||_qt===6){var _pua=_pa.split('');_pua.sort();var _pca=_pc.split('');_pca.sort();_pok=_pua.join('')===_pca.join('');}else{_pok=_pa.toLowerCase()===_pc.toLowerCase();}
      cls+=_pok?' correct-dot':' wrong-dot';
    } else if (answers[QUESTIONS[i].id] !== undefined && answers[QUESTIONS[i].id] !== '') cls += ' answered';
    html += '<span class="' + cls + '" onclick="goToQ(' + i + ')">' + (i+1) + '<\/span>';
  }
  el.innerHTML = html;
  initDotsDrag();
  var cur = el.children[currentIndex];
  if (cur && cur.scrollIntoView) { try { cur.scrollIntoView({inline:'center'}); } catch(e) { cur.scrollIntoView(false); } }
}

function goToQ(i) { currentIndex = i; renderDots(); renderQuestion(); }
function goQ(d) {
  var ni = currentIndex + d;
  if (ni >= 0 && ni < QUESTIONS.length) { currentIndex = ni; renderDots(); renderQuestion(); }
}

function renderQuestion() {
  var q = QUESTIONS[currentIndex];
  var total = QUESTIONS.length;
  var pt = document.getElementById('progressText');
  var pb = document.getElementById('progressBar');
  if (pt) pt.innerHTML = '第 ' + (currentIndex+1) + '/' + total + ' 题';
  if (pb) pb.style.width = Math.round((currentIndex+1)/total*100) + '%';
  var typeLabels = {1:'单选题',2:'多选题',3:'判断题',4:'填空题',6:'不定项选择题'};
  var diffLabels = ['★','★★','★★★','★★★★','★★★★★'];
  var saved = answers[q.id] || '';
  var i, letter, val, isSel, cleanOpt;

  var html = '<div class="question-card"><div class="question-meta">' +
    '<span class="tag tag-type">' + (typeLabels[q.type]||'') + '<\/span>' +
    '<span class="tag tag-diff">' + (diffLabels[(q.difficulty||1)-1]||'') + '<\/span><\/div>' +
    '<div class="question-content">' + renderRichText(q.content) + '<\/div>' +
    (q.imageUrl ? '<div style="margin:8px 0;text-align:center"><img src="' + escAttr(q.imageUrl) + '" style="max-width:100%;max-height:300px;-webkit-border-radius:8px;border-radius:8px;border:1px solid #e0e0e0" onerror="this.style.display=\'none\'"><\/div>' : '');

  if (q.type === 1 || q.type === 3) {
    var isRev13=MODE==='practice'&&!!practiceRevealed[q.id];
    html += '<ul class="options">';
    for (i = 0; i < q.options.length; i++) {
      letter = String.fromCharCode(65+i);
      val = q.type === 3 ? q.options[i] : letter;
      isSel = val === saved;
      cleanOpt = q.options[i].replace(/^[A-Za-z][.、]\s*/, '');
      var safeVal = escAttr(val);
      var isCorrectOpt13=(val===q.correctAnswer);
      var optCls13='option-item'; if(isRev13){if(isSel){optCls13+=isCorrectOpt13?' correct':' wrong';}else if(isCorrectOpt13){optCls13+=' correct';}}else if(isSel){optCls13+=' selected';}
      var clickAttr13=isRev13?'':('onclick="selectOpt('+q.id+',\''+safeVal+'\')"');
      html += '<li class="' + optCls13 + '" ' + clickAttr13 + '>' +
        '<div class="option-radio"><\/div><span>' + (q.type===3 ? renderRichText(q.options[i]) : letter+'. '+renderRichText(cleanOpt)) + '<\/span><\/li>';
    }
    html += '<\/ul>';
    if(isRev13){var _isOk13=saved===q.correctAnswer;html+='<div class="practice-explain">'+(_isOk13?'✅ 回答正确！':'❌ 回答错误，正确答案：<strong>'+renderRichText(q.correctAnswer)+'<\/strong>')+(q.explanation?'<br><span style="font-size:13px;color:#555">解析：'+renderRichText(q.explanation)+'<\/span>':'')+'<\/div>';if(currentIndex<QUESTIONS.length-1){html+='<button type="button" class="btn btn-primary" style="margin-top:8px;width:100%" onclick="goQ(1)">下一题 →<\/button>';}else{html+='<button type="button" class="btn btn-success" style="margin-top:8px;width:100%" onclick="confirmSubmit()">完成练习<\/button>';}}
  } else if (q.type === 2 || q.type === 6) {
    var selArr = saved ? saved.split('') : [];
    var isRev2=MODE==='practice'&&!!practiceRevealed[q.id];
    html += '<ul class="options">';
    for (i = 0; i < q.options.length; i++) {
      letter = String.fromCharCode(65+i);
      isSel = false;
      for (var si = 0; si < selArr.length; si++) { if (selArr[si] === letter) { isSel = true; break; } }
      cleanOpt = q.options[i].replace(/^[A-Za-z][.、]\s*/, '');
      var isCorrectOpt2=(q.correctAnswer||'').indexOf(letter)>=0;
      var optCls2='option-item'; if(isRev2){if(isSel){optCls2+=isCorrectOpt2?' correct':' wrong';}else if(isCorrectOpt2){optCls2+=' correct';}}else if(isSel){optCls2+=' selected';}
      var clickAttr2=isRev2?'':('onclick="toggleMulti('+q.id+',\''+letter+'\')"');
      html += '<li class="' + optCls2 + '" ' + clickAttr2 + '>' +
        '<div class="option-checkbox"><\/div><span>' + letter + '. ' + renderRichText(cleanOpt) + '<\/span><\/li>';
    }
    html += '<\/ul>';
    if(MODE==='practice'&&!isRev2){html+='<button type="button" class="btn btn-primary" style="margin-top:8px;width:100%" onclick="confirmMulti('+q.id+')">确认'+(q.type===6?'不定项':'多选')+'答案<\/button>';}
    if(isRev2){var _ua2=(saved||'').split('');_ua2.sort();var _ca2=(q.correctAnswer||'').split('');_ca2.sort();var _isOk2=_ua2.join('')===_ca2.join('');html+='<div class="practice-explain">'+(_isOk2?'✅ 回答正确！':'❌ 回答错误，正确答案：<strong>'+renderRichText(q.correctAnswer)+'<\/strong>')+(q.explanation?'<br><span style="font-size:13px;color:#555">解析：'+renderRichText(q.explanation)+'<\/span>':'')+'<\/div>';if(currentIndex<QUESTIONS.length-1){html+='<button type="button" class="btn btn-primary" style="margin-top:8px;width:100%" onclick="goQ(1)">下一题 →<\/button>';}else{html+='<button type="button" class="btn btn-success" style="margin-top:8px;width:100%" onclick="confirmSubmit()">完成练习<\/button>';}}
  } else if (q.type === 4) {
    var isRev4=MODE==='practice'&&!!practiceRevealed[q.id];
    if(isRev4){
      var _fillOk=(saved||'').trim().toLowerCase()===(q.correctAnswer||'').trim().toLowerCase();
      html+='<div style="padding:12px 16px;border:2px solid '+(_fillOk?'#2ED573':'#FF4757')+';-webkit-border-radius:8px;border-radius:8px;background:'+(_fillOk?'#E8F5E9':'#FFEBEE')+';font-size:15px">'+escHtml(saved||'（未填写）')+'<\/div>';
      html+='<div class="practice-explain">'+(_fillOk?'✅ 回答正确！':'❌ 回答错误，正确答案：<strong>'+renderRichText(q.correctAnswer)+'<\/strong>')+(q.explanation?'<br><span style="font-size:13px;color:#555">解析：'+renderRichText(q.explanation)+'<\/span>':'')+'<\/div>';
      if(currentIndex<QUESTIONS.length-1){html+='<button type="button" class="btn btn-primary" style="margin-top:8px;width:100%" onclick="goQ(1)">下一题 →<\/button>';}else{html+='<button type="button" class="btn btn-success" style="margin-top:8px;width:100%" onclick="confirmSubmit()">完成练习<\/button>';}
    }else{
      html += '<input class="fill-input" type="text" id="fillInput_' + q.id + '" placeholder="请输入答案" value="' + escAttr(saved) + '" oninput="saveFill(' + q.id + ',this.value)" onchange="saveFill(' + q.id + ',this.value)">';
      if(MODE==='practice'){html+='<button type="button" class="btn btn-primary" style="margin-top:8px;width:100%" onclick="confirmFill('+q.id+')">确认答案<\/button>';}
    }
  }
  html += '<\/div>';
  var qa = document.getElementById('questionArea');
  if (qa) qa.innerHTML = html;

  var btnPrev = document.getElementById('btnPrev');
  var btnNext = document.getElementById('btnNext');
  if (btnPrev) btnPrev.style.visibility = currentIndex > 0 ? 'visible' : 'hidden';
  if (btnNext) btnNext.style.display = currentIndex < total-1 ? '' : 'none';
  renderMath(qa);
}

function selectOpt(qId, val) { if(MODE==='practice'&&practiceRevealed[qId])return; answers[qId]=val; if(MODE==='practice'){practiceRevealed[qId]=true;} renderDots(); renderQuestion(); }
function toggleMulti(qId, letter) {
  if(MODE==='practice'&&practiceRevealed[qId])return;
  var cur = (answers[qId] || '').split('');
  var newCur = [];
  var found = false;
  for (var i = 0; i < cur.length; i++) {
    if (cur[i] === '') continue;
    if (cur[i] === letter) { found = true; } else { newCur.push(cur[i]); }
  }
  if (!found) newCur.push(letter);
  newCur.sort();
  answers[qId] = newCur.join('');
  renderDots(); renderQuestion();
}
function saveFill(qId, val) { answers[qId] = val; renderDots(); }

function confirmSubmit() {
  if(MODE==='practice'){var _ur=0;for(var i=0;i<QUESTIONS.length;i++){if(!practiceRevealed[QUESTIONS[i].id])_ur++;}showConfirm(_ur>0?'还有 '+_ur+' 题未完成，确定结束练习吗？':'确定结束本次练习吗？',doSubmit);return;}
  var unanswered = 0;
  for (var i = 0; i < QUESTIONS.length; i++) { if (!answers[QUESTIONS[i].id]) unanswered++; }
  var msg = unanswered > 0 ? '还有 ' + unanswered + ' 题未作答，确定要交卷吗？' : '确定要交卷吗？';
  showConfirm(msg, doSubmit);
}
function showConfirm(msg, onOk) {
  confirmOkFn = onOk;
  var mask = document.getElementById('confirmMask');
  var msgEl = document.getElementById('confirmMsg');
  if (!mask) { onOk(); return; }
  if (msgEl) msgEl.innerHTML = escHtml(msg);
  mask.style.display = 'block';
}
function hideConfirm() {
  var mask = document.getElementById('confirmMask');
  if (mask) mask.style.display = 'none';
  confirmOkFn = null;
}
function doConfirmOk() {
  var fn = confirmOkFn;
  hideConfirm();
  if (fn) fn();
}

function normalizeFillBlank(text) {
  var s = text.replace(/[\u3000-\u303f\uff01-\uff60\u2018-\u201f\u2014\u2026\u00b7~`!?.,;:"'()\[\]{}<>@#$%^&*+=|\\/_\-]/g, ' ');
  s = s.replace(/\s*(的|地|得|了|着|过|吧|啊|嘛|呢|吗|嗯|哦|哈|喽|罢了|而已|之类|等等|等)\s*/g, ' ');
  return s.replace(/\s+/g, ' ').trim().toLowerCase();
}
function matchFillBlank(user, correct) {
  var u = normalizeFillBlank(user);
  var c = normalizeFillBlank(correct);
  if (u === c) return true;
  if (u.replace(/ /g,'') === c.replace(/ /g,'')) return true;
  var cWords = c.split(' ').filter(function(w){ return w.length > 0; });
  var uWords = u.split(' ').filter(function(w){ return w.length > 0; });
  if (cWords.length > 1 && cWords.every(function(cw){ return uWords.some(function(uw){ return uw.indexOf(cw)>=0 || cw.indexOf(uw)>=0; }); })) return true;
  return false;
}
function checkFillBlank(userAns, correctAnswer) {
  var alts = (correctAnswer||'').split(/[|｜\/／;；]/);
  for (var k=0; k<alts.length; k++) {
    if (matchFillBlank(userAns, alts[k].trim())) return true;
  }
  return false;
}

function doSubmit() {
  if (timerInterval) { clearInterval(timerInterval); timerInterval = null; }
  elapsed = Math.floor(((new Date()).getTime() - startTime) / 1000);
  var correct = 0, totalScore = 0, gotScore = 0;
  var details = [];
  for (var i = 0; i < QUESTIONS.length; i++) {
    var q = QUESTIONS[i];
    var userAns = answers[q.id] || '';
    var isSkipped = !userAns;
    var isCorrect = false;
    if (!isSkipped) {
      if (q.type === 2 || q.type === 6) {
        var ua = userAns.split(''); ua.sort(); var ca = (q.correctAnswer||'').split(''); ca.sort();
        isCorrect = ua.join('') === ca.join('');
      } else if (q.type === 4) {
        isCorrect = checkFillBlank(userAns, q.correctAnswer||'');
      } else {
        isCorrect = userAns.toLowerCase() === (q.correctAnswer||'').toLowerCase();
      }
    }
    if (isCorrect) correct++;
    var qScore = TYPE_SCORES[q.type] || 2;
    totalScore += qScore;
    if (isCorrect) gotScore += qScore;
    details.push({index:i,content:q.content,type:q.type,options:q.options,imageUrl:q.imageUrl,userAnswer:userAns,correctAnswer:q.correctAnswer,explanation:q.explanation,knowledgePoint:q.knowledgePoint,isCorrect:isCorrect,isSkipped:isSkipped});
  }
  document.getElementById('quizPage').style.display = 'none';
  document.getElementById('quizBar').style.display = 'none';
  var rp = document.getElementById('resultPage');
  rp.style.display = 'block';
  var pct = totalScore > 0 ? Math.round(gotScore / totalScore * 100) : 0;
  var scoreClass = pct >= 80 ? 'score-high' : pct >= 60 ? 'score-mid' : 'score-low';
  var min = Math.floor(elapsed / 60), sec = elapsed % 60;
  var wrongCount = 0, skipCount = 0;
  for (var i = 0; i < details.length; i++) {
    if (!details[i].isCorrect && !details[i].isSkipped) wrongCount++;
    if (details[i].isSkipped) skipCount++;
  }
  var typeLabels = {1:'单选',2:'多选',3:'判断',4:'填空',6:'不定项'};
  var html = '<div class="card" style="margin-top:16px"><div class="result-score">' +
    '<div class="score-circle ' + scoreClass + '">' + gotScore + '<span style="font-size:14px">/' + totalScore + '分<\/span><\/div>' +
    '<h2 style="margin-top:8px">' + (MODE==='practice'?(pct>=80?'练习很棒！':pct>=60?'练习完成！':'继续努力！'):(pct>=80?'太棒了！':pct>=60?'继续加油！':'再接再厉！')) + '<\/h2>' +
    '<p style="color:#6E7191;margin-top:4px">' + (MODE==='practice'?'':(escHtml(studentName)+' · '+escHtml(studentClass)+' · ')) + escHtml(TITLE) + '<\/p><\/div>' +
    '<div class="result-stats">' +
    '<div><div class="stat-value">' + QUESTIONS.length + '<\/div><div class="stat-label">总题数<\/div><\/div>' +
    '<div><div class="stat-value" style="color:#2ED573">' + correct + '<\/div><div class="stat-label">正确<\/div><\/div>' +
    '<div><div class="stat-value" style="color:#FF4757">' + wrongCount + '<\/div><div class="stat-label">错误<\/div><\/div>' +
    (skipCount > 0 ? '<div><div class="stat-value" style="color:#6E7191">' + skipCount + '<\/div><div class="stat-label">未作答<\/div><\/div>' : '') +
    '<\/div><div style="text-align:center;color:#6E7191;margin:12px 0">用时 ' + min + '分' + sec + '秒<\/div>' +
    '<div style="text-align:center;margin-top:16px">' +
    '<button type="button" class="btn btn-primary" onclick="restartExam()" style="cursor:pointer">'+(MODE==='practice'?'重新练习':'重新考试')+'<\/button>'
    '<\/div><\/div>' +
    '<div class="card"><div class="card-title">答题详情<\/div>';
  for (var i = 0; i < details.length; i++) {
    var d = details[i];
    var itemClass = d.isSkipped ? '' : (d.isCorrect ? 'correct-item' : 'wrong-item');
    var icon = d.isSkipped ? '[未答]' : (d.isCorrect ? '[正确]' : '[错误]');
    var optStr = '';
    if (d.options && d.options.length > 0) {
      for (var oi = 0; oi < d.options.length; oi++) { if (oi > 0) optStr += ' | '; optStr += renderRichText(d.options[oi]); }
    }
    html += '<div class="detail-item ' + itemClass + '">' +
      '<div style="display:-webkit-box;display:-webkit-flex;display:flex;-webkit-box-pack:justify;-webkit-justify-content:space-between;justify-content:space-between;-webkit-box-align:center;-webkit-align-items:center;align-items:center;margin-bottom:6px"><strong>' + (i+1) + '. [' + (typeLabels[d.type]||'') + '] ' + icon + '<\/strong>' +
      (d.knowledgePoint ? '<span class="tag tag-type">' + escHtml(d.knowledgePoint) + '<\/span>' : '') + '<\/div>' +
      '<div style="margin-bottom:8px">' + renderRichText(d.content) + '<\/div>' +
      (d.imageUrl ? '<div style="margin:6px 0;text-align:center"><img src="' + escAttr(d.imageUrl) + '" style="max-width:100%;max-height:200px;-webkit-border-radius:8px;border-radius:8px;border:1px solid #e0e0e0" onerror="this.style.display=\'none\'"><\/div>' : '') +
      (optStr ? '<div style="font-size:13px;color:#6E7191;margin-bottom:6px">' + optStr + '<\/div>' : '') +
      '<div class="detail-answer"><div>你的答案: <strong style="color:' + (d.isSkipped?'#6E7191':d.isCorrect?'#2ED573':'#FF4757') + '">' + escHtml(d.isSkipped?'未作答':d.userAnswer||'未作答') + '<\/strong><\/div>' +
      (!d.isSkipped ? '<div>正确答案: <strong style="color:#2ED573">' + escHtml(d.correctAnswer) + '<\/strong><\/div>' : '') +
      (d.explanation && !d.isSkipped ? '<div style="margin-top:6px;padding-top:6px;border-top:1px solid #e0e0e0">解析: ' + renderRichText(d.explanation) + '<\/div>' : '') +
      '<\/div><\/div>';
  }
  html += '<\/div>';
  rp.innerHTML = html;
  renderMath(rp);
}

function restartExam() {
  answers = {};
  practiceRevealed = {};
  currentIndex = 0;
  MODE = 'exam';
  document.getElementById('resultPage').style.display = 'none';
  document.getElementById('modePage').style.display = 'block';
  document.getElementById('infoHeader').style.display = 'block';
}

function _latexToUnicode(s) {
  var map = [
    [/\\alpha/g,'\u03b1'],[/\\beta/g,'\u03b2'],[/\\gamma/g,'\u03b3'],[/\\delta/g,'\u03b4'],
    [/\\epsilon/g,'\u03b5'],[/\\varepsilon/g,'\u03b5'],[/\\zeta/g,'\u03b6'],[/\\eta/g,'\u03b7'],
    [/\\theta/g,'\u03b8'],[/\\vartheta/g,'\u03d1'],[/\\iota/g,'\u03b9'],[/\\kappa/g,'\u03ba'],
    [/\\lambda/g,'\u03bb'],[/\\mu/g,'\u03bc'],[/\\nu/g,'\u03bd'],[/\\xi/g,'\u03be'],
    [/\\pi/g,'\u03c0'],[/\\varpi/g,'\u03d6'],[/\\rho/g,'\u03c1'],[/\\varrho/g,'\u03f1'],
    [/\\sigma/g,'\u03c3'],[/\\varsigma/g,'\u03c2'],[/\\tau/g,'\u03c4'],[/\\upsilon/g,'\u03c5'],
    [/\\phi/g,'\u03c6'],[/\\varphi/g,'\u03c6'],[/\\chi/g,'\u03c7'],[/\\psi/g,'\u03c8'],
    [/\\omega/g,'\u03c9'],[/\\Gamma/g,'\u0393'],[/\\Delta/g,'\u0394'],[/\\Theta/g,'\u0398'],
    [/\\Lambda/g,'\u039b'],[/\\Xi/g,'\u039e'],[/\\Pi/g,'\u03a0'],[/\\Sigma/g,'\u03a3'],
    [/\\Upsilon/g,'\u03a5'],[/\\Phi/g,'\u03a6'],[/\\Psi/g,'\u03a8'],[/\\Omega/g,'\u03a9'],
    [/\\times/g,'\u00d7'],[/\\div/g,'\u00f7'],[/\\cdot/g,'\u00b7'],[/\\pm/g,'\u00b1'],
    [/\\mp/g,'\u2213'],[/\\leq/g,'\u2264'],[/\\geq/g,'\u2265'],[/\\neq/g,'\u2260'],
    [/\\approx/g,'\u2248'],[/\\equiv/g,'\u2261'],[/\\sim/g,'\u223c'],[/\\simeq/g,'\u2243'],
    [/\\infty/g,'\u221e'],[/\\partial/g,'\u2202'],[/\\nabla/g,'\u2207'],[/\\forall/g,'\u2200'],
    [/\\exists/g,'\u2203'],[/\\in/g,'\u2208'],[/\\notin/g,'\u2209'],[/\\subset/g,'\u2282'],
    [/\\supset/g,'\u2283'],[/\\subseteq/g,'\u2286'],[/\\supseteq/g,'\u2287'],[/\\cup/g,'\u222a'],
    [/\\cap/g,'\u2229'],[/\\emptyset/g,'\u2205'],[/\\rightarrow/g,'\u2192'],[/\\leftarrow/g,'\u2190'],
    [/\\Rightarrow/g,'\u21d2'],[/\\Leftarrow/g,'\u21d0'],[/\\Leftrightarrow/g,'\u21d4'],
    [/\\leftrightarrow/g,'\u2194'],[/\\uparrow/g,'\u2191'],[/\\downarrow/g,'\u2193'],
    [/\\to/g,'\u2192'],[/\\gets/g,'\u2190'],[/\\sqrt\{([^}]+)\}/g,'\u221a($1)'],
    [/\\sqrt/g,'\u221a'],[/\\sum/g,'\u03a3'],[/\\prod/g,'\u03a0'],[/\\int/g,'\u222b'],
    [/\\oint/g,'\u222e'],[/\\lim/g,'lim'],[/\\log/g,'log'],[/\\ln/g,'ln'],
    [/\\sin/g,'sin'],[/\\cos/g,'cos'],[/\\tan/g,'tan'],[/\\cot/g,'cot'],
    [/\\sec/g,'sec'],[/\\csc/g,'csc'],[/\\max/g,'max'],[/\\min/g,'min'],
    [/\\frac\{([^}]+)\}\{([^}]+)\}/g,'($1)/($2)'],[/\^2/g,'\u00b2'],[/\^3/g,'\u00b3'],
    [/\^\{([^}]+)\}/g,'^($1)'],[/\_\{([^}]+)\}/g,'_($1)'],
    [/\\text\{([^}]+)\}/g,'$1'],[/\\mathrm\{([^}]+)\}/g,'$1'],
    [/\\mathbf\{([^}]+)\}/g,'$1'],[/\\mathit\{([^}]+)\}/g,'$1'],
    [/\\left[\(\[{|]/g,''],[/\\right[\)\]}|]/g,''],[/[{}]/g,''],
    [/\\,/g,' '],[/\\;/g,' '],[/\\!/g,''],[/\\quad/g,' '],[/\\qquad/g,'  '],
    [/\\\\/g,' ']
  ];
  for (var i = 0; i < map.length; i++) { s = s.replace(map[i][0], map[i][1]); }
  return s.replace(/\s+/g, ' ').replace(/^\s+|\s+${'$'}/g, '');
}
function _renderMathFallback(el) {
  if (!el) return;
  var html = el.innerHTML;
  html = html.replace(/\\\[([\s\S]*?)\\\]/g, function(m, inner) {
    return '<span style="display:inline-block;font-family:monospace;background:#f5f5f5;-webkit-border-radius:3px;border-radius:3px;padding:1px 4px;font-size:0.95em">' + _latexToUnicode(inner) + '<\/span>';
  });
  html = html.replace(/\\\(([\s\S]*?)\\\)/g, function(m, inner) {
    return '<span style="font-family:monospace;background:#f5f5f5;-webkit-border-radius:3px;border-radius:3px;padding:1px 4px;font-size:0.95em">' + _latexToUnicode(inner) + '<\/span>';
  });
  el.innerHTML = html;
}
function renderMath(el) {
  if (typeof renderMathInElement === 'function' && el) {
    try { renderMathInElement(el, {delimiters:[{left:"\\[",right:"\\]",display:true},{left:"\\(",right:"\\)",display:false}],throwOnError:false}); return; } catch(e){}
  }
  _renderMathFallback(el);
}
function escHtml(s) { if(!s)return''; return String(s).replace(/&/g,'&amp;').replace(/\x3c/g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/\n/g,'<br>'); }
function escAttr(s) { if(!s)return''; return String(s).replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }
function preprocessMath(s) {
  var formulas=[], fi=0;
  s=s.replace(/\$\$([\s\S]*?)\$\$/g,function(m,inner){var ph='@@MATH'+(fi++)+'@@';formulas.push('\\['+inner+'\\]');return ph;});
  s=s.replace(/\$([^\$\n\r]+?)\$/g,function(m,inner){var ph='@@MATH'+(fi++)+'@@';formulas.push('\\('+inner+'\\)');return ph;});
  s=s.replace(/\$([^\$\n\r]+)$/g,function(m,inner){var ph='@@MATH'+(fi++)+'@@';formulas.push('\\('+inner.trim()+'\\)');return ph;});
  s=s.replace(/\\\[([\s\S]*?)\\\]/g,function(m,inner){var ph='@@MATH'+(fi++)+'@@';formulas.push('\\['+inner+'\\]');return ph;});
  s=s.replace(/\\\(([ \s\S]*?)\\\)/g,function(m,inner){var ph='@@MATH'+(fi++)+'@@';formulas.push('\\('+inner+'\\)');return ph;});
  return {text:s,formulas:formulas};
}
function renderRichText(s) {
  if(!s)return'';
  s=s.replace(/\f/g,'\\f').replace(/\x08/g,'\\b').replace(/\t/g,'\\t').replace(/\r/g,'\\r');
  s=s.replace(/\n(eq|abla|ot(?![a-z])|ewline|u(?![a-z])|i(?![a-z])|leq|geq|mid|otin|parallel|cong|eg(?![a-z])|exists|ormalsize|ormal|vDash|subseteq|supseteq|subset(?![e])|supset|less|Rightarrow|Leftarrow|Leftrightarrow|prec|succ|sqsubset|sqsupset)/g,'\\n$1');
  var mr=preprocessMath(s); s=mr.text; var formulas=mr.formulas;
  var imgs=[],idx=0;
  s=s.replace(/!\[([^\]]*)\]\(([^)]+)\)/g,function(m,alt,url){var ph='@@IMG'+(idx++)+'@@';imgs.push('<img src="'+url+'" alt="'+escAttr(alt)+'" style="max-width:100%;max-height:200px;vertical-align:middle;-webkit-border-radius:4px;border-radius:4px" onerror="this.style.display=\'none\'">');return ph;});
  var h=escHtml(s);
  for(var fi=0;fi<formulas.length;fi++){h=h.replace('@@MATH'+fi+'@@',formulas[fi]);}
  for(var ii=0;ii<imgs.length;ii++){h=h.replace('@@IMG'+ii+'@@',imgs[ii]);}
  return h;
}
</script>
<script>${katexJs}</script>
<script>${autoRenderJs}</script>
<script>
(function(){
  try{renderMathInElement(document.body,{delimiters:[{left:"\\[",right:"\\]",display:true},{left:"\\(",right:"\\)",display:false}],throwOnError:false});}catch(e){}
})();
</script>
<footer style="margin-top:32px;padding:16px;text-align:center;border-top:1px solid #e0e0e0;font-size:12px;color:#6E7191">
  拍书题库 &nbsp;·&nbsp; 技术支持：183209@qq.com
</footer>
</body>
</html>"""
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun jsonStr(s: String): String {
        return "\"" + s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("/", "\\/") + "\""
    }

    private fun buildScoreMapJson(map: Map<Int, Int>): String {
        if (map.isEmpty()) return "{1:2,2:3,3:2,4:2}"
        return "{" + map.entries.joinToString(",") { "${it.key}:${it.value}" } + "}"
    }

    private fun readAssetText(context: Context, path: String): String {
        return try {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    private fun readAssetBase64(context: Context, path: String): String {
        return try {
            val bytes = context.assets.open(path).use { it.readBytes() }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }
}
