package com.pureplayer.video

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricPrompt
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.CancellationSignal
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.ProgressBar
import org.json.JSONObject
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.pureplayer.video.databinding.ActivityMainBinding
import android.widget.PopupMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Hardware-backed AndroidKeyStore guard that binds Biometric authentication to the current
 * enrolled fingerprints ONLY after the owner enters the app Password.
 * If ANY new fingerprint is registered in Android Settings, AndroidKeyStore permanently invalidates
 * the key (setInvalidatedByBiometricEnrollment(true)), blocking fingerprint access until the
 * owner enters the Password again!
 */

internal fun MainActivity.renderIptvCurrentLevel() {
    if (currentMainTab != 2) return
    binding.iptvHeaderBar.visibility = View.VISIBLE
    binding.fabResumeLast.visibility = View.GONE
    binding.btnBackFolder.visibility = View.GONE
    adapter.isVaultMode = false

    val sources = IptvRepository.getSources(this)
    if (activeIptvSource != null) {
        activeIptvSource = sources.find { it.id == activeIptvSource?.id }
    }
    if (activeIptvSource == null && sources.isNotEmpty()) {
        val savedActiveId = IptvRepository.getActiveSourceId(this)
        activeIptvSource = sources.find { it.id == savedActiveId } ?: sources.first()
    }

    if (sources.isEmpty()) {
        iptvNavLevel = 0
        binding.iptvSubTabsBar.visibility = View.GONE
        binding.tvToolbarTitle.text = getString(R.string.iptv_toolbar_title)
        binding.tvIptvStatusSubtitle.text = getString(R.string.iptv_sources_subtitle)
        binding.emptyLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        val initialCards = listOf<Any>(
            IptvAddCardItem(
                title = "➕  " + getString(R.string.iptv_add_source_title),
                subtitle = getString(R.string.iptv_add_source_subtitle)
            )
        )
        adapter.submitIptvList(initialCards)
        return
    }

    binding.emptyLayout.visibility = View.GONE
    binding.recyclerView.visibility = View.VISIBLE

    when (iptvNavLevel) {
        0 -> {
            // Level 0: Xtream & M3U Sources List + prominent (+) Add New Source card right below
            binding.iptvSubTabsBar.visibility = View.GONE
            binding.tvToolbarTitle.text = "${getString(R.string.iptv_toolbar_title)} (${sources.size})"
            binding.tvIptvStatusSubtitle.text = getString(R.string.iptv_sources_manage_hint)
            val list = mutableListOf<Any>()
            list.addAll(sources)
            list.add(
                IptvAddCardItem(
                    title = "➕  " + getString(R.string.iptv_add_source_title),
                    subtitle = getString(R.string.iptv_add_source_subtitle)
                )
            )
            adapter.submitIptvList(list)
        }
        1 -> {
            // Level 1: IPTV Smarters Pro Dashboard for Active Source (Shows ONLY non-empty sections + Favorites & Continue Watching!)
            binding.iptvSubTabsBar.visibility = View.GONE
            val src = activeIptvSource ?: run {
                iptvNavLevel = 0
                renderIptvCurrentLevel()
                return
            }
            binding.tvToolbarTitle.text = src.name
            binding.tvIptvStatusSubtitle.text = "${src.type.name} • ${src.totalItemsCount()} ${getString(R.string.iptv_items_unit)}"

            val countsMap = IptvRepository.getSectionCountsSql(this, src.id)
            val liveCnt = countsMap[IptvContentType.LIVE_TV] ?: 0
            val movieCnt = countsMap[IptvContentType.MOVIES] ?: 0
            val seriesCnt = countsMap[IptvContentType.SERIES] ?: 0
            val radioCnt = countsMap[IptvContentType.RADIO] ?: 0

            val sectionCards = mutableListOf<Any>()
            if (liveCnt > 0) {
                sectionCards.add(
                    IptvSectionCardItem(
                        contentType = IptvContentType.LIVE_TV,
                        title = "📺  " + getString(R.string.iptv_sec_live_tv),
                        subtitle = getString(R.string.iptv_sec_live_sub, liveCnt),
                        count = liveCnt
                    )
                )
            }
            if (movieCnt > 0) {
                sectionCards.add(
                    IptvSectionCardItem(
                        contentType = IptvContentType.MOVIES,
                        title = "🎬  " + getString(R.string.iptv_sec_movies),
                        subtitle = getString(R.string.iptv_sec_movies_sub, movieCnt),
                        count = movieCnt
                    )
                )
            }
            if (seriesCnt > 0) {
                sectionCards.add(
                    IptvSectionCardItem(
                        contentType = IptvContentType.SERIES,
                        title = "🎞️  " + getString(R.string.iptv_sec_series),
                        subtitle = getString(R.string.iptv_sec_series_sub, seriesCnt),
                        count = seriesCnt
                    )
                )
            }
            if (radioCnt > 0) {
                sectionCards.add(
                    IptvSectionCardItem(
                        contentType = IptvContentType.RADIO,
                        title = "📻  " + getString(R.string.iptv_sec_radio),
                        subtitle = getString(R.string.iptv_sec_radio_sub, radioCnt),
                        count = radioCnt
                    )
                )
            }
            sectionCards.add(
                IptvAddCardItem(
                    title = "➕  " + getString(R.string.iptv_add_source_title),
                    subtitle = getString(R.string.iptv_add_source_subtitle)
                )
            )
            adapter.submitIptvList(sectionCards)
        }
        2, 3 -> {
            val src = activeIptvSource ?: return
            val cType = activeIptvContentType ?: return
            val secName = getSectionDisplayName(cType)

            // Show the Sub-Tabs Bar (📂 التصنيفات | ❤️ المفضلة | ⏱️ أُكمل المشاهدة) inside Series, Movies, Live TV, Radio!
            binding.iptvSubTabsBar.visibility = View.VISIBLE
            updateIptvSubTabsVisualState()

            if (iptvSectionSubTab == 1) {
                // Favorites Tab ❤️ for this section
                binding.tvToolbarTitle.text = "$secName • ${getString(R.string.iptv_subtab_favorites)}"
                val favStreams = IptvRepository.getFavoriteItems(this, src.id, cType)
                binding.tvIptvStatusSubtitle.text = "${favStreams.size} ${getString(R.string.iptv_items_unit)}"
                if (favStreams.isEmpty()) {
                    showEmptyState(getString(R.string.iptv_favorites_empty_msg), showButton = false)
                } else {
                    binding.emptyLayout.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    adapter.submitIptvList(favStreams)
                }
                return
            }

            if (iptvSectionSubTab == 2) {
                // Continue Watching / History Tab ⏱️ for this section
                binding.tvToolbarTitle.text = "$secName • ${getString(R.string.iptv_subtab_recent)}"
                val recentStreams = IptvRepository.getRecentWatchedItems(this, src.id, cType)
                binding.tvIptvStatusSubtitle.text = "${recentStreams.size} ${getString(R.string.iptv_items_unit)}"
                if (recentStreams.isEmpty()) {
                    showEmptyState(getString(R.string.iptv_recent_empty_msg), showButton = false)
                } else {
                    binding.emptyLayout.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    adapter.submitIptvList(recentStreams)
                }
                return
            }

            // SubTab 0: Standard Categories (Level 2) or Paged Category Streams (Level 3) via Room SQL
            if (iptvNavLevel == 2) {
                binding.tvToolbarTitle.text = "${src.name} • $secName"
                binding.tvIptvStatusSubtitle.text = getString(R.string.iptv_long_press_reclassify_hint)

                val categories = IptvRepository.getCategoriesForTypeSql(
                    context = this,
                    sourceId = src.id,
                    contentType = cType,
                    allCategoryLabel = getString(R.string.iptv_all_channels_category)
                )
                adapter.submitIptvList(categories)
            } else {
                binding.tvToolbarTitle.text = activeIptvCategoryName
                loadInitialIptvStreamsPage(src, cType)
            }
        }
        4 -> {
            // Level 4: Series Seasons
            binding.iptvSubTabsBar.visibility = View.GONE
            val sInfo = activeIptvSeriesInfo ?: return
            binding.tvToolbarTitle.text = sInfo.seriesTitle
            val src = activeIptvSource
            val sItem = currentOpenedSeriesStreamItem
            val lastEpLabel = if (src != null && sItem != null) {
                val sKey = sItem.seriesId.ifBlank { sItem.seriesName.ifBlank { sItem.title } }
                IptvRepository.getLastWatchedSeriesEpisodeLabel(this, src.id, sKey)
            } else null
            val prefix = if (!lastEpLabel.isNullOrBlank()) "▶ ${getString(R.string.iptv_last_watched_ep_badge, lastEpLabel)}  •  " else ""
            binding.tvIptvStatusSubtitle.text = prefix + sInfo.plot.ifBlank { "${sInfo.seasons.size} Seasons" }
            adapter.submitIptvList(sInfo.seasons)
        }
        5 -> {
            // Level 5: Season Episodes
            binding.iptvSubTabsBar.visibility = View.GONE
            val sInfo = activeIptvSeriesInfo ?: return
            val season = activeIptvSeasonGroup ?: return
            binding.tvToolbarTitle.text = "${sInfo.seriesTitle} • ${season.name}"
            binding.tvIptvStatusSubtitle.text = getString(R.string.iptv_episodes_count_format, season.episodes.size)
            adapter.submitIptvList(season.episodes)
        }
    }
}


