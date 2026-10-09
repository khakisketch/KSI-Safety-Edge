package ksi.rc

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Raw HTTP response at the transport boundary. */
class CoreHttpResponse(val status: Int, val body: ByteArray) {
    val bodyText: String get() = String(body, Charsets.UTF_8)
}

/**
 * Transport boundary for the Core API. rc-core itself stays a portable
 * JVM/Android library: the MSDK app supplies its own implementation
 * (HttpURLConnection/OkHttp); [JdkCoreTransport] serves tests and host
 * tools on a desktop JVM.
 */
interface CoreTransport {
    /** HTTP round trip; `path` starts with "/" and is relative to the API base. */
    fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): CoreHttpResponse
}

/** Non-2xx Core response (mirrors the harness `CoreError`). */
class CoreApiError(
    val method: String,
    val path: String,
    val status: Int,
    bodyText: String,
) : RuntimeException("$method $path -> $status: ${bodyText.take(300)}")

/**
 * JVM host transport (tests, tools) using java.net.http. NOT for Android
 * (the platform lacks java.net.http): the app brings its own [CoreTransport].
 */
class JdkCoreTransport(
    baseUrl: String,
    private val token: String,
    private val timeoutSeconds: Long = 30,
) : CoreTransport {
    private val base = baseUrl.trimEnd('/')
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): CoreHttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer $token")
        for ((key, value) in headers) {
            builder.header(key, value)
        }
        val publisher = if (body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(body)
        }
        val response = client.send(
            builder.method(method, publisher).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        return CoreHttpResponse(response.statusCode(), response.body())
    }
}
