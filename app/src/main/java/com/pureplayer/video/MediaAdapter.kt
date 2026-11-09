package com.pureplayer.video

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.pureplayer.video.databinding.ItemMediaBinding
import java.util.Locale
import java.util.concurrent.TimeUnit

class MediaAdapter(
    private val onFolderClick: (VideoFolder) -> Unit,
    private val onVideoClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<MediaAdapter.MediaViewHolder>() {

    private val items = mutableListOf<Any>()

    fun submitFolders(folders: List<VideoFolder>) {
        items.clear()
        items.addAll(folders)
        notifyDataSetChanged()
    }

    fun submitVideos(videos: List<VideoItem>) {
        items.clear()
        items.addAll(videos)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class MediaViewHolder(private val binding: ItemMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Any) {
            val ctx = binding.root.context
            val accentColor = PlaybackStore.getAccentColor(ctx)
            val surfaceColor = PlaybackStore.getSurfaceColor(ctx)
            binding.root.setCardBackgroundColor(surfaceColor)
            binding.ivIcon.imageTintList = ColorStateList.valueOf(accentColor)

            when (item) {
                is VideoFolder -> {
                    binding.ivIcon.setImageResource(android.R.drawable.ic_menu_agenda)
                    binding.tvTitle.text = item.name
                    binding.tvSubtitle.text = ctx.getString(R.string.videos_count_format, item.videos.size)
                    binding.root.setOnClickListener { onFolderClick(item) }
                }
                is VideoItem -> {
                    binding.ivIcon.setImageResource(android.R.drawable.ic_media_play)
                    binding.tvTitle.text = item.title
                    val durationStr = formatDuration(item.duration)
                    val sizeMb = String.format(Locale.US, "%.1f MB", item.size / (1024.0 * 1024.0))
                    val savedPos = PlaybackStore.getSavedPosition(ctx, item.uri)
                    val resumeBadge = if (savedPos > 3_000L) {
                        "  •  ⏸ " + ctx.getString(R.string.stopped_at_format, PlaybackStore.formatTime(savedPos))
                    } else {
                        ""
                    }
                    binding.tvSubtitle.text = "$durationStr  •  $sizeMb$resumeBadge"
                    binding.root.setOnClickListener { onVideoClick(item) }
                }
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
