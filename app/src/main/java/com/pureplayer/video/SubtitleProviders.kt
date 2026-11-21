package com.pureplayer.video

import android.net.Uri

import android.content.Context

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class SubtitleResultItem(
    val id: String,
    val title: String,
    val providerName: String,
    val languageCode: String,
    val downloadUrl: String,
    val formatExt: String = "srt",
    val matchQuality: MatchQuality = MatchQuality.EXACT_TITLE,
    val rating: Double = 0.0,
    val downloadCount: Int = 0
)

data class SubtitleSearchRequest(
    val videoUri: Uri,
    val rawTitle: String,
    val cleanQuery: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val movieHash: String?,
    val fileByteSize: Long,
    val preferredLanguages: List<String>
)

sealed class SubtitleSearchOutcome {
    data class Success(
        val bestItem: SubtitleResultItem,
        val autoAppliedCues: List<SubtitleCue>,
        val results: List<SubtitleResultItem>
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

object OpenSubtitlesHashCalculator {
    private const val CHUNK_SIZE = 65536

    fun computeHashAndSize(context: Context, uri: Uri): Pair<String, Long>? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                if (size < CHUNK_SIZE * 2) return null
                val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
                val headBuf = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                val tailBuf = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(headBuf, 0L)
                channel.read(tailBuf, size - CHUNK_SIZE)
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
        } catch (_: Exception) {
            null
        }
    }
}

