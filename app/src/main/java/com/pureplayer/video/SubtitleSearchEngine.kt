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
    EXACT_TITLE(80),
    FUZZY_TITLE(50)
}

data class SubtitleSearchRequest(
    val rawTitle: String,
    val cleanQuery: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val movieHash: String?,
    val fileByteSize: Long,
    val imdbId: String?,
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

interface SubtitleProvider {
    val name: String
    suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem>
}

/**
 * Provider 1: Stremio OpenSubtitles v3 Native JSON Provider (100% Free, Zero API Key Required!)
 * Uses resolved IMDb ID + Video Hash to fetch direct .srt download links in Arabic, English, etc.
 */
class StremioOpenSubtitlesV3Provider : SubtitleProvider {
    override val name: String = "OpenSubtitles-v3"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val imdb = request.imdbId ?: return@withContext emptyList()

        val iso3ToIso2 = mapOf(
            "ara" to "ar", "ar" to "ar",
            "eng" to "en", "en" to "en",
            "fre" to "fr", "fra" to "fr", "fr" to "fr",
            "spa" to "es", "es" to "es",
            "tur" to "tr", "tr" to "tr",
            "hin" to "hi", "hi" to "hi",
            "urd" to "ur", "ur" to "ur"
        )

        val isSeries = request.season != null && request.episode != null
        val typeSegment = if (isSeries) "series" else "movie"
        val idSegment = if (isSeries) "${imdb}:${request.season}:${request.episode}" else imdb

        val queryParams = buildList {
            if (!request.movieHash.isNullOrEmpty()) add("videoHash=${request.movieHash}")
            if (request.fileByteSize > 0) add("videoSize=${request.fileByteSize}")
            add("filename=${URLEncoder.encode(request.rawTitle, "UTF-8")}")
        }.joinToString("&")

        val endpoints = listOf(
            "https://opensubtitles-v3.strem.io/subtitles/$typeSegment/$idSegment/$queryParams.json",
            "https://opensubtitles-v3.strem.io/subtitles/$typeSegment/$idSegment.json"
        )

        for (urlStr in endpoints) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    connectTimeout = 6500
                    readTimeout = 6500
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (body.trim().startsWith("{")) {
                        val json = JSONObject(body)
                        val subs = json.optJSONArray("subtitles") ?: JSONArray()
                        for (i in 0 until subs.length()) {
                            val obj = subs.getJSONObject(i)
                            val rawLang = obj.optString("lang", "").lowercase(Locale.ROOT)
                            val iso2 = iso3ToIso2[rawLang] ?: rawLang.take(2)
                            if (!request.preferredLanguages.any { it.equals(iso2, ignoreCase = true) }) continue

                            val dlUrl = obj.optString("url")
                            val subId = obj.optString("id", "stremio_$i")
                            val isHashMatch = obj.optString("m") == "h"
                            if (dlUrl.isNotEmpty()) {
                                val epTag = if (isSeries) " S%02dE%02d".format(Locale.US, request.season, request.episode) else ""
                                results.add(
                                    SubtitleResultItem(
                                        id = "stv3_$subId",
                                        title = "${request.cleanQuery}$epTag (${iso2.uppercase()} #$i)",
                                        providerName = name,
                                        languageCode = iso2,
                                        downloadUrl = dlUrl,
                                        formatExt = "srt",
                                        matchQuality = if (isHashMatch) MatchQuality.HASH_MATCH else MatchQuality.EXACT_TITLE,
                                        rating = if (isHashMatch) 10.0 else 9.5,
                                        downloadCount = 2000 - (i * 10)
                                    )
                                )
                            }
                        }
                        if (results.isNotEmpty()) break
                    }
                }
            } catch (_: Exception) {}
        }
        results
    }
}

/**
 * Provider 2: OpenSubtitles REST API (Uses official X-User-Agent header + 64-bit File Hash + IMDb ID + Query)
 */
class OpenSubtitlesProvider : SubtitleProvider {
    override val name: String = "OpenSubtitles-REST"

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
            if (!request.imdbId.isNullOrEmpty()) {
                val numericImdb = request.imdbId.removePrefix("tt")
                if (request.season != null && request.episode != null) {
                    endpoints.add(
                        "https://rest.opensubtitles.org/search/episode-${request.episode}/imdbid-$numericImdb/season-${request.season}/sublanguageid-$subLangId" to MatchQuality.EXACT_TITLE
                    )
                } else {
                    endpoints.add(
                        "https://rest.opensubtitles.org/search/imdbid-$numericImdb/sublanguageid-$subLangId" to MatchQuality.EXACT_TITLE
                    )
                }
            }
            endpoints.add(
                "https://rest.opensubtitles.org/search/query-$encodedQuery/sublanguageid-$subLangId" to MatchQuality.EXACT_TITLE
            )

