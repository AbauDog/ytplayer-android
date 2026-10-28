package com.example.ytplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object YoutubeRepository {

    suspend fun search(query: String): List<StreamInfoItem> = withContext(Dispatchers.IO) {
        val extractor = ServiceList.YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
    }
}