internal fun MainActivity.loadInitialIptvStreamsPage(src: IptvSource, cType: IptvContentType) {
    currentIptvCategoryOffset = 0
    isLoadingNextIptvPage = false
    currentIptvPagedList.clear()
    val groupSeries = (cType == IptvContentType.SERIES && src.type != IptvSourceType.XTREAM)
    val firstPage = IptvRepository.getStreamsPageSql(
        context = this,
        sourceId = src.id,
        contentType = cType,
        categoryId = activeIptvCategoryId,
        categoryName = activeIptvCategoryName,
        groupM3uSeries = groupSeries,
        limit = IptvRepository.DEFAULT_PAGE_SIZE,
        offset = 0
    )
    currentIptvPagedList.addAll(firstPage)
    currentIptvCategoryOffset = firstPage.size
    hasMoreIptvCategoryPages = (firstPage.size >= IptvRepository.DEFAULT_PAGE_SIZE)
    binding.tvIptvStatusSubtitle.text = "${currentIptvPagedList.size}${if (hasMoreIptvCategoryPages) "+" else ""} ${getString(R.string.iptv_items_unit)}"
    adapter.submitIptvList(ArrayList(currentIptvPagedList))
}

internal fun MainActivity.loadNextIptvStreamsPage() {
    val hostActivity = this
    val src = activeIptvSource ?: return
    val cType = activeIptvContentType ?: return
    if (isLoadingNextIptvPage || !hasMoreIptvCategoryPages) return
    isLoadingNextIptvPage = true
    val groupSeries = (cType == IptvContentType.SERIES && src.type != IptvSourceType.XTREAM)
    val nextOffset = currentIptvCategoryOffset
    lifecycleScope.launch {
        val nextPage = withContext(Dispatchers.IO) {
            IptvRepository.getStreamsPageSql(
                context = hostActivity,
                sourceId = src.id,
                contentType = cType,
                categoryId = activeIptvCategoryId,
                categoryName = activeIptvCategoryName,
                groupM3uSeries = groupSeries,
                limit = IptvRepository.DEFAULT_PAGE_SIZE,
                offset = nextOffset
            )
        }
        isLoadingNextIptvPage = false
        if (currentMainTab == 2 && iptvNavLevel == 3 && iptvSectionSubTab == 0) {
            if (nextPage.isNotEmpty()) {
                currentIptvPagedList.addAll(nextPage)
                currentIptvCategoryOffset += nextPage.size
                hasMoreIptvCategoryPages = (nextPage.size >= IptvRepository.DEFAULT_PAGE_SIZE)
                binding.tvIptvStatusSubtitle.text = "${currentIptvPagedList.size}${if (hasMoreIptvCategoryPages) "+" else ""} ${getString(R.string.iptv_items_unit)}"
                adapter.submitIptvList(ArrayList(currentIptvPagedList))
            } else {
                hasMoreIptvCategoryPages = false
            }
        }
    }
}