            for ((urlStr, quality) in endpoints) {
                try {
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        // Official X-User-Agent required by rest.opensubtitles.org
                        setRequestProperty("X-User-Agent", "TemporaryUserAgent")
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
                } catch (_: Exception) {}
            }
        }
        results
    }
}

/**
 * Provider 3: Podnapisi Public XML/ZIP Subtitle Provider (No API Key Required)
 */
class PodnapisiProvider : SubtitleProvider {
    override val name: String = "Podnapisi"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val encodedQuery = URLEncoder.encode(request.cleanQuery, "UTF-8")
        val sParam = if (request.season != null) "&sTS=${request.season}" else ""
        val eParam = if (request.episode != null) "&sTE=${request.episode}" else ""

        for (lang in request.preferredLanguages) {
            try {
                val urlStr = "https://www.podnapisi.net/subtitles/search/old?sXML=1&sL=$lang&sK=$encodedQuery$sParam$eParam"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
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
                                rating = 8.8,
                                downloadCount = dlCount
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        results
    }
}

/**
 * Provider 4: YIFY Subtitles Direct Movie Provider (Uses IMDb ID to fetch direct .zip/.srt links, zero API key!)
 */
class YifySubtitlesProvider : SubtitleProvider {
    override val name: String = "YIFY-Subtitles"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubtitleResultItem>()
        val imdb = request.imdbId ?: return@withContext emptyList()

        val langNameMap = mapOf(
            "ar" to "arabic", "en" to "english", "fr" to "french",
            "es" to "spanish", "tr" to "turkish", "hi" to "hindi", "ur" to "urdu"
        )

        val domains = listOf("https://yts-subs.com", "https://yifysubtitles.ch")
        for (domain in domains) {
            try {
                val conn = (URL("$domain/movie-imdb/$imdb").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                if (conn.responseCode in 200..299) {
                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                    val rowRegex = Regex("<tr[^>]*>.*?sub-lang\">([^<]+)</span>.*?href=\"(/subtitles/[^\"]+)\".*?</tr>", RegexOption.DOT_MATCHES_ALL)
                    for (match in rowRegex.findAll(html)) {
                        val langLabel = match.groupValues[1].trim().lowercase(Locale.ROOT)
                        val subPagePath = match.groupValues[2].trim()
                        val matchedCode = langNameMap.entries.firstOrNull { it.value.equals(langLabel, ignoreCase = true) }?.key ?: continue
                        if (!request.preferredLanguages.contains(matchedCode)) continue

                        val zipSlug = subPagePath.removePrefix("/subtitles/")
                        val directZipUrl = "$domain/subtitle/$zipSlug.zip"
                        val readableTitle = zipSlug.replace("-", " ")
                        results.add(
                            SubtitleResultItem(
                                id = "yify_$zipSlug",
                                title = readableTitle,
                                providerName = name,
                                languageCode = matchedCode,
                                downloadUrl = directZipUrl,
                                formatExt = "zip",
                                matchQuality = MatchQuality.EXACT_TITLE,
                                rating = 9.0,
                                downloadCount = 400
                            )
                        )
                        if (results.size >= 8) break
                    }
                    if (results.isNotEmpty()) break
                }
            } catch (_: Exception) {}
        }
        results
    }
}

/**
 * Provider 5: SubDL API & Public NextJS Data Provider
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
        } else if (!request.imdbId.isNullOrEmpty()) {
            "https://api.subdl.com/api/v1/subtitles?imdb_id=${request.imdbId}&languages=$langCsv"
        } else {
            "https://api.subdl.com/api/v1/subtitles?film_name=$encodedQuery&languages=$langCsv"
        }

        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
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
                                matchQuality = MatchQuality.EXACT_TITLE,
                                rating = 9.2,
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
 * Provider 6: Addic7ed / Gestdown Community TV Series Provider
 */
class Addic7edCommunityProvider : SubtitleProvider {
    override val name: String = "Addic7ed-TV"

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
        StremioOpenSubtitlesV3Provider(),
        OpenSubtitlesProvider(),
        PodnapisiProvider(),
        YifySubtitlesProvider(),
        SubdlProvider(),
        Addic7edCommunityProvider()
    )

