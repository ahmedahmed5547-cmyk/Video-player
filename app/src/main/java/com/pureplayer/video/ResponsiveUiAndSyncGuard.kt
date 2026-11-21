package com.pureplayer.video

/**
 * Session-scoped synchronization guard for the IPTV section.
 *
 * Guarantees the exact required behavior:
 * 1. Automatically triggers a background library refresh when the user opens the main IPTV section.
 * 2. Prevents repeated refreshes while navigating between internal sections (Movies, TV Series, Live TV, Radio),
 *    switching sub-tabs (Categories, Favorites, Watching), or scrolling down through streams.
 * 3. Resets the session flag only when the user leaves the main IPTV section (e.g. switches to Video Folders
 *    or Private Vault), so reopening the main IPTV section triggers a fresh background synchronization.
 */
class IptvSessionSyncGuard {

    var isInsideIptvSection: Boolean = false
        private set

    var hasSyncedInCurrentVisit: Boolean = false
        private set

    var activeLoadedCategoryKey: String? = null
        private set

    /**
     * Called when the user selects or opens the main IPTV tab.
     * Returns true ONLY if the user is entering the IPTV section from another section
     * (or for the first time) and a background sync has not yet run during this visit.
     */
    @Synchronized
    fun onEnterMainIptvSection(): Boolean {
        return if (!isInsideIptvSection || !hasSyncedInCurrentVisit) {
            isInsideIptvSection = true
            hasSyncedInCurrentVisit = true
            true
        } else {
            false
        }
    }

    /**
     * Called when navigating internally between Dashboard (1), Movies/Series/Live TV Categories (2),
     * Category Streams (3), Series Seasons (4), and Episodes (5), or switching internal sub-tabs.
     * Always returns false so internal navigation NEVER triggers a server refresh.
     */
    @Synchronized
    fun onInternalSectionNavigation(
        navLevel: Int,
        contentType: IptvContentType?,
        categoryId: String?,
        subTab: Int
    ): Boolean {
        isInsideIptvSection = true
        if (navLevel < 3) {
            activeLoadedCategoryKey = null
        }
        return false
    }

    /**
     * Checks whether the current paged stream list in memory already matches the requested
     * (sourceId, contentType, categoryId) so returning from Movie Details or Series Seasons
     * preserves the loaded items and scroll position without reloading from offset 0.
     */
    @Synchronized
    fun shouldReuseLoadedCategoryPage(
        sourceId: String,
        contentType: IptvContentType,
        categoryId: String?,
        currentListNotEmpty: Boolean
    ): Boolean {
        val key = "${sourceId}_${contentType.name}_${categoryId ?: "__ALL__"}"
        if (currentListNotEmpty && activeLoadedCategoryKey == key) {
            return true
        }
        activeLoadedCategoryKey = key
        return false
    }

    @Synchronized
    fun clearLoadedCategoryKey() {
        activeLoadedCategoryKey = null
    }

    /**
     * Called when scrolling vertically inside Movies, TV Series, Live TV, or any IPTV list.
     * Never triggers a server refresh.
     */
    fun onScrollInsideIptv(): Boolean = false

    /**
     * Determines whether SwipeRefreshLayout pull-to-refresh gesture should be enabled.
     * Disabled inside IPTV so scrolling up/down in Movies, TV Series, or Live TV
     * never triggers an unwanted refresh operation.
     */
    fun shouldEnableSwipeRefreshGesture(currentMainTab: Int, iptvNavLevel: Int): Boolean {
        if (currentMainTab == 2) {
            return false
        }
        return true
    }

    /**
     * Called when the user leaves the main IPTV section (e.g., switches to Video Folders or Private Vault).
     * Resets the visit state so reopening the main IPTV section will automatically synchronize fresh data.
     */
    @Synchronized
    fun onLeaveMainIptvSection() {
        isInsideIptvSection = false
        hasSyncedInCurrentVisit = false
        activeLoadedCategoryKey = null
    }
}

