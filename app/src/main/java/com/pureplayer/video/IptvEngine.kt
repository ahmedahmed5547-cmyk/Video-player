package com.pureplayer.video

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.JsonReader
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.util.JsonToken
import android.util.LruCache
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.EnumMap
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
    val itemCount: Int,
    val rawXtreamCategoryId: String = ""
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
    data class Error(val userMessage: String, val technicalDiagnostic: String = "") : IptvSyncResult()
}

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
        val normQ = normalize(normalizedQuery)
        if (normQ.isEmpty()) return true
        val normTitle = normalize(targetTitle)
        if (normTitle.contains(normQ)) return true
        val normCat = normalize(targetCategory)
        if (normCat.contains(normQ)) return true
        val tokens = normQ.split(" ").filter { it.isNotEmpty() }
        return tokens.isNotEmpty() && tokens.all { token ->
            normTitle.contains(token) || normCat.contains(token)
        }
    }
}

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
        } catch (e: Exception) {
            Log.e("IptvCredentialVault", "KeyStore key initialization failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Encrypts [plainText] strictly with AES-256-GCM backed by AndroidKeyStore.
     * Returns null if AndroidKeyStore or AES-GCM fails — NEVER falls back to Base64 or plain text!
     */
    fun encryptSecretOrNull(
        plainText: String,
        keyProvider: () -> SecretKey? = ::getOrCreateKey
    ): String? {
        if (plainText.isEmpty()) return ""
        return try {
            val key = keyProvider() ?: run {
                Log.e("IptvCredentialVault", "Refusing to store IPTV password: KeyStore key is null")
                return null
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "GCM:$ivB64:$encB64"
        } catch (e: Exception) {
            Log.e("IptvCredentialVault", "AES-GCM encryption failed (${e.javaClass.simpleName}); password not saved")
            null
        }
    }

    fun decryptSecretOrNull(
        cipherText: String,
        keyProvider: () -> SecretKey? = ::getOrCreateKey
    ): String? {
        if (cipherText.isEmpty()) return ""
        return try {
            if (!cipherText.startsWith("GCM:")) {
                Log.w("IptvCredentialVault", "Rejected legacy or unencrypted non-GCM credential format")
                return null
            }
            val parts = cipherText.split(":")
            if (parts.size != 3) return null
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
            val key = keyProvider() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("IptvCredentialVault", "AES-GCM decryption failed (${e.javaClass.simpleName})")
            null
        }
    }
}

object IptvImageLoader {
    private val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val memCache = object : LruCache<String, Bitmap>((maxMem / 10).coerceAtLeast(2048)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadSemaphore = Semaphore(4)

    fun loadLogoOrPoster(
        context: Context,
        imageUrl: String,
        imageView: ImageView,
        fallbackResId: Int,
        accentColor: Int
    ): Job {
        val cleanUrl = imageUrl.trim()
        imageView.tag = cleanUrl
        if (cleanUrl.isEmpty() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) {
            imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageView.setPadding(20, 20, 20, 20)
            imageView.imageTintList = ColorStateList.valueOf(accentColor)
            imageView.setImageResource(fallbackResId)
            return Job().apply { complete() }
        }

        val cached = memCache.get(cleanUrl)
        if (cached != null && !cached.isRecycled) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.setPadding(0, 0, 0, 0)
            imageView.imageTintList = null
            imageView.setImageBitmap(cached)
            return Job().apply { complete() }
        }

        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.setPadding(20, 20, 20, 20)
        imageView.imageTintList = ColorStateList.valueOf(accentColor)
        imageView.setImageResource(fallbackResId)

        return scope.launch {
            val bmp = loadSemaphore.withPermit { fetchOrReadDiskCache(context, cleanUrl) }
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
                setRequestProperty("User-Agent", "IPTVSmartersPlayer")
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
                } catch (e: Exception) { Log.w("IptvEngine", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}

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
                val line = rawLine.replace("\uFEFF", "").trim()
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
                    line.startsWith("#") -> {}
                    else -> {
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
 * Ultimate Xtream Codes Client (v33):
 * - Converts Arabic-Indic / Persian digits (٠-٩ / ۰-۹) to ASCII (0-9) and strips invisible WhatsApp/Telegram RTL marks.
 * - Fast Sequential Port & Protocol Probing (fails fast on closed TCP ports without retrying 4 User-Agents on a dead socket, and avoids triggering Xtream max_connections=1 / Cloudflare anti-DDoS).
 * - Preserves `/player_api.php?...` when a domain redirect (like `mar10.sbs` -> `http://host:port/`) points to root `/`.
 * - Streams large JSON arrays directly from `InputStream` via `JsonReader` without loading 50MB strings into memory.
 * - Supports on-demand per-category stream loading (`&category_id=...`) just like IPTV Smarters Pro!
 */
object XtreamCodesClient {
    @Volatile
    var allowUntrustedSslFallback: Boolean = false

    private val invisibleCharsRegex = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF\\u00A0\\r\\n\\t]")

    private val userAgents: List<String> by lazy {
        val sysUa = System.getProperty("http.agent")?.takeIf { it.isNotBlank() }
            ?: "Dalvik/2.1.0 (Linux; U; Android 14; Mobile)"
        listOf(
            "IPTVSmartersPlayer",
            sysUa,
            "okhttp/3.12.1",
            "VLC/3.0.20 LibVLC/3.0.20"
        )
    }

    /**
     * Converts any Arabic-Indic (٠-٩) or Eastern Arabic (۰-۹) digits to ASCII (0-9)
     * and removes invisible Unicode directional/zero-width marks.
     */
    fun sanitizeCredentialOrUrl(raw: String): String {
        val noInvisible = raw.replace(invisibleCharsRegex, "").trim()
        if (noInvisible.isEmpty()) return ""
        val sb = StringBuilder(noInvisible.length)
        for (ch in noInvisible) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append((ch.code - 0x0660 + '0'.code).toChar())
                in '\u06F0'..'\u06F9' -> sb.append((ch.code - 0x06F0 + '0'.code).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    fun normalizeServerBaseUrl(rawUrl: String): String {
        var clean = sanitizeCredentialOrUrl(rawUrl)
        clean = clean
            .substringBefore("/player_api.php")
            .substringBefore("/panel_api.php")
            .substringBefore("/get.php")
            .substringBefore("?")
            .trimEnd('/')
        if (clean.endsWith("/c", ignoreCase = true)) {
            clean = clean.dropLast(2).trimEnd('/')
        }
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            clean = "http://$clean"
        }
        return clean
    }

    fun buildCandidateBaseUrls(rawUrl: String): List<String> {
        val rawClean = sanitizeCredentialOrUrl(rawUrl)
            .substringBefore("/player_api.php")
            .substringBefore("/panel_api.php")
            .substringBefore("/get.php")
            .substringBefore("?")
            .trimEnd('/')
            .let { if (it.endsWith("/c", ignoreCase = true)) it.dropLast(2).trimEnd('/') else it }

        val hasExplicitHttp = rawClean.startsWith("http://", ignoreCase = true)
        val hasExplicitHttps = rawClean.startsWith("https://", ignoreCase = true)
        val hostPart = rawClean
            .removePrefix("http://")
            .removePrefix("HTTP://")
            .removePrefix("https://")
            .removePrefix("HTTPS://")
            .trimEnd('/')

        val hasExplicitPort = hostPart.substringBefore("/").contains(":")
        val candidates = LinkedHashSet<String>()

        if (hasExplicitHttps) {
            candidates.add("https://$hostPart")
            candidates.add("http://$hostPart")
        } else if (hasExplicitHttp) {
            candidates.add("http://$hostPart")
            candidates.add("https://$hostPart")
        } else {
            candidates.add("http://$hostPart")
            candidates.add("https://$hostPart")
        }

        if (!hasExplicitPort) {
            val domainOnly = hostPart.substringBefore("/")
            val subPath = if (hostPart.contains("/")) "/" + hostPart.substringAfter("/") else ""
            // Most common Xtream ports ordered by frequency
            candidates.add("http://$domainOnly:8080$subPath")
            candidates.add("http://$domainOnly:8000$subPath")
            candidates.add("http://$domainOnly:25461$subPath")
            candidates.add("http://$domainOnly:2095$subPath")
            candidates.add("http://$domainOnly:8880$subPath")
            candidates.add("http://$domainOnly:2052$subPath")
            candidates.add("http://$domainOnly:2082$subPath")
        }
        return candidates.toList()
    }

    private fun configurePermissiveSsl(conn: HttpURLConnection) {
        if (conn is HttpsURLConnection) {
            try {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, trustAll, SecureRandom())
                conn.sslSocketFactory = sc.socketFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            } catch (e: Exception) { Log.w("IptvEngine", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
    }

    private fun wrapGzipIfNeeded(rawStream: InputStream, contentEncoding: String?): InputStream {
        val pb = BufferedInputStream(rawStream, 16384)
        pb.mark(4)
        val b0 = pb.read()
        val b1 = pb.read()
        pb.reset()
        val isGzipMagic = (b0 == 0x1f && b1 == 0x8b)
        val isGzipHeader = contentEncoding?.contains("gzip", ignoreCase = true) == true
        return if (isGzipMagic || isGzipHeader) GZIPInputStream(pb) else pb
    }

    /**
     * Advances a BufferedInputStream past any leading UTF-8 BOM or PHP notices until `{` or `[` is reached.
     */
    private fun skipToStartOfJson(rawStream: InputStream): BufferedInputStream? {
        val pb = if (rawStream is BufferedInputStream) rawStream else BufferedInputStream(rawStream, 16384)
        while (true) {
            pb.mark(4)
            val b = pb.read()
            if (b == -1) return null
            val ch = b.toChar()
            if (ch == '{' || ch == '[') {
                pb.reset()
                return pb
            }
        }
    }

    private fun extractCleanJsonString(rawText: String): String {
        val noBom = rawText.replace("\uFEFF", "").trim()
        if (noBom.startsWith("{") || noBom.startsWith("[")) return noBom
        val firstBrace = noBom.indexOf('{')
        val firstBracket = noBom.indexOf('[')
        val startIdx = when {
            firstBrace >= 0 && firstBracket >= 0 -> minOf(firstBrace, firstBracket)
            firstBrace >= 0 -> firstBrace
            firstBracket >= 0 -> firstBracket
            else -> -1
        }
        return if (startIdx >= 0) noBom.substring(startIdx).trim() else noBom
    }

    /**
     * Opens an HTTP connection following cross-protocol redirects (http <-> https)
     * and preserving `/player_api.php?...` if a domain redirect points to root `/`.
     * IMPORTANT: Only retries another User-Agent if the server returned an HTTP error code (e.g. 403/401/503);
     * if TCP connect itself fails (timeout/refused), fails fast immediately so we don't waste time on dead ports!
     */
    private fun openRedirectedStream(
        initialUrl: String,
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 18_000,
        postFormBody: String? = null
    ): Pair<InputStream, String> {
        var lastException: Exception? = null
        val initialUrlObj = runCatching { URL(initialUrl) }.getOrNull()
        val initialPathAndQuery = if (initialUrlObj != null) {
            val p = initialUrlObj.path ?: ""
            val q = initialUrlObj.query
            if (!q.isNullOrEmpty()) "$p?$q" else p
        } else ""

        for (ua in userAgents) {
            try {
                var currentUrl = initialUrl
                var currentPostBody = postFormBody
                var redirectsRemaining = 6

                while (redirectsRemaining >= 0) {
                    val urlObj = URL(currentUrl)
                    val postPayload: String? = currentPostBody
                    fun openHop(permissive: Boolean): Pair<HttpURLConnection, Int> {
                        val c = (urlObj.openConnection() as HttpURLConnection).apply {
                            if (permissive) configurePermissiveSsl(this)
                            connectTimeout = connectTimeoutMs
                            readTimeout = readTimeoutMs
                            instanceFollowRedirects = false
                            setRequestProperty("User-Agent", ua)
                            setRequestProperty("Accept", "*/*")
                            setRequestProperty("Connection", "keep-alive")
                            if (postPayload != null) {
                                requestMethod = "POST"
                                doOutput = true
                                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                            } else {
                                requestMethod = "GET"
                            }
                        }
                        if (postPayload != null) {
                            c.outputStream.use { os ->
                                os.write(postPayload.toByteArray(Charsets.UTF_8))
                            }
                        }
                        return c to c.responseCode
                    }

                    val (conn, code) = try {
                        openHop(permissive = false)
                    } catch (sslEx: SSLException) {
                        if (allowUntrustedSslFallback) {
                            Log.w("XtreamCodesClient", "SSL handshake failed; retrying with permissive fallback", sslEx)
                            openHop(permissive = true)
                        } else {
                            throw sslEx
                        }
                    }
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val location = conn.getHeaderField("Location")?.trim()
                        conn.disconnect()
                        if (location.isNullOrEmpty()) break
                        var resolvedUrl = URL(urlObj, location).toString()
                        val resolvedObj = URL(resolvedUrl)
                        val rPath = resolvedObj.path ?: ""
                        if ((rPath.isEmpty() || rPath == "/") && initialPathAndQuery.isNotEmpty() && initialPathAndQuery != "/") {
                            val baseResolved = resolvedUrl.trimEnd('/')
                            resolvedUrl = "$baseResolved$initialPathAndQuery"
                        }
                        if (code in listOf(301, 302, 303) && currentPostBody != null) {
                            if (!resolvedUrl.contains("?")) {
                                resolvedUrl = "$resolvedUrl?$currentPostBody"
                            }
                            currentPostBody = null
                        }
                        currentUrl = resolvedUrl
                        redirectsRemaining--
                        continue
                    }

                    if (code in 200..299) {
                        val stream = wrapGzipIfNeeded(conn.inputStream, conn.contentEncoding)
                        return Pair(stream, currentUrl)
                    }

                    conn.disconnect()
                    if (code == 403 || code == 401 || code == 503 || code == 406) {
                        // Server is alive on this port but rejected User-Agent; try next User-Agent!
                        lastException = IllegalStateException("HTTP_$code")
                        break
                    }

                    // Other HTTP code (e.g. 404, 500, 502): no need to retry 4 User-Agents
                    throw IllegalStateException("HTTP_$code")
                }
            } catch (e: Exception) {
                lastException = e
                // If TCP connection itself timed out or was refused, do NOT retry 4 User-Agents on this dead port!
                val msg = e.message ?: ""
                if (!msg.startsWith("HTTP_403") && !msg.startsWith("HTTP_401") && !msg.startsWith("HTTP_503")) {
                    break
                }
            }
        }
        throw (lastException ?: IllegalStateException("CONNECTION_FAILED"))
    }

    fun httpRequestWithRedirects(
        initialUrl: String,
        timeoutMs: Int = 9_000,
        postFormBody: String? = null
    ): Pair<String, String> {
        val (stream, finalUrl) = openRedirectedStream(
            initialUrl = initialUrl,
            connectTimeoutMs = timeoutMs.coerceAtMost(6_000),
            readTimeoutMs = timeoutMs,
            postFormBody = postFormBody
        )
        val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Pair(body, finalUrl)
    }

    private fun httpGetString(urlStr: String, timeoutMs: Int = 14_000): String {
        return httpRequestWithRedirects(urlStr, timeoutMs).first
    }

    private data class ProbeOutcome(
        val candidateBase: String,
        val resolvedBase: String?,
        val authJson: JSONObject?,
        val sawExplicitAuthZero: Boolean,
        val errorDetail: String?
    )

    private fun probeSingleBaseUrl(
        candidateBase: String,
        cleanUser: String,
        cleanPass: String,
        uEnc: String,
        pEnc: String
    ): ProbeOutcome {
        var sawAuthZero = false
        var lastErr: String = ""

        val endpoints = mutableListOf(
            Triple("$candidateBase/player_api.php?username=$uEnc&password=$pEnc", null as String?, "/player_api.php")
        )
        if (uEnc != cleanUser || pEnc != cleanPass) {
            endpoints.add(Triple("$candidateBase/player_api.php?username=$cleanUser&password=$cleanPass", null as String?, "/player_api.php"))
        }
        endpoints.add(Triple("$candidateBase/player_api.php", "username=$uEnc&password=$pEnc", "/player_api.php"))
        endpoints.add(Triple("$candidateBase/panel_api.php?username=$uEnc&password=$pEnc", null as String?, "/panel_api.php"))

        for ((idx, triple) in endpoints.withIndex()) {
            val (targetUrl, postBody, apiPath) = triple
            try {
                val (rawResp, finalUrl) = httpRequestWithRedirects(targetUrl, timeoutMs = 5_500, postFormBody = postBody)
                val cleanJsonStr = extractCleanJsonString(rawResp)
                if (cleanJsonStr.startsWith("{")) {
                    val json = JSONObject(cleanJsonStr)
                    val uInfo = json.optJSONObject("user_info")
                    if (uInfo != null) {
                        val authVal = uInfo.opt("auth")?.toString()?.trim() ?: ""
                        val statusVal = uInfo.optString("status", "").trim()
                        val hasUsername = !uInfo.optString("username", "").isNullOrBlank()
                        val isAuthExplicitlyFailed = (authVal == "0" || authVal.equals("false", ignoreCase = true)) && !hasUsername && !statusVal.equals("Active", ignoreCase = true)
                        if (!isAuthExplicitlyFailed && authVal != "0" && !authVal.equals("false", ignoreCase = true)) {
                            var redirectedBase = finalUrl.substringBefore(apiPath).substringBefore("?").trimEnd('/')
                            if (redirectedBase.isEmpty()) redirectedBase = candidateBase
                            return ProbeOutcome(
                                candidateBase = candidateBase,
                                resolvedBase = redirectedBase,
                                authJson = json,
                                sawExplicitAuthZero = false,
                                errorDetail = null
                            )
                        } else {
                            sawAuthZero = true
                        }
                    }
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
                // If the first attempt on this port failed with a TCP Connect/Timeout error, this port is not listening—skip remaining endpoints on this port!
                if (idx == 0 && !lastErr.startsWith("HTTP_")) {
                    break
                }
            }
        }
        return ProbeOutcome(candidateBase, null, null, sawAuthZero, lastErr.ifEmpty { null })
    }

    suspend fun fetchXtreamSourceContent(
        sourceId: String,
        sourceName: String,
        rawServerUrl: String,
        username: String,
        passwordPlain: String
    ): Pair<IptvSource, List<IptvStreamItem>> = withContext(Dispatchers.IO) {
        val cleanUser = sanitizeCredentialOrUrl(username)
        val cleanPass = sanitizeCredentialOrUrl(passwordPlain)
        val candidateBases = buildCandidateBaseUrls(rawServerUrl)
        val uEnc = URLEncoder.encode(cleanUser, "UTF-8")
        val pEnc = URLEncoder.encode(cleanPass, "UTF-8")

        var winningOutcome: ProbeOutcome? = null
        var anySawAuthZero = false
        val diagnostics = mutableListOf<String>()

        // Probe candidates sequentially with fast fail on dead ports so we don't trigger Cloudflare/Xtream flood bans!
        for (cand in candidateBases) {
            val outcome = probeSingleBaseUrl(cand, cleanUser, cleanPass, uEnc, pEnc)
            if (outcome.authJson != null && outcome.resolvedBase != null) {
                winningOutcome = outcome
                break
            }
            if (outcome.sawExplicitAuthZero) {
                anySawAuthZero = true
            }
            outcome.errorDetail?.let { diagnostics.add("${cand.removePrefix("http://")}: $it") }
        }

        // Fallback: If player_api.php wasn't reachable, try get.php M3U endpoint
        if (winningOutcome == null) {
            for (candidateBase in candidateBases.take(3)) {
                try {
                    val m3uUrl = "$candidateBase/get.php?username=$uEnc&password=$pEnc&type=m3u_plus&output=ts"
                    val (stream, finalU) = openRedirectedStream(m3uUrl, connectTimeoutMs = 5_500, readTimeoutMs = 20_000)
                    val parsed = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        M3uPlaylistParser.parseFromReader(sourceId, reader)
                    }
                    if (parsed.isNotEmpty()) {
                        val resolvedBase = finalU.substringBefore("/get.php").trimEnd('/').ifEmpty { candidateBase }
                        val src = IptvSource(
                            id = sourceId,
                            name = sourceName,
                            type = IptvSourceType.XTREAM,
                            serverOrM3uUrl = resolvedBase,
                            username = cleanUser,
                            encryptedPassword = IptvCredentialVault.encryptSecretOrNull(cleanPass) ?: throw IllegalStateException("KEYSTORE_ENCRYPT_FAILED"),
                            lastUpdatedMs = System.currentTimeMillis(),
                            liveCount = parsed.count { it.contentType == IptvContentType.LIVE_TV },
                            movieCount = parsed.count { it.contentType == IptvContentType.MOVIES },
                            seriesCount = parsed.filter { it.contentType == IptvContentType.SERIES }.map { it.seriesName.ifEmpty { it.title } }.distinct().size,
                            radioCount = parsed.count { it.contentType == IptvContentType.RADIO },
                            accountStatus = "Active"
                        )
                        return@withContext Pair(src, parsed)
                    }
                } catch (e: Exception) { Log.w("IptvEngine", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
            }

            if (anySawAuthZero) {
                throw IllegalStateException("AUTH_FAILED")
            } else {
                val diagSummary = diagnostics.take(2).joinToString(" | ").ifEmpty { "Timeout/Refused" }
                throw IllegalStateException("CONN_DETAIL:$diagSummary")
            }
        }

        val finalOutcome = winningOutcome!!
        val baseUrl = finalOutcome.resolvedBase!!
        val authJson = finalOutcome.authJson!!
        val userInfo = authJson.optJSONObject("user_info") ?: JSONObject()
        val apiBase = "$baseUrl/player_api.php?username=$uEnc&password=$pEnc"

        val statusStr = userInfo.optString("status", "Active").ifBlank { "Active" }
        if (statusStr.equals("Expired", ignoreCase = true) ||
            statusStr.equals("Banned", ignoreCase = true) ||
            statusStr.equals("Disabled", ignoreCase = true)
        ) {
            throw IllegalStateException("ACCOUNT_${statusStr.uppercase(Locale.ROOT)}")
        }

        val expDateSec = userInfo.optString("exp_date", "0").toLongOrNull() ?: 0L

        // Fetch Categories & Streams sequentially using direct InputStream -> JsonReader streaming
        // (Zero RAM bloat and zero risk of triggering Xtream max_connections=1 rate limits!)
        val liveCatMap = fetchCategoryMap("$apiBase&action=get_live_categories")
        val vodCatMap = fetchCategoryMap("$apiBase&action=get_vod_categories")
        val seriesCatMap = fetchCategoryMap("$apiBase&action=get_series_categories")

        val allItems = ArrayList<IptvStreamItem>(8192)
        allItems.addAll(
            fetchLiveStreamsDirect(sourceId, baseUrl, cleanUser, cleanPass, "$apiBase&action=get_live_streams", liveCatMap)
        )
        allItems.addAll(
            fetchVodStreamsDirect(sourceId, baseUrl, cleanUser, cleanPass, "$apiBase&action=get_vod_streams", vodCatMap)
        )
        allItems.addAll(
            fetchSeriesStreamsDirect(sourceId, "$apiBase&action=get_series", seriesCatMap)
        )

        // If the server only returns streams per-category (&category_id=...) and returned empty on bulk list,
        // create lightweight category entries or fetch the first categories so sections are immediately visible!
        if (allItems.isEmpty()) {
            for ((catId, _) in liveCatMap.entries.take(5)) {
                allItems.addAll(
                    fetchLiveStreamsDirect(sourceId, baseUrl, cleanUser, cleanPass, "$apiBase&action=get_live_streams&category_id=$catId", liveCatMap)
                )
            }
            for ((catId, _) in vodCatMap.entries.take(5)) {
                allItems.addAll(
                    fetchVodStreamsDirect(sourceId, baseUrl, cleanUser, cleanPass, "$apiBase&action=get_vod_streams&category_id=$catId", vodCatMap)
                )
            }
            for ((catId, _) in seriesCatMap.entries.take(5)) {
                allItems.addAll(
                    fetchSeriesStreamsDirect(sourceId, "$apiBase&action=get_series&category_id=$catId", seriesCatMap)
                )
            }
        }

        val liveCnt = allItems.count { it.contentType == IptvContentType.LIVE_TV }
        val movieCnt = allItems.count { it.contentType == IptvContentType.MOVIES }
        val seriesCnt = allItems.count { it.contentType == IptvContentType.SERIES }
        val radioCnt = allItems.count { it.contentType == IptvContentType.RADIO }

        val updatedSource = IptvSource(
            id = sourceId,
            name = sourceName,
            type = IptvSourceType.XTREAM,
            serverOrM3uUrl = baseUrl,
            username = cleanUser,
            encryptedPassword = IptvCredentialVault.encryptSecretOrNull(cleanPass) ?: throw IllegalStateException("Keystore encryption failed"),
            lastUpdatedMs = System.currentTimeMillis(),
            liveCount = liveCnt,
            movieCount = movieCnt,
            seriesCount = seriesCnt,
            radioCount = radioCnt,
            expDateSec = expDateSec,
            accountStatus = statusStr
        )

        Pair(updatedSource, allItems)
    }

    private fun fetchLiveStreamsDirect(
        sourceId: String,
        baseUrl: String,
        cleanUser: String,
        cleanPass: String,
        urlStr: String,
        liveCatMap: Map<String, String>
    ): List<IptvStreamItem> {
        val out = ArrayList<IptvStreamItem>(2048)
        val seen = HashSet<String>(2048)
        try {
            val (rawStream, _) = openRedirectedStream(urlStr, connectTimeoutMs = 6_000, readTimeoutMs = 28_000)
            rawStream.use { s ->
                val jsonStream = skipToStartOfJson(s) ?: return out
                JsonReader(InputStreamReader(jsonStream, Charsets.UTF_8)).apply { isLenient = true }.use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) return out
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                            reader.skipValue()
                            continue
                        }
                        var streamId = ""
                        var name = ""
                        var catId = "0"
                        var logo = ""
                        var streamType = "live"
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "stream_id" -> streamId = nextStringSafe(reader)
                                "name" -> name = nextStringSafe(reader)
                                "category_id" -> catId = nextStringSafe(reader).ifEmpty { "0" }
                                "stream_icon" -> logo = nextStringSafe(reader)
                                "stream_type" -> streamType = nextStringSafe(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (streamId.isNotEmpty()) {
                            val catName = liveCatMap[catId] ?: "Live Channels"
                            val isRadio = streamType.contains("radio", ignoreCase = true) ||
                                catName.contains("radio", ignoreCase = true) ||
                                catName.contains("راديو") ||
                                catName.contains("إذاعة")
                            val cType = if (isRadio) IptvContentType.RADIO else IptvContentType.LIVE_TV
                            val uKey = "${cType.name}_$streamId"
                            if (seen.add(uKey)) {
                                out.add(
                                    IptvStreamItem(
                                        id = "${sourceId}_$uKey",
                                        sourceId = sourceId,
                                        contentType = cType,
                                        categoryId = "${cType.name}_$catId",
                                        categoryName = catName,
                                        title = name.ifBlank { "Channel $streamId" },
                                        streamUrl = "$baseUrl/live/$cleanUser/$cleanPass/$streamId.ts",
                                        logoUrl = logo
                                    )
                                )
                            }
                        }
                    }
                    reader.endArray()
                }
            }
        } catch (e: Throwable) { Log.w("IptvEngine", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        return out
    }

    private fun fetchVodStreamsDirect(
        sourceId: String,
        baseUrl: String,
        cleanUser: String,
        cleanPass: String,
        urlStr: String,
        vodCatMap: Map<String, String>
    ): List<IptvStreamItem> {
        val out = ArrayList<IptvStreamItem>(2048)
        val seen = HashSet<String>(2048)
        try {
            val (rawStream, _) = openRedirectedStream(urlStr, connectTimeoutMs = 6_000, readTimeoutMs = 28_000)
            rawStream.use { s ->
                val jsonStream = skipToStartOfJson(s) ?: return out
                JsonReader(InputStreamReader(jsonStream, Charsets.UTF_8)).apply { isLenient = true }.use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) return out
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                            reader.skipValue()
                            continue
                        }
                        var streamId = ""
                        var name = ""
                        var catId = "0"
                        var logo = ""
                        var rating = ""
                        var ext = "mp4"
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "stream_id" -> streamId = nextStringSafe(reader)
                                "name" -> name = nextStringSafe(reader)
                                "category_id" -> catId = nextStringSafe(reader).ifEmpty { "0" }
                                "stream_icon" -> logo = nextStringSafe(reader)
                                "rating" -> rating = nextStringSafe(reader)
                                "container_extension" -> ext = nextStringSafe(reader).ifBlank { "mp4" }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (streamId.isNotEmpty() && seen.add(streamId)) {
                            val catName = vodCatMap[catId] ?: "Movies"
                            out.add(
                                IptvStreamItem(
                                    id = "${sourceId}_MOVIES_$streamId",
                                    sourceId = sourceId,
                                    contentType = IptvContentType.MOVIES,
                                    categoryId = "MOVIES_$catId",
                                    categoryName = catName,
                                    title = name.ifBlank { "Movie $streamId" },
                                    streamUrl = "$baseUrl/movie/$cleanUser/$cleanPass/$streamId.$ext",
                                    logoUrl = logo,
                                    rating = rating,
                                    containerExtension = ext
                                )
                            )
                        }
                    }
                    reader.endArray()
                }
            }
        } catch (e: Throwable) { Log.w("IptvEngine", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        return out
    }

    private fun fetchSeriesStreamsDirect(
        sourceId: String,
        urlStr: String,
        seriesCatMap: Map<String, String>
    ): List<IptvStreamItem> {
        val out = ArrayList<IptvStreamItem>(2048)
        val seen = HashSet<String>(2048)
        try {
            val (rawStream, _) = openRedirectedStream(urlStr, connectTimeoutMs = 6_000, readTimeoutMs = 28_000)
            rawStream.use { s ->
                val jsonStream = skipToStartOfJson(s) ?: return out
                JsonReader(InputStreamReader(jsonStream, Charsets.UTF_8)).apply { isLenient = true }.use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) return out
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                            reader.skipValue()
                            continue
                        }
                        var seriesId = ""
                        var name = ""
                        var catId = "0"
                        var cover = ""
                        var plot = ""
                        var rating = ""
                        var releaseDate = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "series_id" -> seriesId = nextStringSafe(reader)
                                "name" -> name = nextStringSafe(reader)
                                "category_id" -> catId = nextStringSafe(reader).ifEmpty { "0" }
                                "cover" -> cover = nextStringSafe(reader)
                                "plot" -> plot = nextStringSafe(reader)
                                "rating" -> rating = nextStringSafe(reader)
                                "releaseDate" -> releaseDate = nextStringSafe(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (seriesId.isNotEmpty() && seen.add(seriesId)) {
                            val catName = seriesCatMap[catId] ?: "Series"
                            val finalName = name.ifBlank { "Series $seriesId" }
                            out.add(
                                IptvStreamItem(
                                    id = "${sourceId}_SERIES_$seriesId",
                                    sourceId = sourceId,
                                    contentType = IptvContentType.SERIES,
                                    categoryId = "SERIES_$catId",
                                    categoryName = catName,
                                    title = finalName,
                                    streamUrl = "",
                                    logoUrl = cover,
                                    rating = rating,
                                    plot = plot,
                                    releaseDate = releaseDate,
                                    seriesId = seriesId,
                                    seriesName = finalName
                                )
                            )
                        }
                    }
                    reader.endArray()
                }
            }
        } catch (e: Throwable) { Log.w("IptvEngine", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        return out
    }

    private fun nextStringSafe(reader: JsonReader): String {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                ""
            }
            JsonToken.STRING -> reader.nextString().trim()
            JsonToken.NUMBER -> reader.nextString().trim()
            JsonToken.BOOLEAN -> reader.nextBoolean().toString()
            else -> {
                reader.skipValue()
                ""
            }
        }
    }

    fun fetchSeriesSeasonsAndEpisodes(
        source: IptvSource,
        seriesItem: IptvStreamItem
    ): IptvSeriesInfoResult {
        val baseUrl = normalizeServerBaseUrl(source.serverOrM3uUrl)
        val user = sanitizeCredentialOrUrl(source.username)
        val pass = sanitizeCredentialOrUrl((IptvCredentialVault.decryptSecretOrNull(source.encryptedPassword) ?: ""))
        val uEnc = URLEncoder.encode(user, "UTF-8")
        val pEnc = URLEncoder.encode(pass, "UTF-8")
        val sEnc = URLEncoder.encode(seriesItem.seriesId, "UTF-8")

        val url = "$baseUrl/player_api.php?username=$uEnc&password=$pEnc&action=get_series_info&series_id=$sEnc"
        val raw = httpGetString(url, timeoutMs = 18_000)
        val root = JSONObject(extractCleanJsonString(raw))

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
        val map = LinkedHashMap<String, String>()
        try {
            val raw = httpGetString(urlStr, timeoutMs = 12_000)
            val arr = parseJsonArrayFlexible(extractCleanJsonString(raw))
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("category_id", "")
                val name = obj.optString("category_name", "")
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    map[id] = name
                }
            }
        } catch (e: Exception) { Log.w("IptvEngine", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
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

object LegacyIptvMigrator {
    /**
     * Streams a legacy `streams_<sourceId>.json` file in bounded chunks (default 1,000 items),
     * inserts each chunk via [insertBatch], and verifies both total count via [countInDb]
     * and boundary item existence via [hasStreamIdInDb] before deleting [jsonFile].
     * Returns true ONLY if migration & verification succeeded and the file was safely deleted.
     */
    fun migrateJsonStreamsFileSafely(
        sourceId: String,
        jsonFile: File,
        chunkSize: Int = 1000,
        clearExistingForSource: (String) -> Unit,
        insertBatch: (List<IptvStreamEntity>) -> Unit,
        countInDb: (String) -> Int,
        hasStreamIdInDb: (String, String) -> Boolean
    ): Boolean {
        if (!jsonFile.exists() || !jsonFile.isFile) return false
        return try {
            var expectedCount = 0
            var firstStreamId: String? = null
            var lastStreamId: String? = null
            val batch = ArrayList<IptvStreamEntity>(chunkSize)

            clearExistingForSource(sourceId)

            jsonFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val o = JSONObject(trimmed)
                    val streamId = o.optString("id")
                    val streamUrl = o.optString("u")
                    val title = o.optString("t")
                    if (streamId.isEmpty() || streamUrl.isEmpty()) {
                        throw IllegalArgumentException("Malformed legacy stream JSON entry")
                    }
                    if (firstStreamId == null) firstStreamId = streamId
                    lastStreamId = streamId
                    val item = IptvStreamItem(
                        id = streamId,
                        sourceId = sourceId,
                        contentType = runCatching { IptvContentType.valueOf(o.optString("ct", "LIVE_TV")) }.getOrDefault(IptvContentType.LIVE_TV),
                        categoryId = o.optString("cid"),
                        categoryName = o.optString("cn"),
                        title = title,
                        streamUrl = streamUrl,
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
                    batch.add(IptvStreamEntity.fromDomain(item))
                    expectedCount++
                    if (batch.size >= chunkSize) {
                        insertBatch(ArrayList(batch))
                        batch.clear()
                    }
                }
            }
            if (batch.isNotEmpty()) {
                insertBatch(ArrayList(batch))
                batch.clear()
            }

            if (expectedCount <= 0) return false
            val dbCount = countInDb(sourceId)
            val firstOk = firstStreamId != null && hasStreamIdInDb(sourceId, firstStreamId!!)
            val lastOk = lastStreamId != null && hasStreamIdInDb(sourceId, lastStreamId!!)
            if (dbCount == expectedCount && firstOk && lastOk) {
                jsonFile.delete()
                true
            } else {
                Log.w("LegacyIptvMigrator", "Migration count/integrity mismatch (expected=$expectedCount, actual=$dbCount); preserving JSON file")
                false
            }
        } catch (e: Exception) {
            Log.w("LegacyIptvMigrator", "Legacy JSON migration aborted (${e.javaClass.simpleName}); preserving JSON file")
            false
        }
    }
}

object IptvRepository {
    private const val PREF_IPTV = "pure_player_iptv_store_v1"
    private const val KEY_SOURCES_JSON = "iptv_sources_json_v1"
    private const val KEY_ACTIVE_SOURCE_ID = "iptv_active_source_id"
    private const val KEY_MIGRATED_TO_ROOM_V2 = "iptv_migrated_to_room_v2"
    private const val PREFIX_CAT_OVERRIDES = "iptv_cat_overrides_"
    private const val PREFIX_FAV_IDS = "iptv_fav_ids_"
    private const val PREFIX_RECENT_IDS = "iptv_recent_ordered_csv_"
    private const val PREFIX_SERIES_LAST_EP = "iptv_series_last_ep_"

    const val DEFAULT_PAGE_SIZE = 200

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_IPTV, Context.MODE_PRIVATE)

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "iptv_cache").apply { if (!exists()) mkdirs() }

    private fun dao(context: Context): IptvDao =
        IptvRoomDatabase.getInstance(context).iptvDao()

    @Synchronized
    fun ensureMigratedToRoom(context: Context) {
        val sp = prefs(context)
        val roomDao = dao(context)
        if (!sp.getBoolean(KEY_MIGRATED_TO_ROOM_V2, false)) {
            try {
                // 1. Migrate Sources from SharedPreferences JSON
                val rawSources = sp.getString(KEY_SOURCES_JSON, null)
                val legacySources = ArrayList<IptvSource>()
                if (!rawSources.isNullOrBlank()) {
                    val arr = JSONArray(rawSources)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        legacySources.add(
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
                }
                if (legacySources.isNotEmpty() && roomDao.countSourcesSync() == 0) {
                    roomDao.upsertSourcesSync(legacySources.map { IptvSourceEntity.fromDomain(it) })
                }

                // 2. Migrate Favorites, Recents & Series Last Watched Episode from SharedPreferences
                val allPrefs = sp.all
                val now = System.currentTimeMillis()
                for ((k, v) in allPrefs) {
                    when {
                        k.startsWith(PREFIX_FAV_IDS) && v is Set<*> -> {
                            val sId = k.removePrefix(PREFIX_FAV_IDS)
                            for (itemObj in v) {
                                val itemId = itemObj?.toString() ?: continue
                                if (itemId.isNotBlank()) {
                                    roomDao.insertFavoriteSync(IptvFavoriteEntity(sId, itemId, now))
                                }
                            }
                        }
                        k.startsWith(PREFIX_RECENT_IDS) && v is String -> {
                            val sId = k.removePrefix(PREFIX_RECENT_IDS)
                            val ordered = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            ordered.forEachIndexed { idx, itemId ->
                                roomDao.insertRecentSync(IptvRecentEntity(sId, itemId, now - idx * 1000L))
                            }
                        }
                        k.startsWith(PREFIX_SERIES_LAST_EP) && v is String -> {
                            val rem = k.removePrefix(PREFIX_SERIES_LAST_EP)
                            val sepIdx = rem.indexOf('_')
                            if (sepIdx > 0) {
                                val sId = rem.substring(0, sepIdx)
                                val sKey = rem.substring(sepIdx + 1)
                                val o = JSONObject(v)
                                roomDao.upsertSeriesLastEpSync(
                                    IptvSeriesLastEpEntity(
                                        sourceId = sId,
                                        seriesKey = sKey,
                                        seasonNumber = o.optInt("s", 1),
                                        episodeNumber = o.optInt("e", 1),
                                        episodeTitle = o.optString("t", ""),
                                        streamUrl = o.optString("u", ""),
                                        updatedMs = now
                                    )
                                )
                            }
                        }
                    }
                }

                if (roomDao.countSourcesSync() >= legacySources.size) {
                    sp.edit().putBoolean(KEY_MIGRATED_TO_ROOM_V2, true).apply()
                }
            } catch (e: Exception) {
                Log.w("IptvRepository", "Metadata migration to Room deferred: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun ensureSourceStreamsMigrated(context: Context, sourceId: String) {
        if (sourceId.isBlank()) return
        val roomDao = dao(context)
        val jsonFile = File(cacheDir(context), "streams_$sourceId.json")
        if (jsonFile.exists()) {
            LegacyIptvMigrator.migrateJsonStreamsFileSafely(
                sourceId = sourceId,
                jsonFile = jsonFile,
                chunkSize = 1000,
                clearExistingForSource = { sId -> roomDao.deleteStreamsForSourceSync(sId) },
                insertBatch = { batch -> roomDao.insertStreamsBatchSync(batch) },
                countInDb = { sId -> roomDao.countStreamsForSourceSync(sId) },
                hasStreamIdInDb = { sId, stId -> roomDao.hasStreamIdSync(sId, stId) }
            )
        }
    }

    fun getSources(context: Context): List<IptvSource> {
        ensureMigratedToRoom(context)
        return try {
            dao(context).getAllSourcesSync().map { it.toDomain() }
        } catch (e: Exception) {
            Log.w("IptvRepository", "getSources failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun saveSourcesList(context: Context, sources: List<IptvSource>) {
        try {
            val roomDao = dao(context)
            roomDao.clearAllSourcesSync()
            roomDao.upsertSourcesSync(sources.map { IptvSourceEntity.fromDomain(it) })
        } catch (e: Exception) {
            Log.w("IptvRepository", "saveSourcesList failed: ${e.javaClass.simpleName}")
        }
    }

    fun isFavoriteStream(context: Context, sourceId: String, itemId: String): Boolean {
        ensureMigratedToRoom(context)
        return try {
            dao(context).isFavoriteSync(sourceId, itemId)
        } catch (e: Exception) {
            Log.w("IptvRepository", "isFavoriteStream failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun toggleFavoriteStream(context: Context, sourceId: String, itemId: String): Boolean {
        ensureMigratedToRoom(context)
        return try {
            val roomDao = dao(context)
            val currentlyFav = roomDao.isFavoriteSync(sourceId, itemId)
            if (currentlyFav) {
                roomDao.deleteFavoriteSync(sourceId, itemId)
                false
            } else {
                roomDao.insertFavoriteSync(IptvFavoriteEntity(sourceId, itemId, System.currentTimeMillis()))
                true
            }
        } catch (e: Exception) {
            Log.w("IptvRepository", "toggleFavoriteStream failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun countFavoritesSql(context: Context, sourceId: String, contentType: IptvContentType? = null): Int {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        return try {
            dao(context).countFavoritesByTypeSync(sourceId, contentType?.name)
        } catch (e: Exception) {
            Log.w("IptvRepository", "countFavoritesSql failed: ${e.javaClass.simpleName}")
            0
        }
    }

    fun getFavoriteItems(
        context: Context,
        sourceId: String,
        contentType: IptvContentType? = null
    ): List<IptvStreamItem> {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        return try {
            dao(context).getFavoriteStreamsSqlSync(sourceId, contentType?.name, limit = 500).map { it.toDomain() }
        } catch (e: Exception) {
            Log.w("IptvRepository", "getFavoriteItems failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    fun recordRecentWatchedItem(context: Context, sourceId: String, itemId: String) {
        if (sourceId.isBlank() || itemId.isBlank()) return
        ensureMigratedToRoom(context)
        try {
            val roomDao = dao(context)
            roomDao.insertRecentSync(IptvRecentEntity(sourceId, itemId, System.currentTimeMillis()))
            roomDao.trimRecentsSync(sourceId)
        } catch (e: Exception) {
            Log.w("IptvRepository", "recordRecentWatchedItem failed: ${e.javaClass.simpleName}")
        }
    }

    fun countRecentsSql(context: Context, sourceId: String, contentType: IptvContentType? = null): Int {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        return try {
            dao(context).countRecentsByTypeSync(sourceId, contentType?.name)
        } catch (e: Exception) {
            Log.w("IptvRepository", "countRecentsSql failed: ${e.javaClass.simpleName}")
            0
        }
    }

    fun getRecentWatchedItems(
        context: Context,
        sourceId: String,
        contentType: IptvContentType? = null
    ): List<IptvStreamItem> {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        return try {
            dao(context).getRecentStreamsSqlSync(sourceId, contentType?.name, limit = 100).map { it.toDomain() }
        } catch (e: Exception) {
            Log.w("IptvRepository", "getRecentWatchedItems failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    fun saveLastWatchedSeriesEpisode(
        context: Context,
        sourceId: String,
        seriesKey: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String,
        streamUrl: String
    ) {
        if (seriesKey.isBlank()) return
        ensureMigratedToRoom(context)
        try {
            dao(context).upsertSeriesLastEpSync(
                IptvSeriesLastEpEntity(
                    sourceId = sourceId,
                    seriesKey = seriesKey,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    streamUrl = streamUrl,
                    updatedMs = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.w("IptvRepository", "saveLastWatchedSeriesEpisode failed: ${e.javaClass.simpleName}")
        }
    }

    fun getLastWatchedSeriesEpisodeLabel(
        context: Context,
        sourceId: String,
        seriesKey: String
    ): String? {
        if (seriesKey.isBlank()) return null
        ensureMigratedToRoom(context)
        return try {
            val row = dao(context).getSeriesLastEpSync(sourceId, seriesKey) ?: return null
            "S${String.format(Locale.US, "%02d", row.seasonNumber)}E${String.format(Locale.US, "%02d", row.episodeNumber)}"
        } catch (e: Exception) {
            Log.w("IptvRepository", "getLastWatchedSeriesEpisodeLabel failed: ${e.javaClass.simpleName}")
            null
        }
    }

    fun getLastWatchedSeriesEpisodeData(
        context: Context,
        sourceId: String,
        seriesKey: String
    ): Triple<Int, Int, String>? {
        if (seriesKey.isBlank()) return null
        ensureMigratedToRoom(context)
        return try {
            val row = dao(context).getSeriesLastEpSync(sourceId, seriesKey) ?: return null
            Triple(row.seasonNumber, row.episodeNumber, row.streamUrl)
        } catch (e: Exception) {
            Log.w("IptvRepository", "getLastWatchedSeriesEpisodeData failed: ${e.javaClass.simpleName}")
            null
        }
    }

    fun getActiveSourceId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_SOURCE_ID, null)

    fun setActiveSourceId(context: Context, sourceId: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_SOURCE_ID, sourceId).apply()
    }

    fun deleteSource(context: Context, sourceId: String) {
        ensureMigratedToRoom(context)
        try {
            dao(context).deleteAllDataForSourceSync(sourceId)
            File(cacheDir(context), "streams_$sourceId.json").delete()
        } catch (e: Exception) {
            Log.w("IptvRepository", "deleteSource failed: ${e.javaClass.simpleName}")
        }
        val remaining = getSources(context)
        if (getActiveSourceId(context) == sourceId) {
            setActiveSourceId(context, remaining.firstOrNull()?.id)
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
        } catch (e: Exception) {
            Log.w("IptvRepository", "getCategoryOverrides failed: ${e.javaClass.simpleName}")
            emptyMap()
        }
    }

    fun setCategoryOverride(
        context: Context,
        sourceId: String,
        categoryName: String,
        newType: IptvContentType
    ) {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        val current = getCategoryOverrides(context, sourceId).toMutableMap()
        current[categoryName.lowercase(Locale.ROOT)] = newType
        val obj = JSONObject()
        for ((k, v) in current) {
            obj.put(k, v.name)
        }
        prefs(context).edit().putString(PREFIX_CAT_OVERRIDES + sourceId, obj.toString()).apply()

        try {
            val newCatId = "${newType.name}_${categoryName.lowercase(Locale.ROOT).hashCode()}"
            dao(context).updateCategoryContentTypeSync(sourceId, categoryName, newType.name, newCatId)
            updateSourceCountsFromSql(context, sourceId)
        } catch (e: Exception) {
            Log.w("IptvRepository", "setCategoryOverride SQL update failed: ${e.javaClass.simpleName}")
        }
    }

    private fun updateSourceCountsFromSql(context: Context, sourceId: String) {
        val roomDao = dao(context)
        val countsMap = getSectionCountsSql(context, sourceId)
        val sources = getSources(context).toMutableList()
        val idx = sources.indexOfFirst { it.id == sourceId }
        if (idx != -1) {
            val old = sources[idx]
            val seriesCnt = if (old.type == IptvSourceType.XTREAM) {
                countsMap[IptvContentType.SERIES] ?: 0
            } else {
                roomDao.countDistinctSeriesSync(sourceId)
            }
            sources[idx] = old.copy(
                liveCount = countsMap[IptvContentType.LIVE_TV] ?: 0,
                movieCount = countsMap[IptvContentType.MOVIES] ?: 0,
                seriesCount = seriesCnt,
                radioCount = countsMap[IptvContentType.RADIO] ?: 0
            )
            saveSourcesList(context, sources)
        }
    }

    fun getSectionCountsSql(context: Context, sourceId: String): Map<IptvContentType, Int> {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        return try {
            val rows = dao(context).getContentTypeCountsSync(sourceId)
            val map = EnumMap<IptvContentType, Int>(IptvContentType::class.java)
            for (r in rows) {
                val ct = runCatching { IptvContentType.valueOf(r.contentType) }.getOrNull() ?: continue
                map[ct] = r.cnt
            }
            map
        } catch (e: Exception) {
            Log.w("IptvRepository", "getSectionCountsSql failed: ${e.javaClass.simpleName}")
            emptyMap()
        }
    }

    fun getCategoriesForTypeSql(
        context: Context,
        sourceId: String,
        contentType: IptvContentType,
        allCategoryLabel: String
    ): List<IptvCategory> {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        return try {
            val roomDao = dao(context)
            val rows = roomDao.getCategoriesForTypeSync(sourceId, contentType.name)
            if (rows.isEmpty()) return emptyList()
            val totalInType = rows.sumOf { it.cnt }
            val result = ArrayList<IptvCategory>(rows.size + 1)
            result.add(
                IptvCategory(
                    id = "__ALL__",
                    name = "🌐  $allCategoryLabel",
                    contentType = contentType,
                    itemCount = totalInType
                )
            )
            for (r in rows) {
                if (r.cnt > 0) {
                    result.add(
                        IptvCategory(
                            id = r.categoryId,
                            name = r.categoryName,
                            contentType = contentType,
                            itemCount = r.cnt
                        )
                    )
                }
            }
            result
        } catch (e: Exception) {
            Log.w("IptvRepository", "getCategoriesForTypeSql failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    fun getStreamsPageSql(
        context: Context,
        sourceId: String,
        contentType: IptvContentType,
        categoryId: String?,
        categoryName: String,
        groupM3uSeries: Boolean,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): List<IptvStreamItem> {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        return try {
            val isAll = if (categoryId == "__ALL__" || categoryId.isNullOrEmpty()) 1 else 0
            val roomDao = dao(context)
            val entities = if (groupM3uSeries && contentType == IptvContentType.SERIES) {
                roomDao.getGroupedM3uSeriesPageSync(sourceId, isAll, categoryName, categoryId ?: "", limit, offset)
            } else {
                roomDao.getStreamsPageSync(sourceId, contentType.name, isAll, categoryName, categoryId ?: "", limit, offset)
            }
            entities.map { it.toDomain() }
        } catch (e: Exception) {
            Log.w("IptvRepository", "getStreamsPageSql failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /**
     * Paging 3 Flow backed directly by Room's @Dao PagingSource.
     */
    fun createStreamsPagerFlow(
        context: Context,
        sourceId: String,
        contentType: IptvContentType,
        categoryName: String?
    ): Flow<PagingData<IptvStreamItem>> {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        val roomDao = dao(context)
        return Pager(
            config = PagingConfig(pageSize = 120, prefetchDistance = 40, enablePlaceholders = false),
            pagingSourceFactory = {
                if (categoryName.isNullOrBlank() || categoryName == "__ALL__") {
                    roomDao.pagingAllStreamsByType(sourceId, contentType.name)
                } else {
                    roomDao.pagingStreamsByCategory(sourceId, contentType.name, categoryName)
                }
            }
        ).flow.map { pagingData -> pagingData.map { it.toDomain() } }
    }

    fun getM3uSeriesSeasonsSql(
        context: Context,
        sourceId: String,
        seriesItem: IptvStreamItem
    ): List<IptvSeasonGroup> {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        return try {
            val sName = seriesItem.seriesName.ifBlank { seriesItem.title }
            val allEps = dao(context).getSeriesEpisodesSync(sourceId, sName, "$sName%").map { it.toDomain() }
            allEps.groupBy { it.seasonNumber.coerceAtLeast(1) }
                .entries
                .sortedBy { it.key }
                .map { (sNum, epList) ->
                    IptvSeasonGroup(
                        seasonNumber = sNum,
                        name = "Season $sNum / الموسم $sNum",
                        episodes = epList.sortedBy { it.episodeNumber }.mapIndexed { idx, ep ->
                            IptvEpisodeItem(
                                id = ep.id,
                                title = ep.title,
                                seasonNumber = sNum,
                                episodeNumber = if (ep.episodeNumber > 0) ep.episodeNumber else idx + 1,
                                streamUrl = ep.streamUrl,
                                durationLabel = ep.durationLabel,
                                httpUserAgent = ep.httpUserAgent,
                                httpReferrer = ep.httpReferrer
                            )
                        }
                    )
                }
        } catch (e: Exception) {
            Log.w("IptvRepository", "getM3uSeriesSeasonsSql failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    fun searchStreamsSql(context: Context, sourceId: String, normQuery: String, limit: Int = 400): List<IptvStreamItem> {
        ensureMigratedToRoom(context)
        ensureSourceStreamsMigrated(context, sourceId)
        return try {
            val roomDao = dao(context)
            val rows = if (normQuery.isBlank()) {
                roomDao.getInitialStreamsBoundedSync(sourceId, limit)
            } else {
                roomDao.searchStreamsBoundedSync(sourceId, "%$normQuery%", limit)
            }
            rows.map { it.toDomain() }
        } catch (e: Exception) {
            Log.w("IptvRepository", "searchStreamsSql failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun saveStreamsToRoom(context: Context, sourceId: String, items: List<IptvStreamItem>) {
        try {
            val entities = items.map { IptvStreamEntity.fromDomain(it) }
            dao(context).replaceAllStreamsForSourceSync(sourceId, entities)
        } catch (e: Exception) {
            Log.w("IptvRepository", "saveStreamsToRoom failed: ${e.javaClass.simpleName}")
        }
    }

    suspend fun syncAndSaveSource(
        context: Context,
        existingSourceId: String? = null,
        name: String,
        type: IptvSourceType,
        serverOrM3uUrl: String,
        username: String = "",
        passwordPlain: String = ""
    ): IptvSyncResult = withContext(Dispatchers.IO) {
        ensureMigratedToRoom(context)
        XtreamCodesClient.allowUntrustedSslFallback = PlaybackStore.isAllowUntrustedSsl(context)
        val sourceId = existingSourceId ?: UUID.randomUUID().toString()
        val overrides = getCategoryOverrides(context, sourceId)

        try {
            val (updatedSource, items) = when (type) {
                IptvSourceType.XTREAM -> {
                    // Verify KeyStore encryption works BEFORE overwriting existing credentials
                    if (IptvCredentialVault.encryptSecretOrNull(passwordPlain) == null) {
                        throw IllegalStateException("KEYSTORE_ENCRYPT_FAILED")
                    }
                    XtreamCodesClient.fetchXtreamSourceContent(
                        sourceId = sourceId,
                        sourceName = name.ifBlank { "Xtream IPTV" },
                        rawServerUrl = serverOrM3uUrl,
                        username = username,
                        passwordPlain = passwordPlain
                    )
                }
                IptvSourceType.M3U_URL -> {
                    val rawClean = XtreamCodesClient.sanitizeCredentialOrUrl(serverOrM3uUrl)
                    val candidateUrls = if (rawClean.startsWith("http://", ignoreCase = true) || rawClean.startsWith("https://", ignoreCase = true)) {
                        listOf(rawClean)
                    } else {
                        listOf("http://$rawClean", "https://$rawClean")
                    }
                    var parsed: List<IptvStreamItem> = emptyList()
                    var resolvedUrl = candidateUrls.first()
                    for (cand in candidateUrls) {
                        try {
                            val (body, finalU) = XtreamCodesClient.httpRequestWithRedirects(cand, timeoutMs = 22_000)
                            val list = body.byteInputStream(Charsets.UTF_8).bufferedReader(Charsets.UTF_8).use { reader ->
                                M3uPlaylistParser.parseFromReader(sourceId, reader, overrides)
                            }
                            if (list.isNotEmpty()) {
                                parsed = list
                                resolvedUrl = finalU
                                break
                            }
                        } catch (e: Exception) {
                            Log.w("IptvRepository", "M3U candidate fetch failed: ${e.javaClass.simpleName}")
                        }
                    }
                    if (parsed.isEmpty()) {
                        throw IllegalStateException("EMPTY_PLAYLIST")
                    }
                    val src = buildM3uSourceSummary(sourceId, name.ifBlank { "M3U Playlist" }, IptvSourceType.M3U_URL, resolvedUrl, parsed)
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

            saveStreamsToRoom(context, sourceId, items)
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
        } catch (t: Throwable) {
            val code = t.message ?: t.javaClass.simpleName
            val friendlyMsg = when {
                code == "KEYSTORE_ENCRYPT_FAILED" -> context.getString(R.string.iptv_err_keystore_encryption)
                code == "AUTH_FAILED" -> context.getString(R.string.iptv_err_auth_failed)
                code == "ACCOUNT_EXPIRED" -> context.getString(R.string.iptv_err_account_expired)
                code.startsWith("ACCOUNT_") -> context.getString(R.string.iptv_err_account_inactive, code.removePrefix("ACCOUNT_"))
                code == "EMPTY_PLAYLIST" -> context.getString(R.string.iptv_err_empty_playlist)
                code == "FILE_READ_ERROR" -> context.getString(R.string.iptv_err_file_read)
                code.startsWith("CONN_DETAIL:") -> {
                    val detail = code.removePrefix("CONN_DETAIL:")
                    "${context.getString(R.string.iptv_err_connection)}\\n($detail)"
                }
                else -> "${context.getString(R.string.iptv_err_connection)}\\n($code)"
            }
            IptvSyncResult.Error(friendlyMsg, code)
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