object CinemetaImdbResolver {
    fun resolveImdbId(query: String, preferSeries: Boolean): Pair<String, Boolean>? {
        val order = if (preferSeries) listOf("series", "movie") else listOf("movie", "series")
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (type in order) {
            try {
                val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
                val json = httpGetString(url) ?: continue
                val metas = JSONObject(json).optJSONArray("metas") ?: continue
                if (metas.length() > 0) {
                    val id = metas.optJSONObject(0)?.optString("id")?.takeIf { it.startsWith("tt") }
                    if (id != null) {
                        return Pair(id, type == "series")
                    }
                }
            } catch (e: Exception) { Log.w("SubtitleProviders", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
        return null
    }
}

object StremioOpenSubtitlesV3Provider : SubtitleProvider {
    override val name: String = "OpenSubtitles-v3"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> {
        val out = mutableListOf<SubtitleResultItem>()
        try {
            val resolved = CinemetaImdbResolver.resolveImdbId(request.cleanQuery, request.season != null) ?: return emptyList()
            val imdbId = resolved.first
            val isSeries = resolved.second || (request.season != null && request.episode != null)

            val urlsToTry = mutableListOf<String>()
            if (isSeries) {
                val s = request.season ?: 1
                val e = request.episode ?: 1
                urlsToTry.add("https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$s:$e.json")
            }
            val hashParam = if (!request.movieHash.isNullOrEmpty() && request.fileByteSize > 0) {
                "/videoHash=${request.movieHash}&videoSize=${request.fileByteSize}"
            } else ""
            urlsToTry.add("https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId$hashParam.json")

            for (urlStr in urlsToTry) {
                val jsonStr = httpGetString(urlStr) ?: continue
                val root = JSONObject(jsonStr)
                val subs = root.optJSONArray("subtitles") ?: continue

                for (i in 0 until subs.length()) {
                    val obj = subs.optJSONObject(i) ?: continue
                    val dlUrl = obj.optString("url")
                    if (dlUrl.isBlank()) continue
                    val rawLang = obj.optString("lang").lowercase(Locale.ROOT)
                    val lang2 = normalizeLangCode(rawLang)
                    if (lang2 !in request.preferredLanguages) continue

                    out.add(
                        SubtitleResultItem(
                            id = obj.optString("id", "stremio_${out.size}"),
                            title = "${request.cleanQuery} (${lang2.uppercase()})",
                            providerName = name,
                            languageCode = lang2,
                            downloadUrl = dlUrl,
                            formatExt = "srt",
                            matchQuality = if (!request.movieHash.isNullOrEmpty()) MatchQuality.HASH_MATCH else MatchQuality.EXACT_TITLE,
                            rating = 9.5,
                            downloadCount = 600 - i
                        )
                    )
                }
                if (out.isNotEmpty()) break
            }
        } catch (e: Exception) { Log.w("SubtitleProviders", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        return out
    }
}

object OpenSubtitlesRestProvider : SubtitleProvider {
    override val name: String = "OpenSubtitles"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> {
        val out = mutableListOf<SubtitleResultItem>()
        val userAgents = listOf("TemporaryUserAgent", "VLSub 0.11.1")
        for (lang2 in request.preferredLanguages) {
            val iso3 = toIso639_2(lang2)
            for (ua in userAgents) {
                try {
                    if (!request.movieHash.isNullOrEmpty() && request.fileByteSize > 0) {
                        val hashUrl = "https://rest.opensubtitles.org/search/moviebytesize-${request.fileByteSize}/moviehash-${request.movieHash}/sublanguageid-$iso3"
                        parseOpenSubRestArray(httpGetString(hashUrl, mapOf("X-User-Agent" to ua)), lang2, MatchQuality.HASH_MATCH, out)
                    }
                    val qEnc = URLEncoder.encode(request.cleanQuery, "UTF-8")
                    val seParams = if (request.season != null && request.episode != null) {
                        "/episode-${request.episode}/season-${request.season}"
                    } else ""
                    val queryUrl = "https://rest.opensubtitles.org/search$seParams/query-$qEnc/sublanguageid-$iso3"
                    parseOpenSubRestArray(httpGetString(queryUrl, mapOf("X-User-Agent" to ua)), lang2, MatchQuality.EXACT_TITLE, out)
                    if (out.isNotEmpty()) break
                } catch (e: Exception) { Log.w("SubtitleProviders", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
            }
        }
        return out
    }

    private fun parseOpenSubRestArray(jsonStr: String?, lang2: String, quality: MatchQuality, out: MutableList<SubtitleResultItem>) {
        if (jsonStr.isNullOrBlank() || !jsonStr.trim().startsWith("[")) return
        val arr = JSONArray(jsonStr)
        for (i in 0 until minOf(arr.length(), 15)) {
            val obj = arr.optJSONObject(i) ?: continue
            val dl = obj.optString("SubDownloadLink").ifBlank { obj.optString("ZipDownloadLink") }
            if (dl.isBlank()) continue
            val fileName = obj.optString("SubFileName", "Subtitle")
            val downloads = obj.optString("SubDownloadsCnt", "0").toIntOrNull() ?: 0
            val rating = obj.optString("SubRating", "0.0").toDoubleOrNull() ?: 0.0
            out.add(
                SubtitleResultItem(
                    id = obj.optString("IDSubtitleFile", "os_${out.size}"),
                    title = fileName,
                    providerName = name,
                    languageCode = lang2,
                    downloadUrl = dl,
                    formatExt = obj.optString("SubFormat", "srt"),
                    matchQuality = quality,
                    rating = rating,
                    downloadCount = downloads
                )
            )
        }
    }
}

object PodnapisiProvider : SubtitleProvider {
    override val name: String = "Podnapisi"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> {
        val out = mutableListOf<SubtitleResultItem>()
        for (lang2 in request.preferredLanguages) {
            try {
                val qEnc = URLEncoder.encode(request.cleanQuery, "UTF-8")
                var url = "https://www.podnapisi.net/subtitles/search/advanced?keywords=$qEnc&language=$lang2"
                if (request.season != null && request.episode != null) {
                    url += "&seasons=${request.season}&episodes=${request.episode}"
                }
                val jsonStr = httpGetString(url, mapOf("Accept" to "application/json")) ?: continue
                if (!jsonStr.trim().startsWith("{")) continue
                val root = JSONObject(jsonStr)
                val data = root.optJSONArray("data") ?: continue
                for (i in 0 until minOf(data.length(), 12)) {
                    val item = data.optJSONObject(i) ?: continue
                    val dlPath = item.optString("download")
                    if (dlPath.isBlank()) continue
                    val fullDl = if (dlPath.startsWith("http")) dlPath else "https://www.podnapisi.net$dlPath"
                    val releases = item.optJSONArray("custom_releases")
                    val releaseTitle = if (releases != null && releases.length() > 0) {
                        releases.optString(0)
                    } else {
                        item.optJSONObject("movie")?.optString("title") ?: request.cleanQuery
                    }
                    out.add(
                        SubtitleResultItem(
                            id = "pod_${lang2}_$i",
                            title = releaseTitle,
                            providerName = name,
                            languageCode = lang2,
                            downloadUrl = fullDl,
                            formatExt = "zip",
                            matchQuality = MatchQuality.EXACT_TITLE,
                            rating = 8.8,
                            downloadCount = 250 - i
                        )
                    )
                }
            } catch (e: Exception) { Log.w("SubtitleProviders", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
        return out
    }
}

object LegacyArchiveSubtitlesProvider : SubtitleProvider {
    override val name: String = "Archive-Subs"
    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> = emptyList()
}

object SubdlProvider : SubtitleProvider {
    override val name: String = "SubDL"

    override suspend fun search(request: SubtitleSearchRequest): List<SubtitleResultItem> {
        val out = mutableListOf<SubtitleResultItem>()
        val apiKey = BuildConfig.SUBDL_API_KEY
        if (apiKey.isNotBlank()) {
            try {
                val qEnc = URLEncoder.encode(request.cleanQuery, "UTF-8")
                val langs = request.preferredLanguages.joinToString(",") { it.uppercase(Locale.ROOT) }
                val apiUrl = "https://api.subdl.com/api/v1/subtitles?api_key=$apiKey&film_name=$qEnc&languages=$langs&subs_per_page=15"
                val jsonStr = httpGetString(apiUrl)
                if (!jsonStr.isNullOrBlank() && jsonStr.trim().startsWith("{")) {
                    val root = JSONObject(jsonStr)
                    val subs = root.optJSONArray("subtitles")
                    if (subs != null) {
                        for (i in 0 until subs.length()) {
                            val s = subs.optJSONObject(i) ?: continue
                            val link = s.optString("url")
                            if (link.isBlank()) continue
                            val fullDl = if (link.startsWith("http")) link else "https://dl.subdl.com$link"
                            val lCode = normalizeLangCode(s.optString("language", "ar"))
                            out.add(
                                SubtitleResultItem(
                                    id = "subdl_$i",
                                    title = s.optString("release_name", request.cleanQuery),
                                    providerName = name,
                                    languageCode = lCode,
                                    downloadUrl = fullDl,
                                    formatExt = "zip",
                                    matchQuality = MatchQuality.EXACT_TITLE,
                                    rating = 9.0,
                                    downloadCount = 300 - i
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) { Log.w("SubtitleProviders", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
        return out
    }
}

private fun httpGetString(urlStr: String, headers: Map<String, String> = emptyMap()): String? {
    return try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 9000
            readTimeout = 9000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }
        if (conn.responseCode in 200..299) {
            val bytes = conn.inputStream.use { SubtitleSearchEngine.readBytesBounded(it, 4 * 1024 * 1024) } ?: return null
            String(bytes, Charsets.UTF_8)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun normalizeLangCode(raw: String): String {
    val clean = raw.trim().lowercase(Locale.ROOT)
    return when {
        clean.startsWith("ara") || clean == "ar" || clean.contains("arabic") -> "ar"
        clean.startsWith("eng") || clean == "en" || clean.contains("english") -> "en"
        clean.startsWith("fre") || clean.startsWith("fra") || clean == "fr" -> "fr"
        clean.startsWith("spa") || clean == "es" -> "es"
        clean.startsWith("tur") || clean == "tr" -> "tr"
        clean.startsWith("hin") || clean == "hi" -> "hi"
        clean.startsWith("urd") || clean == "ur" -> "ur"
        else -> clean.take(2)
    }
}

private fun toIso639_2(lang2: String): String {
    return when (lang2.lowercase(Locale.ROOT)) {
        "ar" -> "ara"
        "en" -> "eng"
        "fr" -> "fre"
        "es" -> "spa"
        "tr" -> "tur"
        "hi" -> "hin"
        "ur" -> "urd"
        else -> "eng"
    }
}

