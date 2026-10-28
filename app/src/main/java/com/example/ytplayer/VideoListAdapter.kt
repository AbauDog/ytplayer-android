package com.example.ytplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class VideoListAdapter(
    private val onClick: (VideoCardData) -> Unit
) : RecyclerView.Adapter<VideoListAdapter.VideoViewHolder>() {

    private val items = mutableListOf<VideoCardData>()

    fun submitList(newItems: List<VideoCardData>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class VideoViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail = itemView.findViewById<ImageView>(R.id.thumbnail)
        private val duration = itemView.findViewById<TextView>(R.id.duration)
        private val title = itemView.findViewById<TextView>(R.id.title)
        private val uploader = itemView.findViewById<TextView>(R.id.uploader)
        private val watchProgress = itemView.findViewById<ProgressBar>(R.id.watchProgress)

        fun bind(item: VideoCardData, onClick: (VideoCardData) -> Unit) {
            title.text = item.title
            uploader.text = item.uploader.orEmpty()
            duration.text = if (item.durationSec > 0) formatDuration(item.durationSec) else ""

            Glide.with(thumbnail)
                .load(item.thumbnailUrl)
                .centerCrop()
                .into(thumbnail)

            if (item.watchedFraction != null) {
                watchProgress.visibility = android.view.View.VISIBLE
                watchProgress.progress = (item.watchedFraction * 1000).toInt().coerceIn(0, 1000)
            } else {
                watchProgress.visibility = android.view.View.GONE
            }

            itemView.setOnClickListener { onClick(item) }
        }

        private fun formatDuration(totalSeconds: Long): String {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return if (h > 0) {
                String.format("%d:%02d:%02d", h, m, s)
            } else {
                String.format("%d:%02d", m, s)
            }
        }
    }
}