internal fun MainActivity.navigateBackInIptvHierarchy() {
    when {
        iptvNavLevel == 5 -> iptvNavLevel = 4
        iptvNavLevel == 4 -> {
            // Return to either Favorites/Recent subtab or Category streams list
            iptvNavLevel = if (iptvSectionSubTab != 0) 2 else 3
        }
        iptvNavLevel == 3 && iptvSectionSubTab == 0 -> iptvNavLevel = 2
        iptvNavLevel in 2..3 && iptvSectionSubTab != 0 -> {
            // If inside ❤️ Favorites or ⏱️ Continue Watching sub-tab, back-swipe returns to All Categories sub-tab first!
            iptvSectionSubTab = 0
            iptvNavLevel = 2
        }
        iptvNavLevel == 2 -> iptvNavLevel = 1
        else -> iptvNavLevel = 0
    }
    renderIptvCurrentLevel()
}

internal fun MainActivity.getSectionDisplayName(type: IptvContentType): String {
    return when (type) {
        IptvContentType.LIVE_TV -> getString(R.string.iptv_sec_live_tv)
        IptvContentType.MOVIES -> getString(R.string.iptv_sec_movies)
        IptvContentType.SERIES -> getString(R.string.iptv_sec_series)
        IptvContentType.RADIO -> getString(R.string.iptv_sec_radio)
    }
}

internal fun MainActivity.handleIptvItemClick(item: Any) {
    when (item) {
        is IptvAddCardItem -> showAddIptvSourceDialog()
        is IptvSource -> {
            activeIptvSource = item
            IptvRepository.setActiveSourceId(this, item.id)
            iptvSectionSubTab = 0
            iptvNavLevel = 1
            renderIptvCurrentLevel()
        }
        is IptvSectionCardItem -> {
            activeIptvContentType = item.contentType
            iptvSectionSubTab = 0
            iptvNavLevel = 2
            renderIptvCurrentLevel()
        }
        is IptvCategory -> {
            activeIptvCategoryId = item.id
            activeIptvCategoryName = item.name.removePrefix("🌐  ")
            iptvSectionSubTab = 0
            iptvNavLevel = 3
            renderIptvCurrentLevel()
        }
        is IptvStreamItem -> {
            when (item.contentType) {
                IptvContentType.LIVE_TV, IptvContentType.RADIO -> {
                    IptvRepository.recordRecentWatchedItem(this, item.sourceId, item.id)
                    launchPlayer(
                        uri = Uri.parse(item.streamUrl),
                        title = item.title,
                        startPositionMs = 0L,
                        httpUserAgent = item.httpUserAgent,
                        httpReferrer = item.httpReferrer
                    )
                }
                IptvContentType.MOVIES -> {
                    showIptvMovieDetailsDialog(item)
                }
                IptvContentType.SERIES -> {
                    currentOpenedSeriesStreamItem = item
                    openIptvSeriesSeasons(item)
                }
            }
        }
        is IptvSeasonGroup -> {
            activeIptvSeasonGroup = item
            iptvNavLevel = 5
            renderIptvCurrentLevel()
        }
        is IptvEpisodeItem -> {
            val src = activeIptvSource
            val sItem = currentOpenedSeriesStreamItem
            if (src != null && sItem != null) {
                IptvRepository.recordRecentWatchedItem(this, src.id, sItem.id)
                val sKey = sItem.seriesId.ifBlank { sItem.seriesName.ifBlank { sItem.title } }
                IptvRepository.saveLastWatchedSeriesEpisode(
                    context = this,
                    sourceId = src.id,
                    seriesKey = sKey,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    episodeTitle = item.title,
                    streamUrl = item.streamUrl
                )
            }
            val savedPos = PlaybackStore.getSavedPosition(this, Uri.parse(item.streamUrl))
            launchPlayer(
                uri = Uri.parse(item.streamUrl),
                title = item.title,
                startPositionMs = if (savedPos > 3000L) savedPos else 0L,
                httpUserAgent = item.httpUserAgent,
                httpReferrer = item.httpReferrer
            )
        }
    }
}

