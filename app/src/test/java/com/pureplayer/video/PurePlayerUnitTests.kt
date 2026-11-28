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
    fun testKeystoreAndIptvEncryptionRoundTripAndFailureHandling() {
        // 1. Real AES-256-GCM round-trip in pure JVM using pure-Kotlin Base64Codec
        val testAesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypted = IptvCredentialVault.encryptSecretOrNull("P@ssw0rd_2026!", keyProvider = { testAesKey })
        assertNotNull("AES-GCM encryption with valid key must succeed", encrypted)
        assertTrue(encrypted!!.startsWith("GCM:"))
        val decrypted = IptvCredentialVault.decryptSecretOrNull(encrypted, keyProvider = { testAesKey })
        assertEquals("P@ssw0rd_2026!", decrypted)

        // 2. Keystore failure (null key) must return null, NEVER Base64 fallback
        val failedEnc = IptvCredentialVault.encryptSecretOrNull("mySecretPassword", keyProvider = { null })
        assertNull("Encryption must return null when Keystore fails", failedEnc)

        // 3. Exception inside key provider must return null safely
        val thrownEnc = IptvCredentialVault.encryptSecretOrNull("mySecretPassword", keyProvider = {
            throw IllegalStateException("KeyStore hardware unavailable")
        })
        assertNull("Encryption must return null when Keystore throws", thrownEnc)

        // 4. Reject legacy unencrypted Base64 strings during decryption
        val rawB64 = "bXlTZWNyZXRQYXNzd29yZA=="
        val rejectedDec = IptvCredentialVault.decryptSecretOrNull(rawB64, keyProvider = { testAesKey })
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

        // Simulate count mismatch -> file MUST NOT be deleted
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
            countInDb = { 1 },
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

    @Test
    fun testSectionRestrictedIptvSearchIsolation() {
        val mixedPool = listOf(
            IptvStreamItem(
                id = "live_1",
                sourceId = "s1",
                contentType = IptvContentType.LIVE_TV,
                categoryId = "c_live",
                categoryName = "Sports Live",
                title = "Action Sports HD",
                streamUrl = "http://example.com/live/1.ts"
            ),
            IptvStreamItem(
                id = "movie_1",
                sourceId = "s1",
                contentType = IptvContentType.MOVIES,
                categoryId = "c_mov",
                categoryName = "Action Movies",
                title = "Action Hero 2026",
                streamUrl = "http://example.com/movie/1.mp4"
            ),
            IptvStreamItem(
                id = "series_1",
                sourceId = "s1",
                contentType = IptvContentType.SERIES,
                categoryId = "c_ser",
                categoryName = "Action Series",
                title = "Action Chronicles S01",
                seriesName = "Action Chronicles",
                streamUrl = "http://example.com/series/1.mkv"
            )
        )

        // 1. Inside Movies -> must return ONLY Movies
        val moviesSection = SectionScopedSearchHelper.resolveActiveSearchSection(2, 2, IptvContentType.MOVIES)
        assertEquals(IptvContentType.MOVIES, moviesSection)
        val movieResults = SectionScopedSearchHelper.filterStreamsForSectionSearch(mixedPool, "Action", moviesSection)
        assertEquals(1, movieResults.size)
        assertEquals(IptvContentType.MOVIES, movieResults.first().contentType)
        assertEquals("Action Hero 2026", movieResults.first().title)

        // 2. Inside TV Series -> must return ONLY TV Series
        val seriesSection = SectionScopedSearchHelper.resolveActiveSearchSection(2, 3, IptvContentType.SERIES)
        assertEquals(IptvContentType.SERIES, seriesSection)
        val seriesResults = SectionScopedSearchHelper.filterStreamsForSectionSearch(mixedPool, "Action", seriesSection)
        assertEquals(1, seriesResults.size)
        assertEquals(IptvContentType.SERIES, seriesResults.first().contentType)
        assertEquals("Action Chronicles S01", seriesResults.first().title)

        // 3. Inside Live TV -> must return ONLY Live TV
        val liveSection = SectionScopedSearchHelper.resolveActiveSearchSection(2, 2, IptvContentType.LIVE_TV)
        assertEquals(IptvContentType.LIVE_TV, liveSection)
        val liveResults = SectionScopedSearchHelper.filterStreamsForSectionSearch(mixedPool, "Action", liveSection)
        assertEquals(1, liveResults.size)
        assertEquals(IptvContentType.LIVE_TV, liveResults.first().contentType)
        assertEquals("Action Sports HD", liveResults.first().title)
    }

    @Test
    fun testChangelogDisplaysStrictlyLatestFourVersions() {
        assertEquals(4, ChangelogVersionManager.MAX_CHANGELOG_VERSIONS)

        val fullHistory = listOf(
            ChangelogReleaseEntry("90.10", "2026-11-18", "📦", "v90.10", "Play Console release 9010", isLatest = true),
            ChangelogReleaseEntry("90.09", "2026-11-18", "🛡️", "v90.09", "API 36 compliance", isLatest = false),
            ChangelogReleaseEntry("90.08", "2026-11-18", "🚀", "v90.08", "VEXO Player brand identity", isLatest = false),
            ChangelogReleaseEntry("90.07", "2026-11-18", "✅", "v90.07", "Store readiness", isLatest = false),
            ChangelogReleaseEntry("90.06", "2026-11-18", "🔍", "v90.06", "Section search", isLatest = false)
        )

        val updated = ChangelogVersionManager.pushNewRelease(
            existingEntries = fullHistory,
            newRelease = ChangelogReleaseEntry("90.11", "2026-11-20", "🛠️", "v90.11", "Luxury Dark UI & Foldable support")
        )

        assertEquals("Changelog must display strictly the latest 4 versions", 4, updated.size)
        assertEquals("90.11", updated[0].version)
        assertTrue(updated[0].isLatest)
        assertEquals("90.10", updated[1].version)
        assertEquals("90.09", updated[2].version)
        assertEquals("90.08", updated[3].version)
        assertFalse("Older version 90.07 must not be displayed in latest 4", updated.any { it.version == "90.07" })
    }

    @Test
    fun testFoldablePosturesAndHingeSplits() {
        // 1. Folded cover screen (Z Fold / Z Flip outer display)
        val cover = FoldableAdaptiveMath.resolvePosture(
            widthDp = 340, heightDp = 720,
            isHalfOpened = false, isHorizontalHinge = false,
            isSeparating = false, hasOcclusion = false, hingeBounds = null
        )
        assertEquals(WindowWidthSizeClass.COMPACT, cover.widthClass)
        assertEquals(FoldPosture.FLAT_COMPACT, cover.posture)
        assertEquals(0, FoldableAdaptiveMath.computeDialogMaxWidthDp(cover.widthClass))

        // 2. Unfolded inner screen (Expanded)
        val unfolded = FoldableAdaptiveMath.resolvePosture(
            widthDp = 880, heightDp = 740,
            isHalfOpened = false, isHorizontalHinge = false,
            isSeparating = false, hasOcclusion = false, hingeBounds = null
        )
        assertEquals(WindowWidthSizeClass.EXPANDED, unfolded.widthClass)
        assertEquals(FoldPosture.FLAT_EXPANDED, unfolded.posture)
        assertEquals(680, FoldableAdaptiveMath.computeDialogMaxWidthDp(unfolded.widthClass))

        // 3. Tabletop posture (Half-opened horizontal hinge)
        val tabletopHinge = HingeBounds(0, 1060, 2208, 1100)
        val tabletop = FoldableAdaptiveMath.resolvePosture(
            widthDp = 740, heightDp = 740,
            isHalfOpened = true, isHorizontalHinge = true,
            isSeparating = true, hasOcclusion = true, hingeBounds = tabletopHinge
        )
        assertEquals(FoldPosture.TABLETOP, tabletop.posture)
        val (upperH, lowerH) = FoldableAdaptiveMath.computeTabletopSplit(2208, 1060, 1100)
        assertEquals(1060, upperH)
        assertEquals(1108, lowerH)

        // 4. Book posture (Half-opened vertical hinge)
        val bookHinge = HingeBounds(1080, 0, 1120, 1840)
        val book = FoldableAdaptiveMath.resolvePosture(
            widthDp = 740, heightDp = 620,
            isHalfOpened = true, isHorizontalHinge = false,
            isSeparating = true, hasOcclusion = true, hingeBounds = bookHinge
        )
        assertEquals(FoldPosture.BOOK, book.posture)
        val (startW, endW) = FoldableAdaptiveMath.computeBookSplit(2200, 1080, 1120)
        assertEquals(1080, startW)
        assertEquals(1080, endW)
    }

    @Test
    fun testIptvSessionSyncGuardAutoRefreshAndNoRepeatOnScrollOrSubSections() {
        val guard = IptvSessionSyncGuard()

        assertTrue("First entry into main IPTV section must trigger refresh", guard.onEnterMainIptvSection())
        assertTrue(guard.isInsideIptvSection)
        assertTrue(guard.hasSyncedInCurrentVisit)

        assertFalse("Must not re-trigger refresh while already in IPTV section", guard.onEnterMainIptvSection())
        assertFalse(guard.onInternalSectionNavigation(2, IptvContentType.MOVIES, null, 0))
        assertFalse(guard.onInternalSectionNavigation(3, IptvContentType.SERIES, "SERIES_1", 0))
        assertFalse(guard.onInternalSectionNavigation(2, IptvContentType.LIVE_TV, null, 0))
        assertFalse(guard.onScrollInsideIptv())
        assertFalse(guard.shouldEnableSwipeRefreshGesture(currentMainTab = 2, iptvNavLevel = 3))

        guard.onLeaveMainIptvSection()
        assertFalse(guard.isInsideIptvSection)
        assertTrue("Reopening main IPTV section after leaving must trigger fresh sync", guard.onEnterMainIptvSection())
    }

    @Test
    fun testResponsiveUiMetricsAcrossPhonesTabletsAndLargeScreens() {
        assertEquals("V Player", ResponsiveUiMetrics.APP_DISPLAY_NAME)
        assertEquals("IPTV and Video player", ResponsiveUiMetrics.APP_SUBTITLE_NAME)
        assertEquals(DeviceFormFactor.COMPACT_PHONE, ResponsiveUiMetrics.classifyDevice(320, 320))
        assertEquals(DeviceFormFactor.STANDARD_PHONE, ResponsiveUiMetrics.classifyDevice(390, 390))
        assertEquals(DeviceFormFactor.TABLET, ResponsiveUiMetrics.classifyDevice(600, 600))
        assertEquals(DeviceFormFactor.LARGE_SCREEN, ResponsiveUiMetrics.classifyDevice(1024, 720))
    }

    @Test
    fun testVlcSurfaceLayoutFitThenStretchNeverCrops() {
        val fitResult = VlcSurfaceLayoutMath.computeLayout(
            sw = 2400, sh = 1080,
            videoWidth = 1920, videoHeight = 1080,
            visibleWidth = 1920, visibleHeight = 1080,
            sarNum = 1, sarDen = 1,
            mode = VlcAspectMode.SURFACE_FIT_SCREEN
        )
        assertEquals(0f, fitResult.scale)
        assertTrue(fitResult.surfaceWidth >= 2400)

        val stretchResult = VlcSurfaceLayoutMath.computeLayout(
            sw = 2400, sh = 1080,
            videoWidth = 1920, videoHeight = 1080,
            visibleWidth = 1920, visibleHeight = 1080,
            sarNum = 1, sarDen = 1,
            mode = VlcAspectMode.SURFACE_FILL
        )
        assertEquals(2400, stretchResult.surfaceWidth)
        assertEquals(1080, stretchResult.surfaceHeight)
        assertEquals("2400:1080", stretchResult.aspectRatio)
        assertEquals(0f, stretchResult.scale)
    }

    @Test
    fun testVexoAudioEffectsVlcPresetsAndBandResampling() {
        assertEquals(16, VexoAudioEffectsManager.vlcPresets.size)
        val rock = VexoAudioEffectsManager.vlcPresets.first { it.name == "Rock" }
        val fiveBand = VexoAudioEffectsManager.resampleBands(rock.bandsDb, 5)
        assertEquals(5, fiveBand.size)
        assertEquals(rock.bandsDb.first(), fiveBand.first(), 0.01f)
        assertEquals(rock.bandsDb.last(), fiveBand.last(), 0.01f)
    }

    @Test
    fun testSleepTimerFormattingAndEndOfMediaMode() {
        assertEquals("15:00", SleepTimerManager.formatRemaining(15 * 60_000L))
        assertEquals("1:05:30", SleepTimerManager.formatRemaining((65 * 60 + 30) * 1000L))
        SleepTimerManager.startEndOfCurrentMedia()
        assertEquals(SleepTimerMode.END_OF_MEDIA, SleepTimerManager.state.value.mode)
        assertTrue(SleepTimerManager.onMediaTrackEnded())
        assertEquals(SleepTimerMode.OFF, SleepTimerManager.state.value.mode)
    }

    @Test
    fun selectionViewModel_multiSelectAndSelectAll() {
        val vm = SelectionViewModel()
        assertFalse(vm.isSelectionActive())
        vm.enterSelectionWith("v:content://1", isVault = false)
        assertTrue(vm.isSelectionActive())
        assertEquals(1, vm.state.value.count)

        vm.toggleKey("f:Movies", isVault = false)
        assertEquals(2, vm.state.value.count)
        assertTrue(vm.isSelected("v:content://1"))
        assertTrue(vm.isSelected("f:Movies"))

        vm.selectAll(listOf("v:content://1", "f:Movies", "v:content://2"), isVault = false)
        assertEquals(3, vm.state.value.count)

        // Calling selectAll again when all are selected exits selection mode
        vm.selectAll(listOf("v:content://1", "f:Movies", "v:content://2"), isVault = false)
        assertFalse(vm.isSelectionActive())
    }

    @Test
    fun vaultFileOperations_duplicatePoliciesAndAtomicMove() {
        val tempDir = java.nio.file.Files.createTempDirectory("vexo_test_").toFile()
        val destDir = java.io.File(tempDir, "dest").apply { mkdirs() }
        val srcFile = java.io.File(tempDir, "clip.mp4").apply { writeText("VEXO_ATOMIC_TEST_DATA") }
        val existing = java.io.File(destDir, "clip.mp4").apply { writeText("OLD") }

        assertNull(VaultFileOperations.resolveDestinationFile(destDir, "clip.mp4", DuplicateResolution.SKIP))
        assertEquals(existing, VaultFileOperations.resolveDestinationFile(destDir, "clip.mp4", DuplicateResolution.REPLACE))
        val keepBoth = VaultFileOperations.resolveDestinationFile(destDir, "clip.mp4", DuplicateResolution.KEEP_BOTH)
        assertNotNull(keepBoth)
        assertEquals("clip (1).mp4", keepBoth!!.name)

        // Move folder into itself guard
        assertTrue(VaultFileOperations.isMovingFolderIntoItself(tempDir, destDir))
        assertFalse(VaultFileOperations.isMovingFolderIntoItself(destDir, tempDir))

        // Atomic move with CRC32 verification
        val movedOk = VaultFileOperations.atomicCopyOrMoveFile(srcFile, keepBoth, deleteSourceAfterVerify = true)
        assertTrue(movedOk)
        assertFalse(srcFile.exists())
        assertTrue(keepBoth.exists())
        assertEquals("VEXO_ATOMIC_TEST_DATA", keepBoth.readText())

        // .nomedia creation
        VaultFileOperations.ensureNoMediaMarker(destDir)
        assertTrue(java.io.File(destDir, ".nomedia").exists())

        tempDir.deleteRecursively()
    }

    @Test
    fun batteryOptimization_autoThresholdAndLowBatteryAlerts() {
        assertTrue(BatteryOptimizationManager.shouldBatterySaverBeActive(true, false, false, 90, 15))
        assertTrue(BatteryOptimizationManager.shouldBatterySaverBeActive(false, true, true, 80, 15))
        assertTrue(BatteryOptimizationManager.shouldBatterySaverBeActive(false, true, false, 15, 15))
        assertFalse(BatteryOptimizationManager.shouldBatterySaverBeActive(false, true, false, 40, 15))

        assertEquals(15, BatteryOptimizationManager.evaluateLowBatteryAlert(15, false, false, false))
        assertNull(BatteryOptimizationManager.evaluateLowBatteryAlert(15, false, true, false))
        assertEquals(5, BatteryOptimizationManager.evaluateLowBatteryAlert(5, false, true, false))
        assertNull(BatteryOptimizationManager.evaluateLowBatteryAlert(5, true, false, false))
    }

    @Test
    fun vaultFileOperations_conflictDetectionAndSameDir() {
        val tempDir = java.nio.file.Files.createTempDirectory("vexo_conflict_").toFile()
        val existing = java.io.File(tempDir, "movie.mp4").apply { writeText("DATA") }
        assertTrue(VaultFileOperations.isSameDirectory(existing, tempDir))
        assertEquals(listOf("movie.mp4"), VaultFileOperations.hasAnyConflict(tempDir, listOf("movie.mp4", "fresh.mp4")))
        assertTrue(VaultFileOperations.hasAnyConflict(tempDir, listOf("fresh.mp4")).isEmpty())
        tempDir.deleteRecursively()
    }

    @Test
    fun responsiveSettingsMenuWidth_allScreenSizes() {
        // Phone Portrait 360x800dp -> Compact (0 = MATCH_PARENT, content = 360 - 40 = 320dp)
        assertEquals(0, FoldableAdaptiveMath.computeEffectiveMenuWidthDp(360))
        assertEquals(20, FoldableAdaptiveMath.computeMenuInnerPaddingDp(360))

        // Phone Landscape 800x360dp & Tablet Portrait 800x1280dp -> Medium (600dp, content = 560dp)
        assertEquals(600, FoldableAdaptiveMath.computeEffectiveMenuWidthDp(800))
        assertTrue(FoldableAdaptiveMath.computeEffectiveMenuWidthDp(800) - 2 * FoldableAdaptiveMath.computeMenuInnerPaddingDp(800) >= 520)

        // Foldable Unfolded 840x900dp -> Expanded (680dp, content = 640dp)
        assertEquals(680, FoldableAdaptiveMath.computeEffectiveMenuWidthDp(840))

        // Tablet 10" Landscape 1280x800dp -> Extra-Large (760dp, content = 720dp — never ~75dp!)
        val tabletWidth = FoldableAdaptiveMath.computeEffectiveMenuWidthDp(1280)
        val tabletInnerPad = FoldableAdaptiveMath.computeMenuInnerPaddingDp(1280)
        assertEquals(760, tabletWidth)
        assertEquals(720, tabletWidth - 2 * tabletInnerPad)

        // Desktop / ChromeOS 1920x1080dp & Ultra-Wide 2560dp -> Extra-Large (760dp centered, content = 720dp)
        assertEquals(760, FoldableAdaptiveMath.computeEffectiveMenuWidthDp(1920))
        assertEquals(760, FoldableAdaptiveMath.computeEffectiveMenuWidthDp(2560))
        assertEquals(720, FoldableAdaptiveMath.computeEffectiveMenuWidthDp(2560) - 2 * FoldableAdaptiveMath.computeMenuInnerPaddingDp(2560))

        // Ultra-wide grid/list spans & bottom nav centering
        assertEquals(7, ResponsiveUiMetrics.calculateAdaptiveSpanCount(2560, 2560, isGridMode = true, isPosterSection = false))
        assertEquals(8, ResponsiveUiMetrics.calculateAdaptiveSpanCount(2560, 2560, isGridMode = true, isPosterSection = true))
        assertEquals(3, ResponsiveUiMetrics.calculateAdaptiveSpanCount(1920, 1920, isGridMode = false))
        assertEquals(12, FoldableAdaptiveMath.computeBottomNavSidePaddingDp(360))
        assertTrue(FoldableAdaptiveMath.computeBottomNavSidePaddingDp(1920) >= 600)

        // Tablet Split-screen 640dp -> Medium (600dp) & narrow split 420dp -> Compact (MATCH_PARENT)
        assertEquals(600, FoldableAdaptiveMath.computeEffectiveMenuWidthDp(640))
        assertEquals(0, FoldableAdaptiveMath.computeEffectiveMenuWidthDp(420))
    }

    @Test
    fun tvFocusHelper_navigationRailAndOverscan() {
        assertFalse(TvFocusHelper.shouldUseSideNavigationRail(isTv = false, screenWidthDp = 360))
        assertFalse(TvFocusHelper.shouldUseSideNavigationRail(isTv = false, screenWidthDp = 640))
        assertTrue(TvFocusHelper.shouldUseSideNavigationRail(isTv = false, screenWidthDp = 840))
        assertTrue(TvFocusHelper.shouldUseSideNavigationRail(isTv = false, screenWidthDp = 1280))
        assertTrue(TvFocusHelper.shouldUseSideNavigationRail(isTv = true, screenWidthDp = 720))

        assertEquals(Pair(36, 20), TvFocusHelper.getOverscanMarginsDp(isTv = true))
        assertEquals(Pair(0, 0), TvFocusHelper.getOverscanMarginsDp(isTv = false))
    }

    @Test
    fun subtitleAutoTranslator_tagsArabicCacheAndSrtRoundTrip() {
        // 1. Inline tag protection & restoration
        val raw = "<i>{\\an8}Hello world!</i>"
        val protected = SubtitleAutoTranslator.protectInlineTags(raw)
        assertFalse(protected.cleanText.contains("<i>"))
        assertFalse(protected.cleanText.contains("{\\an8}"))
        val restored = SubtitleAutoTranslator.restoreInlineTags(
            protected.cleanText.replace("Hello world!", "مرحبا بالعالم!"),
            protected.tokens
        )
        assertEquals("<i>{\\an8}مرحبا بالعالم!</i>", restored)

        // 2. Arabic RTL & punctuation normalization
        val arNormalized = SubtitleAutoTranslator.normalizeArabicSubtitleText("كيف حالك ?\nأنا بخير , شكرا ;")
        assertTrue(arNormalized.contains("؟"))
        assertTrue(arNormalized.contains("،"))
        assertTrue(arNormalized.contains("؛"))
        assertTrue(arNormalized.lines().all { it.startsWith("\u200F") })

        // 3. SRT serialization round-trip with SubtitleParser
        val cues = listOf(
            SubtitleCue(1_500L, 4_250L, "Line one\nLine two"),
            SubtitleCue(65_000L, 69_100L, "Second cue")
        )
        val srt = SubtitleAutoTranslator.serializeToSrt(cues)
        assertTrue(srt.contains("00:00:01,500 --> 00:00:04,250"))
        val parsed = SubtitleParser.parseSubtitleText(srt)
        assertEquals(2, parsed.size)
        assertEquals(1_500L, parsed[0].startMs)
        assertEquals("Line one\nLine two", parsed[0].text)

        // 4. Deterministic cache keys & provider fallback
        val sampleBytes = srt.toByteArray(Charsets.UTF_8)
        val k1 = SubtitleAutoTranslator.computeCacheKey(sampleBytes, "en", "ar", "free_google", "msa")
        val k2 = SubtitleAutoTranslator.computeCacheKey(sampleBytes, "en", "ar", "free_google", "msa")
        val k3 = SubtitleAutoTranslator.computeCacheKey(sampleBytes, "en", "fr", "free_google", "msa")
        assertEquals(k1, k2)
        assertFalse(k1 == k3)

        assertEquals("free_google", SubtitleAutoTranslator.resolveEffectiveProvider("deepl", ""))
        assertEquals("deepl", SubtitleAutoTranslator.resolveEffectiveProvider("deepl", "key-123:fx"))
        assertEquals("Arabic (auto-translated from English)", SubtitleAutoTranslator.buildTranslatedLabel("ar", "en", arabicUi = false))
        assertEquals("العربية (مترجمة تلقائياً من الإنجليزية)", SubtitleAutoTranslator.buildTranslatedLabel("ar", "en", arabicUi = true))
    }

    private class FakeGestureCallbacks(var activeSpeed: Float = 1.0f) : PlayerGestureStateMachine.Callbacks {
        var scheduledLongPressToken: Long? = null
        var longPressCancelledCount = 0
        var scheduledSingleTapToken: Long? = null
        var singleTapCancelledCount = 0
        var longPressStartCount = 0
        var longPressEndCount = 0
        var brightnessEvents = mutableListOf<Float>()
        var volumeEvents = mutableListOf<Float>()
        var seekPreviews = mutableListOf<Long>()
        var seekCommits = mutableListOf<Long>()
        var seekCancelCount = 0
        var doubleTapZones = mutableListOf<TapZone>()
        var singleTapCount = 0
        var multiTouchCancelCount = 0

        override fun scheduleLongPress(delayMs: Long, token: Long) { scheduledLongPressToken = token }
        override fun cancelLongPress() { scheduledLongPressToken = null; longPressCancelledCount++ }
        override fun scheduleSingleTap(delayMs: Long, token: Long) { scheduledSingleTapToken = token }
        override fun cancelSingleTap() { scheduledSingleTapToken = null; singleTapCancelledCount++ }
        override fun getCurrentPlaybackSpeed(): Float = activeSpeed
        override fun onLongPressSpeedStart(targetSpeed: Float, savedOriginalSpeed: Float) {
            longPressStartCount++
            activeSpeed = targetSpeed
        }
        override fun onLongPressSpeedEnd(restoredSpeed: Float) {
            longPressEndCount++
            activeSpeed = restoredSpeed
        }
        override fun onBrightnessDelta(deltaFraction: Float) { brightnessEvents.add(deltaFraction) }
        override fun onVolumeDelta(deltaFraction: Float) { volumeEvents.add(deltaFraction) }
        override fun onSeekPreview(deltaMs: Long) { seekPreviews.add(deltaMs) }
        override fun onSeekCommit(deltaMs: Long) { seekCommits.add(deltaMs) }
        override fun onSeekCancel() { seekCancelCount++ }
        override fun onDoubleTap(zone: TapZone) { doubleTapZones.add(zone) }
        override fun onSingleTapConfirmed() { singleTapCount++ }
        override fun onMultiTouchCancel() { multiTouchCancelCount++ }
    }

    @Test
    fun playerGestureStateMachine_classificationAndStrictAxisLocking() {
        val cb = FakeGestureCallbacks(activeSpeed = 1.0f)
        val sm = PlayerGestureStateMachine(touchSlopPx = 20f, edgeExclusionPx = 48f, callbacks = cb)

        // 1. Edge zone & on-controls touches are ignored
        assertFalse(sm.onPointerEvent(PointerInput(PointerAction.DOWN, 10f, 300f, 100L, 1, 1000f, 600f)))
        assertEquals(PlayerGestureState.IDLE, sm.state)
        assertFalse(sm.onPointerEvent(PointerInput(PointerAction.DOWN, 500f, 500f, 120L, 1, 1000f, 600f, isOnControls = true)))
        assertEquals(PlayerGestureState.IDLE, sm.state)

        // 2. Vertical drag on RIGHT half -> locks VOLUME; later huge horizontal drift MUST NOT seek
        assertTrue(sm.onPointerEvent(PointerInput(PointerAction.DOWN, 750f, 300f, 200L, 1, 1000f, 600f)))
        assertEquals(PlayerGestureState.PENDING, sm.state)
        val lpToken = cb.scheduledLongPressToken!!
        sm.onPointerEvent(PointerInput(PointerAction.MOVE, 752f, 250f, 240L, 1, 1000f, 600f))
        assertEquals(PlayerGestureState.VOLUME, sm.state)
        assertNull(cb.scheduledLongPressToken) // long-press cancelled permanently
        sm.onLongPressTimeout(lpToken) // stale timer must be ignored
        assertEquals(0, cb.longPressStartCount)
        // Diagonal/horizontal drift mid-touch stays locked to VOLUME, never seeks
        sm.onPointerEvent(PointerInput(PointerAction.MOVE, 300f, 240f, 280L, 1, 1000f, 600f))
        assertEquals(PlayerGestureState.VOLUME, sm.state)
        assertTrue(cb.seekPreviews.isEmpty())
        assertTrue(cb.volumeEvents.isNotEmpty())
        sm.onPointerEvent(PointerInput(PointerAction.UP, 300f, 240f, 320L, 1, 1000f, 600f))
        assertEquals(PlayerGestureState.IDLE, sm.state)

        // 3. Vertical drag on LEFT half -> locks BRIGHTNESS
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 200f, 300f, 500L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.MOVE, 205f, 240f, 540L, 1, 1000f, 600f))
        assertEquals(PlayerGestureState.BRIGHTNESS, sm.state)
        assertTrue(cb.brightnessEvents.isNotEmpty())
        sm.onPointerEvent(PointerInput(PointerAction.UP, 205f, 240f, 580L, 1, 1000f, 600f))

        // 4. Horizontal drag -> locks SEEK, previews only, commits on UP (and NOT on CANCEL)
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 400f, 300f, 700L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.MOVE, 500f, 305f, 740L, 1, 1000f, 600f))
        assertEquals(PlayerGestureState.SEEK, sm.state)
        assertTrue(cb.seekPreviews.isNotEmpty())
        assertTrue(cb.seekCommits.isEmpty())
        sm.onPointerEvent(PointerInput(PointerAction.UP, 500f, 305f, 780L, 1, 1000f, 600f))
        assertEquals(1, cb.seekCommits.size)

        // Horizontal drag cancelled -> never commits
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 400f, 300f, 900L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.MOVE, 550f, 300f, 940L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.CANCEL, 550f, 300f, 960L, 1, 1000f, 600f))
        assertEquals(1, cb.seekCommits.size)
        assertEquals(1, cb.seekCancelCount)
    }

    @Test
    fun playerGestureStateMachine_longPressSpeedRestoreNonDefaultAndMultiTouch() {
        val cb = FakeGestureCallbacks(activeSpeed = 1.5f)
        val sm = PlayerGestureStateMachine(touchSlopPx = 20f, edgeExclusionPx = 48f, callbacks = cb)

        // 1. Long-press from 1.5x -> 2.0x -> ignores swipes while active -> restores EXACT 1.5x on UP
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 600f, 300f, 100L, 1, 1000f, 600f))
        val token1 = cb.scheduledLongPressToken!!
        sm.onLongPressTimeout(token1)
        assertEquals(PlayerGestureState.LONG_PRESS_SPEED, sm.state)
        assertEquals(2.0f, cb.activeSpeed, 0.001f)

        // Moving finger while LONG_PRESS_SPEED is active must NOT trigger volume/brightness/seek
        sm.onPointerEvent(PointerInput(PointerAction.MOVE, 800f, 100f, 600L, 1, 1000f, 600f))
        assertEquals(PlayerGestureState.LONG_PRESS_SPEED, sm.state)
        assertTrue(cb.volumeEvents.isEmpty())
        assertTrue(cb.seekPreviews.isEmpty())

        sm.onPointerEvent(PointerInput(PointerAction.UP, 800f, 100f, 700L, 1, 1000f, 600f))
        assertEquals(PlayerGestureState.IDLE, sm.state)
        assertEquals(1.5f, cb.activeSpeed, 0.001f)

        // 2. Multi-touch while LONG_PRESS_SPEED is active cancels & restores exact 1.5x
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 600f, 300f, 1000L, 1, 1000f, 600f))
        sm.onLongPressTimeout(cb.scheduledLongPressToken!!)
        assertEquals(2.0f, cb.activeSpeed, 0.001f)
        assertFalse(sm.onPointerEvent(PointerInput(PointerAction.POINTER_DOWN, 400f, 300f, 1500L, 2, 1000f, 600f)))
        assertEquals(PlayerGestureState.IDLE, sm.state)
        assertEquals(1.5f, cb.activeSpeed, 0.001f)
        assertEquals(1, cb.multiTouchCancelCount)

        // 3. Lifecycle cancelAll() restores exact 1.25x speed
        cb.activeSpeed = 1.25f
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 600f, 300f, 2000L, 1, 1000f, 600f))
        sm.onLongPressTimeout(cb.scheduledLongPressToken!!)
        assertEquals(2.0f, cb.activeSpeed, 0.001f)
        sm.cancelAll()
        assertEquals(PlayerGestureState.IDLE, sm.state)
        assertEquals(1.25f, cb.activeSpeed, 0.001f)
    }

    @Test
    fun playerGestureStateMachine_doubleTapAndSingleTapTiming() {
        val cb = FakeGestureCallbacks(activeSpeed = 1.0f)
        val sm = PlayerGestureStateMachine(touchSlopPx = 20f, edgeExclusionPx = 48f, callbacks = cb)

        // 1. Single tap waits for double-tap window before firing
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 500f, 300f, 100L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.UP, 502f, 301f, 160L, 1, 1000f, 600f))
        assertEquals(0, cb.singleTapCount)
        val tapToken = cb.scheduledSingleTapToken!!
        sm.onSingleTapTimeout(tapToken)
        assertEquals(1, cb.singleTapCount)

        // 2. Double-tap on RIGHT side cancels single tap and fires TapZone.RIGHT
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 800f, 300f, 1000L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.UP, 802f, 300f, 1050L, 1, 1000f, 600f))
        val staleTapToken = cb.scheduledSingleTapToken!!
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 805f, 302f, 1180L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.UP, 805f, 302f, 1220L, 1, 1000f, 600f))
        sm.onSingleTapTimeout(staleTapToken) // stale single tap must NOT fire
        assertEquals(1, cb.singleTapCount)
        assertEquals(listOf(TapZone.RIGHT), cb.doubleTapZones)

        // 3. Double-tap on LEFT side fires TapZone.LEFT
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 200f, 300f, 2000L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.UP, 200f, 300f, 2040L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 205f, 300f, 2140L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.UP, 205f, 300f, 2180L, 1, 1000f, 600f))
        assertEquals(listOf(TapZone.RIGHT, TapZone.LEFT), cb.doubleTapZones)

        // 4. Tap immediately after a drag must NOT fire double-tap
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 800f, 300f, 3000L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.MOVE, 800f, 240f, 3040L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.UP, 800f, 240f, 3080L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.DOWN, 800f, 240f, 3150L, 1, 1000f, 600f))
        sm.onPointerEvent(PointerInput(PointerAction.UP, 800f, 240f, 3190L, 1, 1000f, 600f))
        assertEquals(2, cb.doubleTapZones.size)
    }

    @Test
    fun v9021_subtitleTimingClearingBilingualAndIconAliases() {
        val orig = listOf(
            SubtitleCue(1_000L, 3_500L, "Hello [music]"),
            SubtitleCue(5_000L, 7_000L, "How are you?")
        )
        val merged = SubtitleAutoTranslator.mergeTranslatedTextsPreservingTiming(
            orig,
            listOf("مرحباً [music]", "كيف حالك؟"),
            bilingual = false
        )
        assertEquals(2, merged.size)
        assertEquals(1_000L, merged[0].startMs)
        assertEquals(3_500L, merged[0].endMs)
        assertEquals(5_000L, merged[1].startMs)
        assertEquals(7_000L, merged[1].endMs)

        val bi = SubtitleAutoTranslator.mergeTranslatedTextsPreservingTiming(
            orig,
            listOf("مرحباً [music]", "كيف حالك؟"),
            bilingual = true
        )
        assertTrue(bi[0].text.contains("مرحباً [music]"))
        assertTrue(bi[0].text.contains("Hello [music]"))
        assertEquals(3_500L, bi[0].endMs)

        assertNull(SubtitleAutoTranslator.resolveActiveCueAt(merged, 999L))
        assertEquals("مرحباً [music]", SubtitleAutoTranslator.resolveActiveCueAt(merged, 1_000L)?.text)
        assertEquals("مرحباً [music]", SubtitleAutoTranslator.resolveActiveCueAt(merged, 3_499L)?.text)
        assertNull(SubtitleAutoTranslator.resolveActiveCueAt(merged, 3_500L))
        assertNull(SubtitleAutoTranslator.resolveActiveCueAt(merged, 4_200L))
        assertEquals("كيف حالك؟", SubtitleAutoTranslator.resolveActiveCueAt(merged, 5_000L)?.text)
        assertNull(SubtitleAutoTranslator.resolveActiveCueAt(merged, 7_000L))

        val prot = SubtitleAutoTranslator.protectInlineTags("♪ [music] Meet at 12:30 <i>now</i>")
        assertFalse(prot.cleanText.contains("[music]"))
        assertFalse(prot.cleanText.contains("♪"))
        assertFalse(prot.cleanText.contains("12:30"))
        assertEquals("♪ [music] Meet at 12:30 <i>now</i>", SubtitleAutoTranslator.restoreInlineTags(prot.cleanText, prot.tokens))

        assertEquals("ar", SubtitleAutoTranslator.detectSourceLanguage(listOf(SubtitleCue(0, 1000, "مرحباً بكم في الفيلم"))))
        assertEquals("en", SubtitleAutoTranslator.detectSourceLanguage(listOf(SubtitleCue(0, 1000, "What are you doing and where is the car?"))))

        for (opt in AppIconManager.allOptions) {
            val states = AppIconManager.computeAliasStates(opt.id)
            assertEquals(AppIconManager.allAliasClassNames.size, states.size)
            assertEquals(1, states.values.count { it })
            assertTrue(states[opt.aliasClassName] == true)
        }
        val fallback = AppIconManager.computeAliasStates("nonexistent_style")
        assertEquals(1, fallback.values.count { it })
        assertTrue(fallback["com.pureplayer.video.LauncherAliasDefault"] == true)
    }

    @Test
    fun v9022_hiddenFilteringPlaylistNavigationAndDebounce() {
        // 1. Hidden media classification
        assertTrue(HiddenMediaAndPlaylistHelper.isHiddenMedia("/storage/emulated/0/WhatsApp/Media/.Statuses/a.mp4", ".Statuses"))
        assertTrue(HiddenMediaAndPlaylistHelper.isHiddenMedia("/storage/emulated/0/Movies/Private/a.mp4", "Private", hasNomediaAncestor = true))
        assertTrue(HiddenMediaAndPlaylistHelper.isHiddenMedia("/storage/emulated/0/Android/data/com.app/files/v.mp4", "files"))
        assertTrue(HiddenMediaAndPlaylistHelper.isHiddenMedia("/storage/emulated/0/Movies/.trashed-123-clip.mp4", "Movies", ".trashed-123-clip.mp4"))
        assertFalse(HiddenMediaAndPlaylistHelper.isHiddenMedia("/storage/emulated/0/Movies/Action/hero.mp4", "Action", "hero.mp4"))

        // 2. Hidden folder & video filtering (ON vs OFF, pure Kotlin without Android Uri stubs)
        data class SampleMedia(val folder: String, val path: String, val hidden: Boolean)
        val samples = listOf(
            SampleMedia("Movies", "/storage/emulated/0/Movies/movie.mp4", false),
            SampleMedia(".Hidden", "/storage/emulated/0/.Hidden/secret.mp4", true),
            SampleMedia("Private", "/storage/emulated/0/Private/clip.mp4", true)
        )
        assertEquals(3, HiddenMediaAndPlaylistHelper.filterHiddenItems(samples, showHidden = true) { it.hidden }.size)
        val onlyVisible = HiddenMediaAndPlaylistHelper.filterHiddenItems(samples, showHidden = false) { it.hidden }
        assertEquals(1, onlyVisible.size)
        assertEquals("Movies", onlyVisible[0].folder)
        assertFalse(HiddenMediaAndPlaylistHelper.DEFAULT_SHOW_HIDDEN_FOLDERS)
        // One-time upgrade migration resets legacy true/missing values to OFF once
        assertEquals(Pair(false, true), HiddenMediaAndPlaylistHelper.resolveHiddenFoldersMigration(hasMigratedV2 = false, storedValue = true))
        assertEquals(Pair(false, true), HiddenMediaAndPlaylistHelper.resolveHiddenFoldersMigration(hasMigratedV2 = false, storedValue = null))
        // After migration, explicit user ON persists across restarts
        assertEquals(Pair(true, false), HiddenMediaAndPlaylistHelper.resolveHiddenFoldersMigration(hasMigratedV2 = true, storedValue = true))
        assertEquals(Pair(false, false), HiddenMediaAndPlaylistHelper.resolveHiddenFoldersMigration(hasMigratedV2 = true, storedValue = null))

        // 3. Previous / Next index calculation at boundaries (first, middle, last, single-item)
        assertFalse(HiddenMediaAndPlaylistHelper.canGoPrevious(0, 3))
        assertTrue(HiddenMediaAndPlaylistHelper.canGoNext(0, 3))
        assertNull(HiddenMediaAndPlaylistHelper.computePreviousIndex(0, 3))
        assertEquals(1, HiddenMediaAndPlaylistHelper.computeNextIndex(0, 3))

        assertTrue(HiddenMediaAndPlaylistHelper.canGoPrevious(1, 3))
        assertTrue(HiddenMediaAndPlaylistHelper.canGoNext(1, 3))
        assertEquals(0, HiddenMediaAndPlaylistHelper.computePreviousIndex(1, 3))
        assertEquals(2, HiddenMediaAndPlaylistHelper.computeNextIndex(1, 3))

        assertTrue(HiddenMediaAndPlaylistHelper.canGoPrevious(2, 3))
        assertFalse(HiddenMediaAndPlaylistHelper.canGoNext(2, 3))
        assertNull(HiddenMediaAndPlaylistHelper.computeNextIndex(2, 3))

        // Single video -> both disabled
        assertFalse(HiddenMediaAndPlaylistHelper.canGoPrevious(0, 1))
        assertFalse(HiddenMediaAndPlaylistHelper.canGoNext(0, 1))

        // Repeat-all wraps around cleanly
        assertEquals(2, HiddenMediaAndPlaylistHelper.computePreviousIndex(0, 3, repeatAll = true))
        assertEquals(0, HiddenMediaAndPlaylistHelper.computeNextIndex(2, 3, repeatAll = true))

        // 4. Switch tap debounce
        assertTrue(HiddenMediaAndPlaylistHelper.shouldAcceptSwitch(nowMs = 1000L, lastSwitchMs = 0L))
        assertFalse(HiddenMediaAndPlaylistHelper.shouldAcceptSwitch(nowMs = 1200L, lastSwitchMs = 1000L))
        assertTrue(HiddenMediaAndPlaylistHelper.shouldAcceptSwitch(nowMs = 1500L, lastSwitchMs = 1000L))
    }

    @Test
    fun v9023_shareBatchSummaryAndSubtitleDeletionCleanup() {
        // 1. Share batch summary & thresholds
        val smallSingle = HiddenMediaAndPlaylistHelper.summarizeShareBatch(listOf(5_000_000L), hasFolderSelection = false)
        assertEquals(1, smallSingle.totalCount)
        assertFalse(smallSingle.requiresConfirmation)
        assertFalse(smallSingle.exceedsMaxLimit)

        val folderBatch = HiddenMediaAndPlaylistHelper.summarizeShareBatch(listOf(2_000_000L), hasFolderSelection = true)
        assertTrue(folderBatch.requiresConfirmation)

        val largeBytesBatch = HiddenMediaAndPlaylistHelper.summarizeShareBatch(listOf(60L * 1024L * 1024L), hasFolderSelection = false)
        assertTrue(largeBytesBatch.requiresConfirmation)

        val tooMany = HiddenMediaAndPlaylistHelper.summarizeShareBatch(List(101) { 1024L }, hasFolderSelection = false)
        assertTrue(tooMany.exceedsMaxLimit)

        assertEquals("512 B", HiddenMediaAndPlaylistHelper.formatBytesReadable(512L))
        assertEquals("2.0 MB", HiddenMediaAndPlaylistHelper.formatBytesReadable(2L * 1024L * 1024L))

        // 2. App-downloaded subtitle listing & safe deletion (never deletes files outside subtitles_cache)
        val tmpRoot = java.nio.file.Files.createTempDirectory("vplayer_sub_del_").toFile()
        try {
            val cacheDir = java.io.File(tmpRoot, "subtitles_cache").apply { mkdirs() }
            val trDir = java.io.File(cacheDir, "translated").apply { mkdirs() }
            val origSub = java.io.File(cacheDir, "sub_en_123.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHello") }
            val trSub = java.io.File(trDir, "tr_ar_abc123.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nمرحباً") }
            val trJson = java.io.File(trDir, "cues_ar_abc123.json").apply { writeText("{\"0\":\"مرحباً\"}") }
            val userOwnedOutside = java.io.File(tmpRoot, "user_movie.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nKeep me") }

            val listed = SubtitleAutoTranslator.listAppDownloadedSubtitles(tmpRoot)
            assertEquals(2, listed.size)

            // Refuses to delete user-owned file outside subtitles_cache
            assertEquals(0, SubtitleAutoTranslator.deleteDownloadedSubtitleAndRelatedCaches(tmpRoot, userOwnedOutside))
            assertTrue(userOwnedOutside.exists())

            // Deleting translated SRT also cleans up its cues_*.json cache
            val deleted = SubtitleAutoTranslator.deleteDownloadedSubtitleAndRelatedCaches(tmpRoot, trSub)
            assertEquals(2, deleted)
            assertFalse(trSub.exists())
            assertFalse(trJson.exists())
            assertTrue(origSub.exists())
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    @Test
    fun v9024_vaultAutoLockPoliciesAndEdgeCases() {
        // 1. Immediate mode: locks on tab leave or app leave, NEVER during config change, biometric, or active Vault playback
        assertTrue(HiddenMediaAndPlaylistHelper.shouldAutoLockVault(
            mode = PlaybackStore.VAULT_AUTOLOCK_IMMEDIATE, leftVaultTimestampMs = 1000L, nowMs = 1000L,
            delaySeconds = 60, isVaultMediaPlaying = false, isLeavingApp = false, isLeavingVaultSection = true, isConfigChangeOrBiometric = false
        ))
        assertFalse(HiddenMediaAndPlaylistHelper.shouldAutoLockVault(
            mode = PlaybackStore.VAULT_AUTOLOCK_IMMEDIATE, leftVaultTimestampMs = 1000L, nowMs = 1000L,
            delaySeconds = 60, isVaultMediaPlaying = true, isLeavingApp = true, isLeavingVaultSection = true, isConfigChangeOrBiometric = false
        ))
        assertFalse(HiddenMediaAndPlaylistHelper.shouldAutoLockVault(
            mode = PlaybackStore.VAULT_AUTOLOCK_IMMEDIATE, leftVaultTimestampMs = 1000L, nowMs = 1000L,
            delaySeconds = 60, isVaultMediaPlaying = false, isLeavingApp = true, isLeavingVaultSection = false, isConfigChangeOrBiometric = true
        ))

        // 2. On App Leave mode: does NOT lock when switching tabs inside app, locks on app leave
        assertFalse(HiddenMediaAndPlaylistHelper.shouldAutoLockVault(
            mode = PlaybackStore.VAULT_AUTOLOCK_ON_APP_LEAVE, leftVaultTimestampMs = 1000L, nowMs = 1000L,
            delaySeconds = 60, isVaultMediaPlaying = false, isLeavingApp = false, isLeavingVaultSection = true, isConfigChangeOrBiometric = false
        ))
        assertTrue(HiddenMediaAndPlaylistHelper.shouldAutoLockVault(
            mode = PlaybackStore.VAULT_AUTOLOCK_ON_APP_LEAVE, leftVaultTimestampMs = 1000L, nowMs = 1000L,
            delaySeconds = 60, isVaultMediaPlaying = false, isLeavingApp = true, isLeavingVaultSection = false, isConfigChangeOrBiometric = false
        ))

        // 3. After Delay mode: stays unlocked before expiry, locks at/after expiry
        assertFalse(HiddenMediaAndPlaylistHelper.shouldAutoLockVault(
            mode = PlaybackStore.VAULT_AUTOLOCK_AFTER_DELAY, leftVaultTimestampMs = 10_000L, nowMs = 39_999L,
            delaySeconds = 30, isVaultMediaPlaying = false, isLeavingApp = true, isLeavingVaultSection = true, isConfigChangeOrBiometric = false
        ))
        assertTrue(HiddenMediaAndPlaylistHelper.shouldAutoLockVault(
            mode = PlaybackStore.VAULT_AUTOLOCK_AFTER_DELAY, leftVaultTimestampMs = 10_000L, nowMs = 40_000L,
            delaySeconds = 30, isVaultMediaPlaying = false, isLeavingApp = false, isLeavingVaultSection = false, isConfigChangeOrBiometric = false
        ))
    }

    @Test
    fun v9025_smartVideoOrientationMathAndEdgeCases() {
        // 1. Standard 16:9 landscape movie -> LANDSCAPE
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.LANDSCAPE,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(1920, 1080, rotationDegrees = 0)
        )
        // 2. Portrait phone recording (1080x1920) -> PORTRAIT
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.PORTRAIT,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(1080, 1920, rotationDegrees = 0)
        )
        // 3. Stored 1920x1080 but rotated 90° or 270° -> PORTRAIT!
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.PORTRAIT,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(1920, 1080, rotationDegrees = 90)
        )
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.PORTRAIT,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(1920, 1080, rotationDegrees = 270)
        )
        // 4. Anamorphic video (720x576 with 1.422 PAR -> ~1024x576) -> LANDSCAPE
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.LANDSCAPE,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(720, 576, rotationDegrees = 0, pixelWidthHeightRatio = 1.422f)
        )
        // 5. Square / nearly square (within ~5% of 1:1) -> KEEP_CURRENT
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.KEEP_CURRENT,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(1080, 1080)
        )
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.KEEP_CURRENT,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(1040, 1000)
        )
        // 6. Edge cases: PiP, multi-window, tablet portrait, unknown dimensions -> KEEP_CURRENT
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.KEEP_CURRENT,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(1920, 1080, isInPip = true)
        )
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.KEEP_CURRENT,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(1920, 1080, isInMultiWindow = true)
        )
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.KEEP_CURRENT,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(1080, 1920, smallestScreenWidthDp = 720)
        )
        assertEquals(
            HiddenMediaAndPlaylistHelper.VideoOrientationDecision.KEEP_CURRENT,
            HiddenMediaAndPlaylistHelper.decideSmartVideoOrientation(0, 0)
        )
    }

    @Test
    fun androidAutoUxPolicy_sanitizationCapsMinimalDisplayAndAudioOnlyArtwork() {
        // 1. Controller package detection
        assertTrue(AndroidAutoUxPolicy.isAndroidAutoControllerPackage("com.google.android.projection.gearhead"))
        assertTrue(AndroidAutoUxPolicy.isAndroidAutoControllerPackage("com.google.android.autosimulator"))
        assertFalse(AndroidAutoUxPolicy.isAndroidAutoControllerPackage("com.pureplayer.video"))
        assertFalse(AndroidAutoUxPolicy.isAndroidAutoControllerPackage(null))

        // 2. Minimal display strips noisy tags and keeps only concise group/channel name
        val minimalCfg = AndroidAutoConfig(enabled = true, autoResumeOnConnect = false, audioOnlyOnCar = true, minimalCarDisplay = true)
        val (minTitle, minSub) = AndroidAutoUxPolicy.formatDisplayMetadata(
            rawTitle = "  Al Jazeera [FHD] [H.265] Live  ",
            rawGroupOrSource = "News Channels",
            rawExtraDetail = "1080p • 50fps • Live Stream",
            config = minimalCfg
        )
        assertEquals("Al Jazeera Live", minTitle)
        assertEquals("News Channels", minSub)

        // 3. Full display retains concise extra detail within Car UX char limits
        val fullCfg = minimalCfg.copy(minimalCarDisplay = false)
        val (_, fullSub) = AndroidAutoUxPolicy.formatDisplayMetadata(
            rawTitle = "Sports 1",
            rawGroupOrSource = "Sports",
            rawExtraDetail = "Live",
            config = fullCfg
        )
        assertEquals("Sports • Live", fullSub)

        // 4. Long labels are safely truncated to Car UX max length
        val longLabel = "A".repeat(120)
        val truncated = AndroidAutoUxPolicy.sanitizeLabel(longLabel, AndroidAutoUxPolicy.MAX_TITLE_CHARS, "V Player")
        assertEquals(AndroidAutoUxPolicy.MAX_TITLE_CHARS, truncated.length)
        assertTrue(truncated.endsWith("…"))

        // 5. Audio-Only Mode on Car suppresses heavy remote HTTP/HTTPS artwork for car controllers
        assertFalse(AndroidAutoUxPolicy.shouldIncludeArtwork(minimalCfg, isCarController = true, artworkUrl = "https://cdn.example.com/poster.png"))
        assertTrue(AndroidAutoUxPolicy.shouldIncludeArtwork(minimalCfg, isCarController = false, artworkUrl = "https://cdn.example.com/poster.png"))
        assertTrue(AndroidAutoUxPolicy.shouldIncludeArtwork(minimalCfg.copy(audioOnlyOnCar = false), isCarController = true, artworkUrl = "https://cdn.example.com/poster.png"))
        assertTrue(AndroidAutoUxPolicy.shouldIncludeArtwork(minimalCfg, isCarController = true, artworkUrl = "content://media/external/audio/albumart/42"))

        // 6. Bounded pagination never exceeds Car UX hard cap and never throws out-of-range
        val bigList = (1..100).toList()
        val cappedAll = AndroidAutoUxPolicy.paginate(bigList, page = 0, pageSize = 100, maxCap = AndroidAutoUxPolicy.MAX_LEAF_ITEMS)
        assertEquals(AndroidAutoUxPolicy.MAX_LEAF_ITEMS, cappedAll.size)
        val page2 = AndroidAutoUxPolicy.paginate(bigList, page = 1, pageSize = 15, maxCap = 25)
        assertEquals((16..25).toList(), page2)
        val outOfRange = AndroidAutoUxPolicy.paginate(bigList, page = 9, pageSize = 15, maxCap = 25)
        assertTrue(outOfRange.isEmpty())
    }

    @Test
    fun defaultPlayerPromptPolicy_respectsUsageThresholdsCooldownAndFlags() {
        val now = 1_700_000_000_000L
        // Never show on first launch (0 plays, 1 session)
        assertFalse(DefaultPlayerPromptPolicy.shouldShowPrompt(false, false, 0, 1, 0L, now))
        // Never show if already default or user tapped Don't ask again
        assertFalse(DefaultPlayerPromptPolicy.shouldShowPrompt(true, false, 5, 3, 0L, now))
        assertFalse(DefaultPlayerPromptPolicy.shouldShowPrompt(false, true, 5, 3, 0L, now))
        // Eligible after 3 video plays or 2 sessions + 1 play
        assertTrue(DefaultPlayerPromptPolicy.shouldShowPrompt(false, false, 3, 1, 0L, now))
        assertTrue(DefaultPlayerPromptPolicy.shouldShowPrompt(false, false, 1, 2, 0L, now))
        // Respects 7-day cooldown after "Later"
        assertFalse(DefaultPlayerPromptPolicy.shouldShowPrompt(false, false, 5, 4, now - 1000L, now))
        assertTrue(DefaultPlayerPromptPolicy.shouldShowPrompt(false, false, 5, 4, now - DefaultPlayerPromptPolicy.LATER_COOLDOWN_MS - 1L, now))
    }

    @Test
    fun autoDecoder_picksPerVideoAndKeepsManualChoiceFixed() {
        // Manual choices always stay fixed regardless of file type
        assertEquals(0, HiddenMediaAndPlaylistHelper.resolveEffectiveDecoderMode(0, "file:///a.avi", "a.avi", false))
        assertEquals(1, HiddenMediaAndPlaylistHelper.resolveEffectiveDecoderMode(1, "file:///a.mkv", "a.mkv", false))
        assertEquals(2, HiddenMediaAndPlaylistHelper.resolveEffectiveDecoderMode(2, "file:///a.mp4", "a.mp4", false))
        // Auto (3) adapts per video
        assertEquals(1, HiddenMediaAndPlaylistHelper.resolveEffectiveDecoderMode(3, "file:///clip.mp4", "clip.mp4", false))
        assertEquals(2, HiddenMediaAndPlaylistHelper.resolveEffectiveDecoderMode(3, "file:///old.avi", "old.avi", false))
        assertEquals(0, HiddenMediaAndPlaylistHelper.resolveEffectiveDecoderMode(3, "file:///movie.mkv", "movie.mkv", false))
        assertEquals(0, HiddenMediaAndPlaylistHelper.resolveEffectiveDecoderMode(3, "http://iptv/live.ts", "Live TV", true))
    }

    @Test
    fun shortsFeedPolicy_constantsVerified() {
        assertEquals(180_000L, ShortsFeedPolicy.MAX_SHORT_DURATION_MS)
        assertEquals(300_000L, ShortsFeedPolicy.MAX_VERTICAL_SHORT_MS)
    }

    @Test
    fun cacheSizeBreakdown_sumsAllCategoriesAccurately() {
        val b = CacheSizeBreakdown(1000L, 2000L, 500L, 1500L, 3000L)
        assertEquals(8000L, b.totalBytes)
    }
}
