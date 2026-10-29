package com.example.ytplayer

import android.content.ComponentName
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

    // 實際的 ExoPlayer 在 PlaybackService 裡，這裡透過 MediaController 操作
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    // 目前影片已載入後才指向 controller，載入中為 null，避免把舊進度存到新網址
    private var player: Player? = null
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

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        playFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 從通知列點回來的 Intent 不帶網址，維持目前播放即可
        if (resolveIncomingUrl(intent) == null) return
        setIntent(intent)
        playFromIntent(intent)
    }

    private suspend fun awaitController(): MediaController {
        controller?.let { return it }
        return suspendCancellableCoroutine { cont ->
            controllerFuture.addListener({
                try {
                    val c = controllerFuture.get()
                    controller = c
                    playerView.player = c
                    cont.resume(c)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(this))
        }
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
        player?.stop()
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

                startPlayback(awaitController(), streamUrl, resumePositionMs)
                progressBar.visibility = View.GONE

                refreshFavoriteState()
                startProgressSaveLoop()
            } catch (e: CancellationException) {
                throw e
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

    private fun startPlayback(controller: MediaController, streamUrl: String, resumePositionMs: Long?) {
        val streamUri = Uri.parse(streamUrl)
        val mediaItem = MediaItem.Builder()
            .setMediaId(videoUrl)
            .setUri(streamUri)
            // 跨行程傳遞時 URI 可能被拿掉，PlaybackService 會從這裡補回
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(streamUri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(videoTitle)
                    .setArtist(videoUploader)
                    .setArtworkUri(videoThumbnailUrl?.let { Uri.parse(it) })
                    .build()
            )
            .build()

        val durationMs = videoDurationSec * 1000
        val startPositionMs = if (resumePositionMs != null &&
            resumePositionMs > RESUME_MIN_POSITION_MS &&
            (durationMs <= 0 || resumePositionMs < durationMs - RESUME_END_GUARD_MS)
        ) resumePositionMs else 0L

        controller.setMediaItem(mediaItem, startPositionMs)
        controller.prepare()
        controller.playWhenReady = true
        player = controller
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
        // 關螢幕或切到其他 App 都讓 PlaybackService 在背景繼續播
        saveProgressNow()
    }

    override fun onDestroy() {
        progressSaveJob?.cancel()
        loadJob?.cancel()
        playerView.player = null
        if (isFinishing) {
            controller?.stop()
            controller?.clearMediaItems()
        }
        player = null
        controller = null
        MediaController.releaseFuture(controllerFuture)
        if (isFinishing) {
            stopService(Intent(this, PlaybackService::class.java))
        }
        super.onDestroy()
    }
}
