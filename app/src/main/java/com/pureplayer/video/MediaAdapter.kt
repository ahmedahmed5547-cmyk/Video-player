package com.pureplayer.video

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
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
import androidx.recyclerview.widget.RecyclerView
import com.pureplayer.video.databinding.ItemMediaBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

object VideoThumbnailLoader {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(4096)
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            val bmp = fetchBitmap(context, video)
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

    private fun fetchBitmap(context: Context, video: VideoItem): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(video.uri, Size(320, 180), null)
            } catch (_: Exception) {}
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
        } catch (_: Exception) {}

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
            } catch (_: Exception) {}
        }
    }
}

class MediaAdapter(
    private val onFolderClick: (VideoFolder) -> Unit,
    private val onVideoClick: (VideoItem) -> Unit,
    private val onVideoLongClick: (VideoItem) -> Unit = {},
    private val onFolderLongClick: (VideoFolder) -> Unit = {}
) : RecyclerView.Adapter<MediaAdapter.MediaViewHolder>() {

    private val items = mutableListOf<Any>()
    private var lastAnimatedPosition = -1
    var isGridMode: Boolean = false
    var isVaultMode: Boolean = false
    private val decelerateInterpolator = DecelerateInterpolator(1.6f)
    private val overshootInterpolator = OvershootInterpolator(1.4f)

    fun submitFolders(folders: List<VideoFolder>) {
        items.clear()
        items.addAll(folders)
        lastAnimatedPosition = -1
        notifyDataSetChanged()
    }

    fun submitVideos(videos: List<VideoItem>) {
        items.clear()
        items.addAll(videos)
        lastAnimatedPosition = -1
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(items[position], position)
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

    override fun getItemCount(): Int = items.size

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
            binding.root.setCardBackgroundColor(surfaceColor)

            // Configure List vs Grid layout dynamically
            if (isGridMode) {
                binding.itemContentLayout.orientation = LinearLayout.VERTICAL
                binding.thumbContainer.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(ctx, 108)
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
                    dp(ctx, 116),
                    dp(ctx, 68)
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
            binding.root.strokeColor = Color.argb(42, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            binding.thumbContainer.background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 13).toFloat()
                setColor(Color.parseColor("#0B111E"))
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
                    val hasNewInFolder = item.videos.any { PlaybackStore.isVideoNewBadgeVisible(ctx, it) }
                    binding.tvNewBadge.visibility = if (hasNewInFolder) View.VISIBLE else View.GONE

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

                    val isNew = PlaybackStore.isVideoNewBadgeVisible(ctx, item)
                    binding.tvNewBadge.visibility = if (isNew) View.VISIBLE else View.GONE

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
