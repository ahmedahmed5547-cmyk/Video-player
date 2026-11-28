package com.pureplayer.video

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
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
 * Silky-smooth vertical Reels / Shorts feed controller hosted inside MainActivity's shortsTabContainer.
 * Uses a single shared Media3 ExoPlayer + vertical RecyclerView with PagerSnapHelper for instant
 * swipe-to-play, auto-loop, tap-to-pause, Mute toggle, Share button, and Open-in-Player button.
 */
class ShortsFeedController(
    private val activity: MainActivity,
    private val container: FrameLayout
) {
    private var recyclerView: RecyclerView? = null
    private var emptyTextView: TextView? = null
    private var snapHelper: PagerSnapHelper? = null
    private var layoutManager: LinearLayoutManager? = null
    private var exoPlayer: ExoPlayer? = null
    private var activePlayerView: PlayerView? = null
    private var activePosition: Int = -1
    private var isMuted: Boolean = false
    private var isVisibleToUser: Boolean = false
    private var shortsList: List<VideoItem> = emptyList()
    private var adapter: ShortsAdapter? = null

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
        }
        val lm = LinearLayoutManager(activity, RecyclerView.VERTICAL, false)
        rv.layoutManager = lm
        val snap = PagerSnapHelper()
        snap.attachToRecyclerView(rv)

        val ad = ShortsAdapter()
        rv.adapter = ad

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && isVisibleToUser) {
                    val snapView = snap.findSnapView(lm) ?: return
                    val pos = lm.getPosition(snapView)
                    if (pos != RecyclerView.NO_POSITION && pos != activePosition) {
                        playAtPosition(pos)
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

    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(activity)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (isMuted) 0f else 1f
                exoPlayer = this
            }
    }

    fun showAndPlay(allVideos: List<VideoItem>) {
        ensureInitialized()
        isVisibleToUser = true
        refreshData(allVideos)
    }

    fun refreshData(allVideos: List<VideoItem>) {
        if (recyclerView == null) return
        val locked = PlaybackStore.getLockedFolders(activity)
        val showHidden = PlaybackStore.isShowHiddenFolders(activity)
        val filtered = ShortsFeedPolicy.filterShorts(allVideos, locked, showHidden)
        shortsList = filtered

        emptyTextView?.setTextColor(PlaybackStore.getSecondaryTextColor(activity))
        container.setBackgroundColor(PlaybackStore.getBackgroundColor(activity))

        adapter?.notifyDataSetChanged()

        if (filtered.isEmpty()) {
            pausePlayback()
            activePosition = -1
            recyclerView?.visibility = View.GONE
            emptyTextView?.visibility = View.VISIBLE
            return
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
                playAtPosition(targetPos)
            }
        }
    }

    private fun playAtPosition(position: Int) {
        if (!isVisibleToUser || position !in shortsList.indices) return
        val rv = recyclerView ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) as? ShortsViewHolder ?: run {
            rv.post { if (isVisibleToUser) playAtPosition(position) }
            return
        }

        val video = shortsList[position]
        val player = ensurePlayer()

        activePlayerView?.player = null
        holder.playerView.player = player
        activePlayerView = holder.playerView
        activePosition = position

        holder.tvPauseOverlay.visibility = View.GONE
        holder.updateMuteButton(isMuted)

        val mediaItem = MediaItem.fromUri(video.uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    fun pausePlayback() {
        isVisibleToUser = false
        exoPlayer?.playWhenReady = false
        exoPlayer?.pause()
    }

    fun resumeIfVisible() {
        if (isVisibleToUser && shortsList.isNotEmpty()) {
            val pos = if (activePosition in shortsList.indices) activePosition else 0
            playAtPosition(pos)
        }
    }

    fun release() {
        isVisibleToUser = false
        activePlayerView?.player = null
        activePlayerView = null
        exoPlayer?.release()
        exoPlayer = null
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

    private inner class ShortsViewHolder(val root: FrameLayout) : RecyclerView.ViewHolder(root) {
        val playerView: PlayerView
        val tvTitle: TextView
        val tvMeta: TextView
        val tvPauseOverlay: TextView
        val btnShare: TextView
        val btnMute: TextView
        val btnFullPlayer: TextView

        init {
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            root.setBackgroundColor(Color.BLACK)

            playerView = PlayerView(activity).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            root.addView(playerView)

            // Bottom gradient scrim for crisp readable labels
            val scrim = View(activity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT)
                )
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(170),
                    Gravity.BOTTOM
                )
            }
            root.addView(scrim)

            // Center play/pause tap indicator
            tvPauseOverlay = TextView(activity).apply {
                text = "▶"
                textSize = 36f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(140, 0, 0, 0))
                }
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
            }
            root.addView(tvPauseOverlay)

            // Bottom-start video info overlay
            val infoCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(84), dp(18))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                )
            }
            tvTitle = TextView(activity).apply {
                setTextColor(Color.WHITE)
                textSize = 15.5f
                maxLines = 2
                setTypeface(typeface, Typeface.BOLD)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            tvMeta = TextView(activity).apply {
                setTextColor(Color.argb(215, 255, 255, 255))
                textSize = 12.5f
                maxLines = 1
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(0, dp(4), 0, 0)
            }
            infoCol.addView(tvTitle)
            infoCol.addView(tvMeta)
            root.addView(infoCol)

            // Right-side Reels action buttons (Share, Mute, Full Player)
            val actionCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(14), dp(22))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                )
            }

            fun makeCircleBtn(icon: String, desc: String): TextView {
                val bgShape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(125, 15, 23, 42))
                    setStroke(dp(1), Color.argb(80, 255, 255, 255))
                }
                return TextView(activity).apply {
                    text = icon
                    textSize = 19f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    contentDescription = desc
                    background = RippleDrawable(ColorStateList.valueOf(Color.argb(80, 255, 255, 255)), bgShape, null)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        bottomMargin = dp(12)
                    }
                }
            }

            btnShare = makeCircleBtn("📤", activity.getString(R.string.shorts_share_btn))
            btnMute = makeCircleBtn("🔊", activity.getString(R.string.shorts_mute_btn))
            btnFullPlayer = makeCircleBtn("⛶", activity.getString(R.string.shorts_open_full_btn))

            actionCol.addView(btnShare)
            actionCol.addView(btnMute)
            actionCol.addView(btnFullPlayer)
            root.addView(actionCol)
        }

        fun updateMuteButton(muted: Boolean) {
            btnMute.text = if (muted) "🔇" else "🔊"
        }
    }

    private inner class ShortsAdapter : RecyclerView.Adapter<ShortsViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortsViewHolder {
            return ShortsViewHolder(FrameLayout(parent.context))
        }

        override fun getItemCount(): Int = shortsList.size

        override fun onBindViewHolder(holder: ShortsViewHolder, position: Int) {
            val video = shortsList[position]
            val hiddenPrefix = if (video.isHidden) "👁️‍🗨️ " else ""
            holder.tvTitle.text = "$hiddenPrefix${video.title}"
            val dur = PlaybackStore.formatTime(video.duration)
            holder.tvMeta.text = "${video.folderName}  •  $dur"
            holder.updateMuteButton(isMuted)

            if (position != activePosition && holder.playerView.player != null) {
                holder.playerView.player = null
            }

            holder.playerView.setOnClickListener {
                val p = exoPlayer ?: return@setOnClickListener
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
                exoPlayer?.volume = if (isMuted) 0f else 1f
                holder.updateMuteButton(isMuted)
            }

            holder.btnFullPlayer.setOnClickListener {
                pausePlayback()
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
