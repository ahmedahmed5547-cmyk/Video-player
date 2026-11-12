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
import android.util.JsonToken
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
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
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
        if (normalizedQuery.isEmpty()) return true
        val normTitle = normalize(targetTitle)
        if (normTitle.contains(normalizedQuery)) return true
        val normCat = normalize(targetCategory)
        return normCat.contains(normalizedQuery)
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
                } catch (_: Exception) {}
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
            } catch (_: Exception) {}
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
                    val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                        configurePermissiveSsl(this)
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
                        conn.outputStream.use { os ->
                            os.write(postPayload.toByteArray(Charsets.UTF_8))
                        }
                    }

                    val code = conn.responseCode
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
                            encryptedPassword = IptvCredentialVault.encryptSecret(cleanPass),
                            lastUpdatedMs = System.currentTimeMillis(),
                            liveCount = parsed.count { it.contentType == IptvContentType.LIVE_TV },
                            movieCount = parsed.count { it.contentType == IptvContentType.MOVIES },
                            seriesCount = parsed.filter { it.contentType == IptvContentType.SERIES }.map { it.seriesName.ifEmpty { it.title } }.distinct().size,
                            radioCount = parsed.count { it.contentType == IptvContentType.RADIO },
                            accountStatus = "Active"
                        )
                        return@withContext Pair(src, parsed)
                    }
                } catch (_: Exception) {}
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
            encryptedPassword = IptvCredentialVault.encryptSecret(cleanPass),
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
        } catch (_: Throwable) {}
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
        } catch (_: Throwable) {}
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
        } catch (_: Throwable) {}
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
        val pass = sanitizeCredentialOrUrl(IptvCredentialVault.decryptSecret(source.encryptedPassword))
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

object IptvRepository {
        private const val PREF_IPTV = "pure_player_iptv_store_v1"
    private const val KEY_SOURCES_JSON = "iptv_sources_json_v1"
    private const val KEY_ACTIVE_SOURCE_ID = "iptv_active_source_id"
    private const val PREFIX_CAT_OVERRIDES = "iptv_cat_overrides_"
    private const val PREFIX_FAV_IDS = "iptv_fav_ids_"
    private const val PREFIX_RECENT_IDS = "iptv_recent_ordered_csv_"
    private const val PREFIX_SERIES_LAST_EP = "iptv_series_last_ep_" 

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


    fun isFavoriteStream(context: Context, sourceId: String, itemId: String): Boolean {
        val set = prefs(context).getStringSet(PREFIX_FAV_IDS + sourceId, emptySet()) ?: emptySet()
        return set.contains(itemId)
    }

    fun toggleFavoriteStream(context: Context, sourceId: String, itemId: String): Boolean {
        val p = prefs(context)
        val current = p.getStringSet(PREFIX_FAV_IDS + sourceId, emptySet())?.toMutableSet() ?: mutableSetOf()
        val nowFav = if (current.contains(itemId)) {
            current.remove(itemId)
            false
        } else {
            current.add(itemId)
            true
        }
        p.edit().putStringSet(PREFIX_FAV_IDS + sourceId, current).apply()
        return nowFav
    }

    fun getFavoriteItems(
        context: Context,
        sourceId: String,
        contentType: IptvContentType? = null
    ): List<IptvStreamItem> {
        val favIds = prefs(context).getStringSet(PREFIX_FAV_IDS + sourceId, emptySet()) ?: emptySet()
        if (favIds.isEmpty()) return emptyList()
        return loadCachedStreams(context, sourceId).filter { item ->
            item.id in favIds && (contentType == null || item.contentType == contentType)
        }
    }

    fun recordRecentWatchedItem(context: Context, sourceId: String, itemId: String) {
        if (sourceId.isBlank() || itemId.isBlank()) return
        val p = prefs(context)
        val rawCsv = p.getString(PREFIX_RECENT_IDS + sourceId, "") ?: ""
        val currentList = rawCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != itemId }.toMutableList()
        currentList.add(0, itemId)
        val trimmed = currentList.take(80).joinToString(",")
        p.edit().putString(PREFIX_RECENT_IDS + sourceId, trimmed).apply()
    }

    fun getRecentWatchedItems(
        context: Context,
        sourceId: String,
        contentType: IptvContentType? = null
    ): List<IptvStreamItem> {
        val rawCsv = prefs(context).getString(PREFIX_RECENT_IDS + sourceId, "") ?: ""
        if (rawCsv.isBlank()) return emptyList()
        val orderedIds = rawCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (orderedIds.isEmpty()) return emptyList()
        val allById = loadCachedStreams(context, sourceId).associateBy { it.id }
        return orderedIds.mapNotNull { id ->
            val item = allById[id]
            if (item != null && (contentType == null || item.contentType == contentType)) item else null
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
        val obj = JSONObject().apply {
            put("s", seasonNumber)
            put("e", episodeNumber)
            put("t", episodeTitle)
            put("u", streamUrl)
        }
        prefs(context).edit()
            .putString("${PREFIX_SERIES_LAST_EP}${sourceId}_$seriesKey", obj.toString())
            .apply()
    }

    fun getLastWatchedSeriesEpisodeLabel(
        context: Context,
        sourceId: String,
        seriesKey: String
    ): String? {
        if (seriesKey.isBlank()) return null
        val raw = prefs(context).getString("${PREFIX_SERIES_LAST_EP}${sourceId}_$seriesKey", null) ?: return null
        return try {
            val o = JSONObject(raw)
            val s = o.optInt("s", 1)
            val e = o.optInt("e", 1)
            "S${String.format(Locale.US, "%02d", s)}E${String.format(Locale.US, "%02d", e)}"
        } catch (_: Exception) {
            null
        }
    }

    fun getLastWatchedSeriesEpisodeData(
        context: Context,
        sourceId: String,
        seriesKey: String
    ): Triple<Int, Int, String>? {
        if (seriesKey.isBlank()) return null
        val raw = prefs(context).getString("${PREFIX_SERIES_LAST_EP}${sourceId}_$seriesKey", null) ?: return null
        return try {
            val o = JSONObject(raw)
            Triple(o.optInt("s", 1), o.optInt("e", 1), o.optString("u", ""))
        } catch (_: Exception) {
            null
        }
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
                            val items = body.byteInputStream(Charsets.UTF_8).bufferedReader(Charsets.UTF_8).use { reader ->
                                M3uPlaylistParser.parseFromReader(sourceId, reader, overrides)
                            }
                            if (items.isNotEmpty()) {
                                parsed = items
                                resolvedUrl = finalU
                                break
                            }
                        } catch (_: Exception) {}
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
        } catch (t: Throwable) {
            val code = t.message ?: t.javaClass.simpleName
            val friendlyMsg = when {
                code == "AUTH_FAILED" -> context.getString(R.string.iptv_err_auth_failed)
                code == "ACCOUNT_EXPIRED" -> context.getString(R.string.iptv_err_account_expired)
                code.startsWith("ACCOUNT_") -> context.getString(R.string.iptv_err_account_inactive, code.removePrefix("ACCOUNT_"))
                code == "EMPTY_PLAYLIST" -> context.getString(R.string.iptv_err_empty_playlist)
                code == "FILE_READ_ERROR" -> context.getString(R.string.iptv_err_file_read)
                code.startsWith("CONN_DETAIL:") -> {
                    val detail = code.removePrefix("CONN_DETAIL:")
                    "${context.getString(R.string.iptv_err_connection)}\n($detail)"
                }
                else -> "${context.getString(R.string.iptv_err_connection)}\n($code)"
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
