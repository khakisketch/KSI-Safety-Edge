package ksi.safetyedge.rc

import java.net.HttpURLConnection
import java.net.URL
import ksi.rc.CoreHttpResponse
import ksi.rc.CoreTransport

/**
 * Android [CoreTransport] over HttpURLConnection (no extra dependencies;
 * java.net.http does not exist on Android). Timeouts are fail-fast so a
 * dead field laptop does not hang the sync screen.
 */
class AndroidCoreTransport(
    private val baseUrl: String,
    private val token: String,
    private val timeoutMs: Int = 15_000,
) : CoreTransport {
    override fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): CoreHttpResponse {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection()
            as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $token")
            for ((key, value) in headers) setRequestProperty(key, value)
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                outputStream.use { it.write(body) }
            }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val bytes = stream?.readBytes() ?: ByteArray(0)
            CoreHttpResponse(status, bytes)
        } finally {
            connection.disconnect()
        }
    }
}
