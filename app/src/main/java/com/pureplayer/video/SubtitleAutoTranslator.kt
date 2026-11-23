package com.pureplayer.video

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Automatic Subtitle Translator for VEXO Player (v90.20).
 *
 * Supports:
 * - Batch translation with dialogue context (SRT / VTT / ASS)
 * - Preservation of timing, line breaks, and inline tags (<i>, <b>, {\an8}, etc.)
 * - Multiple backends: Google Cloud Translation, DeepL, OpenAI-compatible LLM, and built-in fallback
 * - Graceful automatic fallback if a configured API key is missing or fails
 * - Arabic RTL & punctuation normalization (MSA or Simple Arabic style for LLM)
 * - Disk caching per subtitle hash + target language (with forceRetranslate bypass)
 * - Progress callback + coroutine cancellation
 */
object SubtitleAutoTranslator {

    private const val TAG = "SubtitleAutoTranslator"
    private const val BATCH_SIZE = 18

    data class TranslationResult(
        val translatedFile: File,
        val cues: List<SubtitleCue>,
        val sourceLang: String,
        val targetLang: String,
        val providerUsed: String,
        val fromCache: Boolean,
        val displayLabel: String
    )

    data class ProtectedText(
        val cleanText: String,
        val tokens: Map<String, String>
    )

    private val INLINE_TAG_REGEX = Regex("(<[^>]{1,40}>|\\{[^}]{1,40}\\})")

    /**
     * Protects inline formatting tags (e.g. <i>, </i>, <b>, {\an8}) with stable placeholders
     * so machine translation does not corrupt or translate them.
     */
    fun protectInlineTags(text: String): ProtectedText {
        val tokens = LinkedHashMap<String, String>()
        var idx = 0
        val replaced = INLINE_TAG_REGEX.replace(text) { match ->
            val key = "[[T$idx]]"
            tokens[key] = match.value
            idx++
            key
        }
        return ProtectedText(replaced, tokens)
    }

    /**
     * Restores previously protected inline tags back into translated text.
     */
    fun restoreInlineTags(text: String, tokens: Map<String, String>): String {
        if (tokens.isEmpty()) return text
        var result = text
        for ((key, originalTag) in tokens) {
            // Handle occasional spaces inserted by translators around brackets
            val flexible = Regex("\\[\\s*\\[\\s*" + Regex.escape(key.removeSurrounding("[[", "]]")) + "\\s*]\\s*]")
            result = result.replace(key, originalTag).replace(flexible, originalTag)
        }
        return result
    }

    /**
     * Normalizes Arabic subtitle text for clean RTL rendering and natural Arabic punctuation.
     */
    fun normalizeArabicSubtitleText(text: String): String {
        val hasArabic = text.any { it in '\u0600'..'\u06FF' }
        if (!hasArabic) return text

        return text.split("\n").joinToString("\n") { rawLine ->
            var line = rawLine.trim()
            if (line.isEmpty()) return@joinToString ""

            // Replace Latin punctuation adjacent to Arabic text with proper Arabic punctuation
            line = line
                .replace(Regex("([\\u0600-\\u06FF])\\s*\\?"), "$1؟")
                .replace(Regex("\\?\\s*([\\u0600-\\u06FF])"), "؟ $1")
                .replace(Regex("([\\u0600-\\u06FF])\\s*,"), "$1،")
                .replace(Regex("([\\u0600-\\u06FF])\\s*;"), "$1؛")
                .replace(Regex("\\s+([؟،؛!.])"), "$1")
                .replace(Regex("\\s{2,}"), " ")
                .trim()

            // Prefix with Right-to-Left Mark (RLM) so leading dashes, quotes, or numbers stay RTL-aligned
            if (!line.startsWith("\u200F")) {
                "\u200F$line"
            } else {
                line
            }
        }
    }

