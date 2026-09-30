// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import com.anthropic.core.RequestOptions
import com.anthropic.core.http.Headers
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.anthropic.errors.AnthropicIoException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * The Anthropic SDK's transport (ADR-0037): every request goes through [GuardedAiTransport]. The
 * same shape as [OpenAiSdkHttpClient]; the SDKs share a generator but no types. [close] leaves the
 * shared transport open.
 */
class AnthropicSdkHttpClient(
    private val transport: GuardedAiTransport,
) : HttpClient {
    override fun execute(
        request: HttpRequest,
        requestOptions: RequestOptions,
    ): HttpResponse =
        try {
            SdkResponse(transport.execute(toAiRequest(request, requestOptions)))
        } catch (failure: IOException) {
            throw AnthropicIoException("Request failed", failure)
        } finally {
            request.body?.close()
        }

    override fun executeAsync(
        request: HttpRequest,
        requestOptions: RequestOptions,
    ): CompletableFuture<HttpResponse> =
        SdkFutures.map(
            transport.executeAsync(toAiRequest(request, requestOptions)),
            whenDone = { request.body?.close() },
            ioFailure = { AnthropicIoException("Request failed", it) },
            toSdk = ::SdkResponse,
        )

    override fun close() = Unit

    private fun toAiRequest(
        request: HttpRequest,
        options: RequestOptions,
    ): AiRequest {
        val headers = request.headers.names().flatMap { name -> request.headers.values(name).map { name to it } }
        val body =
            request.body?.let { body ->
                AiRequestBody(body.contentType(), body.contentLength(), body.repeatable(), body::writeTo)
            }
        return AiRequest(request.method.name, URI(request.url()), headers, body, options.timeout?.request())
    }

    private class SdkResponse(
        private val response: AiResponse,
    ) : HttpResponse {
        private val headers =
            Headers.builder().apply { response.headers.forEach { (name, value) -> put(name, value) } }.build()

        override fun statusCode(): Int = response.statusCode

        override fun headers(): Headers = headers

        override fun body(): InputStream = response.body

        private val cleanup = UnclosedResponses.register(this, response)

        override fun close() = cleanup.clean()
    }
}
