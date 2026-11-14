package com.pureplayer.video

import android.util.Log
import java.io.File
import java.nio.charset.Charset

object SubtitleParser {
    fun parseFromFile(file: File): List<SubtitleCue> {
        return try {
            parseFromBytes(file.readBytes())
        } catch (e: Exception) {
            Log.w("SubtitleParser", "Failed to read subtitle file: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    fun parseFromBytes(bytes: ByteArray): List<SubtitleCue> {
        if (bytes.isEmpty()) return emptyList()
        val text = decodeBestCharset(bytes)
        return parseSubtitleText(text)
    }

    internal fun decodeBestCharset(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) {
            return utf8
        }
        return try {
            String(bytes, Charset.forName("windows-1256"))
        } catch (e: Exception) {
            Log.w("SubtitleParser", "Charset windows-1256 fallback failed: ${e.javaClass.simpleName}")
            utf8
        }
    }

    fun parseSubtitleText(rawText: String): List<SubtitleCue> {
        val normalized = rawText.replace("\r\n", "\n").replace("\r", "\n")
        if (normalized.contains("[Events]", ignoreCase = true) && normalized.contains("Dialogue:", ignoreCase = true)) {
            val assCues = parseAssSubtitles(normalized)
            if (assCues.isNotEmpty()) return assCues
        }

        val cues = mutableListOf<SubtitleCue>()
        val blocks = normalized.split(Regex("\\n\\n+"))
        val timeRegex = Regex("(\\d{1,2}:)?\\d{2}:\\d{2}[,.]\\d{2,3}\\s*-->\\s*(\\d{1,2}:)?\\d{2}:\\d{2}[,.]\\d{2,3}")

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timeIdx = lines.indexOfFirst { timeRegex.containsMatchIn(it) }
            if (timeIdx == -1) continue

            val timeLine = lines[timeIdx]
            val parts = timeLine.split("-->")
            if (parts.size < 2) continue

            val startMs = parseTimeCode(parts[0].trim())
            val endMs = parseTimeCode(parts[1].trim().substringBefore(" "))
            if (endMs <= startMs) continue

            val textLines = lines.subList(timeIdx + 1, lines.size)
                .map {
                    it.replace(Regex("<[^>]*>"), "")
                        .replace(Regex("\\{[^}]*\\}"), "")
                        .trim()
                }
                .filter { it.isNotEmpty() }

            if (textLines.isNotEmpty()) {
                cues.add(SubtitleCue(startMs, endMs, textLines.joinToString("\n")))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseAssSubtitles(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = trimmed.substringAfter(":")
                val cols = payload.split(",", limit = 10)
                if (cols.size >= 10) {
                    val startMs = parseTimeCode(cols[1].trim())
                    val endMs = parseTimeCode(cols[2].trim())
                    val cleanText = cols[9]
                        .replace(Regex("\\{[^}]*\\}"), "")
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .trim()
                    if (endMs > startMs && cleanText.isNotEmpty()) {
                        cues.add(SubtitleCue(startMs, endMs, cleanText))
                    }
                }
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseTimeCode(raw: String): Long {
        val clean = raw.replace(',', '.').trim()
        val segments = clean.split(":")
        return try {
            when (segments.size) {
                3 -> {
                    val h = segments[0].toLong()
                    val m = segments[1].toLong()
                    val secParts = segments[2].split(".")
                    val s = secParts[0].toLong()
                    val msStr = (secParts.getOrNull(1) ?: "0").padEnd(3, '0').take(3)
                    val ms = msStr.toLong()
                    (h * 3600 + m * 60 + s) * 1000L + ms
                }
                2 -> {
                    val m = segments[0].toLong()
                    val secParts = segments[1].split(".")
                    val s = secParts[0].toLong()
                    val msStr = (secParts.getOrNull(1) ?: "0").padEnd(3, '0').take(3)
                    val ms = msStr.toLong()
                    (m * 60 + s) * 1000L + ms
                }
                else -> 0L
            }
        } catch (e: Exception) {
            Log.w("SubtitleParser", "Timecode parse failed: ${e.javaClass.simpleName}")
            0L
        }
    }
}

enum class MatchQuality(val scoreBoost: Int) {
    HASH_MATCH(300),
    EXACT_TITLE(200),
    FUZZY_TITLE(100)
}
