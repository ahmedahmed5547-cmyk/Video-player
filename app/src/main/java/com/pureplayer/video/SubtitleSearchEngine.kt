package com.pureplayer.video

import android.net.Uri

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object SubtitleSearchEngine {

    private val providers: List<SubtitleProvider> = listOf(
        StremioOpenSubtitlesV3Provider,
        OpenSubtitlesRestProvider,
        PodnapisiProvider,
        YifySubtitlesProvider,
        SubdlProvider
    )

    fun buildSearchRequest(
        context: Context,
        videoUri: Uri,
        rawTitle: String,
        preferredLanguages: List<String>,
        customQuery: String? = null
    ): SubtitleSearchRequest {
        val baseName = (customQuery?.takeIf { it.isNotBlank() } ?: rawTitle)
            .substringBeforeLast(".")
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')

        val seMatch = Regex("[Ss](\\d{1,2})\\s*[Ee](\\d{1,3})").find(baseName)
            ?: Regex("(\\d{1,2})x(\\d{1,3})").find(baseName)
        val epOnlyMatch = Regex("(?:[Ee][Pp]?|Episode|الحلقة)\\s*(\\d{1,3})", RegexOption.IGNORE_CASE).find(baseName)

        val season = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: if (epOnlyMatch != null) 1 else null
        val episode = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: epOnlyMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        val yearMatch = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(baseName)
        val year = yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        val cleanQuery = baseName
            .replace(Regex("[Ss]\\d{1,2}\\s*[Ee]\\d{1,3}.*"), "")
            .replace(Regex("\\b\\d{1,2}x\\d{1,3}\\b.*"), "")
            .replace(Regex("(?:[Ee][Pp]?|Episode|الحلقة)\\s*\\d{1,3}.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(19\\d{2}|20\\d{2})\\b.*"), "")
            .replace(Regex("(2160p|1080p|720p|480p|360p|4K|BluRay|BRRip|WEBRip|WEB-DL|WEB|HDRip|HDTV|x264|x265|HEVC|AAC|10bit|YTS|YIFY|RARBG|PSA|EgyBest|Wecima|MyCima|FaselHD|ArabSeed|Cima4u|AnimeSlayer).*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { baseName.trim().ifEmpty { "Movie" } }

        val hashPair = if (videoUri.scheme == "content" || videoUri.scheme == "file") {
            OpenSubtitlesHashCalculator.computeHashAndSize(context, videoUri)
        } else null

        return SubtitleSearchRequest(
            videoUri = videoUri,
            rawTitle = rawTitle,
            cleanQuery = cleanQuery,
            year = year,
            season = season,
            episode = episode,
            movieHash = hashPair?.first,
            fileByteSize = hashPair?.second ?: 0L,
            preferredLanguages = preferredLanguages
        )
    }

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

        var allResults = try {
            withTimeout(15_000L) {
                coroutineScope {
                    providers.map { provider ->
                        async {
                            try {
                                provider.search(request)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
            }
        } catch (_: TimeoutCancellationException) {
            return@withContext SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.TIMEOUT)
        }

        // If full cleaned title returned 0 results and has >2 words, automatically retry with the first 2 main words!
        if (allResults.isEmpty()) {
            val words = request.cleanQuery.split(" ").filter { it.length >= 2 }
            if (words.size > 2) {
                val shorterRequest = request.copy(cleanQuery = words.take(2).joinToString(" "))
                allResults = try {
                    withTimeout(12_000L) {
                        coroutineScope {
                            providers.map { provider ->
                                async {
                                    try {
                                        provider.search(shorterRequest)
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                }
                            }.awaitAll().flatten()
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        if (allResults.isEmpty()) {
            return@withContext SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.NOT_FOUND)
        }

        val ranked = allResults.sortedWith(
            compareBy<SubtitleResultItem> {
                val idx = preferredLangs.indexOf(it.languageCode)
                if (idx == -1) 999 else idx
            }.thenByDescending { it.matchQuality.scoreBoost }
                .thenByDescending { it.rating }
                .thenByDescending { it.downloadCount }
        )

        for (candidate in ranked.take(6)) {
            val downloaded = downloadAndCacheSubtitle(context, candidate)
            if (downloaded != null && downloaded.second.isNotEmpty()) {
                PlaybackStore.saveVideoSubtitleCachePath(context, videoUri, downloaded.first.absolutePath)
                return@withContext SubtitleSearchOutcome.Success(
                    bestItem = candidate,
                    autoAppliedCues = downloaded.second,
                    results = ranked
                )
            }
        }

        SubtitleSearchOutcome.Error(SubtitleSearchOutcome.ErrorType.CORRUPTED_FILE)
    }

    suspend fun downloadAndCacheSubtitle(
        context: Context,
        item: SubtitleResultItem
    ): Pair<File, List<SubtitleCue>>? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.filesDir, "subtitles_cache").apply { mkdirs() }
            val safeId = item.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val cachedFile = File(cacheDir, "sub_${item.languageCode}_$safeId.srt")

            if (cachedFile.exists() && cachedFile.length() > 64) {
                val cachedCues = SubtitleParser.parseFromFile(cachedFile)
                if (cachedCues.isNotEmpty()) {
                    return@withContext Pair(cachedFile, cachedCues)
                }
            }

            val conn = (URL(item.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15)")
                setRequestProperty("X-User-Agent", "TemporaryUserAgent")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val rawBytes = conn.inputStream.use { readBytesBounded(it, 8 * 1024 * 1024) } ?: return@withContext null
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

    fun readBytesBounded(input: InputStream, maxBytes: Int = 8 * 1024 * 1024): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            if (total + read > maxBytes) {
                Log.w("SubtitleSearchEngine", "Stream exceeded maxBytes limit ($maxBytes)")
                return null
            }
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    fun extractSubtitleBytesFromArchiveIfNeeded(rawBytes: ByteArray): ByteArray {
        if (rawBytes.size < 4) return rawBytes
        if (rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()) {
            try {
                ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.lowercase(Locale.ROOT)
                        if (!entry.isDirectory && !name.contains("..") &&
                            (name.endsWith(".srt") || name.endsWith(".vtt") || name.endsWith(".ass") || name.endsWith(".ssa") || name.endsWith(".sub"))
                        ) {
                            return readBytesBounded(zis, 8 * 1024 * 1024) ?: rawBytes
                        }
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) { Log.w("SubtitleSearchEngine", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
        if (rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte()) {
            try {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gis ->
                    return readBytesBounded(gis, 8 * 1024 * 1024) ?: rawBytes
                }
            } catch (e: Exception) { Log.w("SubtitleSearchEngine", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
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


/**
 * High-Speed Local Disk + Memory Video Index Cache for 0ms Instant Folder Loading on App Launch.
 */
