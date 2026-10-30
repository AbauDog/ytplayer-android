package com.example.ytplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 把 ExoPlayer 放在前景服務裡，關閉螢幕後系統不會停掉播放；
 * 通知列的媒體控制由 MediaSessionService 自動處理。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            // 螢幕關閉時持有 CPU + Wi-Fi wake lock，避免串流中斷
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 只播單一影片、沒有上下一首可切。ExoPlayer 本身只有「上一首」會因為
        // 「可以回到開頭」而回報成可用，「下一首」預設是不可用的，耳機的下一首
        // 按鍵事件因此根本不會送進來。用 ForwardingPlayer 把兩個指令都強制標成
        // 可用，並攔截實際呼叫改成調音量，而不是真的去 seek。
        val volumeControlPlayer = object : ForwardingPlayer(exoPlayer) {
            private val remappedCommands = setOf(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            )

            override fun getAvailableCommands(): Player.Commands {
                val builder = super.getAvailableCommands().buildUpon()
                remappedCommands.forEach { builder.add(it) }
                return builder.build()
            }

            override fun isCommandAvailable(command: Int): Boolean =
                command in remappedCommands || super.isCommandAvailable(command)

            // 耳機「上一首」= 音量調大
            override fun seekToPrevious() = raiseVolume()
            override fun seekToPreviousMediaItem() = raiseVolume()

            // 耳機「下一首」= 音量調小
            override fun seekToNext() = lowerVolume()
            override fun seekToNextMediaItem() = lowerVolume()

            private fun raiseVolume() {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
                )
            }

            private fun lowerVolume() {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
                )
            }
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val sessionActivity = PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java), flags
        )

        mediaSession = MediaSession.Builder(this, volumeControlPlayer)
            .setSessionActivity(sessionActivity)
            .setCallback(object : MediaSession.Callback {
                // MediaController 傳過來的 MediaItem 可能被拿掉 URI，從 requestMetadata 補回來
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> {
                    val resolved = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri
                        if (item.localConfiguration == null && uri != null) {
                            item.buildUpon().setUri(uri).build()
                        } else {
                            item
                        }
                    }.toMutableList()
                    return Futures.immediateFuture(resolved)
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 使用者從最近使用清單滑掉 App 時一併停止播放
        mediaSession?.player?.stop()
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
