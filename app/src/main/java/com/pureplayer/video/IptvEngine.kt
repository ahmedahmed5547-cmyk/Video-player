package com.pureplayer.video

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.LruCache
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class IptvSourceType {
    XTREAM,
    M3U_URL,
    M3U_FILE
}

enum class IptvContentType {
    LIVE_TV,
    MOVIES,
    SERIES,
    RADIO
}

data class IptvSource(
    val id: String,
    val name: String,
    val type: IptvSourceType,
    val serverOrM3uUrl: String,
    val username: String = "",
    val encryptedPassword: String = "",
    val lastUpdatedMs: Long = 0L,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val radioCount: Int = 0,
    val expDateSec: Long = 0L,
    val accountStatus: String = "Active"
) {
    fun totalItemsCount(): Int = liveCount + movieCount + seriesCount + radioCount
}

data class IptvCategory(
    val id: String,
    val name: String,
    val contentType: IptvContentType,
    val itemCount: Int
)

data class IptvStreamItem(
    val id: String,
    val sourceId: String,
    val contentType: IptvContentType,
    val categoryId: String,
    val categoryName: String,
    val title: String,
    val streamUrl: String,
    val logoUrl: String = "",
    val rating: String = "",
    val plot: String = "",
    val releaseDate: String = "",
    val durationLabel: String = "",
    val seriesId: String = "",
    val seriesName: String = "",
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val containerExtension: String = "",
    val httpUserAgent: String = "",
    val httpReferrer: String = ""
) {
    val normalizedTitle: String = IptvSearchNormalizer.normalize(title)
    val normalizedCategory: String = IptvSearchNormalizer.normalize(categoryName)
}

data class IptvEpisodeItem(
    val id: String,
    val title: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val streamUrl: String,
    val logoUrl: String = "",
    val durationLabel: String = "",
    val plot: String = "",
    val rating: String = "",
    val httpUserAgent: String = "",
    val httpReferrer: String = ""
)

data class IptvSeasonGroup(
    val seasonNumber: Int,
    val name: String,
    val episodes: List<IptvEpisodeItem>
)

data class IptvSeriesInfoResult(
    val seriesTitle: String,
    val coverUrl: String,
    val plot: String,
    val rating: String,
    val releaseDate: String,
    val seasons: List<IptvSeasonGroup>
)

sealed class IptvSyncResult {
    data class Success(val source: IptvSource, val totalLoaded: Int) : IptvSyncResult()
    data class Error(val userMessage: String) : IptvSyncResult()
}

/**
 * Arabic & English search text normalizer:
 * - Lowercases English characters
 * - Strips Arabic diacritics/Tashkeel (\u064B-\u065F, \u0670, Tatweel \u0640)
 * - Unifies Alef variants (أ, إ, آ, ٱ -> ا), Teh Marbuta (ة -> ه), Alef Maksura (ى -> ي), Hamza forms
 */
object IptvSearchNormalizer {
    private val diacriticsRegex = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val multiSpaceRegex = Regex("\\s+")

    fun normalize(input: String): String {
        if (input.isBlank()) return ""
        val sb = StringBuilder(input.length)
        for (ch in input.lowercase(Locale.ROOT)) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
            .replace(diacriticsRegex, "")
            .replace(multiSpaceRegex, " ")
            .trim()
    }

    fun matches(targetTitle: String, targetCategory: String, normalizedQuery: String): Boolean {
        if (normalizedQuery.isEmpty()) return true
        val normTitle = normalize(targetTitle)
        if (normTitle.contains(normalizedQuery)) return true
        val normCat = normalize(targetCategory)
        return normCat.contains(normalizedQuery)
    }
}

/**
 * Hardware-backed AndroidKeyStore AES-GCM encryption for IPTV Xtream passwords.
 * Never logs credentials or stores plain-text passwords on disk.
 */
object IptvCredentialVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pure_player_iptv_cred_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun getOrCreateKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        } catch (_: Exception) {
            null
        }
    }

    fun encryptSecret(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val key = getOrCreateKey() ?: return Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "GCM:$ivB64:$encB64"
        } catch (_: Exception) {
            Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    fun decryptSecret(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        return try {
            if (!cipherText.startsWith("GCM:")) {
                return String(Base64.decode(cipherText, Base64.NO_WRAP), Charsets.UTF_8)
            }
            val parts = cipherText.split(":")
            if (parts.size != 3) return ""
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
            val key = getOrCreateKey() ?: return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}

/**
 * Memory + Disk cached asynchronous image loader for IPTV channel logos and movie/series posters.
 */
object IptvImageLoader {
    private val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val memCache = object : LruCache<String, Bitmap>((maxMem / 10).coerceAtLeast(2048)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun loadLogoOrPoster(
        context: Context,
        imageUrl: String,
        imageView: ImageView,
        fallbackResId: Int,
        accentColor: Int
    ) {
        val cleanUrl = imageUrl.trim()
        imageView.tag = cleanUrl
        if (cleanUrl.isEmpty() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) {
            imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageView.setPadding(20, 20, 20, 20)
            imageView.imageTintList = ColorStateList.valueOf(accentColor)
            imageView.setImageResource(fallbackResId)
            return
        }

        val cached = memCache.get(cleanUrl)
        if (cached != null && !cached.isRecycled) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.setPadding(0, 0, 0, 0)
            imageView.imageTintList = null
            imageView.setImageBitmap(cached)
            return
        }

        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.setPadding(20, 20, 20, 20)
        imageView.imageTintList = ColorStateList.valueOf(accentColor)
        imageView.setImageResource(fallbackResId)

        scope.launch {
            val bmp = fetchOrReadDiskCache(context, cleanUrl)
            if (bmp != null) {
                memCache.put(cleanUrl, bmp)
                withContext(Dispatchers.Main) {
                    if (imageView.tag == cleanUrl) {
                        imageView.alpha = 0f
                        imageView.imageTintList = null
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.setPadding(0, 0, 0, 0)
                        imageView.setImageBitmap(bmp)
                        imageView.animate()
                            .alpha(1f)
                            .setDuration(180L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
            }
        }
    }

    private fun fetchOrReadDiskCache(context: Context, urlStr: String): Bitmap? {
        return try {
            val dir = File(context.cacheDir, "iptv_logos").apply { if (!exists()) mkdirs() }
            val hash = MessageDigest.getInstance("MD5").digest(urlStr.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val cacheFile = File(dir, "img_$hash.bin")
            if (cacheFile.exists() && cacheFile.length() > 64L) {
                val decoded = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (decoded != null) return decoded
            }

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IPTVSmartersPro/3.1 (Android)")
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 64) return null

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sampleSize = 1
            while (opts.outWidth / sampleSize > 360 || opts.outHeight / sampleSize > 360) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            if (bmp != null) {
                try {
                    FileOutputStream(cacheFile).use { it.write(bytes) }
                } catch (_: Exception) {}
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Background Streaming M3U / M3U8 Parser.
 * Supports #EXTINF, tvg-name, tvg-logo, group-title, #EXTGRP, #EXTVLCOPT:http-user-agent, #EXTVLCOPT:http-referrer.
 * Auto-classifies entries into LIVE_TV, MOVIES, SERIES, and RADIO, and handles tens of thousands of entries smoothly.
 */
object M3uPlaylistParser {
    private val groupTitleRegex = Regex("""group-title\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val tvgNameRegex = Regex("""tvg-name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val tvgLogoRegex = Regex("""tvg-logo\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val seriesEpisodeRegex = Regex("""(?i)^(.*?)[.\s_-]*S(\d{1,2})[.\s_-]*E(\d{1,3})\b""")
    private val arabicSeriesEpisodeRegex = Regex("""(?i)^(.*?)\s*(?:موسم|الموسم)\s*(\d{1,2}).*?(?:حلقة|الحلقة)\s*(\d{1,3})""")

    fun parseFromReader(
        sourceId: String,
        reader: BufferedReader,
        categoryOverrides: Map<String, IptvContentType> = emptyMap()
    ): List<IptvStreamItem> {
        val results = ArrayList<IptvStreamItem>(2048)
        val seenKeys = HashSet<String>(2048)

        var currentExtInf: String? = null
        var currentGroupOverride: String? = null
        var currentUserAgent = ""
        var currentReferrer = ""

        reader.useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                when {
                    line.startsWith("#EXTINF:", ignoreCase = true) -> {
                        currentExtInf = line
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                        currentGroupOverride = line.substringAfter(":").trim()
                    }
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val opt = line.substringAfter(":").trim()
                        if (opt.startsWith("http-user-agent=", ignoreCase = true)) {
                            currentUserAgent = opt.substringAfter("=").trim()
                        } else if (opt.startsWith("http-referrer=", ignoreCase = true) ||
                            opt.startsWith("http-referer=", ignoreCase = true)
                        ) {
                            currentReferrer = opt.substringAfter("=").trim()
                        }
                    }
                    line.startsWith("#") -> {
                        // Other comment or directive
                    }
                    else -> {
                        // Stream URL line
                        val streamUrl = line
                        val extInf = currentExtInf
                        if (streamUrl.startsWith("http://", ignoreCase = true) ||
                            streamUrl.startsWith("https://", ignoreCase = true) ||
                            streamUrl.startsWith("rtmp://", ignoreCase = true) ||
                            streamUrl.startsWith("rtsp://", ignoreCase = true) ||
                            streamUrl.startsWith("mms://", ignoreCase = true)
                        ) {
                            val item = buildStreamItem(
                                sourceId = sourceId,
                                extInfLine = extInf,
                                extGrp = currentGroupOverride,
                                streamUrl = streamUrl,
                                userAgent = currentUserAgent,
                                referrer = currentReferrer,
                                categoryOverrides = categoryOverrides
                            )
                            val dedupKey = "${item.contentType.name}_${item.streamUrl}"
                            if (seenKeys.add(dedupKey)) {
                                results.add(item)
                            }
                        }
                        currentExtInf = null
                        currentGroupOverride = null
                        currentUserAgent = ""
                        currentReferrer = ""
                    }
                }
            }
        }
        return results
    }

    private fun buildStreamItem(
        sourceId: String,
        extInfLine: String?,
        extGrp: String?,
        streamUrl: String,
        userAgent: String,
        referrer: String,
        categoryOverrides: Map<String, IptvContentType>
    ): IptvStreamItem {
        var groupTitle = ""
        var tvgName = ""
        var tvgLogo = ""
        var displayTitle = ""

        if (!extInfLine.isNullOrEmpty()) {
            groupTitle = groupTitleRegex.find(extInfLine)?.groupValues?.getOrNull(1)?.trim() ?: ""
            tvgName = tvgNameRegex.find(extInfLine)?.groupValues?.getOrNull(1)?.trim() ?: ""
            tvgLogo = tvgLogoRegex.find(extInfLine)?.groupValues?.getOrNull(1)?.trim() ?: ""
            displayTitle = extInfLine.substringAfterLast(",").trim()
        }

        if (groupTitle.isEmpty() && !extGrp.isNullOrBlank()) {
            groupTitle = extGrp
        }
        if (displayTitle.isEmpty()) {
            displayTitle = if (tvgName.isNotEmpty()) tvgName else streamUrl.substringAfterLast("/").substringBefore("?")
        }
        if (groupTitle.isEmpty()) {
            groupTitle = "General / عام"
        }

        val classified = classifyEntry(groupTitle, displayTitle, streamUrl, categoryOverrides)
        val contentType = classified.first
        val seriesName = classified.second
        val seasonNum = classified.third.first
        val episodeNum = classified.third.second

        val itemId = "${sourceId}_${streamUrl.hashCode()}"
        val catId = "${contentType.name}_${groupTitle.lowercase(Locale.ROOT).hashCode()}"

        return IptvStreamItem(
            id = itemId,
            sourceId = sourceId,
            contentType = contentType,
            categoryId = catId,
            categoryName = groupTitle,
            title = displayTitle,
            streamUrl = streamUrl,
            logoUrl = tvgLogo,
            seriesId = if (contentType == IptvContentType.SERIES) "${sourceId}_series_${seriesName.lowercase(Locale.ROOT).hashCode()}" else "",
            seriesName = seriesName,
            seasonNumber = seasonNum,
            episodeNumber = episodeNum,
            httpUserAgent = userAgent,
            httpReferrer = referrer
        )
    }

    private fun classifyEntry(
        groupTitle: String,
        title: String,
        streamUrl: String,
        categoryOverrides: Map<String, IptvContentType>
    ): Triple<IptvContentType, String, Pair<Int, Int>> {
        val overrideType = categoryOverrides[groupTitle.lowercase(Locale.ROOT)]
        val lowerGroup = groupTitle.lowercase(Locale.ROOT)
        val lowerTitle = title.lowercase(Locale.ROOT)
        val lowerUrl = streamUrl.lowercase(Locale.ROOT).substringBefore("?")

        // Check if series pattern matches (e.g., S01E02 or الموسم 1 الحلقة 2)
        val seMatch = seriesEpisodeRegex.find(title) ?: arabicSeriesEpisodeRegex.find(title)
        if (overrideType == IptvContentType.SERIES ||
            seMatch != null ||
            lowerUrl.contains("/series/") ||
            lowerGroup.contains("series") ||
            lowerGroup.contains("مسلسلات") ||
            lowerGroup.contains("مسلسل")
        ) {
            val rawSeriesName = seMatch?.groupValues?.getOrNull(1)?.trim()?.trim('-', '.', '_')
                ?.takeIf { it.isNotEmpty() }
                ?: title.replace(Regex("(?i)\\s*(?:S\\d+.*|حلقة\\s*\\d+.*|الحلقة\\s*\\d+.*)"), "").trim().ifEmpty { title }
            val sNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val eNum = seMatch?.groupValues?.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            return Triple(IptvContentType.SERIES, rawSeriesName, Pair(sNum, eNum))
        }

        if (overrideType != null) {
            return Triple(overrideType, "", Pair(0, 0))
        }

        // Check Radio
        if (lowerGroup.contains("radio") ||
            lowerGroup.contains("راديو") ||
            lowerGroup.contains("إذاعة") ||
            lowerGroup.contains("اذاعة") ||
            lowerTitle.contains("radio ") ||
            lowerTitle.contains("راديو ") ||
            lowerUrl.endsWith(".aac") ||
            lowerUrl.endsWith(".mp3")
        ) {
            return Triple(IptvContentType.RADIO, "", Pair(0, 0))
        }

        // Check Movies / VOD
        val isMovieFile = lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".mkv") || lowerUrl.endsWith(".avi")
        if (lowerUrl.contains("/movie/") ||
            lowerUrl.contains("/vod/") ||
            lowerGroup.contains("movie") ||
            lowerGroup.contains("vod") ||
            lowerGroup.contains("film") ||
            lowerGroup.contains("أفلام") ||
            lowerGroup.contains("افلام") ||
            isMovieFile
        ) {
            return Triple(IptvContentType.MOVIES, "", Pair(0, 0))
        }

        return Triple(IptvContentType.LIVE_TV, "", Pair(0, 0))
    }
}

/**
 * Xtream Codes Standard API Client (`player_api.php`).
 * Supports:
 * - Authentication & account status verification (`player_api.php`)
 * - `get_live_categories` & `get_live_streams`
 * - `get_vod_categories` & `get_vod_streams`
 * - `get_series_categories` & `get_series`
 * - `get_series_info` (seasons & episodes on demand)
 */
object XtreamCodesClient {

    fun normalizeServerBaseUrl(rawUrl: String): String {
        var clean = rawUrl.trim()
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            clean = "http://$clean"
        }
        clean = clean.substringBefore("/player_api.php").substringBefore("/get.php").trimEnd('/')
        return clean
    }

    private fun httpGetString(urlStr: String, timeoutMs: Int = 15_000): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "IPTVSmartersPro/3.1 (Android; Mobile)")
            setRequestProperty("Accept", "application/json, */*")
        }
        val code = conn.responseCode
        if (code == 401 || code == 403) {
            throw IllegalStateException("AUTH_FAILED")
        }
        if (code !in 200..299) {
            throw IllegalStateException("HTTP_$code")
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun fetchXtreamSourceContent(
        sourceId: String,
        sourceName: String,
        rawServerUrl: String,
        username: String,
        passwordPlain: String
    ): Pair<IptvSource, List<IptvStreamItem>> {
        val baseUrl = normalizeServerBaseUrl(rawServerUrl)
        val uEnc = URLEncoder.encode(username.trim(), "UTF-8")
        val pEnc = URLEncoder.encode(passwordPlain.trim(), "UTF-8")
        val apiBase = "$baseUrl/player_api.php?username=$uEnc&password=$pEnc"

        // 1. Verify Authentication & Account Status
        val authRaw = httpGetString(apiBase, timeoutMs = 14_000)
        val authJson = JSONObject(authRaw)
        val userInfo = authJson.optJSONObject("user_info")
            ?: throw IllegalStateException("INVALID_RESPONSE")

        val authVal = userInfo.opt("auth")?.toString() ?: "0"
        if (authVal == "0" || authVal.equals("false", ignoreCase = true)) {
            throw IllegalStateException("AUTH_FAILED")
        }

        val statusStr = userInfo.optString("status", "Active")
        if (statusStr.equals("Expired", ignoreCase = true) ||
            statusStr.equals("Banned", ignoreCase = true) ||
            statusStr.equals("Disabled", ignoreCase = true)
        ) {
            throw IllegalStateException("ACCOUNT_${statusStr.uppercase(Locale.ROOT)}")
        }

        val expDateSec = userInfo.optString("exp_date", "0").toLongOrNull() ?: 0L
        val nowSec = System.currentTimeMillis() / 1000L
        if (expDateSec in 1 until nowSec) {
            throw IllegalStateException("ACCOUNT_EXPIRED")
        }

        val allItems = ArrayList<IptvStreamItem>(4096)
        val seenKeys = HashSet<String>(4096)

        // 2. Live Categories & Live Streams (including Radio detection)
        val liveCatMap = fetchCategoryMap("$apiBase&action=get_live_categories")
        try {
            val liveRaw = httpGetString("$apiBase&action=get_live_streams", timeoutMs = 22_000)
            val arr = parseJsonArrayFlexible(liveRaw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val streamId = obj.opt("stream_id")?.toString()?.takeIf { it.isNotEmpty() } ?: continue
                val name = obj.optString("name", "Channel $streamId").trim()
                val catId = obj.optString("category_id", "0")
                val catName = liveCatMap[catId] ?: "Live Channels"
                val logo = obj.optString("stream_icon", "")
                val streamType = obj.optString("stream_type", "live").lowercase(Locale.ROOT)
                val isRadio = streamType.contains("radio") ||
                    catName.contains("radio", ignoreCase = true) ||
                    catName.contains("راديو") ||
                    catName.contains("إذاعة")

                val contentType = if (isRadio) IptvContentType.RADIO else IptvContentType.LIVE_TV
                val streamUrl = "$baseUrl/live/${username.trim()}/${passwordPlain.trim()}/$streamId.ts"
                val uniqueKey = "${contentType.name}_$streamId"
                if (seenKeys.add(uniqueKey)) {
                    allItems.add(
                        IptvStreamItem(
                            id = "${sourceId}_$uniqueKey",
                            sourceId = sourceId,
                            contentType = contentType,
                            categoryId = "${contentType.name}_$catId",
                            categoryName = catName,
                            title = name,
                            streamUrl = streamUrl,
                            logoUrl = logo
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 3. VOD (Movies) Categories & Streams
        val vodCatMap = fetchCategoryMap("$apiBase&action=get_vod_categories")
        try {
            val vodRaw = httpGetString("$apiBase&action=get_vod_streams", timeoutMs = 22_000)
            val arr = parseJsonArrayFlexible(vodRaw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val streamId = obj.opt("stream_id")?.toString()?.takeIf { it.isNotEmpty() } ?: continue
                val name = obj.optString("name", "Movie $streamId").trim()
                val catId = obj.optString("category_id", "0")
                val catName = vodCatMap[catId] ?: "Movies"
                val logo = obj.optString("stream_icon", "")
                val rating = obj.optString("rating", "")
                val ext = obj.optString("container_extension", "mp4").ifBlank { "mp4" }
                val streamUrl = "$baseUrl/movie/${username.trim()}/${passwordPlain.trim()}/$streamId.$ext"
                val uniqueKey = "MOVIES_$streamId"
                if (seenKeys.add(uniqueKey)) {
                    allItems.add(
                        IptvStreamItem(
                            id = "${sourceId}_$uniqueKey",
                            sourceId = sourceId,
                            contentType = IptvContentType.MOVIES,
                            categoryId = "MOVIES_$catId",
                            categoryName = catName,
                            title = name,
                            streamUrl = streamUrl,
                            logoUrl = logo,
                            rating = rating,
                            containerExtension = ext
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 4. Series Categories & Series List
        val seriesCatMap = fetchCategoryMap("$apiBase&action=get_series_categories")
        try {
            val seriesRaw = httpGetString("$apiBase&action=get_series", timeoutMs = 22_000)
            val arr = parseJsonArrayFlexible(seriesRaw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val seriesId = obj.opt("series_id")?.toString()?.takeIf { it.isNotEmpty() } ?: continue
                val name = obj.optString("name", "Series $seriesId").trim()
                val catId = obj.optString("category_id", "0")
                val catName = seriesCatMap[catId] ?: "Series"
                val cover = obj.optString("cover", "")
                val plot = obj.optString("plot", "")
                val rating = obj.optString("rating", "")
                val releaseDate = obj.optString("releaseDate", "")
                val uniqueKey = "SERIES_$seriesId"
                if (seenKeys.add(uniqueKey)) {
                    allItems.add(
                        IptvStreamItem(
                            id = "${sourceId}_$uniqueKey",
                            sourceId = sourceId,
                            contentType = IptvContentType.SERIES,
                            categoryId = "SERIES_$catId",
                            categoryName = catName,
                            title = name,
                            streamUrl = "",
                            logoUrl = cover,
                            rating = rating,
                            plot = plot,
                            releaseDate = releaseDate,
                            seriesId = seriesId,
                            seriesName = name
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        val liveCnt = allItems.count { it.contentType == IptvContentType.LIVE_TV }
        val movieCnt = allItems.count { it.contentType == IptvContentType.MOVIES }
        val seriesCnt = allItems.count { it.contentType == IptvContentType.SERIES }
        val radioCnt = allItems.count { it.contentType == IptvContentType.RADIO }

        val updatedSource = IptvSource(
            id = sourceId,
            name = sourceName,
            type = IptvSourceType.XTREAM,
            serverOrM3uUrl = baseUrl,
            username = username.trim(),
            encryptedPassword = IptvCredentialVault.encryptSecret(passwordPlain.trim()),
            lastUpdatedMs = System.currentTimeMillis(),
            liveCount = liveCnt,
            movieCount = movieCnt,
            seriesCount = seriesCnt,
            radioCount = radioCnt,
            expDateSec = expDateSec,
            accountStatus = statusStr
        )

        return Pair(updatedSource, allItems)
    }

    fun fetchSeriesSeasonsAndEpisodes(
        source: IptvSource,
        seriesItem: IptvStreamItem
    ): IptvSeriesInfoResult {
        val baseUrl = normalizeServerBaseUrl(source.serverOrM3uUrl)
        val user = source.username.trim()
        val pass = IptvCredentialVault.decryptSecret(source.encryptedPassword).trim()
        val uEnc = URLEncoder.encode(user, "UTF-8")
        val pEnc = URLEncoder.encode(pass, "UTF-8")
        val sEnc = URLEncoder.encode(seriesItem.seriesId, "UTF-8")

        val url = "$baseUrl/player_api.php?username=$uEnc&password=$pEnc&action=get_series_info&series_id=$sEnc"
        val raw = httpGetString(url, timeoutMs = 16_000)
        val root = JSONObject(raw)

        val infoObj = root.optJSONObject("info")
        val title = infoObj?.optString("name")?.takeIf { it.isNotBlank() } ?: seriesItem.title
        val cover = infoObj?.optString("cover")?.takeIf { it.isNotBlank() } ?: seriesItem.logoUrl
        val plot = infoObj?.optString("plot")?.takeIf { it.isNotBlank() } ?: seriesItem.plot
        val rating = infoObj?.optString("rating")?.takeIf { it.isNotBlank() } ?: seriesItem.rating
        val releaseDate = infoObj?.optString("releaseDate")?.takeIf { it.isNotBlank() } ?: seriesItem.releaseDate

        val seasonGroups = mutableMapOf<Int, MutableList<IptvEpisodeItem>>()
        val episodesNode = root.opt("episodes")

        when (episodesNode) {
            is JSONObject -> {
                val keys = episodesNode.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val seasonNum = key.toIntOrNull() ?: 1
                    val epArray = episodesNode.optJSONArray(key) ?: continue
                    parseEpisodeJsonArray(epArray, seasonNum, baseUrl, user, pass, seasonGroups)
                }
            }
            is JSONArray -> {
                for (i in 0 until episodesNode.length()) {
                    val node = episodesNode.opt(i)
                    if (node is JSONArray) {
                        parseEpisodeJsonArray(node, i + 1, baseUrl, user, pass, seasonGroups)
                    } else if (node is JSONObject) {
                        val sNum = node.optInt("season", 1).coerceAtLeast(1)
                        val singleArr = JSONArray().put(node)
                        parseEpisodeJsonArray(singleArr, sNum, baseUrl, user, pass, seasonGroups)
                    }
                }
            }
        }

        val sortedSeasons = seasonGroups.entries
            .sortedBy { it.key }
            .map { (sNum, epList) ->
                IptvSeasonGroup(
                    seasonNumber = sNum,
                    name = "Season $sNum / الموسم $sNum",
                    episodes = epList.sortedBy { it.episodeNumber }
                )
            }

        return IptvSeriesInfoResult(
            seriesTitle = title,
            coverUrl = cover,
            plot = plot,
            rating = rating,
            releaseDate = releaseDate,
            seasons = sortedSeasons
        )
    }

    private fun parseEpisodeJsonArray(
        epArray: JSONArray,
        defaultSeasonNum: Int,
        baseUrl: String,
        user: String,
        pass: String,
        seasonGroups: MutableMap<Int, MutableList<IptvEpisodeItem>>
    ) {
        for (i in 0 until epArray.length()) {
            val epObj = epArray.optJSONObject(i) ?: continue
            val epId = epObj.opt("id")?.toString()?.takeIf { it.isNotEmpty() } ?: continue
            val epNum = epObj.optInt("episode_num", i + 1)
            val sNum = epObj.optInt("season", defaultSeasonNum).coerceAtLeast(1)
            val rawTitle = epObj.optString("title", "Episode $epNum").ifBlank { "Episode $epNum" }
            val ext = epObj.optString("container_extension", "mp4").ifBlank { "mp4" }
            val info = epObj.optJSONObject("info")
            val duration = info?.optString("duration", "") ?: ""
            val plot = info?.optString("plot", "") ?: ""
            val thumb = info?.optString("movie_image", "") ?: ""
            val streamUrl = "$baseUrl/series/$user/$pass/$epId.$ext"

            val list = seasonGroups.getOrPut(sNum) { mutableListOf() }
            list.add(
                IptvEpisodeItem(
                    id = epId,
                    title = rawTitle,
                    seasonNumber = sNum,
                    episodeNumber = epNum,
                    streamUrl = streamUrl,
                    logoUrl = thumb,
                    durationLabel = duration,
                    plot = plot
                )
            )
        }
    }

    private fun fetchCategoryMap(urlStr: String): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            val raw = httpGetString(urlStr, timeoutMs = 12_000)
            val arr = parseJsonArrayFlexible(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("category_id", "")
                val name = obj.optString("category_name", "")
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    map[id] = name
                }
            }
        } catch (_: Exception) {}
        return map
    }

    private fun parseJsonArrayFlexible(raw: String): JSONArray {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return JSONArray(trimmed)
        }
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val out = JSONArray()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val child = obj.optJSONObject(k)
                if (child != null) out.put(child)
            }
            return out
        }
        return JSONArray()
    }
}

/**
 * Persistent Repository & Cache Manager for IPTV Sources and Streams.
 * - Preserves existing cached channels/movies/series if a source refresh fails!
 * - Prevents duplicate entries upon reload.
 * - Supports category reclassification overrides for M3U playlists.
 */
object IptvRepository {
    private const val PREF_IPTV = "pure_player_iptv_store_v1"
    private const val KEY_SOURCES_JSON = "iptv_sources_json_v1"
    private const val KEY_ACTIVE_SOURCE_ID = "iptv_active_source_id"
    private const val PREFIX_CAT_OVERRIDES = "iptv_cat_overrides_"

    private val memoryStreamsCache = HashMap<String, List<IptvStreamItem>>()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_IPTV, Context.MODE_PRIVATE)

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "iptv_cache").apply { if (!exists()) mkdirs() }

    fun getSources(context: Context): List<IptvSource> {
        val raw = prefs(context).getString(KEY_SOURCES_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<IptvSource>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    IptvSource(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        type = runCatching { IptvSourceType.valueOf(o.optString("type", "M3U_URL")) }.getOrDefault(IptvSourceType.M3U_URL),
                        serverOrM3uUrl = o.optString("serverOrM3uUrl"),
                        username = o.optString("username"),
                        encryptedPassword = o.optString("encryptedPassword"),
                        lastUpdatedMs = o.optLong("lastUpdatedMs", 0L),
                        liveCount = o.optInt("liveCount", 0),
                        movieCount = o.optInt("movieCount", 0),
                        seriesCount = o.optInt("seriesCount", 0),
                        radioCount = o.optInt("radioCount", 0),
                        expDateSec = o.optLong("expDateSec", 0L),
                        accountStatus = o.optString("accountStatus", "Active")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSourcesList(context: Context, sources: List<IptvSource>) {
        val arr = JSONArray()
        for (s in sources) {
            val o = JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("type", s.type.name)
                put("serverOrM3uUrl", s.serverOrM3uUrl)
                put("username", s.username)
                put("encryptedPassword", s.encryptedPassword)
                put("lastUpdatedMs", s.lastUpdatedMs)
                put("liveCount", s.liveCount)
                put("movieCount", s.movieCount)
                put("seriesCount", s.seriesCount)
                put("radioCount", s.radioCount)
                put("expDateSec", s.expDateSec)
                put("accountStatus", s.accountStatus)
            }
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_SOURCES_JSON, arr.toString()).apply()
    }

    fun getActiveSourceId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_SOURCE_ID, null)

    fun setActiveSourceId(context: Context, sourceId: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_SOURCE_ID, sourceId).apply()
    }

    fun deleteSource(context: Context, sourceId: String) {
        val updated = getSources(context).filterNot { it.id == sourceId }
        saveSourcesList(context, updated)
        memoryStreamsCache.remove(sourceId)
        try {
            File(cacheDir(context), "streams_$sourceId.json").delete()
        } catch (_: Exception) {}
        if (getActiveSourceId(context) == sourceId) {
            setActiveSourceId(context, updated.firstOrNull()?.id)
        }
    }

    fun getCategoryOverrides(context: Context, sourceId: String): Map<String, IptvContentType> {
        val raw = prefs(context).getString(PREFIX_CAT_OVERRIDES + sourceId, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = HashMap<String, IptvContentType>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k)
                runCatching { IptvContentType.valueOf(v) }.getOrNull()?.let { map[k] = it }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setCategoryOverride(
        context: Context,
        sourceId: String,
        categoryName: String,
        newType: IptvContentType
    ) {
        val current = getCategoryOverrides(context, sourceId).toMutableMap()
        current[categoryName.lowercase(Locale.ROOT)] = newType
        val obj = JSONObject()
        for ((k, v) in current) {
            obj.put(k, v.name)
        }
        prefs(context).edit().putString(PREFIX_CAT_OVERRIDES + sourceId, obj.toString()).apply()

        // Immediately update cached items in memory & disk so reclassification takes effect right away
        val cachedItems = loadCachedStreams(context, sourceId)
        if (cachedItems.isNotEmpty()) {
            val updatedItems = cachedItems.map { item ->
                if (item.categoryName.equals(categoryName, ignoreCase = true)) {
                    item.copy(
                        contentType = newType,
                        categoryId = "${newType.name}_${item.categoryName.lowercase(Locale.ROOT).hashCode()}"
                    )
                } else {
                    item
                }
            }
            saveStreamsToDiskCache(context, sourceId, updatedItems)
            updateSourceCounts(context, sourceId, updatedItems)
        }
    }

    private fun updateSourceCounts(context: Context, sourceId: String, items: List<IptvStreamItem>) {
        val sources = getSources(context).toMutableList()
        val idx = sources.indexOfFirst { it.id == sourceId }
        if (idx != -1) {
            val old = sources[idx]
            sources[idx] = old.copy(
                liveCount = items.count { it.contentType == IptvContentType.LIVE_TV },
                movieCount = items.count { it.contentType == IptvContentType.MOVIES },
                seriesCount = items.count {
                    it.contentType == IptvContentType.SERIES
                }.let { rawCnt ->
                    if (old.type == IptvSourceType.XTREAM) rawCnt
                    else items.filter { it.contentType == IptvContentType.SERIES }.map { it.seriesName.ifEmpty { it.title } }.distinct().size
                },
                radioCount = items.count { it.contentType == IptvContentType.RADIO }
            )
            saveSourcesList(context, sources)
        }
    }

    fun loadCachedStreams(context: Context, sourceId: String): List<IptvStreamItem> {
        memoryStreamsCache[sourceId]?.let { return it }
        val file = File(cacheDir(context), "streams_$sourceId.json")
        if (!file.exists()) return emptyList()
        return try {
            val items = ArrayList<IptvStreamItem>(2048)
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val o = JSONObject(line)
                    items.add(
                        IptvStreamItem(
                            id = o.optString("id"),
                            sourceId = sourceId,
                            contentType = runCatching { IptvContentType.valueOf(o.optString("ct", "LIVE_TV")) }.getOrDefault(IptvContentType.LIVE_TV),
                            categoryId = o.optString("cid"),
                            categoryName = o.optString("cn"),
                            title = o.optString("t"),
                            streamUrl = o.optString("u"),
                            logoUrl = o.optString("l"),
                            rating = o.optString("r"),
                            plot = o.optString("p"),
                            releaseDate = o.optString("rd"),
                            durationLabel = o.optString("dl"),
                            seriesId = o.optString("sid"),
                            seriesName = o.optString("sn"),
                            seasonNumber = o.optInt("snum", 0),
                            episodeNumber = o.optInt("enum", 0),
                            containerExtension = o.optString("ext"),
                            httpUserAgent = o.optString("ua"),
                            httpReferrer = o.optString("ref")
                        )
                    )
                }
            }
            memoryStreamsCache[sourceId] = items
            items
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Writes streams in JSON-Lines batches so even 50,000+ channels serialize quickly without OOM.
     */
    private fun saveStreamsToDiskCache(context: Context, sourceId: String, items: List<IptvStreamItem>) {
        memoryStreamsCache[sourceId] = items
        val tempFile = File(cacheDir(context), "streams_${sourceId}.tmp")
        val finalFile = File(cacheDir(context), "streams_${sourceId}.json")
        try {
            tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (item in items) {
                    val o = JSONObject().apply {
                        put("id", item.id)
                        put("ct", item.contentType.name)
                        put("cid", item.categoryId)
                        put("cn", item.categoryName)
                        put("t", item.title)
                        put("u", item.streamUrl)
                        if (item.logoUrl.isNotEmpty()) put("l", item.logoUrl)
                        if (item.rating.isNotEmpty()) put("r", item.rating)
                        if (item.plot.isNotEmpty()) put("p", item.plot)
                        if (item.releaseDate.isNotEmpty()) put("rd", item.releaseDate)
                        if (item.durationLabel.isNotEmpty()) put("dl", item.durationLabel)
                        if (item.seriesId.isNotEmpty()) put("sid", item.seriesId)
                        if (item.seriesName.isNotEmpty()) put("sn", item.seriesName)
                        if (item.seasonNumber > 0) put("snum", item.seasonNumber)
                        if (item.episodeNumber > 0) put("enum", item.episodeNumber)
                        if (item.containerExtension.isNotEmpty()) put("ext", item.containerExtension)
                        if (item.httpUserAgent.isNotEmpty()) put("ua", item.httpUserAgent)
                        if (item.httpReferrer.isNotEmpty()) put("ref", item.httpReferrer)
                    }
                    writer.write(o.toString())
                    writer.newLine()
                }
            }
            if (finalFile.exists()) finalFile.delete()
            tempFile.renameTo(finalFile)
        } catch (_: Exception) {
            try { tempFile.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Adds, edits, or refreshes an Xtream or M3U source off the main thread.
     * If refreshing an existing source fails, existing cached streams and source metadata are preserved!
     */
    suspend fun syncAndSaveSource(
        context: Context,
        existingSourceId: String? = null,
        name: String,
        type: IptvSourceType,
        serverOrM3uUrl: String,
        username: String = "",
        passwordPlain: String = ""
    ): IptvSyncResult = withContext(Dispatchers.IO) {
        val sourceId = existingSourceId ?: UUID.randomUUID().toString()
        val overrides = getCategoryOverrides(context, sourceId)

        try {
            val (updatedSource, items) = when (type) {
                IptvSourceType.XTREAM -> {
                    XtreamCodesClient.fetchXtreamSourceContent(
                        sourceId = sourceId,
                        sourceName = name.ifBlank { "Xtream IPTV" },
                        rawServerUrl = serverOrM3uUrl,
                        username = username,
                        passwordPlain = passwordPlain
                    )
                }
                IptvSourceType.M3U_URL -> {
                    val cleanUrl = serverOrM3uUrl.trim()
                    val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 25_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "IPTVSmartersPro/3.1 (Android)")
                    }
                    if (conn.responseCode !in 200..299) {
                        throw IllegalStateException("HTTP_${conn.responseCode}")
                    }
                    val parsed = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        M3uPlaylistParser.parseFromReader(sourceId, reader, overrides)
                    }
                    if (parsed.isEmpty()) {
                        throw IllegalStateException("EMPTY_PLAYLIST")
                    }
                    val src = buildM3uSourceSummary(sourceId, name.ifBlank { "M3U Playlist" }, IptvSourceType.M3U_URL, cleanUrl, parsed)
                    Pair(src, parsed)
                }
                IptvSourceType.M3U_FILE -> {
                    val uri = Uri.parse(serverOrM3uUrl)
                    val input = if (uri.scheme == "file" && !uri.path.isNullOrEmpty()) File(uri.path!!).inputStream() else context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("FILE_READ_ERROR")
                    val parsed = BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        M3uPlaylistParser.parseFromReader(sourceId, reader, overrides)
                    }
                    if (parsed.isEmpty()) {
                        throw IllegalStateException("EMPTY_PLAYLIST")
                    }
                    val src = buildM3uSourceSummary(sourceId, name.ifBlank { "Local M3U File" }, IptvSourceType.M3U_FILE, serverOrM3uUrl, parsed)
                    Pair(src, parsed)
                }
            }

            // Persist streams to disk cache and update source list
            saveStreamsToDiskCache(context, sourceId, items)
            val currentList = getSources(context).toMutableList()
            val idx = currentList.indexOfFirst { it.id == sourceId }
            if (idx != -1) {
                currentList[idx] = updatedSource
            } else {
                currentList.add(0, updatedSource)
            }
            saveSourcesList(context, currentList)
            setActiveSourceId(context, sourceId)

            IptvSyncResult.Success(updatedSource, items.size)
        } catch (e: Exception) {
            val code = e.message ?: "UNKNOWN"
            val friendlyMsg = when {
                code == "AUTH_FAILED" -> context.getString(R.string.iptv_err_auth_failed)
                code == "ACCOUNT_EXPIRED" -> context.getString(R.string.iptv_err_account_expired)
                code.startsWith("ACCOUNT_") -> context.getString(R.string.iptv_err_account_inactive, code.removePrefix("ACCOUNT_"))
                code == "EMPTY_PLAYLIST" -> context.getString(R.string.iptv_err_empty_playlist)
                code == "FILE_READ_ERROR" -> context.getString(R.string.iptv_err_file_read)
                else -> context.getString(R.string.iptv_err_connection)
            }
            IptvSyncResult.Error(friendlyMsg)
        }
    }

    private fun buildM3uSourceSummary(
        sourceId: String,
        name: String,
        type: IptvSourceType,
        urlOrUri: String,
        items: List<IptvStreamItem>
    ): IptvSource {
        val liveCnt = items.count { it.contentType == IptvContentType.LIVE_TV }
        val movieCnt = items.count { it.contentType == IptvContentType.MOVIES }
        val seriesCnt = items.filter { it.contentType == IptvContentType.SERIES }
            .map { it.seriesName.ifEmpty { it.title } }
            .distinct()
            .size
        val radioCnt = items.count { it.contentType == IptvContentType.RADIO }

        return IptvSource(
            id = sourceId,
            name = name,
            type = type,
            serverOrM3uUrl = urlOrUri,
            lastUpdatedMs = System.currentTimeMillis(),
            liveCount = liveCnt,
            movieCount = movieCnt,
            seriesCount = seriesCnt,
            radioCount = radioCnt
        )
    }
}
