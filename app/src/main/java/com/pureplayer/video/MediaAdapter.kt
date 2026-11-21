package com.pureplayer.video

import android.util.Log

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pureplayer.video.databinding.ItemMediaBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

data class IptvAddCardItem(
    val title: String,
    val subtitle: String
)

data class IptvSectionCardItem(
    val contentType: IptvContentType,
    val title: String,
    val subtitle: String,
    val count: Int
)

object VideoThumbnailLoader {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(4096)
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadSemaphore = Semaphore(4)

    fun loadVideoThumbnail(context: Context, video: VideoItem, imageView: ImageView, accentColor: Int): Job {
        val key = video.uri.toString()
        imageView.tag = key

        val cached = memoryCache.get(key)
        if (cached != null && !cached.isRecycled) {
            imageView.animate().cancel()
            imageView.alpha = 1f
            imageView.imageTintList = null
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.setPadding(0, 0, 0, 0)
            imageView.setImageBitmap(cached)
            return Job().apply { complete() }
        }

        imageView.animate().cancel()
        imageView.alpha = 0.85f
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.setPadding(24, 24, 24, 24)
        imageView.imageTintList = ColorStateList.valueOf(accentColor)
        imageView.setImageResource(R.drawable.ic_play_modern)

        return scope.launch {
            val bmp = loadSemaphore.withPermit { fetchBitmap(context, video) }
            if (bmp != null) {
                memoryCache.put(key, bmp)
                withContext(Dispatchers.Main) {
                    if (imageView.tag == key) {
                        imageView.alpha = 0f
                        imageView.imageTintList = null
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.setPadding(0, 0, 0, 0)
                        imageView.setImageBitmap(bmp)
                        imageView.animate()
                            .alpha(1f)
                            .setDuration(220L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
            }
        }
    }


    fun loadFolderCollageThumbnail(context: Context, folder: VideoFolder, imageView: ImageView, accentColor: Int): Job {
        val sampleVideos = folder.videos.take(4)
        val signature = sampleVideos.joinToString("|") { it.uri.toString() }
        val key = "folder_collage:${folder.name}:$signature"
        imageView.tag = key

        val cached = memoryCache.get(key)
        if (cached != null && !cached.isRecycled) {
            imageView.animate().cancel()
            imageView.alpha = 1f
            imageView.imageTintList = null
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.setPadding(0, 0, 0, 0)
            imageView.setImageBitmap(cached)
            return Job().apply { complete() }
        }

        // Placeholder while loading
        imageView.animate().cancel()
        imageView.alpha = 0.85f
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.setPadding(24, 24, 24, 24)
        imageView.imageTintList = ColorStateList.valueOf(accentColor)
        imageView.setImageResource(R.drawable.ic_folder_cinema)

        return scope.launch {
            val diskDir = File(context.cacheDir, "folder_thumbs").apply { mkdirs() }
            val diskFile = File(diskDir, "${key.hashCode()}.jpg")
            var collageBmp: Bitmap? = if (diskFile.exists() && diskFile.length() > 0L) {
                runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
            } else null

            if (collageBmp == null && sampleVideos.isNotEmpty()) {
                val frames = mutableListOf<Bitmap>()
                for (vid in sampleVideos) {
                    val bmp = loadSemaphore.withPermit { fetchBitmap(context, vid) }
                    if (bmp != null && !bmp.isRecycled) frames.add(bmp)
                }
                if (frames.isNotEmpty()) {
                    collageBmp = composeFolderCollage(frames)
                    runCatching {
                        FileOutputStream(diskFile).use { fos ->
                            collageBmp.compress(Bitmap.CompressFormat.JPEG, 82, fos)
                        }
                    }
                }
            }

            if (collageBmp != null) {
                memoryCache.put(key, collageBmp)
                withContext(Dispatchers.Main) {
                    if (imageView.tag == key) {
                        imageView.alpha = 0f
                        imageView.imageTintList = null
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.setPadding(0, 0, 0, 0)
                        imageView.setImageBitmap(collageBmp)
                        imageView.animate().alpha(1f).setDuration(200L).setInterpolator(DecelerateInterpolator()).start()
                    }
                }
            }
        }
    }

    private fun composeFolderCollage(frames: List<Bitmap>): Bitmap {
        val outW = 320
        val outH = 180
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.parseColor("#0A0A0F"))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val gap = 2

        fun drawCropped(src: Bitmap, dst: Rect) {
            val srcRatio = src.width.toFloat() / src.height.coerceAtLeast(1).toFloat()
            val dstRatio = dst.width().toFloat() / dst.height().coerceAtLeast(1).toFloat()
            val srcRect = if (srcRatio > dstRatio) {
                val cropW = (src.height * dstRatio).toInt()
                val left = ((src.width - cropW) / 2).coerceAtLeast(0)
                Rect(left, 0, (left + cropW).coerceAtMost(src.width), src.height)
            } else {
                val cropH = (src.width / dstRatio).toInt()
                val top = ((src.height - cropH) / 2).coerceAtLeast(0)
                Rect(0, top, src.width, (top + cropH).coerceAtMost(src.height))
            }
            canvas.drawBitmap(src, srcRect, dst, paint)
        }

        val halfW = outW / 2
        val halfH = outH / 2
        when (frames.size) {
            1 -> drawCropped(frames[0], Rect(0, 0, outW, outH))
            2 -> {
                drawCropped(frames[0], Rect(0, 0, halfW - gap / 2, outH))
                drawCropped(frames[1], Rect(halfW + gap / 2, 0, outW, outH))
            }
            3 -> {
                drawCropped(frames[0], Rect(0, 0, halfW - gap / 2, outH))
                drawCropped(frames[1], Rect(halfW + gap / 2, 0, outW, halfH - gap / 2))
                drawCropped(frames[2], Rect(halfW + gap / 2, halfH + gap / 2, outW, outH))
            }
            else -> {
                drawCropped(frames[0], Rect(0, 0, halfW - gap / 2, halfH - gap / 2))
                drawCropped(frames[1], Rect(halfW + gap / 2, 0, outW, halfH - gap / 2))
                drawCropped(frames[2], Rect(0, halfH + gap / 2, halfW - gap / 2, outH))
                drawCropped(frames[3], Rect(halfW + gap / 2, halfH + gap / 2, outW, outH))
            }
        }
        return out
    }

    private fun fetchBitmap(context: Context, video: VideoItem): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(video.uri, Size(320, 180), null)
            } catch (e: Exception) { Log.w("MediaAdapter", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }

        try {
            @Suppress("DEPRECATION")
            val mini = MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                video.id,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
            if (mini != null) return mini
        } catch (e: Exception) { Log.w("MediaAdapter", "Handled non-fatal exception: ${e.javaClass.simpleName}") }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, video.uri)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) { Log.w("MediaAdapter", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
    }
}

object MediaDiffCallback : DiffUtil.ItemCallback<Any>() {
    override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
        return when {
            oldItem is VideoFolder && newItem is VideoFolder -> oldItem.name == newItem.name
            oldItem is VideoItem && newItem is VideoItem -> oldItem.uri == newItem.uri
            oldItem is IptvSource && newItem is IptvSource -> oldItem.id == newItem.id
            oldItem is IptvSectionCardItem && newItem is IptvSectionCardItem -> oldItem.contentType == newItem.contentType
            oldItem is IptvCategory && newItem is IptvCategory ->
                oldItem.contentType == newItem.contentType && oldItem.id == newItem.id && oldItem.name == newItem.name
            oldItem is IptvStreamItem && newItem is IptvStreamItem ->
                oldItem.sourceId == newItem.sourceId && oldItem.id == newItem.id && oldItem.contentType == newItem.contentType
            oldItem is IptvSeasonGroup && newItem is IptvSeasonGroup -> oldItem.seasonNumber == newItem.seasonNumber
            oldItem is IptvEpisodeItem && newItem is IptvEpisodeItem -> oldItem.id == newItem.id && oldItem.seasonNumber == newItem.seasonNumber
            oldItem is IptvAddCardItem && newItem is IptvAddCardItem -> oldItem.title == newItem.title
            else -> oldItem == newItem
        }
    }

