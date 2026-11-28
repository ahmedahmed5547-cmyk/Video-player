package com.pureplayer.video

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CacheSizeBreakdown(
    val thumbnailsBytes: Long = 0L,
    val preloadBytes: Long = 0L,
    val folderCacheBytes: Long = 0L,
    val translatedSubtitlesBytes: Long = 0L,
    val otherTempBytes: Long = 0L
) {
    val totalBytes: Long
        get() = thumbnailsBytes + preloadBytes + folderCacheBytes + translatedSubtitlesBytes + otherTempBytes
}

/**
 * Safe off-main-thread cache calculator & cleaner.
 * NEVER touches SharedPreferences, Room databases, IPTV credentials, playback history,
 * favorites, hidden-folder settings, user-downloaded original subtitles, Vault files, or user videos.
 */
object AppCacheManager {
    @Volatile
    var lastKnownTotalBytes: Long = -1L
        private set


    private fun dirSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length().coerceAtLeast(0L)
        var total = 0L
        file.listFiles()?.forEach { child ->
            total += dirSize(child)
        }
        return total
    }

    private fun deleteDirContents(dir: File?, skipNames: Set<String> = emptySet()): Long {
        if (dir == null || !dir.exists()) return 0L
        var freed = 0L
        if (dir.isFile) {
            val len = dir.length().coerceAtLeast(0L)
            if (dir.delete()) freed += len
            return freed
        }
        dir.listFiles()?.forEach { child ->
            if (child.name in skipNames) return@forEach
            freed += deleteDirContents(child)
            if (child.isDirectory) child.delete()
        }
        return freed
    }

    private val PRELOAD_DIR_NAMES = setOf("exoplayer", "media3", "shorts_cache", "vlc_cache")
    private val THUMB_DIR_NAMES = setOf("image_cache", "thumb_cache", "thumbnails")

    fun computeBreakdown(context: Context): CacheSizeBreakdown {
        val appCtx = context.applicationContext
        val cacheRoot = appCtx.cacheDir
        val extCacheRoot = appCtx.externalCacheDir

        var thumbBytes = VideoThumbnailLoader.getMemoryCacheBytes()
        var preloadBytes = 0L
        var otherBytes = 0L

        cacheRoot?.listFiles()?.forEach { child ->
            when {
                child.name in THUMB_DIR_NAMES -> thumbBytes += dirSize(child)
                child.name in PRELOAD_DIR_NAMES -> preloadBytes += dirSize(child)
                else -> otherBytes += dirSize(child)
            }
        }
        otherBytes += dirSize(extCacheRoot)

        val folderCacheBytes = VideoIndexCache.getCacheSizeBytes(appCtx)
        val translatedSubDir = File(appCtx.filesDir, "subtitles_cache/translated")
        val translatedSubBytes = dirSize(translatedSubDir)

        return CacheSizeBreakdown(
            thumbnailsBytes = thumbBytes.coerceAtLeast(0L),
            preloadBytes = preloadBytes.coerceAtLeast(0L),
            folderCacheBytes = folderCacheBytes.coerceAtLeast(0L),
            translatedSubtitlesBytes = translatedSubBytes.coerceAtLeast(0L),
            otherTempBytes = otherBytes.coerceAtLeast(0L)
        ).also { lastKnownTotalBytes = it.totalBytes }
    }

    fun clearSelectedCaches(
        context: Context,
        clearThumbnails: Boolean,
        clearPreload: Boolean,
        clearFolderCache: Boolean,
        clearTranslatedSubs: Boolean,
        clearOtherTemp: Boolean
    ): Long {
        val appCtx = context.applicationContext
        val cacheRoot = appCtx.cacheDir
        val extCacheRoot = appCtx.externalCacheDir
        var freed = 0L

        if (clearThumbnails) {
            freed += VideoThumbnailLoader.clearMemoryCache()
            THUMB_DIR_NAMES.forEach { name ->
                freed += deleteDirContents(File(cacheRoot, name))
            }
        }
        if (clearPreload) {
            PRELOAD_DIR_NAMES.forEach { name ->
                freed += deleteDirContents(File(cacheRoot, name))
            }
        }
        if (clearFolderCache) {
            freed += VideoIndexCache.clearCache(appCtx)
        }
        if (clearTranslatedSubs) {
            // ONLY clears auto-translated temp cache; preserves user-downloaded original subtitles!
            val translatedSubDir = File(appCtx.filesDir, "subtitles_cache/translated")
            freed += deleteDirContents(translatedSubDir)
        }
        if (clearOtherTemp) {
            val skip = THUMB_DIR_NAMES + PRELOAD_DIR_NAMES
            freed += deleteDirContents(cacheRoot, skipNames = skip)
            freed += deleteDirContents(extCacheRoot)
        }
        lastKnownTotalBytes = computeBreakdown(appCtx).totalBytes
        return freed
    }
}

/**
 * Theme-aware "Clear cache" confirmation dialog with per-category checkboxes and progress bar.
 */
