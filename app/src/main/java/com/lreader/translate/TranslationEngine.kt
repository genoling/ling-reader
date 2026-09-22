package com.lreader.translate

import com.lreader.model.TranslationResult

/**
 * 翻译引擎抽象。后续可在 [TranslationEngines] 中注册具体实现（填 API Key）。
 *
 * ==== 如何接入新引擎（预留） ====
 * 1. 实现本接口，例如：
 *
 *   class DeepLTranslator(private val apiKey: String) : TranslationEngine {
 *       override val id = "deepl"
 *       override val displayName = "DeepL"
 *       override suspend fun translate(text: String, from: String, to: String): TranslationResult { ... }
 *       override fun isConfigured() = apiKey.isNotBlank()
 *   }
 *
 * 2. 在 [TranslationEngines.registry] 中 add(DeepLTranslator(key))
 * 3. 在设置页填写 API Key 后调用 [TranslationEngines.reload]
 */
interface TranslationEngine {
    /** 唯一标识，如 "deepl" / "baidu" / "openai" */
    val id: String

    /** 展示名 */
    val displayName: String

    /** 是否已配置 Key（未配置则不参与翻译） */
    fun isConfigured(): Boolean

    /**
     * 翻译文本。
     * @param text 原文
     * @param from 源语言（"auto" 表示自动）
     * @param to   目标语言（"zh" / "en" ...）
     */
    suspend fun translate(text: String, from: String = "en", to: String = "zh"): TranslationResult
}
