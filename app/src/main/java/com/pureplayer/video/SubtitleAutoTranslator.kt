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
 * Professional Subtitle Translator for V Player (v90.21).
 *
 * Key guarantees:
 * - Translation NEVER alters cue timing: every translated cue preserves exact original [startMs, endMs].
 * - Strict half-open active cue resolution [startMs, endMs) so cues always clear on time.
 * - Context-aware batching with neighboring cue context + character-name glossary consistency.
 * - Protects inline HTML/ASS tags, sound-effect brackets ([music]), music notes (♪), and timecodes.
 * - Auto-detects source language and skips translation when it already matches the target language.
 * - Proper RTL punctuation & alignment for Arabic and Hebrew.
 * - Readable line-length wrapping (max ~42 chars/line, max 2 lines).
 * - Per-cue persistent JSON cache keyed by subtitle hash + target language + provider.
 * - Optional bilingual mode (translated + original).
 * - Automatic graceful fallback from paid APIs (Google Cloud / DeepL / OpenAI LLM) to built-in translator.
 */
object SubtitleAutoTranslator {

    private const val TAG = "SubtitleAutoTranslator"
    const val BATCH_SIZE = 16
    const val CONTEXT_OVERLAP_CUES = 2
    const val MAX_LINE_CHARS = 42

    data class TranslationResult(
        val translatedFile: File,
        val cues: List<SubtitleCue>,
        val originalCues: List<SubtitleCue>,
        val translatedTexts: List<String>,
        val sourceLang: String,
        val targetLang: String,
        val providerUsed: String,
        val fromCache: Boolean,
        val skippedSameLanguage: Boolean,
        val displayLabel: String
    )

    data class ProtectedText(
        val cleanText: String,
        val tokens: Map<String, String>
    )

    // Protects HTML tags, ASS override tags, sound-effect brackets [music], music symbols, and timecodes
    private val PROTECTED_TOKEN_REGEX = Regex(
        "(<[^>]{1,40}>|\\{[^}]{1,40}\\}|\\[[^\\]]{1,40}\\]|[♪♫]+|\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b)"
    )

    /**
     * Strict cue lookup from playback position.
     * Uses half-open interval [startMs, endMs) so a cue ALWAYS clears the moment its endTime passes.
     */
    fun resolveActiveCueAt(
        cues: List<SubtitleCue>,
        playbackTimeMs: Long,
        syncOffsetMs: Long = 0L
    ): SubtitleCue? {
        if (cues.isEmpty()) return null
        val adjustedTime = playbackTimeMs - syncOffsetMs
        if (adjustedTime < 0L) return null
        // Binary search on sorted cues for O(log N) exact lookup
        var lo = 0
        var hi = cues.lastIndex
        var candidateIdx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= adjustedTime) {
                candidateIdx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (candidateIdx == -1) return null
        val cue = cues[candidateIdx]
        if (adjustedTime >= cue.startMs && adjustedTime < cue.endMs) {
            return cue
        }
        // Check immediate predecessor in case of slightly overlapping cues
        if (candidateIdx > 0) {
            val prev = cues[candidateIdx - 1]
            if (adjustedTime >= prev.startMs && adjustedTime < prev.endMs) {
                return prev
            }
        }
        return null
    }

    /**
     * Combines translated texts back onto the EXACT original cue timing (1-to-1 by index).
     * Never alters startMs or endMs; falls back to original text if a cue translation is blank/missing.
     */
    fun mergeTranslatedTextsPreservingTiming(
        originalCues: List<SubtitleCue>,
        translatedTexts: List<String>,
        bilingual: Boolean = false
    ): List<SubtitleCue> {
        return originalCues.mapIndexed { idx, orig ->
            val tr = translatedTexts.getOrNull(idx)?.trim().orEmpty()
            val finalText = when {
                tr.isEmpty() -> orig.text
                bilingual && !tr.equals(orig.text.trim(), ignoreCase = true) -> "$tr\n${orig.text.trim()}"
                else -> tr
            }
            SubtitleCue(
                startMs = orig.startMs,
                endMs = orig.endMs,
                text = finalText
            )
        }
    }

