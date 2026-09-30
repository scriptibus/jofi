// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import org.springframework.ai.chat.model.ChatResponse
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers

/**
 * Consumes a Spring AI stream on the calling (virtual) thread, fragment by fragment. Before each
 * fragment it polls the cancellation callback; once that says stop, or the consumer throws, it
 * closes the stream, which cancels the subscription and aborts the HTTP connection (the transport
 * does not drain it), and returns [AiResult.Cancelled] as `LlmPort.stream` promises. Each new
 * usage total a fragment reports goes to [Consumers.onUsage], so a cancelled stream can be metered.
 */
internal object StreamCollector {
    /** Where the text fragments and the usage totals go. */
    class Consumers(
        val onTextDelta: (String) -> Unit,
        val onUsage: (TokenUsage) -> Unit,
    )

    fun collect(
        fragments: Flux<ChatResponse>,
        isCancelled: () -> Boolean,
        consumers: Consumers,
    ): AiResult<LlmResponse> {
        // Before subscribing: cancelling while the request still waits for headers cannot reach the
        // transport through the Anthropic SDK's future chain, so an already cancelled call never starts.
        if (cancelled(isCancelled)) return AiResult.Cancelled
        val accumulator = ResponseAccumulator()
        val outcome =
            fragments.cancelOn(CANCELLATIONS).toStream(1).use { stream ->
                val iterator = stream.iterator()
                var state = State.RUNNING
                while (state == State.RUNNING) {
                    state = step(iterator, accumulator, isCancelled, consumers)
                }
                state
            }
        return if (outcome == State.COMPLETED) AiResult.Success(accumulator.toResponse()) else AiResult.Cancelled
    }

    private enum class State { RUNNING, COMPLETED, CANCELLED }

    /** Polls for cancellation, then waits for the next fragment and hands its text on. */
    private fun step(
        fragments: Iterator<ChatResponse>,
        accumulator: ResponseAccumulator,
        isCancelled: () -> Boolean,
        consumers: Consumers,
    ): State =
        when {
            cancelled(isCancelled) -> State.CANCELLED
            !fragments.hasNext() -> State.COMPLETED
            else -> handOn(add(accumulator, fragments.next(), consumers), consumers.onTextDelta)
        }

    /** Adds [fragment] and reports a changed usage total; returns the fragment's text. */
    private fun add(
        accumulator: ResponseAccumulator,
        fragment: ChatResponse,
        consumers: Consumers,
    ): String {
        val before = accumulator.usage
        val delta = accumulator.add(fragment)
        if (accumulator.usage != before) consumers.onUsage(accumulator.usage)
        return delta
    }

    private fun handOn(
        delta: String,
        onTextDelta: (String) -> Unit,
    ): State = if (delta.isEmpty() || delivered(delta, onTextDelta)) State.RUNNING else State.CANCELLED

    /**
     * Cancelling closes the SDK's reader, which waits until a read in progress returns. That happens
     * in the background, so the caller gets [AiResult.Cancelled] at once.
     */
    private val CANCELLATIONS: Scheduler = Schedulers.boundedElastic()

    /** A failing cancellation callback counts as a cancellation: nothing may escape the port. */
    private fun cancelled(isCancelled: () -> Boolean): Boolean =
        try {
            isCancelled()
        } catch (_: Exception) {
            true
        }

    private fun delivered(
        delta: String,
        onTextDelta: (String) -> Unit,
    ): Boolean =
        try {
            onTextDelta(delta)
            true
        } catch (_: Exception) {
            false
        }
}
