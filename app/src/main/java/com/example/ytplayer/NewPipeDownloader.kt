package com.example.ytplayer

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Request as OkHttpRequest

/**
 * NewPipeExtractor 需要呼叫端提供實際的 HTTP 下載實作，這裡用 OkHttp 實作。
 */
class NewPipeDownloader private constructor(private val client: OkHttpClient) : Downloader() {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        @Volatile
        private var INSTANCE: NewPipeDownloader? = null

        fun instance(): NewPipeDownloader =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NewPipeDownloader(
                    OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                ).also { INSTANCE = it }
            }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val body = dataToSend?.let {
            it.toRequestBody(null, 0, it.size)
        }

        val builder = OkHttpRequest.Builder()
            .url(url)
            .method(httpMethod, body)

        for ((headerName, headerValues) in headers) {
            if (headerValues.isEmpty()) continue
            builder.removeHeader(headerName)
            for (value in headerValues) {
                builder.addHeader(headerName, value)
            }
        }
        if (headers["User-Agent"].isNullOrEmpty()) {
            builder.header("User-Agent", USER_AGENT)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }

            val responseBody = response.body?.string().orEmpty()
            val latestUrl = response.request.url.toString()

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                latestUrl
            )
        }
    }
}
