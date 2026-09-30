// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.util.Locale

/** What a provider answered with an error: status, `Retry-After` and the error text (never logged). */
internal data class ProviderError(
    val status: Int,
    val retryAfter: String?,
    val message: String,
) {
    override fun toString(): String = "ProviderError(status=$status)"
}

/**
 * Maps whatever a provider call threw (SDK exceptions, possibly wrapped by Reactor or a future) to
 * an [AiResult] failure, so no exception or SDK type leaves the adapter. The error text is only
 * matched, never logged or returned: providers may quote the prompt in it (T4).
 */
internal object ProviderFailures {
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val REQUEST_TIMEOUT = 408
    private const val TOO_MANY_REQUESTS = 429
    private const val SERVER_ERRORS = 500

    private val CONTEXT_TOO_LONG =
        Regex(
            "context[_ ]length|context window|prompt is too long|too many (input )?tokens|" +
                "maximum (context|number of tokens)|exceeds the (maximum|max)|too large for model",
        )
    private val CAPABILITY_MISSING = Regex("does not support (tools|function|streaming|embedding)")
    private val DELAY_SECONDS = Regex("\\d{1,9}")

    fun map(
        failure: Throwable,
        task: AiTask,
    ): AiResult<Nothing> {
        val chain = generateSequence(failure) { it.cause }.take(MAX_CAUSES).toList()
        chain.firstNotNullOfOrNull(::providerError)?.let { return fromStatus(it, task) }
        return if (chain.any(::isIoFailure)) AiResult.Unavailable else AiResult.Rejected(null)
    }

    private fun providerError(failure: Throwable): ProviderError? =
        when (failure) {
            is OpenAIServiceException -> {
                ProviderError(
                    failure.statusCode(),
                    failure.headers().values(RETRY_AFTER).firstOrNull(),
                    failure.message.orEmpty(),
                )
            }

            is AnthropicServiceException -> {
                ProviderError(
                    failure.statusCode(),
                    failure.headers().values(RETRY_AFTER).firstOrNull(),
                    failure.message.orEmpty(),
                )
            }

            else -> {
                null
            }
        }

    /**
     * A class the provider stack needs is missing or incompatible, e.g. Spring AI fell back to its
     * own OkHttp client, which is excluded on purpose (ADR-0037). A deployment bug, not a provider
     * answer: logged loudly (class name only), reported as unavailable, never thrown across the port.
     */
    fun brokenClasspath(failure: LinkageError): AiResult<Nothing> {
        log.error("AI provider stack is broken ({}): {}", failure.javaClass.name, failure.message)
        return AiResult.Unavailable
    }

    private val log = LoggerFactory.getLogger(ProviderFailures::class.java)

    private fun isIoFailure(failure: Throwable): Boolean =
        failure is IOException || failure is OpenAIIoException || failure is AnthropicIoException

    fun fromStatus(
        error: ProviderError,
        task: AiTask,
    ): AiResult<Nothing> {
        val text = error.message.lowercase(Locale.ROOT)
        return when {
            error.status == UNAUTHORIZED || error.status == FORBIDDEN -> AiResult.AuthenticationFailed
            error.status == TOO_MANY_REQUESTS -> AiResult.RateLimited(retryAfter(error.retryAfter))
            error.status == REQUEST_TIMEOUT || error.status >= SERVER_ERRORS -> AiResult.Unavailable
            CONTEXT_TOO_LONG.containsMatchIn(text) -> AiResult.ContextTooLong
            CAPABILITY_MISSING.containsMatchIn(text) -> AiResult.CapabilityMissing(task)
            else -> AiResult.Rejected(error.status)
        }
    }

    /** Delay seconds only; an HTTP date is rare for AI APIs and then treated as unknown. */
    private fun retryAfter(value: String?): Duration? =
        value?.trim()?.takeIf(DELAY_SECONDS::matches)?.let { Duration.ofSeconds(it.toLong()) }

    private const val RETRY_AFTER = "retry-after"
    private const val MAX_CAUSES = 10
}
