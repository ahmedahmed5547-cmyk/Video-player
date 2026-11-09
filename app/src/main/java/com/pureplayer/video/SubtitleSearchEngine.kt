package com.pureplayer.video

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

enum class MatchQuality(val score: Int) {
    HASH_MATCH(100),
    EXACT_TITLE(75),
    FUZZY_TITLE(40)
}

data class SubtitleSearchRequest(
    val rawTitle: String,
    val cleanQuery: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val movieHash: String?,
    val fileByteSize: Long,
    val preferredLanguages: List<String>
)

data class SubtitleResultItem(
    val id: String,
    val title: String,
    val providerName: String,
    val languageCode: String,
    val downloadUrl: String,
    val formatExt: String,
    val matchQuality: MatchQuality,
    val rating: Double,
    val downloadCount: Int
)

sealed class SubtitleSearchOutcome {
    data class Success(
        val results: List<SubtitleResultItem>,
        val autoAppliedFile: File?,
        val autoAppliedCues: List<SubtitleCue>,
        val bestItem: SubtitleResultItem
    ) : SubtitleSearchOutcome()

    data class Error(val errorType: ErrorType) : SubtitleSearchOutcome()

    enum class ErrorType {
        NO_INTERNET,
        TIMEOUT,
        RATE_LIMIT,
        CORRUPTED_FILE,
        NOT_FOUND
    }
}

/**
 * Common interface for all subtitle providers so new providers can be plugged in effortlessly.
 */
interface SubtitleProvider {
    val name: String
    suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem>
}

/**
 * Provider 1: OpenSubtitles REST / Legacy JSON Provider (supports 64-bit OpenSubtitles File Hash + Query)
 */
class OpenSubtitlesProvider : SubtitleProvider {
    override val name: String = "OpenSubtitles"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val iso3Map = mapOf(
            "ar" to "ara", "en" to "eng", "fr" to "fre",
            "es" to "spa", "tr" to "tur", "hi" to "hin", "ur" to "urd"
        )

        for (lang in request.preferredLanguages) {
            val subLangId = iso3Map[lang] ?: "eng"
            val encodedQuery = URLEncoder.encode(request.cleanQuery, "UTF-8")
            val endpoints = mutableListOf<Pair<String, MatchQuality>>()

            if (!request.movieHash.isNullOrEmpty() && request.fileByteSize > 0) {
                endpoints.add(
                    "https://rest.opensubtitles.org/search/moviebytesize-${request.fileByteSize}/moviehash-${request.movieHash}/sublanguageid-$subLangId" to MatchQuality.HASH_MATCH
                )
            }
            endpoints.add(
                "https://rest.opensubtitles.org/search/query-$encodedQuery/sublanguageid-$subLangId" to MatchQuality.EXACT_TITLE
            )

            for ((urlStr, quality) in endpoints) {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "TemporaryUserAgent")
                    if (BuildConfig.OPENSUBTITLES_API_KEY.isNotEmpty()) {
                        setRequestProperty("Api-Key", BuildConfig.OPENSUBTITLES_API_KEY)
                    }
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                val code = conn.responseCode
                if (code == 429) throw IllegalStateException("RATE_LIMIT")
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (body.trim().startsWith("[")) {
                        val arr = JSONArray(body)
                        for (i in 0 until minOf(arr.length(), 10)) {
                            val obj = arr.getJSONObject(i)
                            val dlUrl = obj.optString("SubDownloadLink").ifEmpty { obj.optString("ZipDownloadLink") }
                            val fileName = obj.optString("SubFileName", "${request.cleanQuery}.srt")
                            val fmt = obj.optString("SubFormat", "srt")
                            val downloads = obj.optString("SubDownloadsCnt", "0").toIntOrNull() ?: 0
                            val score = obj.optString("SubRating", "0.0").toDoubleOrNull() ?: 0.0
                            if (dlUrl.isNotEmpty()) {
                                results.add(
                                    SubtitleResultItem(
                                        id = "os_${obj.optString("IDSubtitleFile", i.toString())}",
                                        title = fileName,
                                        providerName = name,
                                        languageCode = lang,
                                        downloadUrl = dlUrl,
                                        formatExt = fmt,
                                        matchQuality = quality,
                                        rating = score,
                                        downloadCount = downloads
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        results
    }
}

/**
 * Provider 2: SubDL API & Public Index Provider
 */
class SubdlProvider : SubtitleProvider {
    override val name: String = "SubDL"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val encodedQuery = URLEncoder.encode(request.cleanQuery, "UTF-8")
        val langCsv = request.preferredLanguages.joinToString(",") { it.uppercase(Locale.ROOT) }
        val apiKey = BuildConfig.SUBDL_API_KEY

        val apiUrl = if (apiKey.isNotEmpty()) {
            "https://api.subdl.com/api/v1/subtitles?api_key=$apiKey&film_name=$encodedQuery&languages=$langCsv&subs_per_page=10"
        } else {
            "https://api.subdl.com/api/v1/subtitles?film_name=$encodedQuery&languages=$langCsv"
        }

        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "PureVideoPlayer/7.0")
            connectTimeout = 6000
            readTimeout = 6000
        }

        if (conn.responseCode == 429) throw IllegalStateException("RATE_LIMIT")
        if (conn.responseCode in 200..299) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.trim().startsWith("{")) {
                val root = JSONObject(body)
                val subs = root.optJSONArray("subtitles") ?: JSONArray()
                for (i in 0 until minOf(subs.length(), 10)) {
                    val sub = subs.getJSONObject(i)
                    val rawUrl = sub.optString("url")
                    val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "https://dl.subdl.com$rawUrl"
                    val lang = sub.optString("language", request.preferredLanguages.firstOrNull() ?: "ar").lowercase(Locale.ROOT).take(2)
                    val releaseName = sub.optString("release_name", request.cleanQuery)
                    if (rawUrl.isNotEmpty()) {
                        results.add(
                            SubtitleResultItem(
                                id = "subdl_$i",
                                title = releaseName,
                                providerName = name,
                                languageCode = lang,
                                downloadUrl = fullUrl,
                                formatExt = "zip",
                                matchQuality = if (releaseName.contains(request.cleanQuery, ignoreCase = true)) MatchQuality.EXACT_TITLE else MatchQuality.FUZZY_TITLE,
                                rating = 9.0,
                                downloadCount = 500 - (i * 20)
                            )
                        )
                    }
                }
            }
        }
        results
    }
}

