// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.application.port.AiProviderPort
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilityCheck
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.shared.application.port.EmbeddingPort
import io.github.scriptibus.jofi.shared.application.port.LlmPort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingResponse
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * The AI gateway (ADR-0032, ADR-0043): the one implementation of [LlmPort] and [EmbeddingPort], and
 * the only caller of [AiProviderPort]. Every call runs the same steps, in this order, and the
 * provider is called only when all of them pass:
 *
 * 1. [AiRouter] resolves the task's provider and model once.
 * 2. The model must have what this call needs (tools, streaming, embeddings): else
 *    [AiResult.CapabilityMissing].
 * 3. [AiMeter.admit]: non-essential tasks pause at the monthly budget cap.
 * 4. [NeverSendGuard]: the "never send to AI" filter, fail closed.
 * 5. The provider call with the filtered request.
 * 6. [AiMeter.record]: a cost entry for every completed or cancelled call, and for a failed one that
 *    already reported token usage.
 *
 * Logs name the task, provider kind, model and result kind only (threat model T4). Nothing throws
 * across the ports: an unexpected exception ends as [AiResult.Unavailable], and one before step 5
 * sends nothing.
 */
class AiGatewayAdapter(
    private val provider: AiProviderPort,
    private val router: AiRouter,
    private val guard: NeverSendGuard,
    private val meter: AiMeter,
) : LlmPort,
    EmbeddingPort {
    override fun complete(request: LlmRequest): AiResult<LlmResponse> =
        call(
            request.task,
            CapabilityCheck.neededBy(request, streaming = false),
            { guard.guard(request) },
            LlmResponse::usage,
        ) {
            target,
            filtered,
            _,
            ->
            provider.complete(target, filtered)
        }

    override fun stream(
        request: LlmRequest,
        isCancelled: () -> Boolean,
        onTextDelta: (String) -> Unit,
    ): AiResult<LlmResponse> =
        call(
            request.task,
            CapabilityCheck.neededBy(request, streaming = true),
            { guard.guard(request) },
            LlmResponse::usage,
        ) {
            target,
            filtered,
            usage,
            ->
            provider.stream(target, filtered, isCancelled, { usage.set(it) }, onTextDelta)
        }

    override fun embed(request: EmbeddingRequest): AiResult<EmbeddingResponse> =
        call(request.task, CapabilityCheck.neededForEmbedding, { guard.guard(request) }, EmbeddingResponse::usage) {
            target,
            filtered,
            _,
            ->
            provider.embed(target, filtered)
        }

    private fun <R, T> call(
        task: AiTask,
        needs: Set<Capability>,
        filter: () -> Guarded<R>,
        usageOf: (T) -> TokenUsage,
        send: (ResolvedModel, R, AtomicReference<TokenUsage>) -> AiResult<T>,
    ): AiResult<T> {
        val ready =
            try {
                prepare(task, needs, filter)
            } catch (failure: Exception) {
                log.error("AI call for {} failed before sending: {}", task, failure.javaClass.name)
                return AiResult.Unavailable
            }
        return when (ready) {
            is Prepared.Refused -> ready.result
            is Prepared.Ready -> sendAndMeter(task, ready, usageOf, send)
        }
    }

    private fun <R> prepare(
        task: AiTask,
        needs: Set<Capability>,
        filter: () -> Guarded<R>,
    ): Prepared<R> =
        when (val routed = router.route(task)) {
            is Route.Unroutable -> refused(task, routed.result)
            is Route.Resolved -> checked(task, needs, routed, filter)
        }

    private fun <R> checked(
        task: AiTask,
        needs: Set<Capability>,
        route: Route.Resolved,
        filter: () -> Guarded<R>,
    ): Prepared<R> {
        val refusal =
            if (needs.any { !route.capabilities.meets(it) }) AiResult.CapabilityMissing(task) else meter.admit(task)
        if (refusal != null) return refused(task, refusal)
        return when (val guarded = filter()) {
            is Guarded.Pass -> Prepared.Ready(route.target, guarded.request)
            is Guarded.Refused -> refused(task, guarded.result)
        }
    }

    private fun <R, T> sendAndMeter(
        task: AiTask,
        ready: Prepared.Ready<R>,
        usageOf: (T) -> TokenUsage,
        send: (ResolvedModel, R, AtomicReference<TokenUsage>) -> AiResult<T>,
    ): AiResult<T> {
        val reported = AtomicReference(TokenUsage.NONE)
        val result =
            try {
                send(ready.target, ready.request, reported)
            } catch (failure: Exception) {
                // The provider port never throws; if it does, the caller still gets a result.
                log.error("AI call for {} failed: {}", task, failure.javaClass.name)
                AiResult.Unavailable
            }
        val usage =
            when (result) {
                is AiResult.Success -> usageOf(result.value)
                AiResult.Cancelled -> reported.get()
                else -> reported.get().takeIf { it != TokenUsage.NONE }
            }
        if (usage != null) meter.record(task, ready.target, usage)
        log.debug(
            "AI call for {} to {} ({}) ended: {}",
            task,
            ready.target.provider.kind,
            ready.target.model.value,
            result.javaClass.simpleName,
        )
        return result
    }

    private fun refused(
        task: AiTask,
        result: AiResult<Nothing>,
    ): Prepared.Refused {
        log.info("AI call for {} not sent: {}", task, result.javaClass.simpleName)
        return Prepared.Refused(result)
    }

    private sealed interface Prepared<out R> {
        data class Ready<out R>(
            val target: ResolvedModel,
            val request: R,
        ) : Prepared<R>

        data class Refused(
            val result: AiResult<Nothing>,
        ) : Prepared<Nothing>
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(AiGatewayAdapter::class.java)
    }
}
