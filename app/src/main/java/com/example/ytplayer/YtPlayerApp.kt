package com.example.ytplayer

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe

class YtPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(NewPipeDownloader.instance())
    }
}
