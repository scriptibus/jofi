// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.FilterOutcome
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** A request the filter let through, or the result that replaces the call. */
sealed interface Guarded<out R> {
    data class Pass<out R>(
        val request: R,
        val redactions: Int,
    ) : Guarded<R>

    data class Refused(
        val result: AiResult<Nothing>,
    ) : Guarded<Nothing>
}

/**
 * Applies the "never send to AI" filter ([NeverSendFilter]) with the rules [visibility] gives for
 * this one call (spec §4.1, threat model T3, ADR-0043). Fail closed: when the source is unavailable,
 * does not know an item the request quotes, or anything throws, the call is refused with
 * [AiResult.PrivacyFilterFailed] and nothing is sent. Logs counts only, never content.
 */
class NeverSendGuard(
    private val visibility: AiVisibilityPort,
) {
    fun guard(request: LlmRequest): Guarded<LlmRequest> =
        guard(request.task, NeverSendFilter.sourcesOf(request)) { NeverSendFilter.apply(request, it) }

    fun guard(request: EmbeddingRequest): Guarded<EmbeddingRequest> =
        guard(request.task, NeverSendFilter.sourcesOf(request)) { NeverSendFilter.apply(request, it) }

    private fun <R> guard(
        task: AiTask,
        sources: Set<ContentSource>,
        filter: (NeverSendRules) -> FilterOutcome<R>,
    ): Guarded<R> =
        try {
            when (val answer = visibility.rulesFor(sources)) {
                is AiVisibilityResult.Known -> outcome(task, filter(answer.rules))
                is AiVisibilityResult.Unavailable -> refused(task, "the source is unavailable")
            }
        } catch (failure: Exception) {
            refused(task, "the filter failed with ${failure.javaClass.name}")
        }

    private fun <R> outcome(
        task: AiTask,
        outcome: FilterOutcome<R>,
    ): Guarded<R> =
        when (outcome) {
            is FilterOutcome.Passed -> {
                if (outcome.redactions >
                    0
                ) {
                    log.info("Withheld {} flagged pieces from a {} call", outcome.redactions, task)
                }
                Guarded.Pass(outcome.value, outcome.redactions)
            }

            is FilterOutcome.Undecided -> {
                refused(task, "${outcome.sources} quoted items have no verdict")
            }

            FilterOutcome.Withheld -> {
                log.info("A {} call quoting a flagged item was withheld", task)
                Guarded.Refused(AiResult.Withheld(task))
            }
        }

    private fun refused(
        task: AiTask,
        why: String,
    ): Guarded<Nothing> {
        log.warn("The never-send-to-AI filter refused a {} call: {}", task, why)
        return Guarded.Refused(AiResult.PrivacyFilterFailed(task))
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(NeverSendGuard::class.java)
    }
}
