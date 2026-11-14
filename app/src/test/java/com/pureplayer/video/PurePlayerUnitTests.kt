package com.pureplayer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.nio.charset.Charset
import javax.crypto.KeyGenerator

class PurePlayerUnitTests {

    @Test
    fun testPinCryptoHelperIterationsAndLegacyAutoMigration() {
        val hash1 = PinCryptoHelper.hashPin("4829")
        val hash2 = PinCryptoHelper.hashPin("4829")

        assertTrue(PinCryptoHelper.isHashedFormat(hash1))
        assertTrue(hash1.startsWith("v2:100000:"))
        assertFalse("Random 16-byte salt must differ per hash", hash1 == hash2)
        assertTrue(PinCryptoHelper.verifyPinHash("4829", hash1))
        assertFalse(PinCryptoHelper.verifyPinHash("0000", hash1))

        // Reject hashes with iterations < 100,000
        val parts = hash1.split(":")
        val weakIterHash = "v2:50000:${parts[2]}:${parts[3]}"
        assertFalse("Must reject PBKDF2 hash with iterations < 100,000", PinCryptoHelper.verifyPinHash("4829", weakIterHash))

        // Automatic migration from legacy plain-text PIN
        var migratedHash: String? = null
        val wrongResult = PinCryptoHelper.verifyAndMigratePin("9999", "7351") { migratedHash = it }
        assertFalse(wrongResult)
        assertNull("Must not migrate when plain-text PIN is wrong", migratedHash)

        val okResult = PinCryptoHelper.verifyAndMigratePin("7351", "7351") { migratedHash = it }
        assertTrue(okResult)
        assertNotNull("Must migrate plain-text PIN on first valid login", migratedHash)
        assertTrue(migratedHash!!.startsWith("v2:100000:"))
        assertTrue(PinCryptoHelper.verifyPinHash("7351", migratedHash!!))
    }

    @Test
    fun testKeystoreAndIptvEncryptionFailureHandling() {
        // 1. Keystore failure (null key) must return null, NEVER Base64 fallback
        val failedEnc = IptvCredentialVault.encryptSecretOrNull("mySecretPassword", keyProvider = { null })
        assertNull("Encryption must return null when Keystore fails", failedEnc)

        // 2. Exception inside key provider must return null safely
        val thrownEnc = IptvCredentialVault.encryptSecretOrNull("mySecretPassword", keyProvider = {
            throw IllegalStateException("KeyStore hardware unavailable")
        })
        assertNull("Encryption must return null when Keystore throws", thrownEnc)

        // 3. Reject legacy unencrypted Base64 strings during decryption
        val rawB64 = "bXlTZWNyZXRQYXNzd29yZA=="
        val rejectedDec = IptvCredentialVault.decryptSecretOrNull(rawB64)
        assertNull("Decryption must reject non-GCM Base64 strings", rejectedDec)
    }

    @Test
    fun testSubtitleParserAssSrtVttAndMultipleEncodings() {
        // 1. SRT & HTML tag stripping
        val srt = """
            1
            00:00:01,500 --> 00:00:04,000
            مرحبا بكم في المشغل

            2
            00:00:05,000 --> 00:00:08,250
            <i>Second line</i>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val srtCues = SubtitleParser.parseFromBytes(srt)
        assertEquals(2, srtCues.size)
        assertEquals(1500L, srtCues[0].startMs)
        assertEquals("مرحبا بكم في المشغل", srtCues[0].text)
        assertEquals("Second line", srtCues[1].text)

        // 2. ASS / SSA format with override tags {\an8}, {\pos(...)}, and \N line breaks
        val assContent = """
            [Script Info]
            Title: Arabic Sample ASS
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.00,0:00:06.50,Default,,0,0,0,,{\an8\pos(400,50)}السطر الأول\Nالسطر الثاني
            Dialogue: 0,0:00:07.10,0:00:10.00,Default,,0,0,0,,{\i1}Hello ASS Subtitle{\i0}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val assCues = SubtitleParser.parseFromBytes(assContent)
        assertEquals(2, assCues.size)
        assertEquals(2000L, assCues[0].startMs)
        assertEquals(6500L, assCues[0].endMs)
        assertEquals("السطر الأول\nالسطر الثاني", assCues[0].text)
        assertEquals("Hello ASS Subtitle", assCues[1].text)

        // 3. UTF-8 with BOM (0xEF, 0xBB, 0xBF)
        val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt
        val bomCues = SubtitleParser.parseFromBytes(utf8BomBytes)
        assertEquals(2, bomCues.size)
        assertEquals("مرحبا بكم في المشغل", bomCues[0].text)

        // 4. UTF-16LE with BOM (0xFF, 0xFE)
        val utf16Body = "1\n00:00:01,000 --> 00:00:03,000\nترجمة UTF-16\n".toByteArray(Charsets.UTF_16LE)
        val utf16Bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16Body
        val utf16Cues = SubtitleParser.parseFromBytes(utf16Bytes)
        assertEquals(1, utf16Cues.size)
        assertEquals("ترجمة UTF-16", utf16Cues[0].text)

        // 5. Windows-1256 (Arabic legacy encoding)
        val win1256Charset = Charset.forName("windows-1256")
        val win1256Srt = "1\r\n00:00:02,000 --> 00:00:05,000\r\nترجمة عربية بترميز ويندوز\r\n".toByteArray(win1256Charset)
        val win1256Cues = SubtitleParser.parseFromBytes(win1256Srt)
        assertEquals(1, win1256Cues.size)
        assertEquals("ترجمة عربية بترميز ويندوز", win1256Cues[0].text)
    }