    /**
     * Auto-detects the dominant language of a subtitle cue list so we can skip unnecessary translation.
     */
    fun detectSourceLanguage(cues: List<SubtitleCue>): String {
        val sample = cues.take(40).joinToString(" ") { it.text }
        if (sample.isBlank()) return "auto"

        var arabic = 0
        var hebrew = 0
        var cyrillic = 0
        var cjk = 0
        var hangul = 0
        var latin = 0
        for (ch in sample) {
            when (ch) {
                in '\u0600'..'\u06FF', in '\u0750'..'\u077F' -> arabic++
                in '\u0590'..'\u05FF' -> hebrew++
                in '\u0400'..'\u04FF' -> cyrillic++
                in '\uAC00'..'\uD7AF' -> hangul++
                in '\u3040'..'\u30FF', in '\u4E00'..'\u9FFF' -> cjk++
                in 'A'..'Z', in 'a'..'z' -> latin++
            }
        }
        val total = (arabic + hebrew + cyrillic + cjk + hangul + latin).coerceAtLeast(1)
        if (arabic * 100 / total >= 30) return "ar"
        if (hebrew * 100 / total >= 30) return "he"
        if (cyrillic * 100 / total >= 30) return "ru"
        if (hangul * 100 / total >= 25) return "ko"
        if (cjk * 100 / total >= 25) return "zh"

        val lower = " " + sample.lowercase(Locale.ROOT) + " "
        val enHits = listOf(" the ", " and ", " you ", " that ", " what ", " is ", " are ", " have ").count { lower.contains(it) }
        val esHits = listOf(" que ", " los ", " las ", " por ", " para ", " una ", " pero ", " está ").count { lower.contains(it) }
        val frHits = listOf(" les ", " des ", " est ", " une ", " dans ", " pour ", " pas ", " vous ").count { lower.contains(it) }
        val deHits = listOf(" der ", " die ", " das ", " und ", " nicht ", " ich ", " ein ", " ist ").count { lower.contains(it) }
        val trHits = listOf(" bir ", " ve ", " bu ", " için ", " ama ", " değil ", " çok ").count { lower.contains(it) }

        val scores = listOf("en" to enHits, "es" to esHits, "fr" to frHits, "de" to deHits, "tr" to trHits)
        val best = scores.maxByOrNull { it.second }
        return if (best != null && best.second >= 2) best.first else "auto"
    }

    /**
     * Extracts recurring capitalized proper nouns (character names / places) across cues
     * so LLM translation keeps names consistent throughout the file.
     */
    fun extractProperNounHints(cues: List<SubtitleCue>, maxNames: Int = 12): List<String> {
        val counts = HashMap<String, Int>()
        val nameRegex = Regex("(?<=\\s)[A-Z][a-z]{2,15}\\b")
        val stopWords = setOf("The", "And", "But", "You", "She", "His", "Her", "They", "This", "That", "What", "When", "Where", "Why", "How", "Yes", "Not", "Just", "Don")
        for (cue in cues.take(250)) {
            for (m in nameRegex.findAll(cue.text)) {
                val word = m.value
                if (word !in stopWords) {
                    counts[word] = (counts[word] ?: 0) + 1
                }
            }
        }
        return counts.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(maxNames)
            .map { it.key }
    }

    /**
     * Protects inline formatting tags, sound-effect brackets, music symbols, and timecodes.
     */
    fun protectInlineTags(text: String): ProtectedText {
        val tokens = LinkedHashMap<String, String>()
        var idx = 0
        val replaced = PROTECTED_TOKEN_REGEX.replace(text) { match ->
            val key = "[[T$idx]]"
            tokens[key] = match.value
            idx++
            key
        }
        return ProtectedText(replaced, tokens)
    }

