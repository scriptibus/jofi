// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import java.util.UUID

/** The answer of a delete tool once the user was asked: [status] is [DELETED] or [DECLINED]. */
data class DeleteOutcome(
    val status: String,
    val kind: String,
    val id: UUID,
) {
    companion object {
        const val DELETED = "deleted"
        const val DECLINED = "declined"
    }
}

/**
 * The MCP side of the two-step confirmation (ADR-0039). The use case owns the gate; this only drives its two
 * steps for a tool: the first call (no token) mutates nothing and yields a token and the server's own effect;
 * the human, not the model, is asked to confirm that effect; only then is the same call repeated with the
 * token, which stays inside this function and never reaches the tool's answer. Without a human to ask,
 * nothing runs.
 */
object TwoStepDelete {
    fun <R> run(
        call: ToolCall,
        id: UUID,
        execute: (ConfirmationRequester, ConfirmationToken?) -> R,
        unconfirmed: (R) -> ConfirmationResult.Unconfirmed?,
        finish: (R) -> ToolAnswer,
    ): ToolAnswer {
        refusal(call)?.let { return it }
        val requester = ConfirmationRequester(call.caller, call.session)
        val first = execute(requester, null)
        return when (val gate = unconfirmed(first)) {
            null -> {
                finish(first)
            }

            is ConfirmationResult.Rejected -> {
                INVALID
            }

            is ConfirmationResult.Required -> {
                askThenRepeat(call, id, gate) { token ->
                    val second = execute(requester, token)
                    if (unconfirmed(second) == null) deleted(finish(second), gate.action.effect, id) else INVALID
                }
            }
        }
    }

    /** Before the first step: with nobody to ask, no token is issued at all. */
    private fun refusal(call: ToolCall): ToolAnswer.Error? =
        when {
            call.session == ToolCall.NO_SESSION -> UNAVAILABLE
            call.human.availability == Availability.UNSUPPORTED -> UNAVAILABLE
            call.human.availability == Availability.BUSY -> PENDING
            else -> null
        }

    private fun askThenRepeat(
        call: ToolCall,
        id: UUID,
        required: ConfirmationResult.Required,
        repeat: (ConfirmationToken) -> ToolAnswer,
    ): ToolAnswer =
        when (call.human.ask(ConfirmationMessage.of(required.action.effect))) {
            HumanAnswer.UNAVAILABLE -> {
                UNAVAILABLE
            }

            HumanAnswer.TIMED_OUT -> {
                TIMED_OUT
            }

            HumanAnswer.BUSY -> {
                PENDING
            }

            HumanAnswer.DECLINED -> {
                ToolAnswer.Result(DeleteOutcome(DeleteOutcome.DECLINED, required.action.effect.kind, id))
            }

            HumanAnswer.CONFIRMED -> {
                repeat(required.token)
            }
        }

    private fun deleted(
        answer: ToolAnswer,
        effect: ConfirmationEffect,
        id: UUID,
    ): ToolAnswer =
        when (answer) {
            is ToolAnswer.Result -> ToolAnswer.Result(DeleteOutcome(DeleteOutcome.DELETED, effect.kind, id))
            is ToolAnswer.Error -> answer
        }

    private val UNAVAILABLE =
        ToolAnswer.Error(
            "confirmation-unavailable",
            "Nothing was deleted: this client cannot ask the user to confirm. The user can delete it in Jofi.",
        )
    private val TIMED_OUT =
        ToolAnswer.Error(
            "confirmation-timeout",
            "Nothing was deleted: the user did not answer in time. Call the tool again to ask again.",
        )
    private val PENDING =
        ToolAnswer.Error(
            "confirmation-pending",
            "Nothing was deleted: another confirmation in this session still waits for the user's answer.",
        )
    private val INVALID =
        ToolAnswer.Error(
            "confirmation-invalid",
            "Nothing was deleted: the confirmation no longer matches. Call the tool again to start over.",
        )
}
