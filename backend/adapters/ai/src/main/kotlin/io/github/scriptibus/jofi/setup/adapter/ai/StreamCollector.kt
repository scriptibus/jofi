// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse
import org.springframework.ai.chat.model.ChatResponse
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers

/**
 * Consumes a Spring AI stream on the calling (virtual) thread, fragment by fragment. Before each
 * fragment it polls the cancellation callback; once that says stop, or the consumer throws, it
 * closes the stream, which cancels the subscription and aborts the HTTP connection (the transport
 * does not drain it), and returns [AiResult.Cancelled] as `LlmPort.stream` promises.
 */
internal object StreamCollector {
    fun collect(
        fragments: Flux<ChatResponse>,
        isCancelled: () -> Boolean,
        onTextDelta: (String) -> Unit,
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
                    state = step(iterator, accumulator, isCancelled, onTextDelta)
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
        onTextDelta: (String) -> Unit,
    ): State =
        when {
            cancelled(isCancelled) -> State.CANCELLED
            !fragments.hasNext() -> State.COMPLETED
            else -> handOn(accumulator.add(fragments.next()), onTextDelta)
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
