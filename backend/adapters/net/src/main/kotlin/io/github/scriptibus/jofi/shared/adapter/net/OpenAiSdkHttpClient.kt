// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import com.openai.core.RequestOptions
import com.openai.core.http.Headers
import com.openai.core.http.HttpClient
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpResponse
import com.openai.errors.OpenAIIoException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * The OpenAI SDK's transport for one AI call (ADR-0039), used for OpenAI, Gemini, Mistral and
 * OpenAI-compatible endpoints: every request goes through the shared [GuardedAiTransport]. The AI
 * adapter takes a fresh instance per call and closes it when the call ends, which aborts whatever
 * that call still has open (see [ExchangeScope]); the transport itself stays open.
 */
class OpenAiSdkHttpClient(
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
            throw OpenAIIoException("Request failed", failure)
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
            ioFailure = { OpenAIIoException("Request failed", it) },
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