/**
 * Provider 3: Podnapisi XML/JSON Public Search Provider
 */
class PodnapisiProvider : SubtitleProvider {
    override val name: String = "Podnapisi"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val encodedQuery = URLEncoder.encode(request.cleanQuery, "UTF-8")

        for (lang in request.preferredLanguages) {
            val urlStr = "https://www.podnapisi.net/subtitles/search/old?sXML=1&sL=$lang&sK=$encodedQuery"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PureVideoPlayer/7.0")
                setRequestProperty("Accept", "application/xml, text/xml")
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode == 429) throw IllegalStateException("RATE_LIMIT")
            if (conn.responseCode in 200..299) {
                val xml = conn.inputStream.bufferedReader().use { it.readText() }
                val pidRegex = Regex("<pid>([^<]+)</pid>")
                val titleRegex = Regex("<title>([^<]+)</title>")
                val releaseRegex = Regex("<release>([^<]*)</release>")
                val downloadsRegex = Regex("<downloads>(\\d+)</downloads>")

                val pids = pidRegex.findAll(xml).map { it.groupValues[1] }.toList()
                val titles = titleRegex.findAll(xml).map { it.groupValues[1] }.toList()
                val releases = releaseRegex.findAll(xml).map { it.groupValues[1] }.toList()
                val downloads = downloadsRegex.findAll(xml).map { it.groupValues[1].toIntOrNull() ?: 0 }.toList()

                for (i in 0 until minOf(pids.size, 8)) {
                    val pid = pids[i]
                    val t = releases.getOrNull(i)?.ifBlank { titles.getOrNull(i) } ?: request.cleanQuery
                    val dlCount = downloads.getOrNull(i) ?: 50
                    results.add(
                        SubtitleResultItem(
                            id = "pod_$pid",
                            title = t,
                            providerName = name,
                            languageCode = lang,
                            downloadUrl = "https://www.podnapisi.net/subtitles/$pid/download",
                            formatExt = "zip",
                            matchQuality = MatchQuality.EXACT_TITLE,
                            rating = 8.5,
                            downloadCount = dlCount
                        )
                    )
                }
            }
        }
        results
    }
}

/**
 * Provider 4: Addic7ed / Gestdown Community Series & Movie Subtitle Provider
 */
