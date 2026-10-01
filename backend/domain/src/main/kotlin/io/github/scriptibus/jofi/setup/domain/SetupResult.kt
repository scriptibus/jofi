// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult

/**
 * Outcome of a setup use case (#23). Callers map every case: the REST controller to a status and
 * problem type. No case carries a key.
 */
sealed interface SetupResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : SetupResult<T>

    /** Every outcome but [Success]: nothing was changed. */
    sealed interface Failure : SetupResult<Nothing>

    data class Invalid(
        val violations: List<SetupViolation>,
    ) : Failure

    /** No provider with this id. */
    data object NotFound : Failure

    /**
     * The provider is not an OpenAI-compatible endpoint: cloud providers are priced by the verified price
     * table only (ADR-0043), so a user price for one of their models is refused.
     */
    data object PriceNotAllowed : Failure

    /** The provider still has tasks assigned; reassign them first. */
    data object InUse : Failure

    /**
     * Only the logged-in user may change where prompts go (#20 security review): never the AI, a
     * scanner or an external (MCP) client.
     */
    data object Forbidden : Failure

    /** The removal needs (another) confirmation step (ADR-0039); nothing was removed. */
    data class Unconfirmed(
        val outcome: ConfirmationResult.Unconfirmed,
    ) : Failure

    /** Calling the provider failed; [result] says how (never with prompt or key content). */
    data class ProviderFailed(
        val result: AiResult<*>,
    ) : Failure

    /** The store could not complete [operation]; nothing was changed. */
    data class StorageFailure(
        val operation: String,
    ) : Failure
}

/**
 * A task with its assignment (null while none is set), the capabilities it needs and the warnings for
 * the assigned model (spec §3.2: warn when a task's model lacks a needed capability).
 */
data class TaskAssignmentView(
    val task: AiTask,
    val assignment: ModelAssignment?,
    val required: Set<Capability>,
    val warnings: List<CapabilityWarning>,
)
