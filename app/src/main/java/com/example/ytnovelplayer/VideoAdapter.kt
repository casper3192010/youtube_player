package com.example.ytnovelplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.ytnovelplayer.databinding.ItemVideoRecommendationBinding

class VideoAdapter(
    private val videos: List<VideoItem>,
    private val onVideoClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    inner class VideoViewHolder(private val binding: ItemVideoRecommendationBinding) : 
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(video: VideoItem) {
            binding.videoTitle.text = video.title
            binding.videoMeta.text = "${video.channelName} • ${video.views} • ${video.timestamp}"
            binding.videoDuration.text = video.duration
            
            // For now, thumbnail is just a colored view placeholder
            // In a real app, use resizing/loading library like Glide/Coil
            
            binding.root.setOnClickListener {
                onVideoClick(video)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoRecommendationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(videos[position])
    }

    override fun getItemCount() = videos.size
}