    /**
     * Deterministic cache key based on source subtitle bytes, source/target languages, provider, and style.
     */
    fun computeCacheKey(
        sourceBytes: ByteArray,
        sourceLang: String,
        targetLang: String,
        provider: String,
        arabicStyle: String
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sourceBytes)
        md.update("|${sourceLang.lowercase(Locale.ROOT)}|${targetLang.lowercase(Locale.ROOT)}|$provider|$arabicStyle".toByteArray(Charsets.UTF_8))
        return md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Formats cues into standard UTF-8 SRT content preserving exact timing and line breaks.
     */
    fun serializeToSrt(cues: List<SubtitleCue>): String {
        val sb = StringBuilder(cues.size * 64)
        cues.forEachIndexed { index, cue ->
            sb.append(index + 1).append('\n')
            sb.append(formatSrtTime(cue.startMs))
                .append(" --> ")
                .append(formatSrtTime(cue.endMs))
                .append('\n')
            sb.append(cue.text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    fun formatSrtTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3_600_000L
        val minutes = (clamped % 3_600_000L) / 60_000L
        val seconds = (clamped % 60_000L) / 1_000L
        val millis = clamped % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun getLanguageDisplayName(langCode: String, arabicUi: Boolean): String {
        val code = langCode.lowercase(Locale.ROOT).take(2)
        return if (arabicUi) {
            when (code) {
                "ar" -> "العربية"
                "en" -> "الإنجليزية"
                "fr" -> "الفرنسية"
                "es" -> "الإسبانية"
                "de" -> "الألمانية"
                "tr" -> "التركية"
                "ru" -> "الروسية"
                "pt" -> "البرتغالية"
                "it" -> "الإيطالية"
                "hi" -> "الهندية"
                "ur" -> "الأردية"
                "id" -> "الإندونيسية"
                "zh" -> "الصينية"
                "ja" -> "اليابانية"
                "ko" -> "الكورية"
                else -> code.uppercase(Locale.ROOT)
            }
        } else {
            when (code) {
                "ar" -> "Arabic"
                "en" -> "English"
                "fr" -> "French"
                "es" -> "Spanish"
                "de" -> "German"
                "tr" -> "Turkish"
                "ru" -> "Russian"
                "pt" -> "Portuguese"
                "it" -> "Italian"
                "hi" -> "Hindi"
                "ur" -> "Urdu"
                "id" -> "Indonesian"
                "zh" -> "Chinese"
                "ja" -> "Japanese"
                "ko" -> "Korean"
                else -> code.uppercase(Locale.ROOT)
            }
        }
    }

    fun buildTranslatedLabel(targetLang: String, sourceLang: String, arabicUi: Boolean): String {
        val targetName = getLanguageDisplayName(targetLang, arabicUi)
        val sourceName = getLanguageDisplayName(sourceLang, arabicUi)
        return if (arabicUi) {
            "$targetName (مترجمة تلقائياً من $sourceName)"
        } else {
            "$targetName (auto-translated from $sourceName)"
        }
    }

    /**
     * Translates a list of cues (and caches the resulting SRT on disk).
     */
    suspend fun translateSubtitleCues(
        context: Context,
        sourceBytes: ByteArray,
        sourceCues: List<SubtitleCue>,
        sourceLang: String,
        targetLang: String,
        forceRetranslate: Boolean = false,
        onProgress: ((completed: Int, total: Int, provider: String) -> Unit)? = null
    ): TranslationResult? = withContext(Dispatchers.IO) {
        if (sourceCues.isEmpty()) return@withContext null

        val configuredProvider = PlaybackStore.getAutoTranslateProvider(context)
        val apiKey = PlaybackStore.getAutoTranslateApiKey(context)
        val arabicStyle = PlaybackStore.getArabicTranslationStyle(context)
        val isArabicUi = Locale.getDefault().language.equals("ar", ignoreCase = true) ||
            PlaybackStore.getAppLanguageTag(context).startsWith("ar", ignoreCase = true)

        val effectiveProvider = resolveEffectiveProvider(configuredProvider, apiKey)
        val cacheKey = computeCacheKey(sourceBytes, sourceLang, targetLang, effectiveProvider, arabicStyle)
        val cacheDir = File(context.filesDir, "subtitles_cache/translated").apply { mkdirs() }
        val cachedFile = File(cacheDir, "tr_${targetLang}_${cacheKey}.srt")
        val label = buildTranslatedLabel(targetLang, sourceLang, isArabicUi)

        if (!forceRetranslate && cachedFile.exists() && cachedFile.length() > 32) {
            val cachedCues = SubtitleParser.parseFromFile(cachedFile)
            if (cachedCues.isNotEmpty()) {
                onProgress?.invoke(cachedCues.size, cachedCues.size, effectiveProvider)
                return@withContext TranslationResult(
                    translatedFile = cachedFile,
                    cues = cachedCues,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    providerUsed = effectiveProvider,
                    fromCache = true,
                    displayLabel = label
                )
            }
        }

        val total = sourceCues.size
        val translatedCues = ArrayList<SubtitleCue>(total)
        var actualProviderUsed = effectiveProvider
        var completed = 0

        onProgress?.invoke(0, total, actualProviderUsed)

        var idx = 0
        while (idx < total) {
            coroutineContext.ensureActive()
            val end = (idx + BATCH_SIZE).coerceAtMost(total)
            val batch = sourceCues.subList(idx, end)

            val protectedBatch = batch.map { protectInlineTags(it.text) }
            val textsToTranslate = protectedBatch.map { it.cleanText }

            var translatedTexts = translateBatchWithProvider(
                texts = textsToTranslate,
                sourceLang = sourceLang,
                targetLang = targetLang,
                provider = actualProviderUsed,
                apiKey = apiKey,
                arabicStyle = arabicStyle
            )

            // Graceful fallback to built-in free translator if paid provider fails
            if (translatedTexts == null && actualProviderUsed != "free_google") {
                Log.w(TAG, "Provider $actualProviderUsed failed, falling back to free_google")
                actualProviderUsed = "free_google"
                translatedTexts = translateBatchWithProvider(
                    texts = textsToTranslate,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    provider = "free_google",
                    apiKey = "",
                    arabicStyle = arabicStyle
                )
            }

            if (translatedTexts == null || translatedTexts.size != batch.size) {
                // Keep original cues for this batch rather than failing completely if partial translation succeeded
                if (completed == 0) return@withContext null
                for (cue in batch) {
                    translatedCues.add(cue)
                }
            } else {
                for (i in batch.indices) {
                    val restored = restoreInlineTags(translatedTexts[i], protectedBatch[i].tokens)
                    val finalText = if (targetLang.equals("ar", ignoreCase = true)) {
                        normalizeArabicSubtitleText(restored)
                    } else {
                        restored.trim()
                    }
                    translatedCues.add(
                        SubtitleCue(
                            startMs = batch[i].startMs,
                            endMs = batch[i].endMs,
                            text = finalText.ifBlank { batch[i].text }
                        )
                    )
                }
            }

            completed = end
            onProgress?.invoke(completed, total, actualProviderUsed)
            idx = end
        }

        if (translatedCues.isEmpty()) return@withContext null

        val srtContent = serializeToSrt(translatedCues)
        cachedFile.writeText(srtContent, Charsets.UTF_8)

        TranslationResult(
            translatedFile = cachedFile,
            cues = translatedCues,
            sourceLang = sourceLang,
            targetLang = targetLang,
            providerUsed = actualProviderUsed,
            fromCache = false,
            displayLabel = label
        )
    }

    /**
     * Resolves which provider to run based on configuration and API key presence.
     */
    fun resolveEffectiveProvider(configuredProvider: String, apiKey: String): String {
        return when (configuredProvider) {
            "google_cloud", "deepl", "openai_llm" -> {
                if (apiKey.isNotBlank()) configuredProvider else "free_google"
            }
            else -> "free_google"
        }
    }

    private fun translateBatchWithProvider(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        provider: String,
        apiKey: String,
        arabicStyle: String
    ): List<String>? {
        return try {
            when (provider) {
                "google_cloud" -> translateViaGoogleCloud(texts, sourceLang, targetLang, apiKey)
                "deepl" -> translateViaDeepL(texts, sourceLang, targetLang, apiKey)
                "openai_llm" -> translateViaOpenAiLlm(texts, sourceLang, targetLang, apiKey, arabicStyle)
                else -> translateViaFreeGoogle(texts, sourceLang, targetLang)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Batch translation error ($provider): ${e.javaClass.simpleName}")
            null
        }
    }

    private fun translateViaGoogleCloud(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        apiKey: String
    ): List<String>? {
        val url = URL("https://translation.googleapis.com/language/translate/v2?key=${URLEncoder.encode(apiKey, "UTF-8")}")
        val payload = JSONObject().apply {
            put("q", JSONArray(texts))
            put("target", targetLang)
            if (sourceLang.isNotBlank() && sourceLang != "auto") {
                put("source", sourceLang)
            }
            put("format", "text")
        }
        val responseStr = httpPostJson(url, payload.toString()) ?: return null
        val json = JSONObject(responseStr)
        val translations = json.optJSONObject("data")?.optJSONArray("translations") ?: return null
        if (translations.length() != texts.size) return null
        return List(translations.length()) { i ->
            translations.getJSONObject(i).optString("translatedText", texts[i])
        }
    }

    private fun translateViaDeepL(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        apiKey: String
    ): List<String>? {
        val host = if (apiKey.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val url = URL("$host/v2/translate")
        val payload = JSONObject().apply {
            put("text", JSONArray(texts))
            put("target_lang", targetLang.uppercase(Locale.ROOT))
            if (sourceLang.isNotBlank() && sourceLang != "auto") {
                put("source_lang", sourceLang.uppercase(Locale.ROOT))
            }
            put("preserve_formatting", true)
        }
        val responseStr = httpPostJson(
            url = url,
            jsonBody = payload.toString(),
            headers = mapOf("Authorization" to "DeepL-Auth-Key $apiKey")
        ) ?: return null
        val translations = JSONObject(responseStr).optJSONArray("translations") ?: return null
        if (translations.length() != texts.size) return null
        return List(translations.length()) { i ->
            translations.getJSONObject(i).optString("text", texts[i])
        }
    }

    private fun translateViaOpenAiLlm(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        apiKey: String,
        arabicStyle: String
    ): List<String>? {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val targetName = getLanguageDisplayName(targetLang, arabicUi = false)
        val arabicGuidance = if (targetLang.equals("ar", ignoreCase = true)) {
            if (arabicStyle == "simple") {
                " Use natural, easy-to-read everyday Arabic suitable for subtitles."
            } else {
                " Use natural, cinematic Modern Standard Arabic (فصحى سلسة وطبيعية للأفلام)."
            }
        } else ""

        val systemPrompt = "You are a professional movie subtitle translator. Translate the JSON array of subtitle lines into $targetName.$arabicGuidance " +
            "Preserve dialogue context across lines, preserve line breaks (\\n), keep placeholders like [[T0]] untouched, " +
            "and return ONLY a JSON object {\"lines\": [...]} with the EXACT same number of items."

        val userPayload = JSONObject().apply {
            put("source_lang", sourceLang)
            put("lines", JSONArray(texts))
        }.toString()

        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("temperature", 0.2)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPayload))
            })
        }

        val responseStr = httpPostJson(
            url = url,
            jsonBody = body.toString(),
            headers = mapOf("Authorization" to "Bearer $apiKey")
        ) ?: return null

        val content = JSONObject(responseStr)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?.trim()
            ?: return null

        val parsed = JSONObject(content).optJSONArray("lines") ?: return null
        if (parsed.length() != texts.size) return null
        return List(parsed.length()) { i -> parsed.optString(i, texts[i]) }
    }