    /**
     * Resolves IMDb ID automatically without any API key using Cinemeta Public Catalog API.
     */
    private fun resolveImdbIdKeyless(cleanQuery: String, isSeries: Boolean, year: Int?): String? {
        val catalogTypes = if (isSeries) listOf("series", "movie") else listOf("movie", "series")
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        for (catType in catalogTypes) {
            try {
                val url = "https://v3-cinemeta.strem.io/catalog/$catType/top/search=$encoded.json"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    connectTimeout = 4500
                    readTimeout = 4500
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val metas = json.optJSONArray("metas") ?: continue
                    if (metas.length() > 0) {
                        if (year != null) {
                            for (i in 0 until metas.length()) {
                                val m = metas.getJSONObject(i)
                                val releaseInfo = m.optString("releaseInfo", "")
                                if (releaseInfo.contains(year.toString())) {
                                    val id = m.optString("id")
                                    if (id.startsWith("tt")) return id
                                }
                            }
                        }
                        val firstId = metas.getJSONObject(0).optString("id")
                        if (firstId.startsWith("tt")) return firstId
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun buildSearchRequest(
        context: Context,
        videoUri: Uri,
        rawTitle: String,
        preferredLanguages: List<String>,
        customSearchQuery: String? = null
    ): SubtitleSearchRequest {
        val withoutExt = rawTitle.substringBeforeLast(".")
        val sxeMatch = Regex("[Ss](\\d{1,2})[._\\-\\s]*[Ee](\\d{1,2})").find(withoutExt)
            ?: Regex("(\\d{1,2})x(\\d{1,2})").find(withoutExt)
        val season = sxeMatch?.groupValues?.get(1)?.toIntOrNull()
        val episode = sxeMatch?.groupValues?.get(2)?.toIntOrNull()

        val yearMatch = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(withoutExt)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val baseBeforeEpisode = if (sxeMatch != null) {
            withoutExt.substring(0, sxeMatch.range.first)
        } else if (yearMatch != null) {
            withoutExt.substring(0, yearMatch.range.first)
        } else {
            withoutExt
        }

        val cleaned = (customSearchQuery?.takeIf { it.isNotBlank() } ?: baseBeforeEpisode)
            .replace(Regex("[._\\-\\[\\]()]"), " ")
            .replace(
                Regex(
                    "\\b(2160p|1080p|720p|480p|4K|8K|UHD|HDR|HDR10|DV|Dolby|BluRay|BRRip|BDRip|WEB|WEBDL|WEBRip|HDTV|x264|x265|H264|H265|HEVC|AV1|10bit|AAC|AC3|EAC3|DTS|Atmos|5\\.1|7\\.1|YTS|YIFY|RARBG|PSA|GalaxyRG|AMZN|NF)\\b",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { withoutExt }

        val (hash, size) = computeOpenSubtitlesHashAndSize(context, videoUri)
        val imdbId = resolveImdbIdKeyless(cleaned, isSeries = (season != null), year = year)

        return SubtitleSearchRequest(
            rawTitle = rawTitle,
            cleanQuery = cleaned,
            year = year,
            season = season,
            episode = episode,
            movieHash = hash,
            fileByteSize = size,
            imdbId = imdbId,
            preferredLanguages = preferredLanguages
        )
    }

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
     * Queries all 6 SubtitleProviders in parallel using Coroutines async/awaitAll + withTimeout.
     * Ranks results by MatchQuality (Hash > Exact > Fuzzy), Language Priority, and Rating/Downloads.
     * Automatically downloads, extracts, and caches the #1 best subtitle in app storage!
     */
    suspend fun searchAndAutoDownload(
        context: Context,
        videoUri: Uri,
        rawTitle: String,
        customSearchQuery: String? = null
    ): SubtitleSearchOutcome = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(context)) {
            return@withContext SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.NO_INTERNET)
        }

        val preferredLangs = PlaybackStore.getPreferredSubtitleLanguages(context)
        val request = buildSearchRequest(context, videoUri, rawTitle, preferredLangs, customSearchQuery)

        var anyTimeout = false
        var anyRateLimit = false

        val providerLists = coroutineScope {
            providers.map { provider ->
                async {
                    try {
                        withTimeout(8500L) {
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

        val allResults = providerLists.flatten().distinctBy { it.downloadUrl }
        if (allResults.isEmpty()) {
            val err = when {
                anyRateLimit -> SubtitleSearchOutcome.ErrorType.RATE_LIMIT
                anyTimeout -> SubtitleSearchOutcome.ErrorType.TIMEOUT
                else -> SubtitleSearchOutcome.ErrorType.NOT_FOUND
            }
            return@withContext SubtitleSearchOutcome.Error(err)
        }

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
        for (candidate in ranked.take(6)) {
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

    suspend fun downloadAndCacheSubtitle(
        context: Context,
        item: SubtitleResultItem
    ): Pair<File, List<SubtitleCue>>? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, "subtitles_cache").apply { mkdirs() }
        val keyHash = MessageDigest.getInstance("MD5")
            .digest(item.downloadUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cachedFile = File(cacheDir, "sub_${item.languageCode}_$keyHash.srt")

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
                setRequestProperty("X-User-Agent", "TemporaryUserAgent")
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
