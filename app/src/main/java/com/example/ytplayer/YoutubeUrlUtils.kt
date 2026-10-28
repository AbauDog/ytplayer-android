package com.example.ytplayer

import android.net.Uri

object YoutubeUrlUtils {

    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "music.youtube.com", "youtu.be"
    )

    fun isYoutubeUrl(url: String): Boolean = try {
        Uri.parse(url.trim()).host?.lowercase() in HOSTS
    } catch (e: Exception) {
        false
    }

    /** 允許使用者直接貼影片 ID，自動補成完整網址 */
    fun normalize(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://www.youtube.com/watch?v=$trimmed"
        }
    }
}