    /**
     * Built-in zero-config fallback using Google Translate public GTX endpoint.
     * Batches lines using a unique numeric separator to keep context and minimize requests.
     */
    private fun translateViaFreeGoogle(
        texts: List<String>,
        sourceLang: String,
        targetLang: String
    ): List<String>? {
        val separator = "\n@@@VEXO_SEP@@@\n"
        // Protect internal line breaks inside each cue so they don't collide with batch splitting
        val joined = texts.joinToString(separator) { it.replace("\n", " @@BR@@ ") }
        val sl = if (sourceLang.isBlank()) "auto" else sourceLang
        val encoded = URLEncoder.encode(joined, "UTF-8")
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$targetLang&dt=t&q=$encoded")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15)")
        }
        if (conn.responseCode !in 200..299) return null
        val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONArray(resp)
        val sentences = root.optJSONArray(0) ?: return null
        val fullTranslated = StringBuilder()
        for (i in 0 until sentences.length()) {
            val part = sentences.optJSONArray(i)?.optString(0, "") ?: ""
            fullTranslated.append(part)
        }

        val parts = fullTranslated.toString()
            .split(Regex("\\s*@@@\\s*VEXO_SEP\\s*@@@\\s*"))
            .map {
                it.replace(Regex("\\s*@@\\s*BR\\s*@@\\s*"), "\n").trim()
            }

        if (parts.size == texts.size) {
            return parts
        }

        // Fallback to per-cue translation if separator count drifted
        return texts.map { single ->
            translateSingleFreeGoogle(single, sl, targetLang) ?: single
        }
    }

    private fun translateSingleFreeGoogle(text: String, sl: String, tl: String): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15)")
            }
            if (conn.responseCode !in 200..299) return null
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val sentences = JSONArray(resp).optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                sb.append(sentences.optJSONArray(i)?.optString(0, "") ?: "")
            }
            sb.toString().trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun httpPostJson(
        url: URL,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("User-Agent", "VexoPlayer/90.20")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { os ->
            os.write(jsonBody.toByteArray(Charsets.UTF_8))
        }
        if (conn.responseCode !in 200..299) {
            Log.w(TAG, "HTTP ${conn.responseCode} from ${url.host}")
            return null
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
