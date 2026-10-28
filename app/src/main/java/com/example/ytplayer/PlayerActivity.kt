package com.example.ytplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        private const val TAG = "PlayerActivity"
        private const val PROGRESS_SAVE_INTERVAL_MS = 5000L
        private const val RESUME_MIN_POSITION_MS = 3000L
        private const val RESUME_END_GUARD_MS = 5000L
    }

    private var player: ExoPlayer? = null
    private var loadJob: Job? = null
    private var progressSaveJob: Job? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var playerView: PlayerView
    private lateinit var videoTitleView: TextView
    private lateinit var favoriteButton: ImageButton
    private lateinit var repository: PlayerRepository

    // 目前播放中影片的中繼資料，用來存續看進度／收藏
    private var videoUrl: String = ""
    private var videoTitle: String = ""
    private var videoUploader: String? = null
    private var videoThumbnailUrl: String? = null
    private var videoDurationSec: Long = 0
    private var isFavorite: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        repository = PlayerRepository(this)

        progressBar = findViewById(R.id.progressBar)
        playerView = findViewById(R.id.playerView)
        videoTitleView = findViewById(R.id.videoTitle)
        favoriteButton = findViewById(R.id.favoriteButton)
        favoriteButton.isEnabled = false
        favoriteButton.setOnClickListener { toggleFavorite() }

        playFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        playFromIntent(intent)
    }

    private fun playFromIntent(intent: Intent) {
        val url = resolveIncomingUrl(intent)
        if (url == null) {
            Toast.makeText(this, R.string.toast_unresolved_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        loadAndPlay(url)
    }

    private fun loadAndPlay(url: String) {
        saveProgressNow()
        progressSaveJob?.cancel()
        loadJob?.cancel()
        player?.release()
        player = null

        videoUrl = url
        videoTitle = ""
        videoUploader = null
        videoThumbnailUrl = null
        videoDurationSec = 0
        videoTitleView.text = ""
        favoriteButton.isEnabled = false

        progressBar.visibility = View.VISIBLE

        loadJob = lifecycleScope.launch {
            try {
                val streamInfo = withContext(Dispatchers.IO) {
                    StreamInfo.getInfo(ServiceList.YouTube, url)
                }
                val streamUrl = pickPlayableStreamUrl(streamInfo)
                if (streamUrl == null) {
                    Toast.makeText(this@PlayerActivity, R.string.toast_no_stream, Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                videoTitle = streamInfo.name ?: ""
                videoUploader = streamInfo.uploaderName
                videoThumbnailUrl = streamInfo.thumbnails?.maxByOrNull { it.height }?.url
                videoDurationSec = if (streamInfo.duration > 0) streamInfo.duration else 0
                videoTitleView.text = videoTitle

                val resumePositionMs = withContext(Dispatchers.IO) {
                    repository.getWatchProgress(url)?.positionMs
                }

                startPlayback(streamUrl, resumePositionMs)
                progressBar.visibility = View.GONE

                refreshFavoriteState()
                startProgressSaveLoop()
            } catch (e: Exception) {
                Log.e(TAG, "extract failed for $url", e)
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.toast_extract_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun resolveIncomingUrl(intent: Intent): String? {
        intent.getStringExtra(EXTRA_URL)?.let { return it }
        intent.data?.let { uri: Uri -> return uri.toString() }
        return null
    }

    /** 先求簡單：優先挑「視訊+音訊合一」的串流，不處理 DASH 分離軌的合併 */
    private fun pickPlayableStreamUrl(info: StreamInfo): String? {
        val muxed = info.videoStreams
            ?.filter { !it.isVideoOnly && !it.content.isNullOrEmpty() }
            ?.maxByOrNull { it.resolution?.removeSuffix("p")?.toIntOrNull() ?: 0 }
        if (muxed != null) return muxed.content

        return info.audioStreams?.firstOrNull { !it.content.isNullOrEmpty() }?.content
    }

    private fun startPlayback(streamUrl: String, resumePositionMs: Long?) {
        val exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
        exoPlayer.prepare()

        val durationMs = videoDurationSec * 1000
        if (resumePositionMs != null &&
            resumePositionMs > RESUME_MIN_POSITION_MS &&
            (durationMs <= 0 || resumePositionMs < durationMs - RESUME_END_GUARD_MS)
        ) {
            exoPlayer.seekTo(resumePositionMs)
        }

        exoPlayer.playWhenReady = true
        player = exoPlayer
    }

    private fun startProgressSaveLoop() {
        progressSaveJob = lifecycleScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                saveProgressNow()
            }
        }
    }

    private fun saveProgressNow() {
        val currentPlayer = player ?: return
        if (videoUrl.isEmpty()) return
        val position = currentPlayer.currentPosition
        val url = videoUrl
        val title = videoTitle
        val uploader = videoUploader
        val thumb = videoThumbnailUrl
        val durationSec = videoDurationSec
        lifecycleScope.launch(Dispatchers.IO) {
            repository.saveWatchProgress(url, title, thumb, uploader, durationSec, position)
        }
    }

    private fun refreshFavoriteState() {
        val url = videoUrl
        lifecycleScope.launch {
            isFavorite = withContext(Dispatchers.IO) { repository.isFavorite(url) }
            updateFavoriteIcon()
            favoriteButton.isEnabled = true
        }
    }

    private fun toggleFavorite() {
        val url = videoUrl
        if (url.isEmpty()) return
        val wasFavorite = isFavorite
        isFavorite = !wasFavorite
        updateFavoriteIcon()
        lifecycleScope.launch(Dispatchers.IO) {
            if (wasFavorite) {
                repository.removeFavorite(url)
            } else {
                repository.addFavorite(url, videoTitle, videoThumbnailUrl, videoUploader, videoDurationSec)
            }
        }
        Toast.makeText(
            this,
            getString(if (wasFavorite) R.string.toast_favorite_removed else R.string.toast_favorite_added),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateFavoriteIcon() {
        favoriteButton.setImageResource(
            if (isFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )
    }

    override fun onStop() {
        super.onStop()
        saveProgressNow()
        progressSaveJob?.cancel()
        loadJob?.cancel()
        player?.release()
        player = null
    }
}
