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
 * The Anthropic SDK's transport for one AI call (ADR-0037): the same shape as
 * [OpenAiSdkHttpClient]; the SDKs share a generator but no types. Closing it aborts whatever the
 * call still has open. This matters most here: the Anthropic SDK wraps the request future in its
 * logging layer, so cancelling a stream before its headers arrive never reaches the transport.
 */
class AnthropicSdkHttpClient(
    private val transport: GuardedAiTransport,
) : HttpClient {
    private val scope = ExchangeScope()

    override fun execute(
        request: HttpRequest,
        requestOptions: RequestOptions,
    ): HttpResponse =
        try {
            SdkResponse(scope.track(transport.execute(toAiRequest(request, requestOptions))), scope)
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
            scope.track(transport.executeAsync(toAiRequest(request, requestOptions))),
            whenDone = { request.body?.close() },
            ioFailure = { AnthropicIoException("Request failed", it) },
            toSdk = { SdkResponse(it, scope) },
        )

    /** Aborts the call's requests and responses that are still open. */
    override fun close() = scope.close()

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
        private val scope: ExchangeScope,
    ) : HttpResponse {
        private val headers =
            Headers.builder().apply { response.headers.forEach { (name, value) -> put(name, value) } }.build()
        private val cleanup = UnclosedResponses.register(this, response)

        override fun statusCode(): Int = response.statusCode

        override fun headers(): Headers = headers

        override fun body(): InputStream = response.body

        override fun close() {
            cleanup.clean()
            scope.forget(response)
        }
    }
}
