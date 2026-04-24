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
package com.smartquiz.api

import android.net.Uri
import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 本地OCR服务 - 使用Google ML Kit进行文字识别
 * 支持中文和英文混合识别
 */
class LocalOcrService {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    /**
     * 识别单张图片中的文字
     */
    suspend fun recognizeImage(context: Context, imageUri: Uri): String = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromFilePath(context, imageUri)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!cont.isCancelled) cont.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    if (!cont.isCancelled) {
                        cont.resumeWithException(RuntimeException("OCR识别失败: ${e.message}"))
                    }
                }
        } catch (e: Exception) {
            cont.resumeWithException(RuntimeException("加载图片失败: ${e.message}"))
        }
    }

    /**
     * 批量识别多张图片
     */
    suspend fun recognizeImages(context: Context, imageUris: List<Uri>): String {
        val results = StringBuilder()
        imageUris.forEachIndexed { index, uri ->
            val text = recognizeImage(context, uri)
            if (index > 0) results.append("\n\n---\n\n")
            results.append(text)
        }
        return results.toString()
    }

    fun close() {
        recognizer.close()
    }
}