    /**
     * Restores previously protected tokens back into translated text.
     */
    fun restoreInlineTags(text: String, tokens: Map<String, String>): String {
        if (tokens.isEmpty()) return text
        var result = text
        for ((key, originalTag) in tokens) {
            val inner = Regex.escape(key.removeSurrounding("[[", "]]"))
            val flexible = Regex("\\[\\s*\\[\\s*$inner\\s*]\\s*]")
            result = result.replace(key, originalTag).replace(flexible, originalTag)
        }
        return result
    }

    /**
     * Wraps long single-line subtitle text into at most 2 balanced readable lines (~42 chars/line)
     * without breaking dialogue dashes or existing explicit multi-line splits.
     */
    fun wrapSubtitleTextForReadability(text: String, maxCharsPerLine: Int = MAX_LINE_CHARS): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxCharsPerLine || trimmed.contains("\n")) return trimmed
        // Don't break inside protected tags
        val words = trimmed.split(Regex("\\s+"))
        if (words.size < 3) return trimmed
        val targetMid = trimmed.length / 2
        var bestSplitIdx = -1
        var bestDiff = Int.MAX_VALUE
        var runningLen = 0
        for (i in 0 until words.lastIndex) {
            runningLen += words[i].length + (if (i > 0) 1 else 0)
            val diff = kotlin.math.abs(runningLen - targetMid)
            if (diff < bestDiff) {
                bestDiff = diff
                bestSplitIdx = i
            }
        }
        if (bestSplitIdx == -1) return trimmed
        val line1 = words.subList(0, bestSplitIdx + 1).joinToString(" ")
        val line2 = words.subList(bestSplitIdx + 1, words.size).joinToString(" ")
        return "$line1\n$line2"
    }

    /**
     * Normalizes Arabic and Hebrew subtitle text for clean RTL rendering and punctuation placement.
     */
    fun normalizeArabicSubtitleText(text: String): String = normalizeRtlSubtitleText(text, "ar")

    fun normalizeRtlSubtitleText(text: String, targetLang: String): String {
        val isArabic = text.any { it in '\u0600'..'\u06FF' } || targetLang.equals("ar", ignoreCase = true)
        val isHebrew = text.any { it in '\u0590'..'\u05FF' } || targetLang.equals("he", ignoreCase = true)
        if (!isArabic && !isHebrew) return text.trim()

        return text.split("\n").joinToString("\n") { rawLine ->
            var line = rawLine.trim()
            if (line.isEmpty()) return@joinToString ""

            if (isArabic) {
                line = line
                    .replace(Regex("([\\u0600-\\u06FF])\\s*\\?"), "$1؟")
                    .replace(Regex("\\?\\s*([\\u0600-\\u06FF])"), "؟ $1")
                    .replace(Regex("([\\u0600-\\u06FF])\\s*,"), "$1،")
                    .replace(Regex("([\\u0600-\\u06FF])\\s*;"), "$1؛")
                    .replace(Regex("\\s+([؟،؛!.])"), "$1")
                    .replace(Regex("\\s{2,}"), " ")
                    .trim()
            } else {
                line = line
                    .replace(Regex("\\s+([?!.,;:])"), "$1")
                    .replace(Regex("\\s{2,}"), " ")
                    .trim()
            }

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
                "he" -> "العبرية"
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
                "he" -> "Hebrew"
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
     * Translates subtitle cues while strictly preserving original [startMs, endMs] per cue.
     * Supports per-cue persistent JSON caching, priority batch around [priorityPlaybackMs],
     * source-language auto-detection, bilingual output, and progressive partial updates.
     */
    suspend fun translateSubtitleCues(
        context: Context,
        sourceBytes: ByteArray,
        sourceCues: List<SubtitleCue>,
        sourceLang: String,
        targetLang: String,
        forceRetranslate: Boolean = false,
        bilingual: Boolean = PlaybackStore.isBilingualSubtitleEnabled(context),
        priorityPlaybackMs: Long = 0L,
        onBatchReady: ((partialCues: List<SubtitleCue>) -> Unit)? = null,
        onProgress: ((completed: Int, total: Int, provider: String) -> Unit)? = null
    ): TranslationResult? = withContext(Dispatchers.IO) {
        if (sourceCues.isEmpty()) return@withContext null

        val detectedLang = if (sourceLang.isBlank() || sourceLang == "auto") {
            detectSourceLanguage(sourceCues)
        } else {
            sourceLang.lowercase(Locale.ROOT)
        }
        val normalizedTarget = targetLang.lowercase(Locale.ROOT)
        val isArabicUi = Locale.getDefault().language.equals("ar", ignoreCase = true) ||
            PlaybackStore.getAppLanguageTag(context).startsWith("ar", ignoreCase = true)

        // Auto-skip if source language already matches target language
        if (detectedLang == normalizedTarget && detectedLang != "auto") {
            val cacheDir = File(context.filesDir, "subtitles_cache/translated").apply { mkdirs() }
            val sameFile = File(cacheDir, "orig_${normalizedTarget}_${sourceBytes.contentHashCode()}.srt")
            sameFile.writeText(serializeToSrt(sourceCues), Charsets.UTF_8)
            onProgress?.invoke(sourceCues.size, sourceCues.size, "same_lang")
            return@withContext TranslationResult(
                translatedFile = sameFile,
                cues = sourceCues,
                originalCues = sourceCues,
                translatedTexts = sourceCues.map { it.text },
                sourceLang = detectedLang,
                targetLang = normalizedTarget,
                providerUsed = "same_lang",
                fromCache = true,
                skippedSameLanguage = true,
                displayLabel = getLanguageDisplayName(normalizedTarget, isArabicUi)
            )
        }

        val configuredProvider = PlaybackStore.getAutoTranslateProvider(context)
        val apiKey = PlaybackStore.getAutoTranslateApiKey(context)
        val arabicStyle = PlaybackStore.getArabicTranslationStyle(context)
        val effectiveProvider = resolveEffectiveProvider(configuredProvider, apiKey)
        val cacheKey = computeCacheKey(sourceBytes, detectedLang, normalizedTarget, effectiveProvider, arabicStyle)

        val cacheDir = File(context.filesDir, "subtitles_cache/translated").apply { mkdirs() }
        val cueCacheJsonFile = File(cacheDir, "cues_${normalizedTarget}_${cacheKey}.json")
        val srtCacheFile = File(cacheDir, "tr_${normalizedTarget}_${cacheKey}.srt")
        val label = buildTranslatedLabel(normalizedTarget, if (detectedLang == "auto") "en" else detectedLang, isArabicUi)

        // Load per-cue persistent cache if available
        val translatedMap = HashMap<Int, String>(sourceCues.size)
        if (!forceRetranslate && cueCacheJsonFile.exists()) {
            runCatching {
                val obj = JSONObject(cueCacheJsonFile.readText(Charsets.UTF_8))
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val idx = k.toIntOrNull()
                    if (idx != null && idx in sourceCues.indices) {
                        val v = obj.optString(k, "")
                        if (v.isNotBlank()) translatedMap[idx] = v
                    }
                }
            }
        }

        if (translatedMap.size == sourceCues.size) {
            val texts = List(sourceCues.size) { i -> translatedMap[i] ?: sourceCues[i].text }
            val merged = mergeTranslatedTextsPreservingTiming(sourceCues, texts, bilingual)
            onProgress?.invoke(sourceCues.size, sourceCues.size, effectiveProvider)
            return@withContext TranslationResult(
                translatedFile = srtCacheFile.takeIf { it.exists() } ?: srtCacheFile.apply { writeText(serializeToSrt(merged), Charsets.UTF_8) },
                cues = merged,
                originalCues = sourceCues,
                translatedTexts = texts,
                sourceLang = detectedLang,
                targetLang = normalizedTarget,
                providerUsed = effectiveProvider,
                fromCache = true,
                skippedSameLanguage = false,
                displayLabel = label
            )
        }

        val total = sourceCues.size
        val properNouns = extractProperNounHints(sourceCues)
        var actualProviderUsed = effectiveProvider

        // Order batches so the batch covering the user's current playback position translates FIRST (prefetch ahead!)
        val batchStarts = (0 until total step BATCH_SIZE).toMutableList()
        if (priorityPlaybackMs > 0L) {
            val curCueIdx = sourceCues.indexOfFirst { it.endMs >= priorityPlaybackMs }.coerceAtLeast(0)
            val priorityBatchStart = (curCueIdx / BATCH_SIZE) * BATCH_SIZE
            val pPos = batchStarts.indexOf(priorityBatchStart)
            if (pPos > 0) {
                val reordered = batchStarts.subList(pPos, batchStarts.size) + batchStarts.subList(0, pPos)
                batchStarts.clear()
                batchStarts.addAll(reordered)
            }
        }

        onProgress?.invoke(translatedMap.size, total, actualProviderUsed)
        var anyBatchTranslated = translatedMap.isNotEmpty()

        for (startIdx in batchStarts) {
            coroutineContext.ensureActive()
            val endIdx = (startIdx + BATCH_SIZE).coerceAtMost(total)

            // Skip batch if every cue in it is already cached
            if ((startIdx until endIdx).all { translatedMap.containsKey(it) }) continue

            val batchCues = sourceCues.subList(startIdx, endIdx)
            val contextBefore = if (startIdx > 0) {
                sourceCues.subList((startIdx - CONTEXT_OVERLAP_CUES).coerceAtLeast(0), startIdx).map { it.text }
            } else emptyList()

            val protectedBatch = batchCues.map { protectInlineTags(it.text) }
            val textsToTranslate = protectedBatch.map { it.cleanText }

            var translatedTexts = translateBatchWithProvider(
                texts = textsToTranslate,
                contextBefore = contextBefore,
                properNouns = properNouns,
                sourceLang = detectedLang,
                targetLang = normalizedTarget,
                provider = actualProviderUsed,
                apiKey = apiKey,
                arabicStyle = arabicStyle
            )

            if (translatedTexts == null && actualProviderUsed != "free_google") {
                Log.w(TAG, "Provider $actualProviderUsed failed, falling back to free_google")
                actualProviderUsed = "free_google"
                translatedTexts = translateBatchWithProvider(
                    texts = textsToTranslate,
                    contextBefore = contextBefore,
                    properNouns = properNouns,
                    sourceLang = detectedLang,
                    targetLang = normalizedTarget,
                    provider = "free_google",
                    apiKey = "",
                    arabicStyle = arabicStyle
                )
            }

            if (translatedTexts == null || translatedTexts.size != batchCues.size) {
                if (!anyBatchTranslated) return@withContext null
                continue
            }

            anyBatchTranslated = true
            for (i in batchCues.indices) {
                val cueIndex = startIdx + i
                val restored = restoreInlineTags(translatedTexts[i], protectedBatch[i].tokens)
                val wrapped = wrapSubtitleTextForReadability(restored, MAX_LINE_CHARS)
                val formatted = if (normalizedTarget == "ar" || normalizedTarget == "he") {
                    normalizeRtlSubtitleText(wrapped, normalizedTarget)
                } else {
                    wrapped.trim()
                }
                translatedMap[cueIndex] = formatted.ifBlank { batchCues[i].text }
            }

            // Persist per-cue cache incrementally so progress is never lost on interruption
            runCatching {
                val obj = JSONObject()
                translatedMap.forEach { (k, v) -> obj.put(k.toString(), v) }
                cueCacheJsonFile.writeText(obj.toString(), Charsets.UTF_8)
            }

            val snapshotTexts = List(total) { i -> translatedMap[i] ?: sourceCues[i].text }
            val snapshotCues = mergeTranslatedTextsPreservingTiming(sourceCues, snapshotTexts, bilingual)
            onBatchReady?.invoke(snapshotCues)
            onProgress?.invoke(translatedMap.size, total, actualProviderUsed)
        }

        if (!anyBatchTranslated) return@withContext null

        val finalTexts = List(total) { i -> translatedMap[i] ?: sourceCues[i].text }
        val finalCues = mergeTranslatedTextsPreservingTiming(sourceCues, finalTexts, bilingual)
        srtCacheFile.writeText(serializeToSrt(finalCues), Charsets.UTF_8)

        TranslationResult(
            translatedFile = srtCacheFile,
            cues = finalCues,
            originalCues = sourceCues,
            translatedTexts = finalTexts,
            sourceLang = detectedLang,
            targetLang = normalizedTarget,
            providerUsed = actualProviderUsed,
            fromCache = false,
            skippedSameLanguage = false,
            displayLabel = label
        )
    }

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
        contextBefore: List<String>,
        properNouns: List<String>,
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
                "openai_llm" -> translateViaOpenAiLlm(texts, contextBefore, properNouns, sourceLang, targetLang, apiKey, arabicStyle)
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
        val translations = JSONObject(responseStr).optJSONObject("data")?.optJSONArray("translations") ?: return null
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
        contextBefore: List<String>,
        properNouns: List<String>,
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
        val glossaryNote = if (properNouns.isNotEmpty()) {
            " Keep these recurring character/place names consistent: ${properNouns.joinToString(", ")}."
        } else ""

        val systemPrompt = "You are a professional movie subtitle translator. Translate ONLY the array in `lines` into $targetName.$arabicGuidance$glossaryNote " +
            "Use `context_before` strictly for dialogue coherence and cross-cue sentences, do NOT include `context_before` in your output. " +
            "Preserve line breaks (\\n), speaker dashes (- ), numbers, and placeholders like [[T0]] untouched. Keep each subtitle concise and natural. " +
            "Return ONLY a JSON object {\"lines\": [...]} with the EXACT same length as input `lines`."

        val userPayload = JSONObject().apply {
            put("source_lang", sourceLang)
            if (contextBefore.isNotEmpty()) put("context_before", JSONArray(contextBefore))
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

    private fun translateViaFreeGoogle(
        texts: List<String>,
        sourceLang: String,
        targetLang: String
    ): List<String>? {
        // Use numeric-safe sentinel tags so Google Translate never merges/reorders cues
        val numbered = texts.mapIndexed { idx, line ->
            "<<<$idx>>> ${line.replace("\n", " @@BR@@ ")}"
        }.joinToString("\n")

        val sl = if (sourceLang.isBlank()) "auto" else sourceLang
        val encoded = URLEncoder.encode(numbered, "UTF-8")
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$targetLang&dt=t&q=$encoded")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15)")
        }
        if (conn.responseCode !in 200..299) return null
        val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val sentences = JSONArray(resp).optJSONArray(0) ?: return null
        val fullTranslated = StringBuilder()
        for (i in 0 until sentences.length()) {
            fullTranslated.append(sentences.optJSONArray(i)?.optString(0, "") ?: "")
        }

        // Parse back strictly by cue index <<<0>>>, <<<1>>>, ... so cue indices NEVER drift
        val markerRegex = Regex("<<<\\s*(\\d+)\\s*>>>")
        val matches = markerRegex.findAll(fullTranslated.toString()).toList()
        if (matches.size == texts.size) {
            val out = MutableList(texts.size) { texts[it] }
            for (mIdx in matches.indices) {
                val cueIdx = matches[mIdx].groupValues[1].toIntOrNull() ?: continue
                if (cueIdx !in out.indices) continue
                val start = matches[mIdx].range.last + 1
                val end = if (mIdx + 1 < matches.size) matches[mIdx + 1].range.first else fullTranslated.length
                val chunk = fullTranslated.substring(start, end)
                    .replace(Regex("\\s*@@\\s*BR\\s*@@\\s*"), "\n")
                    .trim()
                if (chunk.isNotEmpty()) out[cueIdx] = chunk
            }
            return out
        }

        // Safe fallback: per-cue translation guarantees 1-to-1 alignment
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
            setRequestProperty("User-Agent", "VPlayer/90.21")
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