    @SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean = oldItem == newItem
}

class MediaAdapter(
    private val onFolderClick: (VideoFolder) -> Unit,
    private val onVideoClick: (VideoItem) -> Unit,
    private val onVideoLongClick: (VideoItem) -> Unit = {},
    private val onFolderLongClick: (VideoFolder) -> Unit = {},
    private val onIptvItemClick: (Any) -> Unit = {},
    private val onIptvItemLongClick: (Any) -> Unit = {},
    private val onIptvFavoriteToggle: (IptvStreamItem, Boolean) -> Unit = { _, _ -> },
    private val onIptvDownloadClick: (Any) -> Unit = {}
) : ListAdapter<Any, MediaAdapter.MediaViewHolder>(MediaDiffCallback) {

    init {
        setHasStableIds(true)
    }

    private var lastAnimatedPosition = -1
    var isGridMode: Boolean = false
    var isVaultMode: Boolean = false
    private val decelerateInterpolator = DecelerateInterpolator(1.6f)
    private val overshootInterpolator = OvershootInterpolator(1.4f)

    override fun getItemId(position: Int): Long {
        val item = getItem(position)
        val rawKey = when (item) {
            is VideoFolder -> "folder:${item.name}"
            is VideoItem -> "video:${item.uri}"
            is IptvSource -> "src:${item.id}"
            is IptvSectionCardItem -> "sec:${item.contentType.name}"
            is IptvCategory -> "cat:${item.contentType.name}:${item.id}:${item.name}"
            is IptvStreamItem -> "str:${item.sourceId}:${item.contentType.name}:${item.id}"
            is IptvSeasonGroup -> "sea:${item.seasonNumber}"
            is IptvEpisodeItem -> "ep:${item.seasonNumber}:${item.id}"
            is IptvAddCardItem -> "add_card:${item.title}"
            else -> item.toString()
        }
        return rawKey.hashCode().toLong()
    }

    fun submitFolders(folders: List<VideoFolder>) {
        lastAnimatedPosition = -1
        submitList(folders.toList())
    }

    fun submitVideos(videos: List<VideoItem>) {
        lastAnimatedPosition = -1
        submitList(videos.toList())
    }

    fun submitIptvList(iptvElements: List<Any>, resetAnimation: Boolean = true) {
        if (resetAnimation) {
            lastAnimatedPosition = -1
        }
        submitList(iptvElements.toList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onViewRecycled(holder: MediaViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelThumbnailLoad()
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
    }

    inner class MediaViewHolder(private val binding: ItemMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var thumbJob: Job? = null

        init {
            binding.root.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.965f).scaleY(0.965f).setDuration(110L)
                            .setInterpolator(decelerateInterpolator).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(180L)
                            .setInterpolator(overshootInterpolator).start()
                    }
                }
                false
            }
        }

        private fun dp(ctx: Context, value: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                ctx.resources.displayMetrics
            ).toInt()
        }

        fun cancelThumbnailLoad() {
            thumbJob?.cancel()
            thumbJob = null
        }

        fun bind(item: Any, position: Int) {
            cancelThumbnailLoad()
            val ctx = binding.root.context
            val accentColor = PlaybackStore.getAccentColor(ctx)
            val surfaceColor = PlaybackStore.getSurfaceColor(ctx)
            val isLight = PlaybackStore.isLightThemeActive(ctx)
            val primaryText = PlaybackStore.getPrimaryTextColor(ctx)
            val secondaryText = PlaybackStore.getSecondaryTextColor(ctx)
            binding.root.setCardBackgroundColor(surfaceColor)
            binding.itemContentLayout.alpha = 1.0f
            binding.tvTitle.setTextColor(primaryText)
            binding.tvSubtitle.setTextColor(secondaryText)
            binding.btnFavoriteHeart.visibility = View.GONE
            binding.btnDownloadIptv.visibility = View.GONE

            // Configure List vs Grid layout dynamically & responsively across phones, tablets, and large screens
            val cfg = ctx.resources.configuration
            val isPosterItem = item is IptvStreamItem &&
                (item.contentType == IptvContentType.MOVIES || item.contentType == IptvContentType.SERIES)
            val (thumbW, thumbH) = ResponsiveUiMetrics.getThumbnailDimensionsDp(
                screenWidthDp = cfg.screenWidthDp,
                smallestScreenWidthDp = cfg.smallestScreenWidthDp,
                isGridMode = isGridMode,
                isPosterItem = isPosterItem
            )
            if (isGridMode) {
                binding.itemContentLayout.orientation = LinearLayout.VERTICAL
                binding.thumbContainer.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(ctx, thumbH)
                )
                binding.textContainer.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(ctx, 10)
                    marginStart = 0
                }
            } else {
                binding.itemContentLayout.orientation = LinearLayout.HORIZONTAL
                binding.thumbContainer.layoutParams = LinearLayout.LayoutParams(
                    dp(ctx, thumbW),
                    dp(ctx, thumbH)
                )
                binding.textContainer.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = dp(ctx, 14)
                    topMargin = 0
                }
            }

            // Smooth rounded thumbnail container & card border
            binding.root.strokeColor = Color.argb(if (isLight) 75 else 42, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            binding.thumbContainer.background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 13).toFloat()
                setColor(if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#0B111E"))
            }
            binding.thumbContainer.clipToOutline = true

            // Style NEW badge pill & Duration badge pill
            binding.tvNewBadge.background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 6).toFloat()
                setColor(Color.parseColor("#E610B981"))
            }
            binding.tvDurationBadge.background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 6).toFloat()
                setColor(Color.parseColor("#CC050811"))
            }

            when (item) {
                is VideoFolder -> {
                    binding.tvDurationBadge.visibility = View.GONE
                    val isLastActiveFolder = (PlaybackStore.getLastActiveFolderName(ctx) == item.name)
                    val hasNewInFolder = item.videos.any { PlaybackStore.isVideoNewBadgeVisible(ctx, it) }
                    if (isLastActiveFolder) {
                        binding.root.strokeColor = accentColor
                        binding.tvNewBadge.background = GradientDrawable().apply {
                            cornerRadius = dp(ctx, 6).toFloat()
                            setColor(accentColor)
                        }
                        binding.tvNewBadge.text = ctx.getString(R.string.active_folder_badge)
                        binding.tvNewBadge.visibility = View.VISIBLE
                    } else if (hasNewInFolder) {
                        binding.tvNewBadge.text = "NEW"
                        binding.tvNewBadge.visibility = View.VISIBLE
                    } else {
                        binding.tvNewBadge.visibility = View.GONE
                    }

                    binding.ivIcon.tag = null
                    binding.ivIcon.alpha = 1f
                    binding.ivIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    binding.ivIcon.setPadding(20, 20, 20, 20)
                    binding.ivIcon.imageTintList = ColorStateList.valueOf(accentColor)
                    binding.ivIcon.setImageResource(R.drawable.ic_folder_cinema)

                    binding.tvTitle.text = if (isVaultMode) "🛡️ ${item.name}" else item.name
                    binding.tvSubtitle.text = ctx.getString(R.string.videos_count_format, item.videos.size)
                    binding.root.setOnClickListener { onFolderClick(item) }
                    binding.root.setOnLongClickListener {
                        onFolderLongClick(item)
                        true
                    }
                }
                is VideoItem -> {
                    binding.tvTitle.text = item.title
                    val durationStr = formatDuration(item.duration)
                    binding.tvDurationBadge.text = durationStr
                    binding.tvDurationBadge.visibility = View.VISIBLE

                    val isLastPlayedInFolder = (PlaybackStore.getLastPlayedVideoInFolder(ctx, item.folderName) == item.uri.toString())
                    val isNew = PlaybackStore.isVideoNewBadgeVisible(ctx, item)

                    if (isLastPlayedInFolder) {
                        // Dimmed / muted appearance for the last played video in this specific folder!
                        binding.itemContentLayout.alpha = 0.52f
                        binding.tvTitle.setTextColor(secondaryText)
                        binding.root.setCardBackgroundColor(if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#0D1320"))
                        binding.root.strokeColor = Color.argb(115, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                        binding.tvNewBadge.background = GradientDrawable().apply {
                            cornerRadius = dp(ctx, 6).toFloat()
                            setColor(Color.parseColor("#D9334155"))
                        }
                        binding.tvNewBadge.text = ctx.getString(R.string.last_in_folder_badge)
                        binding.tvNewBadge.visibility = View.VISIBLE
                    } else if (isNew) {
                        binding.tvNewBadge.text = "NEW"
                        binding.tvNewBadge.visibility = View.VISIBLE
                    } else {
                        binding.tvNewBadge.visibility = View.GONE
                    }

                    val sizeMb = String.format(Locale.US, "%.1f MB", item.size / (1024.0 * 1024.0))
                    val resShort = if (item.width > 0 && item.height > 0) "  •  ${item.getResolutionLabel().substringBefore(" ")}" else ""
                    val savedPos = PlaybackStore.getSavedPosition(ctx, item.uri)
                    val resumeBadge = if (savedPos > 3_000L) {
                        "  •  ⏸ " + ctx.getString(R.string.stopped_at_format, PlaybackStore.formatTime(savedPos))
                    } else {
                        ""
                    }
                    binding.tvSubtitle.text = "$sizeMb$resShort$resumeBadge"

                    thumbJob = VideoThumbnailLoader.loadVideoThumbnail(ctx, item, binding.ivIcon, accentColor)
                    binding.root.setOnClickListener { onVideoClick(item) }
                    binding.root.setOnLongClickListener {
                        onVideoLongClick(item)
                        true
                    }
                }
                is IptvAddCardItem -> {
                    binding.root.strokeColor = accentColor
                    binding.tvDurationBadge.visibility = View.GONE
                    binding.tvNewBadge.text = "＋"
                    binding.tvNewBadge.visibility = View.VISIBLE
                    binding.ivIcon.tag = null
                    binding.ivIcon.alpha = 1f
                    binding.ivIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    binding.ivIcon.setPadding(18, 18, 18, 18)
                    binding.ivIcon.imageTintList = ColorStateList.valueOf(accentColor)
                    binding.ivIcon.setImageResource(R.drawable.ic_plus_circle)

                    binding.tvTitle.text = item.title
                    binding.tvSubtitle.text = item.subtitle
                    binding.root.setOnClickListener { onIptvItemClick(item) }
                    binding.root.setOnLongClickListener {
                        onIptvItemClick(item)
                        true
                    }
                }
                is IptvSource -> {
                    binding.tvNewBadge.text = if (item.type == IptvSourceType.XTREAM) "XTREAM" else "M3U"
                    binding.tvNewBadge.visibility = View.VISIBLE
                    binding.tvDurationBadge.text = "${item.totalItemsCount()}"
                    binding.tvDurationBadge.visibility = View.VISIBLE

                    binding.ivIcon.tag = null
                    binding.ivIcon.alpha = 1f
                    binding.ivIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    binding.ivIcon.setPadding(20, 20, 20, 20)
                    binding.ivIcon.imageTintList = ColorStateList.valueOf(accentColor)
                    binding.ivIcon.setImageResource(R.drawable.ic_iptv_tv)

                    binding.tvTitle.text = item.name
                    val summaryParts = mutableListOf<String>()
                    if (item.liveCount > 0) summaryParts.add("📺 ${item.liveCount}")
                    if (item.movieCount > 0) summaryParts.add("🎬 ${item.movieCount}")
                    if (item.seriesCount > 0) summaryParts.add("🎞️ ${item.seriesCount}")
                    if (item.radioCount > 0) summaryParts.add("📻 ${item.radioCount}")
                    binding.tvSubtitle.text = summaryParts.joinToString("  •  ").ifEmpty { item.accountStatus }

                    binding.root.setOnClickListener { onIptvItemClick(item) }
                    binding.root.setOnLongClickListener {
                        onIptvItemLongClick(item)
                        true
                    }
                }
                is IptvSectionCardItem -> {
                    binding.tvNewBadge.visibility = View.GONE
                    binding.tvDurationBadge.text = "${item.count}"
                    binding.tvDurationBadge.visibility = View.VISIBLE

                    binding.ivIcon.tag = null
                    binding.ivIcon.alpha = 1f
                    binding.ivIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    binding.ivIcon.setPadding(20, 20, 20, 20)
                    binding.ivIcon.imageTintList = ColorStateList.valueOf(accentColor)
                    val iconRes = when (item.contentType) {
                        IptvContentType.LIVE_TV -> R.drawable.ic_iptv_tv
                        IptvContentType.MOVIES -> R.drawable.ic_play_modern
                        IptvContentType.SERIES -> R.drawable.ic_folder_cinema
                        IptvContentType.RADIO -> R.drawable.ic_audio_track_modern
                    }
                    binding.ivIcon.setImageResource(iconRes)

                    binding.tvTitle.text = item.title
                    binding.tvSubtitle.text = item.subtitle
                    binding.root.setOnClickListener { onIptvItemClick(item) }
                    binding.root.setOnLongClickListener {
                        onIptvItemLongClick(item)
                        true
                    }
                }
                is IptvCategory -> {
                    binding.tvNewBadge.visibility = View.GONE
                    binding.tvDurationBadge.text = "${item.itemCount}"
                    binding.tvDurationBadge.visibility = View.VISIBLE

                    binding.ivIcon.tag = null
                    binding.ivIcon.alpha = 1f
                    binding.ivIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    binding.ivIcon.setPadding(20, 20, 20, 20)
                    binding.ivIcon.imageTintList = ColorStateList.valueOf(accentColor)
                    binding.ivIcon.setImageResource(R.drawable.ic_folder_cinema)

                    binding.tvTitle.text = item.name
                    binding.tvSubtitle.text = ctx.getString(R.string.iptv_category_count_format, item.itemCount)
                    binding.root.setOnClickListener { onIptvItemClick(item) }
                    binding.root.setOnLongClickListener {
                        onIptvItemLongClick(item)
                        true
                    }
                }
                is IptvStreamItem -> {
                    binding.tvTitle.text = item.title
                    val typeLabel = when (item.contentType) {
                        IptvContentType.LIVE_TV -> "LIVE"
                        IptvContentType.MOVIES -> "VOD"
                        IptvContentType.SERIES -> "SERIES"
                        IptvContentType.RADIO -> "RADIO"
                    }
                    binding.tvNewBadge.text = typeLabel
                    binding.tvNewBadge.visibility = View.VISIBLE

                    // Interactive Heart Button ❤️ on Series, Movies, Live TV
                    val isFav = IptvRepository.isFavoriteStream(ctx, item.sourceId, item.id)
                    binding.btnFavoriteHeart.visibility = View.VISIBLE
                    binding.btnFavoriteHeart.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#B3090D16"))
                    }
                    binding.btnFavoriteHeart.setImageResource(
                        if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                    )
                    binding.btnFavoriteHeart.setOnClickListener {
                        val nowFav = IptvRepository.toggleFavoriteStream(ctx, item.sourceId, item.id)
                        binding.btnFavoriteHeart.setImageResource(
                            if (nowFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                        )
                        binding.btnFavoriteHeart.animate().scaleX(1.25f).scaleY(1.25f).setDuration(100L).withEndAction {
                            binding.btnFavoriteHeart.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                        }.start()
                        onIptvFavoriteToggle(item, nowFav)
                    }

                    if (item.rating.isNotBlank() && item.rating != "0") {
                        binding.tvDurationBadge.text = "⭐ ${item.rating}"
                        binding.tvDurationBadge.visibility = View.VISIBLE
                    } else {
                        binding.tvDurationBadge.visibility = View.GONE
                    }

                    // Show last watched episode/position indicator on Series & Movies!
                    val seriesKey = item.seriesId.ifBlank { item.seriesName.ifBlank { item.title } }
                    val lastEpBadge = if (item.contentType == IptvContentType.SERIES) {
                        IptvRepository.getLastWatchedSeriesEpisodeLabel(ctx, item.sourceId, seriesKey)
                    } else null
                    val savedMoviePos = if (item.streamUrl.isNotBlank()) {
                        PlaybackStore.getSavedPosition(ctx, Uri.parse(item.streamUrl))
                    } else 0L

                    val progressSuffix = when {
                        !lastEpBadge.isNullOrBlank() -> "  •  ▶ $lastEpBadge"
                        savedMoviePos > 3000L -> "  •  ⏸ ${PlaybackStore.formatTime(savedMoviePos)}"
                        else -> ""
                    }
                    binding.tvSubtitle.text = "${item.categoryName}$progressSuffix"

                    val fallbackIcon = when (item.contentType) {
                        IptvContentType.LIVE_TV -> R.drawable.ic_iptv_tv
                        IptvContentType.RADIO -> R.drawable.ic_audio_track_modern
                        IptvContentType.SERIES -> R.drawable.ic_folder_cinema
                        IptvContentType.MOVIES -> R.drawable.ic_play_modern
                    }
                    thumbJob = IptvImageLoader.loadLogoOrPoster(ctx, item.logoUrl, binding.ivIcon, fallbackIcon, accentColor)

                    if (item.contentType == IptvContentType.MOVIES && item.streamUrl.isNotBlank()) {
                        binding.btnDownloadIptv.visibility = View.VISIBLE
                        binding.btnDownloadIptv.imageTintList = ColorStateList.valueOf(accentColor)
                        binding.btnDownloadIptv.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.argb(36, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                            setStroke(dp(ctx, 1), Color.argb(110, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                        }
                        binding.btnDownloadIptv.setOnClickListener {
                            onIptvDownloadClick(item)
                        }
                    }

                    binding.root.setOnClickListener { onIptvItemClick(item) }
                    binding.root.setOnLongClickListener {
                        onIptvItemLongClick(item)
                        true
                    }
                }
                is IptvSeasonGroup -> {
                    binding.tvNewBadge.visibility = View.GONE
                    binding.tvDurationBadge.text = "${item.episodes.size}"
                    binding.tvDurationBadge.visibility = View.VISIBLE

                    binding.ivIcon.tag = null
                    binding.ivIcon.alpha = 1f
                    binding.ivIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    binding.ivIcon.setPadding(20, 20, 20, 20)
                    binding.ivIcon.imageTintList = ColorStateList.valueOf(accentColor)
                    binding.ivIcon.setImageResource(R.drawable.ic_folder_cinema)

                    binding.tvTitle.text = item.name
                    binding.tvSubtitle.text = ctx.getString(R.string.iptv_episodes_count_format, item.episodes.size)
                    binding.root.setOnClickListener { onIptvItemClick(item) }
                    binding.root.setOnLongClickListener(null)
                }
                is IptvEpisodeItem -> {
                    val savedEpPos = if (item.streamUrl.isNotBlank()) {
                        PlaybackStore.getSavedPosition(ctx, Uri.parse(item.streamUrl))
                    } else 0L
                    val hasBeenOpened = item.streamUrl.isNotBlank() && PlaybackStore.hasVideoBeenOpened(ctx, Uri.parse(item.streamUrl))
                    if (hasBeenOpened) {
                        binding.itemContentLayout.alpha = 0.55f
                        binding.tvTitle.setTextColor(Color.parseColor("#94A3B8"))
                        binding.tvNewBadge.background = GradientDrawable().apply {
                            cornerRadius = dp(ctx, 6).toFloat()
                            setColor(Color.parseColor("#D9334155"))
                        }
                        binding.tvNewBadge.text = "✓ S${item.seasonNumber}E${item.episodeNumber}"
                    } else {
                        binding.tvNewBadge.text = "S${item.seasonNumber}E${item.episodeNumber}"
                    }
                    binding.tvNewBadge.visibility = View.VISIBLE

                    if (item.durationLabel.isNotBlank()) {
                        binding.tvDurationBadge.text = item.durationLabel
                        binding.tvDurationBadge.visibility = View.VISIBLE
                    } else {
                        binding.tvDurationBadge.visibility = View.GONE
                    }

                    thumbJob = IptvImageLoader.loadLogoOrPoster(ctx, item.logoUrl, binding.ivIcon, R.drawable.ic_play_modern, accentColor)
                    binding.tvTitle.text = item.title
                    val resumeHint = if (savedEpPos > 3000L) "  •  ⏸ ${PlaybackStore.formatTime(savedEpPos)}" else ""
                    val baseSub = if (item.plot.isNotBlank()) item.plot else "S${item.seasonNumber} • E${item.episodeNumber}"
                    binding.tvSubtitle.text = "$baseSub$resumeHint"
                    if (item.streamUrl.isNotBlank()) {
                        binding.btnDownloadIptv.visibility = View.VISIBLE
                        binding.btnDownloadIptv.imageTintList = ColorStateList.valueOf(accentColor)
                        binding.btnDownloadIptv.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.argb(36, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                            setStroke(dp(ctx, 1), Color.argb(110, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                        }
                        binding.btnDownloadIptv.setOnClickListener {
                            onIptvDownloadClick(item)
                        }
                    }
                    binding.root.setOnClickListener { onIptvItemClick(item) }
                    binding.root.setOnLongClickListener {
                        onIptvItemLongClick(item)
                        true
                    }
                }
            }

            if (position > lastAnimatedPosition) {
                lastAnimatedPosition = position
                binding.root.alpha = 0f
                binding.root.translationY = 36f
                val staggerDelay = (position.coerceAtMost(8) * 28L)
                binding.root.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(staggerDelay)
                    .setDuration(240L)
                    .setInterpolator(decelerateInterpolator)
                    .start()
            } else {
                binding.root.alpha = 1f
                binding.root.translationY = 0f
            }
        }

        private fun formatDuration(durationMs: Long): String {
            val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }
}