internal fun MainActivity.showClearCacheDialog(onCleared: () -> Unit = {}) {
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    val accent = PlaybackStore.getAccentColor(this)
    val surface = PlaybackStore.getSurfaceColor(this)
    val primaryText = PlaybackStore.getPrimaryTextColor(this)
    val secondaryText = PlaybackStore.getSecondaryTextColor(this)
    val isLight = PlaybackStore.isLightThemeActive(this)
    val borderColor = Color.argb(if (isLight) 65 else 42, Color.red(secondaryText), Color.green(secondaryText), Color.blue(secondaryText))

    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setCancelable(true)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(22), dp(22), dp(18))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(surface)
            setStroke(dp(1), borderColor)
        }
    }

    val tvTitle = TextView(this).apply {
        text = getString(R.string.clear_cache_title)
        textSize = 18f
        setTextColor(primaryText)
        setTypeface(typeface, Typeface.BOLD)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(6) }
    }
    val tvSub = TextView(this).apply {
        text = getString(R.string.clear_cache_safe_note)
        textSize = 12.5f
        setTextColor(secondaryText)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(14) }
    }
    card.addView(tvTitle)
    card.addView(tvSub)

    fun makeCheckRow(label: String): CheckBox = CheckBox(this).apply {
        text = label
        isChecked = true
        setTextColor(primaryText)
        textSize = 13.5f
        buttonTintList = ColorStateList.valueOf(accent)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    val cbThumbs = makeCheckRow(getString(R.string.clear_cache_cat_thumbs))
    val cbPreload = makeCheckRow(getString(R.string.clear_cache_cat_preload))
    val cbFolders = makeCheckRow(getString(R.string.clear_cache_cat_folders))
    val cbSubs = makeCheckRow(getString(R.string.clear_cache_cat_subs))
    val cbOther = makeCheckRow(getString(R.string.clear_cache_cat_other))

    card.addView(cbThumbs)
    card.addView(cbPreload)
    card.addView(cbFolders)
    card.addView(cbSubs)
    card.addView(cbOther)

    val progressBar = ProgressBar(this).apply {
        visibility = View.GONE
        indeterminateTintList = ColorStateList.valueOf(accent)
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        }
    }
    card.addView(progressBar)

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(16) }
    }

    val btnCancel = TextView(this).apply {
        text = getString(R.string.cancel)
        textSize = 14f
        setTextColor(secondaryText)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { dialog.dismiss() }
    }

    val onAccentText = if (Color.red(accent) * 0.299 + Color.green(accent) * 0.587 + Color.blue(accent) * 0.114 > 170) {
        Color.parseColor("#0B1120")
    } else {
        Color.WHITE
    }
    val clearShape = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(accent)
    }
    val btnClear = TextView(this).apply {
        text = getString(R.string.clear_cache_btn_clear)
        textSize = 14f
        setTextColor(onAccentText)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), clearShape, null)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8) }
    }

    btnRow.addView(btnCancel)
    btnRow.addView(btnClear)
    card.addView(btnRow)

    // Load exact per-category sizes off the main thread
    lifecycleScope.launch {
        val b = withContext(Dispatchers.IO) { AppCacheManager.computeBreakdown(applicationContext) }
        if (isFinishing || isDestroyed) return@launch
        cbThumbs.text = "${getString(R.string.clear_cache_cat_thumbs)} (${HiddenMediaAndPlaylistHelper.formatBytesReadable(b.thumbnailsBytes)})"
        cbPreload.text = "${getString(R.string.clear_cache_cat_preload)} (${HiddenMediaAndPlaylistHelper.formatBytesReadable(b.preloadBytes)})"
        cbFolders.text = "${getString(R.string.clear_cache_cat_folders)} (${HiddenMediaAndPlaylistHelper.formatBytesReadable(b.folderCacheBytes)})"
        cbSubs.text = "${getString(R.string.clear_cache_cat_subs)} (${HiddenMediaAndPlaylistHelper.formatBytesReadable(b.translatedSubtitlesBytes)})"
        cbOther.text = "${getString(R.string.clear_cache_cat_other)} (${HiddenMediaAndPlaylistHelper.formatBytesReadable(b.otherTempBytes)})"
    }

    btnClear.setOnClickListener {
        val clearThumbs = cbThumbs.isChecked
        val clearPreload = cbPreload.isChecked
        val clearFolders = cbFolders.isChecked
        val clearSubs = cbSubs.isChecked
        val clearOther = cbOther.isChecked
        if (!clearThumbs && !clearPreload && !clearFolders && !clearSubs && !clearOther) {
            dialog.dismiss()
            return@setOnClickListener
        }

        // Safely pause/detach Shorts player before clearing cache so active playback never crashes
        shortsFeedController?.pauseAndDetachSurface()
        progressBar.visibility = View.VISIBLE
        btnClear.isEnabled = false
        btnCancel.isEnabled = false

        lifecycleScope.launch {
            val freedBytes = withContext(Dispatchers.IO) {
                AppCacheManager.clearSelectedCaches(
                    context = applicationContext,
                    clearThumbnails = clearThumbs,
                    clearPreload = clearPreload,
                    clearFolderCache = clearFolders,
                    clearTranslatedSubs = clearSubs,
                    clearOtherTemp = clearOther
                )
            }
            if (isFinishing || isDestroyed) return@launch
            dialog.dismiss()
            val freedLabel = HiddenMediaAndPlaylistHelper.formatBytesReadable(freedBytes)
            Toast.makeText(
                this@showClearCacheDialog,
                getString(R.string.clear_cache_freed_toast, freedLabel),
                Toast.LENGTH_LONG
            ).show()
            if (clearFolders) {
                videoLibraryViewModel.refreshLibrary(force = true, showLoadingIfEmpty = false)
            } else if (clearThumbs) {
                adapter.notifyDataSetChanged()
            }
            onCleared()
        }
    }

    val wrap = LinearLayout(this).apply {
        setPadding(dp(20), dp(20), dp(20), dp(20))
        addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    dialog.setContentView(wrap)
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.show()
}
