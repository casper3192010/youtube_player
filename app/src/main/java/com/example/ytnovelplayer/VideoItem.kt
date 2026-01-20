package com.example.ytnovelplayer

data class VideoItem(
    val title: String,
    val channelName: String,
    val views: String,
    val timestamp: String,
    val duration: String,
    val thumbnailUrl: String? = null // In a real app this would be a URL
)
