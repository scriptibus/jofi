// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity
import org.apache.hc.core5.util.TimeValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A request body the AI SDKs write themselves (JSON, or multipart for audio later). */
class AiRequestBody(
    val contentType: String?,
    /** -1 when unknown (chunked). */
    val contentLength: Long,
    val repeatable: Boolean,
    val writeTo: (OutputStream) -> Unit,
)

/**
 * One request of an AI SDK, provider-neutral. [deadline] bounds the whole exchange, body included
 * (the SDK's request timeout, at most [GuardedAiTransport.MAX_DEADLINE]). [toString] shows the
 * method and host only (T4).
 */
class AiRequest(
    val method: String,
    val uri: URI,
    val headers: List<Pair<String, String>>,
    val body: AiRequestBody?,
    val deadline: Duration? = null,
) {
    override fun toString(): String = "AiRequest($method ${uri.host})"
}

/**
 * A streamed response. [close] aborts the connection instead of draining the body, so a cancelled
 * completion stops at once and the provider stops generating (and billing) tokens.
 */
class AiResponse internal constructor(
    val statusCode: Int,
    val headers: List<Pair<String, String>>,
    val body: InputStream,
    private val abort: () -> Unit,
) : AutoCloseable {
    override fun close() = abort()
}

/**
 * The only transport of the AI vendor SDKs (ADR-0034, ADR-0040): Apache HttpClient with the SSRF
 * guard, whose allowlist holds the configured AI endpoints. No redirects, cookies, retries or system
 * proxies; response sizes are not capped (completions stream). The SDK bridges
 * ([OpenAiSdkHttpClient], [AnthropicSdkHttpClient]) translate their request types into [AiRequest].
 *
 * SDK telemetry headers (`X-Stainless-*`: OS, architecture, runtime versions) are dropped, and the
 * SDK's `User-Agent` is replaced by Jofi's. Logs carry the host and the outcome only (T4).
 */
