package com.example.ytplayer

import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** 首頁清單的共用顯示資料，可能來自 NewPipeExtractor 的熱門/搜尋結果，也可能來自本機資料庫（收藏／繼續觀看） */
data class VideoCardData(
    val url: String,
    val title: String,
    val uploader: String?,
    val durationSec: Long,
    val thumbnailUrl: String?,
    /** 0f~1f，已看的比例；非續看清單時為 null，不顯示進度條 */
    val watchedFraction: Float? = null
)

fun StreamInfoItem.toCardData(): VideoCardData = VideoCardData(
    url = url,
    title = name,
    uploader = uploaderName,
    durationSec = if (duration > 0) duration else 0,
    thumbnailUrl = thumbnails?.maxByOrNull { it.height }?.url
)

fun FavoriteEntry.toCardData(): VideoCardData = VideoCardData(
    url = url,
    title = title,
    uploader = uploader,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl
)

fun WatchHistoryEntry.toCardData(): VideoCardData {
    val fraction = if (durationSec > 0) {
        (positionMs / 1000f / durationSec).coerceIn(0f, 1f)
    } else null
    return VideoCardData(
        url = url,
        title = title,
        uploader = uploader,
        durationSec = durationSec,
        thumbnailUrl = thumbnailUrl,
        watchedFraction = fraction
    )
}