class Addic7edCommunityProvider : SubtitleProvider {
    override val name: String = "Addic7ed-Community"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val encodedQuery = URLEncoder.encode(request.cleanQuery, "UTF-8")
        val urlStr = "https://api.gestdown.info/shows/search/$encodedQuery"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "PureVideoPlayer/7.0")
            connectTimeout = 5000
            readTimeout = 5000
        }
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.trim().startsWith("{")) {
                val json = JSONObject(body)
                val shows = json.optJSONArray("shows")
                if (shows != null && shows.length() > 0) {
                    val show = shows.getJSONObject(0)
                    val showId = show.optString("id")
                    val showName = show.optString("name", request.cleanQuery)
                    val season = request.season ?: 1
                    val ep = request.episode ?: 1
                    for (lang in request.preferredLanguages) {
                        results.add(
                            SubtitleResultItem(
                                id = "addic_${showId}_${season}_${ep}_$lang",
                                title = "$showName S${season}E${ep}",
                                providerName = name,
                                languageCode = lang,
                                downloadUrl = "https://api.gestdown.info/subtitles/get/$showId/$season/$ep/$lang",
                                formatExt = "srt",
                                matchQuality = MatchQuality.FUZZY_TITLE,
                                rating = 8.0,
                                downloadCount = 100
                            )
                        )
                    }
                }
            }
        }
        results
    }
}

object SubtitleSearchEngine {

    private val providers: List<SubtitleProvider> = listOf(
        OpenSubtitlesProvider(),
        SubdlProvider(),
        PodnapisiProvider(),
        Addic7edCommunityProvider()
    )