internal fun MainActivity.handleIptvItemLongClick(item: Any) {
    when (item) {
        is IptvSource -> showIptvSourceManagementMenu(item)
        is IptvCategory -> {
            if (item.id != "__ALL__") {
                showReclassifyCategoryDialog(item.name)
            }
        }
        is IptvStreamItem -> {
            settingsBackStack.clear()
            val isFav = IptvRepository.isFavoriteStream(this, item.sourceId, item.id)
            val favTitle = if (isFav) getString(R.string.iptv_fav_remove_option) else getString(R.string.iptv_fav_add_option)
            val menuItems = mutableListOf<PremiumMenuItem>()
            if (item.contentType == IptvContentType.MOVIES && item.streamUrl.isNotBlank()) {
                menuItems.add(
                    PremiumMenuItem(
                        iconEmoji = "⬇️",
                        title = getString(R.string.iptv_download_btn),
                        subtitle = getString(R.string.iptv_download_sub),
                        isAccentHighlighted = true,
                        onClick = {
                            downloadIptvMediaLocally(
                                title = item.title,
                                streamUrl = item.streamUrl,
                                userAgent = item.httpUserAgent,
                                referrer = item.httpReferrer
                            )
                        }
                    )
                )
            }
            menuItems.add(
                PremiumMenuItem(
                    iconEmoji = if (isFav) "💔" else "❤️",
                    title = favTitle,
                    subtitle = getString(R.string.iptv_subtab_favorites),
                    isAccentHighlighted = (item.contentType != IptvContentType.MOVIES),
                    onClick = {
                        val nowFav = IptvRepository.toggleFavoriteStream(this, item.sourceId, item.id)
                        val msgRes = if (nowFav) R.string.iptv_fav_added_toast else R.string.iptv_fav_removed_toast
                        Toast.makeText(this, getString(msgRes, item.title), Toast.LENGTH_SHORT).show()
                        renderIptvCurrentLevel()
                    }
                )
            )
            menuItems.add(
                PremiumMenuItem(
                    iconEmoji = "🗂️",
                    title = getString(R.string.iptv_reclassify_category_title),
                    subtitle = item.categoryName,
                    onClick = {
                        showReclassifyCategoryDialog(item.categoryName)
                    }
                )
            )
            showPremiumMenuDialog(
                headerIcon = if (isFav) "❤️" else "🎬",
                title = item.title,
                subtitle = item.categoryName,
                items = menuItems
            )
        }
        is IptvEpisodeItem -> {
            settingsBackStack.clear()
            val epLabel = "S${item.seasonNumber}E${item.episodeNumber} • ${item.title}"
            showPremiumMenuDialog(
                headerIcon = "🎞️",
                title = item.title,
                subtitle = "Season ${item.seasonNumber} • Episode ${item.episodeNumber}",
                items = listOf(
                    PremiumMenuItem(
                        iconEmoji = "▶️",
                        title = getString(R.string.iptv_episode_play_option),
                        subtitle = epLabel,
                        isAccentHighlighted = true,
                        onClick = {
                            handleIptvItemClick(item)
                        }
                    ),
                    PremiumMenuItem(
                        iconEmoji = "⬇️",
                        title = getString(R.string.iptv_download_btn),
                        subtitle = getString(R.string.iptv_download_sub),
                        isAccentHighlighted = true,
                        onClick = {
                            val sTitle = activeIptvSeriesInfo?.seriesTitle ?: ""
                            val dlTitle = if (sTitle.isNotBlank()) "${sTitle}_S${item.seasonNumber}E${item.episodeNumber}_${item.title}" else item.title
                            downloadIptvMediaLocally(
                                title = dlTitle,
                                streamUrl = item.streamUrl,
                                userAgent = item.httpUserAgent,
                                referrer = item.httpReferrer
                            )
                        }
                    )
                )
            )
        }
    }
}

internal fun MainActivity.showAddIptvSourceDialog() {
    settingsBackStack.clear()
    val items = listOf(
        PremiumMenuItem(
            iconEmoji = "🔗",
            title = getString(R.string.iptv_add_m3u_url_option),
            subtitle = getString(R.string.iptv_add_m3u_url_sub),
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.iptv_add_opt1_header),
            onClick = {
                pushSettingsBackStack { showAddIptvSourceDialog() }
                showAddOrEditM3uUrlDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "📂",
            title = getString(R.string.iptv_add_m3u_file_option),
            subtitle = getString(R.string.iptv_add_m3u_file_sub),
            onClick = {
                pendingM3uFileSourceName = ""
                m3uFilePickerLauncher.launch(arrayOf("*/*"))
            }
        ),
        PremiumMenuItem(
            iconEmoji = "📡",
            title = getString(R.string.iptv_add_xtream_option),
            subtitle = getString(R.string.iptv_add_xtream_sub),
            isAccentHighlighted = true,
            sectionHeader = getString(R.string.iptv_add_opt2_header),
            onClick = {
                pushSettingsBackStack { showAddIptvSourceDialog() }
                showAddOrEditXtreamDialog()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🧪",
            title = getString(R.string.iptv_add_demo_source_option),
            subtitle = getString(R.string.iptv_add_demo_source_sub),
            onClick = {
                addBuiltInDemoIptvSource()
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "➕",
        title = getString(R.string.iptv_add_source_title),
        subtitle = getString(R.string.iptv_add_source_subtitle),
        items = items
    )
}

internal fun MainActivity.showAddOrEditM3uUrlDialog(existingSource: IptvSource? = null) {
    val hostActivity = this
    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)
    val bg = PlaybackStore.getBackgroundColor(this)

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(20))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26).toFloat()
            setColor(surface)
            setStroke(dp(1), Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }

    val tvTitle = TextView(this).apply {
        text = "🔗  " + getString(R.string.iptv_add_m3u_url_option)
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(12))
    }

    val etName = EditText(this).apply {
        setText(existingSource?.name ?: "")
        hint = getString(R.string.iptv_hint_source_name)
        setSingleLine(true)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    stylePremiumInputBox(etName, bg, accent)

    val etUrl = EditText(this).apply {
        setText(existingSource?.serverOrM3uUrl ?: "")
        hint = "https://example.com/playlist.m3u8"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        }
    }
    stylePremiumInputBox(etUrl, bg, accent)

    container.addView(tvTitle)
    container.addView(etName)
    container.addView(etUrl)

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(16), 0, 0)
    }

    val dialog = AlertDialog.Builder(this).setView(container).create()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    val btnCancel = TextView(this).apply {
        text = getString(R.string.cancel)
        setTextColor(Color.parseColor("#94A3B8"))
        textSize = 14f
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
    bindSubDialogBackNavigation(dialog, btnCancel)

    val btnSave = TextView(this).apply {
        text = getString(R.string.save)
        setTextColor(Color.parseColor("#090D16"))
        textSize = 14.5f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(22), dp(10), dp(22), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(accent)
        }
        setOnClickListener {
            val urlInput = XtreamCodesClient.sanitizeCredentialOrUrl(etUrl.text.toString())
            val nameInput = etName.text.toString().trim().ifEmpty { "M3U Playlist" }
            if (urlInput.isEmpty() || (!urlInput.contains(".") && !urlInput.startsWith("http", ignoreCase = true))) {
                Toast.makeText(hostActivity, getString(R.string.iptv_err_invalid_url), Toast.LENGTH_SHORT).show()
            } else {
                isNavigatingToSubDialog = true
                dialog.dismiss()
                isNavigatingToSubDialog = false
                settingsBackStack.clear()
                syncIptvSourceWithProgress(
                    existingSourceId = existingSource?.id,
                    name = nameInput,
                    type = IptvSourceType.M3U_URL,
                    serverOrM3uUrl = urlInput
                )
            }
        }
    }

    btnRow.addView(btnCancel)
    btnRow.addView(btnSave)
    container.addView(btnRow)
    dialog.show()
}