class GuardedAiTransport(
    private val client: CloseableHttpClient,
) : AutoCloseable {
    fun execute(request: AiRequest): AiResponse = execute(request, toApacheRequest(request))

    /** A fresh OpenAI SDK bridge for one AI call; closing it aborts what the call left open. */
    fun openAiBridge(): OpenAiSdkHttpClient = OpenAiSdkHttpClient(this)

    /** A fresh Anthropic SDK bridge for one AI call; closing it aborts what the call left open. */
    fun anthropicBridge(): AnthropicSdkHttpClient = AnthropicSdkHttpClient(this)

    /**
     * Runs the request on a virtual thread. Cancelling the future aborts the request, whether it is
     * still connecting or waiting for the headers; a response that arrives after the cancellation
     * is closed (and so aborted) at once.
     */
    fun executeAsync(request: AiRequest): CompletableFuture<AiResponse> {
        val outbound = toApacheRequest(request)
        val future = CompletableFuture<AiResponse>()
        ASYNC.execute {
            try {
                val response = execute(request, outbound)
                if (!future.complete(response)) response.close()
            } catch (failure: Exception) {
                // Every failure must reach the SDK, or its future would never complete.
                future.completeExceptionally(CompletionException(failure))
            }
        }
        future.whenComplete { _, error -> if (error is CancellationException) outbound.cancel() }
        return future
    }

    private fun execute(
        request: AiRequest,
        outbound: HttpUriRequestBase,
    ): AiResponse {
        // One deadline for the whole exchange: a stream that trickles forever is still cut off.
        val deadline = minOf(request.deadline ?: MAX_DEADLINE, MAX_DEADLINE)
        val expiry = DEADLINES.schedule({ outbound.cancel() }, deadline.toNanos(), TimeUnit.NANOSECONDS)
        return try {
            toAiResponse(client.executeOpen(null, outbound, null), outbound, expiry)
        } catch (failure: IOException) {
            expiry.cancel(false)
            outbound.cancel()
            log.warn("AI request to {} failed: {}", request.uri.host, describe(failure))
            throw failure
        }
    }

    override fun close() = client.close()

    private fun toApacheRequest(request: AiRequest): HttpUriRequestBase {
        val outbound = HttpUriRequestBase(request.method, request.uri)
        request.headers
            .filterNot { (name, _) -> isDropped(name) }
            .forEach { (name, value) -> outbound.addHeader(name, value) }
        request.body?.let { outbound.entity = BodyEntity(it) }
        return outbound
    }

    private fun toAiResponse(
        response: ClassicHttpResponse,
        outbound: HttpUriRequestBase,
        expiry: ScheduledFuture<*>,
    ): AiResponse {
        val headers = response.headers.map { it.name to it.value }
        val content = response.entity?.content ?: InputStream.nullInputStream()
        val body = AbortingStream(content, outbound, response, expiry)
        return AiResponse(response.code, headers, body, body::close)
    }

    private fun isDropped(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower in MANAGED_HEADERS || lower.startsWith(TELEMETRY_PREFIX)
    }

    private fun describe(failure: IOException): String =
        (failure as? BlockedDestinationException)?.let { "blocked (${it.addressClass})" } ?: failure.javaClass.name

    /**
     * The response body. Closing it (the SDKs close their reader before the response) aborts an
     * unfinished exchange, because Apache HttpClient would otherwise read a chunked stream to its
     * end. A body read to its end is only closed, so its connection goes back to the pool.
     */
    private class AbortingStream(
        body: InputStream,
        private val request: HttpUriRequestBase,
        private val response: ClassicHttpResponse,
        private val expiry: ScheduledFuture<*>,
    ) : FilterInputStream(body) {
        @Volatile private var ended = false
        private val closed = AtomicBoolean(false)

        override fun read(): Int = super.read().also { if (it == -1) ended = true }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = super.read(buffer, offset, length).also { if (it == -1) ended = true }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            expiry.cancel(false)
            if (!ended) request.cancel()
            try {
                response.close()
            } catch (_: IOException) {
                // Expected after the cancel: the aborted connection has nothing left to release.
            }
        }
    }

    /** Streams the SDK's body writer; never read back, so [getContent] is unsupported. */
    private class BodyEntity(
        private val body: AiRequestBody,
    ) : AbstractHttpEntity(body.contentType?.let(ContentType::parseLenient), null) {
        override fun writeTo(outStream: OutputStream) = body.writeTo(outStream)

        override fun getContentLength(): Long = body.contentLength

        override fun isRepeatable(): Boolean = body.repeatable

        override fun isStreaming(): Boolean = false

        override fun getContent(): InputStream = throw UnsupportedOperationException("Request bodies are write-only")

        override fun close() = Unit
    }

    companion object {
        /** Framing and identity headers the client sets itself. */
        private val MANAGED_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "user-agent")
        private const val TELEMETRY_PREFIX = "x-stainless-"
        private val ASYNC: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

        /** The longest any AI exchange may take, streaming included. */
        val MAX_DEADLINE: Duration = Duration.ofMinutes(15)

        /** One daemon thread for all deadlines; a cancelled one is removed at once. */
        private val DEADLINES: ScheduledThreadPoolExecutor =
            ScheduledThreadPoolExecutor(1) { task -> Thread(task, "jofi-ai-deadlines").apply { isDaemon = true } }
                .apply { removeOnCancelPolicy = true }
        private val log: Logger = LoggerFactory.getLogger(GuardedAiTransport::class.java)

        /** The AI transport over the guarded client with [allowlist] (the configured AI endpoints). */
        fun create(
            allowlist: DestinationAllowlist,
            userAgent: String,
        ): GuardedAiTransport =
            GuardedAiTransport(
                GuardedHttpClients
                    .aiClientBuilder(allowlist, userAgent)
                    .evictExpiredConnections()
                    .evictIdleConnections(TimeValue.of(GuardedHttpClients.AI_MAX_IDLE))
                    .build(),
            )
    }
}