    /**
     * Cleans video file name of resolution, codec, audio, and release-group noise,
     * and extracts year, season, and episode when present.
     */
    fun buildSearchRequest(
        context: Context,
        videoUri: Uri,
        rawTitle: String,
        preferredLanguages: List<String>
    ): SubtitleSearchRequest {
        val withoutExt = rawTitle.substringBeforeLast(".")
        val sxeMatch = Regex("[Ss](\\d{1,2})[Ee](\\d{1,2})").find(withoutExt)
        val season = sxeMatch?.groupValues?.get(1)?.toIntOrNull()
        val episode = sxeMatch?.groupValues?.get(2)?.toIntOrNull()

        val yearMatch = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(withoutExt)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val cleaned = withoutExt
            .replace(Regex("[._\\-\\[\\]()]"), " ")
            .replace(
                Regex(
                    "\\b(2160p|1080p|720p|480p|4K|8K|UHD|HDR|HDR10|DV|Dolby|BluRay|BRRip|BDRip|WEB|WEBDL|WEBRip|HDTV|x264|x265|H264|H265|HEVC|AV1|10bit|AAC|AC3|EAC3|DTS|Atmos|5\\.1|7\\.1|YTS|YIFY|RARBG|PSA|GalaxyRG)\\b",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { withoutExt }

        val (hash, size) = computeOpenSubtitlesHashAndSize(context, videoUri)

        return SubtitleSearchRequest(
            rawTitle = rawTitle,
            cleanQuery = cleaned,
            year = year,
            season = season,
            episode = episode,
            movieHash = hash,
            fileByteSize = size,
            preferredLanguages = preferredLanguages
        )
    }

    /**
     * Computes the official 64-bit OpenSubtitles MovieHash (size + first 64KB + last 64KB).
     */
    private fun computeOpenSubtitlesHashAndSize(context: Context, uri: Uri): Pair<String?, Long> {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                if (size < 131072L) return Pair(null, size.coerceAtLeast(0L))
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val chunkSize = 65536
                    val headBuf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
                    val tailBuf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
                    channel.read(headBuf, 0L)
                    channel.read(tailBuf, size - chunkSize)
                    headBuf.flip()
                    tailBuf.flip()

                    var hash = size
                    while (headBuf.remaining() >= 8) {
                        hash += headBuf.long
                    }
                    while (tailBuf.remaining() >= 8) {
                        hash += tailBuf.long
                    }
                    Pair(String.format(Locale.US, "%016x", hash), size)
                }
            } ?: Pair(null, 0L)
        } catch (_: Exception) {
            Pair(null, 0L)
        }
    }

    /**
     * Queries all SubtitleProviders in parallel using Coroutines async/awaitAll + per-provider withTimeout.
     * Ranks results by MatchQuality (Hash > Exact > Fuzzy), Language Priority, and Rating/Downloads.
     * Automatically downloads and caches the #1 ranked subtitle in app storage.
     */
    suspend fun searchAndAutoDownload(
        context: Context,
        videoUri: Uri,
        rawTitle: String
    ): SubtitleSearchOutcome = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(context)) {
            return@withContext SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.NO_INTERNET)
        }

        val preferredLangs = PlaybackStore.getPreferredSubtitleLanguages(context)
        val request = buildSearchRequest(context, videoUri, rawTitle, preferredLangs)

        var anyTimeout = false
        var anyRateLimit = false

        val providerLists = coroutineScope {
            providers.map { provider ->
                async {
                    try {
                        withTimeout(7500L) {
                            provider.search(request)
                        }
                    } catch (_: TimeoutCancellationException) {
                        anyTimeout = true
                        emptyList<SubtitleResultItem>()
                    } catch (e: Exception) {
                        if (e.message == "RATE_LIMIT") anyRateLimit = true
                        emptyList<SubtitleResultItem>()
                    }
                }
            }.awaitAll()
        }

        val allResults = providerLists.flatten()
        if (allResults.isEmpty()) {
            val err = when {
                anyRateLimit -> SubtitleSearchOutcome.ErrorType.RATE_LIMIT
                anyTimeout -> SubtitleSearchOutcome.ErrorType.TIMEOUT
                else -> SubtitleSearchOutcome.ErrorType.NOT_FOUND
            }
            return@withContext SubtitleSearchOutcome.Error(err)
        }

        // Rank by: 1) MatchQuality (Hash > Exact > Fuzzy), 2) Preferred Language Priority Order, 3) Rating & Downloads
        val ranked = allResults.sortedWith(
            compareByDescending<SubtitleResultItem> { it.matchQuality.score }
                .thenBy { item ->
                    val idx = preferredLangs.indexOfFirst { it.equals(item.languageCode, ignoreCase = true) }
                    if (idx == -1) 999 else idx
                }
                .thenByDescending { it.rating }
                .thenByDescending { it.downloadCount }
        )

        // Try downloading & caching starting from top-ranked result (automatic fallback if #1 link fails)
        for (candidate in ranked.take(5)) {
            val downloaded = downloadAndCacheSubtitle(context, candidate)
            if (downloaded != null) {
                val (file, cues) = downloaded
                if (cues.isNotEmpty()) {
                    PlaybackStore.saveVideoSubtitleCachePath(context, videoUri, file.absolutePath)
                    return@withContext SubtitleSearchOutcome.Success(
                        results = ranked,
                        autoAppliedFile = file,
                        autoAppliedCues = cues,
                        bestItem = candidate
                    )
                }
            }
        }

        SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.CORRUPTED_FILE)
    }

    /**
     * Downloads a specific SubtitleResultItem (or returns its cached copy from app storage if already downloaded).
     */
    suspend fun downloadAndCacheSubtitle(
        context: Context,
        item: SubtitleResultItem
    ): Pair<File, List<SubtitleCue>>? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, "subtitles_cache").apply { mkdirs() }
        val keyHash = MessageDigest.getInstance("MD5")
            .digest(item.downloadUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cachedFile = File(cacheDir, "sub_${item.languageCode}_$keyHash.srt")

        // Return cached subtitle immediately if already downloaded!
        if (cachedFile.exists() && cachedFile.length() > 32L) {
            val cachedCues = SubtitleParser.parseFromFile(cachedFile)
            if (cachedCues.isNotEmpty()) {
                return@withContext Pair(cachedFile, cachedCues)
            }
        }

        try {
            val conn = (URL(item.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val rawBytes = conn.inputStream.use { it.readBytes() }
            if (rawBytes.isEmpty()) return@withContext null

            val extractedBytes = extractSubtitleBytesFromArchiveIfNeeded(rawBytes)
            val cues = SubtitleParser.parseFromBytes(extractedBytes)
            if (cues.isNotEmpty()) {
                cachedFile.writeBytes(extractedBytes)
                return@withContext Pair(cachedFile, cues)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun extractSubtitleBytesFromArchiveIfNeeded(rawBytes: ByteArray): ByteArray {
        if (rawBytes.size < 4) return rawBytes
        // Check ZIP magic header (PK 0x50 0x4B)
        if (rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()) {
            try {
                ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase(Locale.ROOT)
                        if (!entry.isDirectory && (name.endsWith(".srt") || name.endsWith(".vtt") || name.endsWith(".ass") || name.endsWith(".ssa") || name.endsWith(".sub"))) {
                            val out = ByteArrayOutputStream()
                            zis.copyTo(out)
                            return out.toByteArray()
                        }
                        entry = zis.nextEntry
                    }
                }
            } catch (_: Exception) {}
        }
        // Check GZIP magic header (0x1F 0x8B) used by OpenSubtitles .gz links
        if (rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte()) {
            try {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gis ->
                    return gis.readBytes()
                }
            } catch (_: Exception) {}
        }
        return rawBytes
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