internal fun MainActivity.showAddOrEditXtreamDialog(
    existingSource: IptvSource? = null,
    prefillName: String = "",
    prefillServer: String = "",
    prefillUser: String = "",
    prefillPass: String = ""
) {
    val hostActivity = this
    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)
    val bg = PlaybackStore.getBackgroundColor(this)

    val outerScroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
    }

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(20))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26).toFloat()
            setColor(surface)
            setStroke(dp(1), Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }
    outerScroll.addView(container)

    val tvTitle = TextView(this).apply {
        text = "📡  " + getString(R.string.iptv_add_xtream_option)
        setTextColor(PlaybackStore.getPrimaryTextColor(hostActivity))
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(10))
    }
    container.addView(tvTitle)

    fun makeFieldLabel(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(accent)
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
    }

    // 1. Source Name (Optional)
    container.addView(makeFieldLabel("📝 " + getString(R.string.iptv_hint_source_name)))
    val etName = EditText(this).apply {
        setSingleLine(true)
        setText(existingSource?.name ?: prefillName)
        hint = getString(R.string.iptv_hint_source_name)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    stylePremiumInputBox(etName, bg, accent)
    container.addView(etName)

    // 2. Username (setSingleLine BEFORE inputType so Android never resets inputType or auto-capitalizes!)
    container.addView(makeFieldLabel("👤 " + getString(R.string.iptv_hint_username)))
    val etUsername = EditText(this).apply {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setText(existingSource?.username ?: prefillUser)
        hint = getString(R.string.iptv_hint_username)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    stylePremiumInputBox(etUsername, bg, accent)
    container.addView(etUsername)

    // 3. Password (setSingleLine BEFORE inputType!)
    container.addView(makeFieldLabel("🔑 " + getString(R.string.iptv_hint_password)))
    val existingPass = if (existingSource != null) (IptvCredentialVault.decryptSecretOrNull(existingSource.encryptedPassword) ?: "") else prefillPass
    val etPassword = EditText(this).apply {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setText(existingPass)
        hint = getString(R.string.iptv_hint_password)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    stylePremiumInputBox(etPassword, bg, accent)
    container.addView(etPassword)

    val cbShowPass = CheckBox(this).apply {
        text = getString(R.string.iptv_show_password_toggle)
        setTextColor(Color.parseColor("#CBD5E1"))
        textSize = 13f
        buttonTintList = ColorStateList.valueOf(accent)
        isChecked = true
        setPadding(dp(6), dp(6), dp(6), dp(2))
        setOnCheckedChangeListener { _, isChecked ->
            val sel = etPassword.selectionEnd.coerceAtLeast(0)
            etPassword.inputType = if (isChecked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            etPassword.setSelection(sel.coerceAtMost(etPassword.text.length))
        }
    }
    // Default to visible password so user can immediately verify there are no typos!
    etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    container.addView(cbShowPass)

    // 4. Server URL / Domain (e.g. mar10.sbs or http://mar10.sbs:8080)
    container.addView(makeFieldLabel("🌐 " + getString(R.string.iptv_hint_server_url)))
    val etServer = EditText(this).apply {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setText(existingSource?.serverOrM3uUrl ?: prefillServer)
        hint = "mar10.sbs  أو  http://mar10.sbs:8080"
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    stylePremiumInputBox(etServer, bg, accent)
    container.addView(etServer)

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(16), 0, 0)
    }

    val dialog = AlertDialog.Builder(this).setView(outerScroll).create()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    val btnCancel = TextView(this).apply {
        text = getString(R.string.cancel)
        setTextColor(Color.parseColor("#94A3B8"))
        textSize = 14f
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
    bindSubDialogBackNavigation(dialog, btnCancel)

    val btnSave = TextView(this).apply {
        text = getString(R.string.save)
        setTextColor(Color.parseColor("#090D16"))
        textSize = 14.5f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(22), dp(10), dp(22), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(accent)
        }
        setOnClickListener {
            var srv = XtreamCodesClient.sanitizeCredentialOrUrl(etServer.text.toString())
            var usr = XtreamCodesClient.sanitizeCredentialOrUrl(etUsername.text.toString())
            var pwd = XtreamCodesClient.sanitizeCredentialOrUrl(etPassword.text.toString())

            // Smart Auto-Swap: If user accidentally typed the domain (e.g. mar10.sbs) in Username box and username in Server box!
            if (!srv.contains(".") && usr.contains(".") && !usr.contains("@")) {
                val tmp = srv
                srv = usr
                usr = tmp
            }

            // If user pasted a full get.php URL with ?username=...&password=... into Server URL box, auto-extract them!
            if (srv.contains("username=", ignoreCase = true) && (usr.isEmpty() || pwd.isEmpty())) {
                val uMatch = Regex("(?i)[?&]username=([^&]+)").find(srv)?.groupValues?.getOrNull(1)
                val pMatch = Regex("(?i)[?&]password=([^&]+)").find(srv)?.groupValues?.getOrNull(1)
                if (usr.isEmpty() && !uMatch.isNullOrBlank()) usr = Uri.decode(uMatch)
                if (pwd.isEmpty() && !pMatch.isNullOrBlank()) pwd = Uri.decode(pMatch)
            }

            val nameStr = etName.text.toString().trim().ifEmpty {
                srv.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")
                    .ifEmpty { "Xtream IPTV" }
            }
            if (srv.isEmpty() || usr.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(hostActivity, getString(R.string.iptv_err_missing_fields), Toast.LENGTH_SHORT).show()
            } else if (IptvCredentialVault.encryptSecretOrNull(pwd) == null) {
                Toast.makeText(hostActivity, getString(R.string.iptv_err_keystore_encryption), Toast.LENGTH_LONG).show()
            } else {
                isNavigatingToSubDialog = true
                dialog.dismiss()
                isNavigatingToSubDialog = false
                settingsBackStack.clear()
                syncIptvSourceWithProgress(
                    existingSourceId = existingSource?.id,
                    name = nameStr,
                    type = IptvSourceType.XTREAM,
                    serverOrM3uUrl = srv,
                    username = usr,
                    passwordPlain = pwd
                )
            }
        }
    }

    btnRow.addView(btnCancel)
    btnRow.addView(btnSave)
    container.addView(btnRow)
    dialog.show()
}

internal fun MainActivity.syncIptvSourceWithProgress(
    existingSourceId: String?,
    name: String,
    type: IptvSourceType,
    serverOrM3uUrl: String,
    username: String = "",
    passwordPlain: String = ""
) {
    val hostActivity = this
    settingsBackStack.clear()
    closeActiveFullScreenDialogSmoothly()
    iptvSyncJob?.cancel()
    binding.iptvLoadingOverlay.visibility = View.VISIBLE
    binding.tvIptvLoadingStatus.text = getString(R.string.iptv_loading_data)

    iptvSyncJob = lifecycleScope.launch {
        val res = IptvRepository.syncAndSaveSource(
            context = hostActivity,
            existingSourceId = existingSourceId,
            name = name,
            type = type,
            serverOrM3uUrl = serverOrM3uUrl,
            username = username,
            passwordPlain = passwordPlain
        )
        binding.iptvLoadingOverlay.visibility = View.GONE
        when (res) {
            is IptvSyncResult.Success -> {
                activeIptvSource = res.source
                iptvNavLevel = 1
                Toast.makeText(
                    hostActivity,
                    getString(R.string.iptv_sync_success_toast, res.source.name, res.totalLoaded),
                    Toast.LENGTH_LONG
                ).show()
                renderIptvCurrentLevel()
            }
            is IptvSyncResult.Error -> {
                Toast.makeText(hostActivity, res.userMessage, Toast.LENGTH_LONG).show()
                if (type == IptvSourceType.XTREAM) {
                    val diagInfo = "Server: $serverOrM3uUrl\nUser: $username\nStatus: ${res.technicalDiagnostic}"
                    showPremiumMenuDialog(
                        headerIcon = "⚠️",
                        title = res.userMessage,
                        subtitle = diagInfo,
                        items = listOf(
                            PremiumMenuItem(
                                iconEmoji = "✏️",
                                title = getString(R.string.iptv_source_edit_action),
                                subtitle = "مراجعة وتعديل الرابط أو اليوزر أو الباسورد",
                                isAccentHighlighted = true,
                                onClick = {
                                    showAddOrEditXtreamDialog(
                                        existingSource = null,
                                        prefillName = name,
                                        prefillServer = serverOrM3uUrl,
                                        prefillUser = username,
                                        prefillPass = passwordPlain
                                    )
                                }
                            )
                        )
                    )
                }
                renderIptvCurrentLevel()
            }
        }
    }
}

internal fun MainActivity.refreshExistingIptvSource(source: IptvSource) {
    val decryptedPass = if (source.type == IptvSourceType.XTREAM) {
        val dec = IptvCredentialVault.decryptSecretOrNull(source.encryptedPassword)
        if (dec == null) {
            Toast.makeText(this, getString(R.string.iptv_err_keystore_encryption), Toast.LENGTH_LONG).show()
            return
        }
        dec
    } else ""
    syncIptvSourceWithProgress(
        existingSourceId = source.id,
        name = source.name,
        type = source.type,
        serverOrM3uUrl = source.serverOrM3uUrl,
        username = source.username,
        passwordPlain = decryptedPass
    )
}

internal fun MainActivity.showIptvSourceManagementMenu(source: IptvSource) {
    settingsBackStack.clear()
    val items = mutableListOf(
        PremiumMenuItem(
            iconEmoji = "📺",
            title = getString(R.string.iptv_source_open_action),
            subtitle = "${source.totalItemsCount()} ${getString(R.string.iptv_items_unit)}",
            isAccentHighlighted = true,
            onClick = {
                activeIptvSource = source
                IptvRepository.setActiveSourceId(this, source.id)
                iptvNavLevel = 1
                renderIptvCurrentLevel()
            }
        ),
        PremiumMenuItem(
            iconEmoji = "🔄",
            title = getString(R.string.iptv_source_refresh_action),
            subtitle = getString(R.string.iptv_source_refresh_sub),
            onClick = {
                refreshExistingIptvSource(source)
            }
        )
    )

    if (source.type == IptvSourceType.XTREAM || source.type == IptvSourceType.M3U_URL) {
        items.add(
            PremiumMenuItem(
                iconEmoji = "✏️",
                title = getString(R.string.iptv_source_edit_action),
                subtitle = source.serverOrM3uUrl,
                onClick = {
                    pushSettingsBackStack { showIptvSourceManagementMenu(source) }
                    if (source.type == IptvSourceType.XTREAM) {
                        showAddOrEditXtreamDialog(source)
                    } else {
                        showAddOrEditM3uUrlDialog(source)
                    }
                }
            )
        )
    }

    items.add(
        PremiumMenuItem(
            iconEmoji = "🗑️",
            title = getString(R.string.iptv_source_delete_action),
            subtitle = getString(R.string.delete_action),
            isDanger = true,
            onClick = {
                IptvRepository.deleteSource(this, source.id)
                if (activeIptvSource?.id == source.id) {
                    activeIptvSource = IptvRepository.getSources(this).firstOrNull()
                    iptvNavLevel = 0
                }
                Toast.makeText(this, getString(R.string.iptv_source_deleted_toast, source.name), Toast.LENGTH_SHORT).show()
                renderIptvCurrentLevel()
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "📡",
        title = source.name,
        subtitle = "${source.type.name} • ${source.accountStatus}",
        items = items
    )
}

internal fun MainActivity.handleIptvDownloadRequest(item: Any) {
    when (item) {
        is IptvStreamItem -> {
            if (item.streamUrl.isNotBlank()) {
                downloadIptvMediaLocally(
                    title = item.title,
                    streamUrl = item.streamUrl,
                    userAgent = item.httpUserAgent,
                    referrer = item.httpReferrer
                )
            }
        }
        is IptvEpisodeItem -> {
            if (item.streamUrl.isNotBlank()) {
                val sTitle = activeIptvSeriesInfo?.seriesTitle ?: ""
                val dlTitle = if (sTitle.isNotBlank()) {
                    "${sTitle}_S${item.seasonNumber}E${item.episodeNumber}_${item.title}"
                } else {
                    "S${item.seasonNumber}E${item.episodeNumber}_${item.title}"
                }
                downloadIptvMediaLocally(
                    title = dlTitle,
                    streamUrl = item.streamUrl,
                    userAgent = item.httpUserAgent,
                    referrer = item.httpReferrer
                )
            }
        }
    }
}

internal fun MainActivity.downloadIptvMediaLocally(
    title: String,
    streamUrl: String,
    userAgent: String = "",
    referrer: String = ""
) {
    val hostActivity = this
    val cleanUrl = streamUrl.trim()
    if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
        Toast.makeText(this, getString(R.string.iptv_download_failed_toast), Toast.LENGTH_SHORT).show()
        return
    }

    val urlPath = cleanUrl.substringBefore("?").substringAfterLast("/")
    val rawExt = urlPath.substringAfterLast(".", "").lowercase(Locale.ROOT)
    val ext = when {
        rawExt in setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "flv", "m4v") -> rawExt
        else -> "mp4"
    }

    val sanitizedBase = title
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifEmpty { "PureVideo_${System.currentTimeMillis()}" }

    val fileName = if (sanitizedBase.lowercase(Locale.ROOT).endsWith(".$ext")) {
        sanitizedBase
    } else {
        "$sanitizedBase.$ext"
    }

    val ua = userAgent.ifBlank {
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }

    try {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(cleanUrl)).apply {
            setTitle(title)
            setDescription("Pure Video Player • Offline Download")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            addRequestHeader("User-Agent", ua)
            if (referrer.isNotBlank()) {
                addRequestHeader("Referer", referrer)
            }
            @Suppress("DEPRECATION")
            allowScanningByMediaScanner()
            setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "PureVideoPlayer/$fileName")
        }
        dm.enqueue(req)
        Toast.makeText(this, getString(R.string.iptv_download_started_toast, title), Toast.LENGTH_LONG).show()
    } catch (_: Exception) {
        // Fallback direct background stream download to Movies/PureVideoPlayer
        Toast.makeText(this, getString(R.string.iptv_download_started_toast, title), Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            val savedFile = withContext(Dispatchers.IO) {
                try {
                    val moviesRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        ?: getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                        ?: filesDir
                    val targetDir = File(moviesRoot, "PureVideoPlayer").apply { mkdirs() }
                    val outFile = File(targetDir, fileName)
                    val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", ua)
                        if (referrer.isNotBlank()) setRequestProperty("Referer", referrer)
                    }
                    if (conn.responseCode !in 200..299) return@withContext null
                    conn.inputStream.use { input ->
                        FileOutputStream(outFile).use { output ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                output.write(buf, 0, r)
                            }
                        }
                    }
                    if (outFile.exists() && outFile.length() > 0L) {
                        runCatching {
                            MediaScannerConnection.scanFile(
                                applicationContext,
                                arrayOf(outFile.absolutePath),
                                null,
                                null
                            )
                        }
                        outFile
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
            if (!isFinishing && !isDestroyed) {
                if (savedFile != null) {
                    Toast.makeText(
                        hostActivity,
                        getString(R.string.iptv_download_completed_toast, title),
                        Toast.LENGTH_LONG
                    ).show()
                    loadVideoFoldersOnly()
                } else {
                    Toast.makeText(
                        hostActivity,
                        getString(R.string.iptv_download_failed_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}

internal fun MainActivity.showIptvMovieDetailsDialog(movie: IptvStreamItem) {
    settingsBackStack.clear()
    val metaParts = mutableListOf<String>()
    if (movie.categoryName.isNotBlank()) metaParts.add(movie.categoryName)
    if (movie.rating.isNotBlank() && movie.rating != "0") metaParts.add("⭐ ${movie.rating}")
    if (movie.releaseDate.isNotBlank()) metaParts.add(movie.releaseDate)
    val subLine = metaParts.joinToString(" • ").ifEmpty { "VOD Movie" }
    val isFav = IptvRepository.isFavoriteStream(this, movie.sourceId, movie.id)

    val items = mutableListOf(
        PremiumMenuItem(
            iconEmoji = "▶️",
            title = getString(R.string.iptv_play_movie_now),
            subtitle = subLine,
            isAccentHighlighted = true,
            onClick = {
                IptvRepository.recordRecentWatchedItem(this, movie.sourceId, movie.id)
                val savedPos = PlaybackStore.getSavedPosition(this, Uri.parse(movie.streamUrl))
                launchPlayer(
                    uri = Uri.parse(movie.streamUrl),
                    title = movie.title,
                    startPositionMs = if (savedPos > 3000L) savedPos else 0L,
                    httpUserAgent = movie.httpUserAgent,
                    httpReferrer = movie.httpReferrer
                )
            }
        ),
        PremiumMenuItem(
            iconEmoji = "⬇️",
            title = getString(R.string.iptv_download_btn),
            subtitle = getString(R.string.iptv_download_sub),
            isAccentHighlighted = true,
            onClick = {
                settingsBackStack.clear()
                downloadIptvMediaLocally(
                    title = movie.title,
                    streamUrl = movie.streamUrl,
                    userAgent = movie.httpUserAgent,
                    referrer = movie.httpReferrer
                )
            }
        ),
        PremiumMenuItem(
            iconEmoji = if (isFav) "💔" else "❤️",
            title = if (isFav) getString(R.string.iptv_fav_remove_option) else getString(R.string.iptv_fav_add_option),
            subtitle = getString(R.string.iptv_subtab_favorites),
            onClick = {
                val nowFav = IptvRepository.toggleFavoriteStream(this, movie.sourceId, movie.id)
                val msgRes = if (nowFav) R.string.iptv_fav_added_toast else R.string.iptv_fav_removed_toast
                Toast.makeText(this, getString(msgRes, movie.title), Toast.LENGTH_SHORT).show()
                renderIptvCurrentLevel()
                showIptvMovieDetailsDialog(movie)
            }
        )
    )
    if (movie.plot.isNotBlank()) {
        items.add(
            PremiumMenuItem(
                iconEmoji = "📝",
                title = getString(R.string.iptv_movie_story_label),
                subtitle = movie.plot,
                isInteractive = false
            )
        )
    }
    items.add(
        PremiumMenuItem(
            iconEmoji = "🗂️",
            title = getString(R.string.iptv_reclassify_category_title),
            subtitle = movie.categoryName,
            onClick = {
                pushSettingsBackStack { showIptvMovieDetailsDialog(movie) }
                showReclassifyCategoryDialog(movie.categoryName)
            }
        )
    )

    showPremiumMenuDialog(
        headerIcon = "🎬",
        title = movie.title,
        subtitle = subLine,
        items = items
    )
}

internal fun MainActivity.openIptvSeriesSeasons(seriesItem: IptvStreamItem) {
    val hostActivity = this
    val src = activeIptvSource ?: return
    if (src.type == IptvSourceType.XTREAM && seriesItem.seriesId.isNotBlank()) {
        binding.iptvLoadingOverlay.visibility = View.VISIBLE
        binding.tvIptvLoadingStatus.text = getString(R.string.iptv_loading_series_episodes)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { XtreamCodesClient.fetchSeriesSeasonsAndEpisodes(src, seriesItem) }.getOrNull()
            }
            binding.iptvLoadingOverlay.visibility = View.GONE
            if (res != null && res.seasons.isNotEmpty()) {
                activeIptvSeriesInfo = res
                iptvNavLevel = 4
                renderIptvCurrentLevel()
            } else {
                Toast.makeText(hostActivity, getString(R.string.iptv_err_no_episodes), Toast.LENGTH_SHORT).show()
            }
        }
    } else {
        // M3U Series: query episodes directly from Room DAO by seriesName
        val sName = seriesItem.seriesName.ifBlank { seriesItem.title }
        val seasons = IptvRepository.getM3uSeriesSeasonsSql(this, src.id, seriesItem)
        if (seasons.isNotEmpty()) {
            activeIptvSeriesInfo = IptvSeriesInfoResult(
                seriesTitle = sName,
                coverUrl = seriesItem.logoUrl,
                plot = seriesItem.plot,
                rating = seriesItem.rating,
                releaseDate = seriesItem.releaseDate,
                seasons = seasons
            )
            iptvNavLevel = 4
            renderIptvCurrentLevel()
        } else {
            Toast.makeText(this, getString(R.string.iptv_err_no_episodes), Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun MainActivity.showReclassifyCategoryDialog(categoryName: String) {
    val src = activeIptvSource ?: return
    val options = listOf(
        Triple("📺", getString(R.string.iptv_sec_live_tv), IptvContentType.LIVE_TV),
        Triple("🎬", getString(R.string.iptv_sec_movies), IptvContentType.MOVIES),
        Triple("🎞️", getString(R.string.iptv_sec_series), IptvContentType.SERIES),
        Triple("📻", getString(R.string.iptv_sec_radio), IptvContentType.RADIO)
    )
    val items = options.map { (emoji, label, targetType) ->
        PremiumMenuItem(
            iconEmoji = emoji,
            title = label,
            subtitle = categoryName,
            isAccentHighlighted = (activeIptvContentType == targetType),
            onClick = {
                IptvRepository.setCategoryOverride(this, src.id, categoryName, targetType)
                activeIptvSource = IptvRepository.getSources(this).find { it.id == src.id }
                Toast.makeText(this, "✅ $categoryName → $label", Toast.LENGTH_SHORT).show()
                settingsBackStack.clear()
                renderIptvCurrentLevel()
            }
        )
    }
    showPremiumMenuDialog(
        headerIcon = "🗂️",
        title = getString(R.string.iptv_reclassify_category_title),
        subtitle = categoryName,
        items = items
    )
}

internal fun MainActivity.addBuiltInDemoIptvSource() {
    val sampleM3u = """
        #EXTM3U
        #EXTINF:-1 tvg-name="Big Buck Bunny Live" group-title="Live Channels / قنوات مباشرة",Big Buck Bunny HD (Live Stream)
        https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
        #EXTINF:-1 tvg-name="Apple BipBop Live" group-title="Live Channels / قنوات مباشرة",Apple Advanced HLS Live
        https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8
        #EXTINF:-1 tvg-name="Tears of Steel HLS Live" group-title="Live Documentary / وثائقي مباشر",Tears of Steel Live HLS
        https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8
        #EXTINF:-1 tvg-name="Sintel Cinema Movie" group-title="Movies / أفلام سينمائية",Sintel Open Movie (1080p VOD)
        https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4
        #EXTINF:-1 tvg-name="Tears of Steel Movie" group-title="Movies / أفلام سينمائية",Tears of Steel Sci-Fi (VOD)
        https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4
        #EXTINF:-1 tvg-name="Elephants Dream Movie" group-title="Movies / أفلام أنيميشن",Elephants Dream HD (VOD)
        https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4
        #EXTINF:-1 tvg-name="Blender Adventures S01E01" group-title="Series / مسلسلات",Blender Adventures S01E01
        https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4
        #EXTINF:-1 tvg-name="Blender Adventures S01E02" group-title="Series / مسلسلات",Blender Adventures S01E02
        https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4
        #EXTINF:-1 tvg-name="Blender Adventures S02E01" group-title="Series / مسلسلات",Blender Adventures S02E01
        https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4
        #EXTINF:-1 tvg-name="Blender Adventures S02E02" group-title="Series / مسلسلات",Blender Adventures S02E02
        https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4
    """.trimIndent()

    lifecycleScope.launch {
        val demoFile = withContext(Dispatchers.IO) {
            val f = File(filesDir, "demo_iptv_showcase.m3u8")
            f.writeText(sampleM3u, Charsets.UTF_8)
            f
        }
        syncIptvSourceWithProgress(
            existingSourceId = "demo_builtin_source",
            name = "Pure IPTV Demo (قنوات وأفلام ومسلسلات تجريبية)",
            type = IptvSourceType.M3U_FILE,
            serverOrM3uUrl = Uri.fromFile(demoFile).toString()
        )
    }
}
