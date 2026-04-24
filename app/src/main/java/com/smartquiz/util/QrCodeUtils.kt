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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeUtils {

    /**
     * 生成QR码Bitmap
     * @param content 二维码内容
     * @param size    像素尺寸（宽高相同）
     * @param fgColor 前景色（默认黑色）
     * @param bgColor 背景色（默认透明）
     */
    fun generate(
        content: String,
        size: Int = 512,
        fgColor: Int = Color.BLACK,
        bgColor: Int = Color.TRANSPARENT
    ): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) fgColor else bgColor)
            }
        }
        return bitmap
    }

    /**
     * 生成分享卡片Bitmap（渐变背景 + 标题 + URL文字 + 二维码）
     * 所有绘制使用原生Canvas，不依赖额外视图。
     */
    fun generateShareCard(title: String, url: String, widthPx: Int = 900): Bitmap {
        val padding = 60
        val qrSize = widthPx - padding * 2
        val titleTextSize = 42f
        val urlTextSize = 28f
        val subtitleTextSize = 24f
        val lineSpacing = 16f

        // 计算文字高度
        val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = titleTextSize
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val urlPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            textSize = urlTextSize
        }
        val subtitlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt()
            textSize = subtitleTextSize
        }

        // 自动换行：拆分title
        val titleLines = breakText(title, titlePaint, widthPx - padding * 2)
        val titleBlockH = titleLines.size * (titleTextSize + lineSpacing).toInt()

        val totalHeight = padding + titleBlockH + lineSpacing.toInt() +
            (urlTextSize + lineSpacing).toInt() +
            (subtitleTextSize + lineSpacing * 2).toInt() +
            qrSize + padding

        val bitmap = Bitmap.createBitmap(widthPx, totalHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 渐变背景
        val gradientPaint = android.graphics.Paint()
        val shader = android.graphics.LinearGradient(
            0f, 0f, widthPx.toFloat(), totalHeight.toFloat(),
            intArrayOf(0xFF5C6BC0.toInt(), 0xFF7E57C2.toInt()),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        gradientPaint.shader = shader
        val bgRect = android.graphics.RectF(0f, 0f, widthPx.toFloat(), totalHeight.toFloat())
        val bgRad = 48f
        canvas.drawRoundRect(bgRect, bgRad, bgRad, gradientPaint)

        // 绘制标题行
        var y = padding + titleTextSize
        for (line in titleLines) {
            canvas.drawText(line, padding.toFloat(), y, titlePaint)
            y += titleTextSize + lineSpacing
        }
        y += lineSpacing

        // 绘制副标题
        canvas.drawText("扫码或访问以下链接开始练习", padding.toFloat(), y, subtitlePaint)
        y += subtitleTextSize + lineSpacing * 2

        // 绘制URL
        canvas.drawText(url, padding.toFloat(), y, urlPaint)
        y += urlTextSize + lineSpacing * 2

        // 绘制二维码（白色背景圆角方块）
        val qrPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        val qrLeft = padding.toFloat()
        val qrTop = y
        val qrRect = android.graphics.RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize)
        canvas.drawRoundRect(qrRect, 24f, 24f, qrPaint)

        val qrBitmap = generate(url, size = qrSize - 40, fgColor = 0xFF3949AB.toInt(), bgColor = Color.WHITE)
        canvas.drawBitmap(qrBitmap, qrLeft + 20, qrTop + 20, null)
        qrBitmap.recycle()

        return bitmap
    }

    private fun breakText(text: String, paint: android.graphics.Paint, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val count = paint.breakText(text, start, text.length, true, maxWidth.toFloat(), null)
            lines.add(text.substring(start, start + count))
            start += count
        }
        return lines.ifEmpty { listOf(text) }
    }
}