    @Test
    fun testLegacyIptvJsonMigrationAndCountVerification() {
        val tempGood = File.createTempFile("streams_good_", ".json")
        tempGood.writeText(
            """
            {"id":"ch_1","ct":"LIVE_TV","cid":"c1","cn":"Sports","t":"Channel 1","u":"http://example.com/1.ts"}
            {"id":"mov_2","ct":"MOVIES","cid":"c2","cn":"Cinema","t":"Movie 2","u":"http://example.com/2.mp4"}
            """.trimIndent(),
            Charsets.UTF_8
        )

        val fakeDb = mutableListOf<IptvStreamEntity>()
        val ok = LegacyIptvMigrator.migrateJsonStreamsFileSafely(
            sourceId = "src_test",
            jsonFile = tempGood,
            chunkSize = 1,
            clearExistingForSource = { fakeDb.clear() },
            insertBatch = { batch -> fakeDb.addAll(batch) },
            countInDb = { fakeDb.size },
            hasStreamIdInDb = { _, stId -> fakeDb.any { it.streamId == stId } }
        )

        assertTrue("Migration must succeed when counts and IDs match", ok)
        assertEquals(2, fakeDb.size)
        assertFalse("JSON file must be deleted after verified migration", tempGood.exists())

        // Now simulate count mismatch -> file MUST NOT be deleted
        val tempKeep = File.createTempFile("streams_keep_", ".json")
        tempKeep.writeText(
            """
            {"id":"ch_1","ct":"LIVE_TV","cid":"c1","cn":"Sports","t":"Channel 1","u":"http://example.com/1.ts"}
            {"id":"ch_2","ct":"LIVE_TV","cid":"c1","cn":"Sports","t":"Channel 2","u":"http://example.com/2.ts"}
            """.trimIndent(),
            Charsets.UTF_8
        )

        val failedOk = LegacyIptvMigrator.migrateJsonStreamsFileSafely(
            sourceId = "src_mismatch",
            jsonFile = tempKeep,
            chunkSize = 10,
            clearExistingForSource = {},
            insertBatch = {},
            countInDb = { 1 }, // Simulated incomplete DB insert (1 instead of 2)
            hasStreamIdInDb = { _, _ -> true }
        )
        assertFalse("Migration must fail when DB count does not match JSON line count", failedOk)
        assertTrue("JSON file must be preserved when migration verification fails", tempKeep.exists())
        tempKeep.delete()
    }

    @Test
    fun testArabicSearchNormalizerAndM3uParser() {
        val normalized = IptvSearchNormalizer.normalize("أَحْمَدُ المَسْرَحِيَّةُ الكُبْرَى")
        assertTrue(normalized.contains("احمد"))
        assertTrue(normalized.contains("المسرحيه"))
        assertTrue(normalized.contains("الكبري"))
        assertTrue(IptvSearchNormalizer.matches("الأسد الملك", "أفلام كرتون", "اسد ملك"))

        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Sports HD",beIN Sports 1
            #EXTVLCOPT:http-user-agent=CustomAgent/2.0
            #EXTVLCOPT:http-referrer=https://example.com/
            http://iptv.example.com/live/101.ts
            #EXTINF:-1 group-title="Movies 2026",Action Movie (2026)
            http://iptv.example.com/movie/user/pass/555.mp4
            #EXTINF:-1 group-title="Series Drama",Breaking Show S01E05
            http://iptv.example.com/series/user/pass/901.mkv
            #EXTINF:-1 group-title="Radio Stations",Quran Radio FM
            http://iptv.example.com/radio/88.mp3
        """.trimIndent()

        val items = M3uPlaylistParser.parseFromReader("src_1", BufferedReader(StringReader(m3u)))
        assertEquals(4, items.size)
        assertEquals(IptvContentType.LIVE_TV, items[0].contentType)
        assertEquals("CustomAgent/2.0", items[0].httpUserAgent)
        assertEquals(IptvContentType.MOVIES, items[1].contentType)
        assertEquals(IptvContentType.SERIES, items[2].contentType)
        assertEquals(1, items[2].seasonNumber)
        assertEquals(5, items[2].episodeNumber)
        assertEquals(IptvContentType.RADIO, items[3].contentType)
    }
}