enum class DeviceFormFactor {
    COMPACT_PHONE,
    STANDARD_PHONE,
    TABLET,
    LARGE_SCREEN
}

/**
 * Responsive UI calculation engine for Smartphones (small & large), Tablets (7" & 10"),
 * Foldables, and Large-Screen / Desktop / TV displays.
 */
object ResponsiveUiMetrics {

    const val APP_DISPLAY_NAME = "VEXO Player"
    const val APP_SUBTITLE_NAME = "IPTV and Video player"

    fun classifyDevice(screenWidthDp: Int, smallestScreenWidthDp: Int = screenWidthDp): DeviceFormFactor {
        return when {
            smallestScreenWidthDp >= 720 || screenWidthDp >= 840 -> DeviceFormFactor.LARGE_SCREEN
            smallestScreenWidthDp >= 600 || screenWidthDp >= 600 -> DeviceFormFactor.TABLET
            screenWidthDp < 360 -> DeviceFormFactor.COMPACT_PHONE
            else -> DeviceFormFactor.STANDARD_PHONE
        }
    }

    /**
     * Calculates the optimal RecyclerView column span count across phones, tablets, and large screens.
     * - In Grid mode: 2 columns on phones, 3 on landscape phones/small tablets, 4 on tablets, 5–6 on large screens.
     * - In List mode: 1 column on phones, 2 columns on tablets/large screens (>= 700dp width) so cards never stretch excessively.
     */
    fun calculateAdaptiveSpanCount(
        screenWidthDp: Int,
        smallestScreenWidthDp: Int = screenWidthDp,
        isGridMode: Boolean,
        isPosterSection: Boolean = false
    ): Int {
        return if (isGridMode) {
            when {
                screenWidthDp >= 1200 -> if (isPosterSection) 6 else 5
                screenWidthDp >= 840 -> if (isPosterSection) 5 else 4
                screenWidthDp >= 600 -> if (isPosterSection) 4 else 3
                screenWidthDp >= 480 -> 3
                else -> 2
            }
        } else {
            when {
                screenWidthDp >= 1080 -> 2
                smallestScreenWidthDp >= 600 && screenWidthDp >= 700 -> 2
                else -> 1
            }
        }
    }

    /**
     * Returns (widthDp, heightDp) for media item thumbnails and posters.
     * In Grid mode, widthDp is 0 (representing MATCH_PARENT).
     */
    fun getThumbnailDimensionsDp(
        screenWidthDp: Int,
        smallestScreenWidthDp: Int = screenWidthDp,
        isGridMode: Boolean,
        isPosterItem: Boolean = false
    ): Pair<Int, Int> {
        val formFactor = classifyDevice(screenWidthDp, smallestScreenWidthDp)
        return if (isGridMode) {
            val heightDp = when (formFactor) {
                DeviceFormFactor.LARGE_SCREEN -> if (isPosterItem) 184 else 136
                DeviceFormFactor.TABLET -> if (isPosterItem) 168 else 124
                DeviceFormFactor.COMPACT_PHONE -> if (isPosterItem) 132 else 98
                DeviceFormFactor.STANDARD_PHONE -> if (isPosterItem) 148 else 110
            }
            Pair(0, heightDp)
        } else {
            when (formFactor) {
                DeviceFormFactor.LARGE_SCREEN, DeviceFormFactor.TABLET -> {
                    if (isPosterItem) Pair(92, 118) else Pair(136, 80)
                }
                DeviceFormFactor.COMPACT_PHONE -> {
                    if (isPosterItem) Pair(68, 88) else Pair(98, 60)
                }
                DeviceFormFactor.STANDARD_PHONE -> {
                    if (isPosterItem) Pair(78, 100) else Pair(116, 68)
                }
            }
        }
    }

