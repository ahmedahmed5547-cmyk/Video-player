package com.pureplayer.video

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pure-Kotlin Shorts / Reels eligibility filter (100% JVM unit-testable):
 * - Excludes Private Vault locked folders.
 * - Strictly respects Hidden folders toggle (OFF hides hidden shorts; ON includes them).
 * - Selects short clips (<= 3 min) or vertical clips (height > width, <= 5 min), newest first.
 */
object ShortsFeedPolicy {
    const val MAX_SHORT_DURATION_MS = 180_000L // 3 minutes
    const val MAX_VERTICAL_SHORT_MS = 300_000L // 5 minutes for portrait reels

    fun isShortVideo(video: VideoItem): Boolean {
        val isPortrait = video.height > 0 && video.width > 0 && video.height > video.width
        if (video.duration <= 0L) return isPortrait
        if (video.duration <= MAX_SHORT_DURATION_MS) return true
        return isPortrait && video.duration <= MAX_VERTICAL_SHORT_MS
    }

    fun filterShorts(
        allVideos: List<VideoItem>,
        lockedFolders: Set<String>,
        showHidden: Boolean
    ): List<VideoItem> {
        return allVideos
            .asSequence()
            .filter { it.folderName !in lockedFolders }
            .filter { showHidden || !it.isHidden }
            .filter { isShortVideo(it) }
            .sortedByDescending { it.dateAddedSec }
            .toList()
    }
}

/**
 * Zero-Black-Flash, Instagram-Reels-style vertical Shorts feed controller:
 * - Bounded 3-player ExoPlayer pool (hard max = 3: current, next, previous) pre-warms neighbors
 *   with playWhenReady = false so frame 0 is already decoded before the user swipes.
 * - TextureView + transparent shutter + cached first-frame poster overlay that fades out (120ms)
 *   ONLY when Player.Listener.onRenderedFirstFrame() fires — zero black flash on swipe or re-entry.
 * - Tuned fast-start local DefaultLoadControl (bufferForPlaybackMs = 250ms).
 * - Pauses active audio immediately when dragging starts so audio NEVER overlaps.
 * - Releases ALL pooled players cleanly on tab leave / onPause / onStop / onDestroy (zero leaks).
 */
