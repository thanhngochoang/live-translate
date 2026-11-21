package io.github.thanhngochoang.voicetrans.core.model

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Opens model downloads. Replaced by a fake in unit tests. */
fun interface ModelDownloader {
    /** Opens [url] starting at byte [offset]. Throws [IOException] when the server cannot be reached or refuses. */
    fun open(url: String, offset: Long): Opened

    /** [resumed] is false when the server ignored the offset and is sending the whole file again. */
    class Opened(val stream: InputStream, val resumed: Boolean)
}

class HttpModelDownloader : ModelDownloader {
    override fun open(url: String, offset: Long): ModelDownloader.Opened {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        return when (val code = connection.responseCode) {
            HttpURLConnection.HTTP_OK -> ModelDownloader.Opened(connection.inputStream, resumed = false)
            HttpURLConnection.HTTP_PARTIAL -> ModelDownloader.Opened(connection.inputStream, resumed = true)
            else -> {
                connection.disconnect()
                throw IOException("HTTP $code for $url")
            }
        }
    }
}