    /**
     * Calculates horizontal padding (in dp) for full-screen menus and dialogs so content stays
     * readable and well-proportioned on tablets and large displays without edge-to-edge stretching.
     */
    fun getMenuHorizontalPaddingDp(screenWidthDp: Int): Int {
        return when {
            screenWidthDp >= 1200 -> ((screenWidthDp - 760) / 2).coerceAtLeast(80)
            screenWidthDp >= 900 -> ((screenWidthDp - 680) / 2).coerceAtLeast(56)
            screenWidthDp >= 600 -> 36
            screenWidthDp < 360 -> 14
            else -> 20
        }
    }

    /**
     * Calculates RecyclerView horizontal padding (in dp) for phones, tablets, and large screens.
     */
    fun getListHorizontalPaddingDp(screenWidthDp: Int): Int {
        return when {
            screenWidthDp >= 1200 -> 24
            screenWidthDp >= 840 -> 18
            screenWidthDp >= 600 -> 14
            else -> 6
        }
    }

    /**
     * Returns true when the video player top bar should collapse secondary quick-subtitle buttons
     * into the subtitle/pro menus so the video title and primary buttons never clip on narrow screens.
     */
    fun shouldUseCompactPlayerTopBar(screenWidthDp: Int): Boolean {
        return screenWidthDp < 540
    }
}

data class ChangelogReleaseEntry(
    val version: String,
    val date: String,
    val iconEmoji: String,
    val title: String,
    val description: String,
    val isLatest: Boolean = false
)

/**
 * Manages the application's Changelog entries so that ONLY the latest 3 versions
 * are retained and displayed at all times.
 *
 * When a new version is released:
 * - Adds the new version at index 0.
 * - Keeps the previous two versions (indices 1 and 2).
 * - Automatically removes any older versions beyond the latest 3.
 */
object ChangelogVersionManager {
    const val MAX_CHANGELOG_VERSIONS = 4

    fun retainLatestVersions(
        entries: List<ChangelogReleaseEntry>,
        maxCount: Int = MAX_CHANGELOG_VERSIONS
    ): List<ChangelogReleaseEntry> {
        return entries.take(maxCount).mapIndexed { index, entry ->
            entry.copy(isLatest = (index == 0))
        }
    }

    fun retainLatestThreeVersions(entries: List<ChangelogReleaseEntry>): List<ChangelogReleaseEntry> {
        return retainLatestVersions(entries, MAX_CHANGELOG_VERSIONS)
    }

    fun pushNewRelease(
        existingEntries: List<ChangelogReleaseEntry>,
        newRelease: ChangelogReleaseEntry
    ): List<ChangelogReleaseEntry> {
        val combined = ArrayList<ChangelogReleaseEntry>(existingEntries.size + 1)
        combined.add(newRelease.copy(isLatest = true))
        for (entry in existingEntries) {
            if (entry.version != newRelease.version) {
                combined.add(entry.copy(isLatest = false))
            }
        }
        return retainLatestThreeVersions(combined)
    }
}

/**
 * Enforces strict section-scoped IPTV search:
 * - When inside Movies, searches ONLY Movies.
 * - When inside TV Series, searches ONLY TV Series.
 * - When inside Live TV, searches ONLY Live TV.
 * - When inside Radio, searches ONLY Radio.
 * Never returns results from other sections.
 */
object SectionScopedSearchHelper {

    fun resolveActiveSearchSection(
        currentMainTab: Int,
        iptvNavLevel: Int,
        activeContentType: IptvContentType?
    ): IptvContentType? {
        if (currentMainTab != 2) return null
        return if (iptvNavLevel >= 2 && activeContentType != null) {
            activeContentType
        } else {
            activeContentType
        }
    }

    fun filterStreamsForSectionSearch(
        streams: List<IptvStreamItem>,
        rawQuery: String,
        requiredSection: IptvContentType?
    ): List<IptvStreamItem> {
        val sectionFiltered = if (requiredSection != null) {
            streams.filter { it.contentType == requiredSection }
        } else {
            streams
        }
        val normQ = IptvSearchNormalizer.normalize(rawQuery)
        if (normQ.isEmpty()) return sectionFiltered
        return sectionFiltered.filter { item ->
            IptvSearchNormalizer.matches(item.title, item.categoryName, normQ)
        }
    }
}