class ShortsFeedController(
    private val activity: MainActivity,
    private val container: FrameLayout
) {
    private companion object {
        const val MAX_POOL_SIZE = 3
    }

    private data class PooledPlayerEntry(
        val player: ExoPlayer,
        var boundPosition: Int = -1,
        var boundUri: Uri? = null,
        var firstFrameRendered: Boolean = false,
        var activeListener: Player.Listener? = null
    )

    private var recyclerView: RecyclerView? = null
    private var emptyTextView: TextView? = null
    private var snapHelper: PagerSnapHelper? = null
    private var layoutManager: LinearLayoutManager? = null
    private val playerPool = mutableListOf<PooledPlayerEntry>()
    private var activePosition: Int = -1
    private var isMuted: Boolean = false
    private var isVisibleToUser: Boolean = false
    private var shortsList: List<VideoItem> = emptyList()
    private var adapter: ShortsAdapter? = null
    private var filterJob: Job? = null
    var isDataDirty: Boolean = true

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun ensureInitialized() {
        if (recyclerView != null) return

        val rv = RecyclerView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
            setHasFixedSize(true)
            setItemViewCacheSize(2) // Keeps prev + next ViewHolders attached for seamless swipes
            itemAnimator = null
        }
        val lm = LinearLayoutManager(activity, RecyclerView.VERTICAL, false).apply {
            initialPrefetchItemCount = 2
        }
        rv.layoutManager = lm
        val snap = PagerSnapHelper()
        snap.attachToRecyclerView(rv)

        val ad = ShortsAdapter().apply {
            setHasStableIds(true)
        }
        rv.adapter = ad

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        // Immediately silence/pause outgoing video so audio never overlaps during swipe
                        pauseAllPooledPlayers(seekOthersToZero = false)
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!isVisibleToUser) return
                        val snapView = snap.findSnapView(lm) ?: return
                        val pos = lm.getPosition(snapView)
                        if (pos != RecyclerView.NO_POSITION) {
                            activateSettledPosition(pos)
                        }
                    }
                }
            }
        })

        val tvEmpty = TextView(activity).apply {
            text = activity.getString(R.string.shorts_empty_msg)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        container.removeAllViews()
        container.addView(rv)
        container.addView(tvEmpty)

        recyclerView = rv
        emptyTextView = tvEmpty
        snapHelper = snap
        layoutManager = lm
        adapter = ad
    }

    private fun createPooledPlayer(): PooledPlayerEntry {
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 4_000, 250, 500)
            .build()
        val exo = ExoPlayer.Builder(activity)
            .setAudioAttributes(audioAttrs, true)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                volume = 0f
                playWhenReady = false
            }
        val entry = PooledPlayerEntry(player = exo)
        playerPool.add(entry)
        return entry
    }

    private fun obtainEntryForPosition(targetPos: Int, keepWindow: Set<Int>): PooledPlayerEntry {
        // 1. Reuse existing pre-warmed entry for this exact position
        playerPool.firstOrNull { it.boundPosition == targetPos }?.let { return it }
        // 2. Allocate new player if pool hasn't reached hard cap (3)
        if (playerPool.size < MAX_POOL_SIZE) {
            return createPooledPlayer()
        }
        // 3. Recycle a player whose position is outside {current-1, current, current+1}
        val recyclable = playerPool.firstOrNull { it.boundPosition !in keepWindow } ?: playerPool.first()
        detachPlayerFromAnyView(recyclable.player)
        recyclable.activeListener?.let { recyclable.player.removeListener(it) }
        recyclable.activeListener = null
        recyclable.boundPosition = -1
        recyclable.boundUri = null
        recyclable.firstFrameRendered = false
        recyclable.player.playWhenReady = false
        recyclable.player.volume = 0f
        return recyclable
    }

    private fun detachPlayerFromAnyView(player: ExoPlayer) {
        val rv = recyclerView ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val holder = rv.getChildViewHolder(child) as? ShortsViewHolder ?: continue
            if (holder.playerView.player == player) {
                holder.playerView.player = null
                holder.showPosterImmediately()
            }
        }
    }

    private fun pauseAllPooledPlayers(seekOthersToZero: Boolean, exceptPos: Int = -1) {
        for (entry in playerPool) {
            if (entry.boundPosition == exceptPos) continue
            entry.player.playWhenReady = false
            entry.player.volume = 0f
            if (seekOthersToZero && entry.player.playbackState != Player.STATE_IDLE && entry.player.currentPosition > 0L) {
                entry.player.seekTo(0L)
            }
        }
    }

    fun showAndPlay(allVideos: List<VideoItem>) {
        ensureInitialized()
        isVisibleToUser = true
        refreshDataAsync(allVideos)
    }

    fun markDirtyIfHidden(allVideos: List<VideoItem>) {
        isDataDirty = true
        if (isVisibleToUser) {
            refreshDataAsync(allVideos)
        }
    }

    fun refreshDataAsync(allVideos: List<VideoItem>) {
        if (recyclerView == null) return
        val snapshot = allVideos.toList()
        val locked = PlaybackStore.getLockedFolders(activity)
        val showHidden = PlaybackStore.isShowHiddenFolders(activity)
        val prevList = shortsList

        filterJob?.cancel()
        filterJob = activity.lifecycleScope.launch {
            val (filtered, diffResult) = withContext(Dispatchers.Default) {
                val next = ShortsFeedPolicy.filterShorts(snapshot, locked, showHidden)
                val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = prevList.size
                    override fun getNewListSize(): Int = next.size
                    override fun areItemsTheSame(o: Int, n: Int): Boolean = prevList[o].id == next[n].id
                    override fun areContentsTheSame(o: Int, n: Int): Boolean =
                        prevList[o].uri == next[n].uri && prevList[o].title == next[n].title && prevList[o].isHidden == next[n].isHidden
                })
                Pair(next, diff)
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch

            isDataDirty = false
            shortsList = filtered
            emptyTextView?.setTextColor(PlaybackStore.getSecondaryTextColor(activity))
            container.setBackgroundColor(PlaybackStore.getBackgroundColor(activity))
            adapter?.let { diffResult.dispatchUpdatesTo(it) }

            if (filtered.isEmpty()) {
                pauseAndDetachSurface()
                activePosition = -1
                recyclerView?.visibility = View.GONE
                emptyTextView?.visibility = View.VISIBLE
                return@launch
            }

            recyclerView?.visibility = View.VISIBLE
            emptyTextView?.visibility = View.GONE

            if (isVisibleToUser) {
                val targetPos = if (activePosition in filtered.indices) activePosition else 0
                recyclerView?.post {
                    if (!isVisibleToUser) return@post
                    if (activePosition !in filtered.indices) {
                        recyclerView?.scrollToPosition(0)
                    }
                    activateSettledPosition(targetPos)
                }
            }
        }
    }

    private fun activateSettledPosition(position: Int) {
        if (!isVisibleToUser || position !in shortsList.indices) return
        val rv = recyclerView ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) as? ShortsViewHolder ?: run {
            rv.post { if (isVisibleToUser) activateSettledPosition(position) }
            return
        }

        val keepWindow = setOf(position - 1, position, position + 1).filter { it in shortsList.indices }.toSet()
        activePosition = position

        // Pause & rewind all non-active pooled players so only the visible short plays audio
        pauseAllPooledPlayers(seekOthersToZero = true, exceptPos = position)

        val video = shortsList[position]
        val entry = obtainEntryForPosition(position, keepWindow)
        bindEntryToHolder(entry, position, video, holder, autoPlay = true)

        // Pre-warm next (+1) and previous (-1) neighbors paused so frame 0 is decoded before swipe!
        if (position + 1 in shortsList.indices) {
            prewarmNeighbor(position + 1, keepWindow)
        }
        if (position - 1 in shortsList.indices) {
            prewarmNeighbor(position - 1, keepWindow)
        }
    }

    private fun prewarmNeighbor(neighborPos: Int, keepWindow: Set<Int>) {
        if (!isVisibleToUser || neighborPos !in shortsList.indices) return
        val video = shortsList[neighborPos]
        val entry = obtainEntryForPosition(neighborPos, keepWindow)
        val holder = recyclerView?.findViewHolderForAdapterPosition(neighborPos) as? ShortsViewHolder
        bindEntryToHolder(entry, neighborPos, video, holder, autoPlay = false)
    }

    private fun bindEntryToHolder(
        entry: PooledPlayerEntry,
        position: Int,
        video: VideoItem,
        holder: ShortsViewHolder?,
        autoPlay: Boolean
    ) {
        val player = entry.player
        val needsPrepare = (entry.boundUri != video.uri || player.playbackState == Player.STATE_IDLE)

        if (needsPrepare) {
            entry.boundPosition = position
            entry.boundUri = video.uri
            entry.firstFrameRendered = false
            player.setMediaItem(MediaItem.fromUri(video.uri))
            player.prepare()
        } else {
            entry.boundPosition = position
        }

        // Attach Player.Listener to fade out poster ONLY when first frame actually renders
        entry.activeListener?.let { player.removeListener(it) }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                entry.firstFrameRendered = true
                val liveHolder = recyclerView?.findViewHolderForAdapterPosition(position) as? ShortsViewHolder
                liveHolder?.fadeOutPoster()
            }
        }
        entry.activeListener = listener
        player.addListener(listener)

        if (holder != null) {
            if (holder.playerView.player != player) {
                holder.playerView.player = player
            }
            if (entry.firstFrameRendered) {
                holder.hidePosterImmediately()
            } else {
                holder.showPosterImmediately()
            }
            holder.tvPauseOverlay.visibility = View.GONE
            holder.updateMuteIcon(isMuted, animate = false)
        }

        if (autoPlay) {
            player.volume = if (isMuted) 0f else 1f
            player.playWhenReady = true
        } else {
            player.volume = 0f
            player.playWhenReady = false
        }
    }

    /**
     * Releases all pooled ExoPlayer instances and detaches every PlayerView surface cleanly
     * when leaving Shorts or when the app goes to background (onPause / onStop).
     * Poster thumbnails remain visible so re-entering Shorts has zero black flash.
     */
    fun pauseAndDetachSurface() {
        isVisibleToUser = false
        filterJob?.cancel()
        val rv = recyclerView
        if (rv != null) {
            for (i in 0 until rv.childCount) {
                val h = rv.getChildViewHolder(rv.getChildAt(i)) as? ShortsViewHolder ?: continue
                h.playerView.player = null
                h.showPosterImmediately()
            }
        }
        for (entry in playerPool) {
            entry.activeListener?.let { entry.player.removeListener(it) }
            entry.player.release()
        }
        playerPool.clear()
    }

    fun resumeIfVisible() {
        if (isVisibleToUser && shortsList.isNotEmpty()) {
            val pos = if (activePosition in shortsList.indices) activePosition else 0
            activateSettledPosition(pos)
        }
    }

    fun release() {
        pauseAndDetachSurface()
        activePosition = -1
    }

    private fun shareShortVideo(video: VideoItem) {
        runCatching {
            val shareUri: Uri = if (video.uri.scheme == "content") {
                video.uri
            } else if (video.path.isNotBlank() && File(video.path).exists()) {
                FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(video.path))
            } else {
                video.uri
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, video.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.shorts_share_btn)))
        }.onFailure {
            Toast.makeText(activity, activity.getString(R.string.shorts_share_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun attachPressScaleAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
            }
            false
        }
    }

    private inner class ShortsViewHolder(val root: FrameLayout) : RecyclerView.ViewHolder(root) {
        val playerView: PlayerView = root.findViewById(R.id.shortPlayerView)
        val ivPoster: ImageView = root.findViewById(R.id.ivShortPoster)
        val tvTitle: TextView
        val tvMeta: TextView
        val tvPauseOverlay: TextView
        val btnShare: FrameLayout
        val btnMute: FrameLayout
        val ivMuteIcon: ImageView
        val btnFullPlayer: FrameLayout
        var posterJob: Job? = null

        init {
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Bottom gradient scrim for crisp readable labels
            val scrim = View(activity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(195, 0, 0, 0), Color.TRANSPARENT)
                )
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(168),
                    Gravity.BOTTOM
                )
            }
            root.addView(scrim)

            // Center play/pause tap indicator
            tvPauseOverlay = TextView(activity).apply {
                text = "▶"
                textSize = 32f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(120, 0, 0, 0))
                }
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER)
            }
            root.addView(tvPauseOverlay)

            // Bottom-start video info overlay
            val infoCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(82), dp(20))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                )
            }
            tvTitle = TextView(activity).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 2
                setTypeface(typeface, Typeface.BOLD)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            tvMeta = TextView(activity).apply {
                setTextColor(Color.argb(210, 255, 255, 255))
                textSize = 12.5f
                maxLines = 1
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(0, dp(4), 0, 0)
            }
            infoCol.addView(tvTitle)
            infoCol.addView(tvMeta)
            root.addView(infoCol)

            // Calm Instagram-style side action stack (20dp vertical spacing, 40% black circles)
            val actionCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(14), dp(24))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                )
            }

            fun makeMinimalActionBadge(iconRes: Int, iconSizeDp: Int, desc: String): Pair<FrameLayout, ImageView> {
                val badgeBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(102, 0, 0, 0)) // ~40% black subtle circle
                }
                val badge = FrameLayout(activity).apply {
                    contentDescription = desc
                    elevation = dp(4).toFloat()
                    background = RippleDrawable(ColorStateList.valueOf(Color.argb(72, 255, 255, 255)), badgeBg, null)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                        bottomMargin = dp(20)
                    }
                }
                val iv = ImageView(activity).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp), Gravity.CENTER)
                }
                badge.addView(iv)
                attachPressScaleAnimation(badge)
                return badge to iv
            }

            val (shareBadge, _) = makeMinimalActionBadge(R.drawable.ic_shorts_share, 26, activity.getString(R.string.shorts_share_btn))
            val (muteBadge, muteIv) = makeMinimalActionBadge(R.drawable.ic_shorts_volume_on, 22, activity.getString(R.string.shorts_mute_btn))
            val (fullBadge, _) = makeMinimalActionBadge(R.drawable.ic_shorts_fullscreen, 22, activity.getString(R.string.shorts_open_full_btn))

            btnShare = shareBadge
            btnMute = muteBadge
            ivMuteIcon = muteIv
            btnFullPlayer = fullBadge

            actionCol.addView(btnShare)
            actionCol.addView(btnMute)
            actionCol.addView(btnFullPlayer)
            root.addView(actionCol)
        }

        fun showPosterImmediately() {
            ivPoster.animate().cancel()
            ivPoster.alpha = 1f
            ivPoster.visibility = View.VISIBLE
        }

        fun hidePosterImmediately() {
            ivPoster.animate().cancel()
            ivPoster.alpha = 0f
            ivPoster.visibility = View.INVISIBLE
        }

        fun fadeOutPoster() {
            if (ivPoster.visibility != View.VISIBLE) return
            ivPoster.animate().cancel()
            ivPoster.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction { ivPoster.visibility = View.INVISIBLE }
                .start()
        }

        fun updateMuteIcon(muted: Boolean, animate: Boolean) {
            val targetRes = if (muted) R.drawable.ic_shorts_volume_off else R.drawable.ic_shorts_volume_on
            if (animate) {
                ivMuteIcon.animate().alpha(0.2f).setDuration(80L).withEndAction {
                    ivMuteIcon.setImageResource(targetRes)
                    ivMuteIcon.animate().alpha(1f).setDuration(120L).start()
                }.start()
            } else {
                ivMuteIcon.setImageResource(targetRes)
                ivMuteIcon.alpha = 1f
            }
        }
    }

    private inner class ShortsAdapter : RecyclerView.Adapter<ShortsViewHolder>() {
        override fun getItemId(position: Int): Long = shortsList.getOrNull(position)?.id ?: position.toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortsViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_short_page, parent, false) as FrameLayout
            return ShortsViewHolder(view)
        }

        override fun getItemCount(): Int = shortsList.size

        override fun onViewRecycled(holder: ShortsViewHolder) {
            holder.posterJob?.cancel()
            holder.posterJob = null
            holder.playerView.player = null
            holder.showPosterImmediately()
            super.onViewRecycled(holder)
        }

        override fun onBindViewHolder(holder: ShortsViewHolder, position: Int) {
            val video = shortsList[position]
            val accent = PlaybackStore.getAccentColor(activity)
            val hiddenPrefix = if (video.isHidden) "👁️‍🗨️ " else ""
            holder.tvTitle.text = "$hiddenPrefix${video.title}"
            val dur = PlaybackStore.formatTime(video.duration)
            holder.tvMeta.text = "${video.folderName}  •  $dur"
            holder.updateMuteIcon(isMuted, animate = false)

            // Load cached first-frame poster off the main thread so zero black flash ever appears
            holder.posterJob?.cancel()
            holder.showPosterImmediately()
            holder.posterJob = VideoThumbnailLoader.loadVideoThumbnail(activity, video, holder.ivPoster, accent)

            // If this position already has a pre-warmed player in the pool, attach it immediately
            val existingEntry = playerPool.firstOrNull { it.boundPosition == position && it.boundUri == video.uri }
            if (existingEntry != null) {
                if (holder.playerView.player != existingEntry.player) {
                    holder.playerView.player = existingEntry.player
                }
                if (existingEntry.firstFrameRendered) {
                    holder.hidePosterImmediately()
                }
            } else {
                holder.playerView.player = null
            }

            holder.playerView.setOnClickListener {
                val p = playerPool.firstOrNull { it.boundPosition == activePosition }?.player ?: return@setOnClickListener
                if (p.isPlaying) {
                    p.pause()
                    holder.tvPauseOverlay.visibility = View.VISIBLE
                } else {
                    p.play()
                    holder.tvPauseOverlay.visibility = View.GONE
                }
            }

            holder.btnShare.setOnClickListener {
                shareShortVideo(video)
            }

            holder.btnMute.setOnClickListener {
                isMuted = !isMuted
                val activePlayer = playerPool.firstOrNull { it.boundPosition == activePosition }?.player
                activePlayer?.volume = if (isMuted) 0f else 1f
                holder.updateMuteIcon(isMuted, animate = true)
            }

            holder.btnFullPlayer.setOnClickListener {
                pauseAndDetachSurface()
                val intent = Intent(activity, PlayerActivity::class.java).apply {
                    data = video.uri
                    putExtra("VIDEO_TITLE", video.title)
                    putExtra("VIDEO_URI_STR", video.uri.toString())
                }
                activity.startActivity(intent)
            }
        }
    }
}
