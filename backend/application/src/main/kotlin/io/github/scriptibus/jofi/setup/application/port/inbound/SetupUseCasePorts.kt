// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port.inbound

import io.github.scriptibus.jofi.setup.domain.CapabilityInput
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderInput
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyOverview
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.TaskAssignmentView
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken

// Inbound ports of the setup context (ADR-0041), each implemented by the use case of the same name.
// The provider config decides where prompts go and feeds the AI transport's SSRF allowlist, so every
// mutation refuses any actor but `Actor.User` (`SetupResult.Forbidden`; #20 security review): the AI
// and MCP clients never change it, whatever path they find. Each mutation writes a changelog entry
// with the actor in the same transaction. Updates carry no version: only the user writes these
// settings, so there is no concurrent editor to lose a change to.

interface ListProvidersPort {
    fun execute(): SetupResult<List<ProviderConfig>>
}

/** Stores the key only through `SecretStorePort`; the result carries its secret id, never the key. */
interface CreateProviderPort {
    fun execute(
        kind: ProviderKind,
        input: ProviderInput,
        actor: Actor,
    ): SetupResult<ProviderConfig>
}

/** Replaces name and base URL; an absent key keeps the stored one, a given key replaces it. */
interface UpdateProviderPort {
    fun execute(
        id: ProviderId,
        input: ProviderInput,
        actor: Actor,
    ): SetupResult<ProviderConfig>
}

/**
 * Removes a provider, its key and its model capabilities in two steps (ADR-0039), operation
 * [ProviderId.DELETE_OPERATION], effect `ConfirmationEffect("ai_provider", <name>)`.
 * A provider with assigned tasks is [SetupResult.InUse] before any token is issued.
 */
interface DeleteProviderPort {
    fun execute(
        id: ProviderId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): SetupResult<Unit>
}

/**
 * Tests the connection by listing the provider's models through the guarded transport, and stores
 * them as detected capability profiles. A profile the user corrected is kept.
 */
interface RefreshProviderModelsPort {
    fun execute(
        id: ProviderId,
        actor: Actor,
    ): SetupResult<List<ModelCapabilityProfile>>
}

interface ListProviderModelsPort {
    fun execute(id: ProviderId): SetupResult<List<ModelCapabilityProfile>>
}

/** Stores what the user says a model can do (source USER); later refreshes keep it. */
interface CorrectModelCapabilitiesPort {
    fun execute(
        id: ProviderId,
        input: CapabilityInput,
        actor: Actor,
    ): SetupResult<ModelCapabilityProfile>
}

/** Every task, with its assignment and the capability warnings for it. */
interface ListTaskAssignmentsPort {
    fun execute(): SetupResult<List<TaskAssignmentView>>
}

/**
 * The dated privacy info of every provider kind with the "verify these terms yourself" disclaimer
 * (spec §3.2); entries older than the catalog allows are flagged stale. Read-only.
 */
interface ListProviderPrivacyInfoPort {
    fun execute(): ProviderPrivacyOverview
}

/** Assigns a model to a task; answers with the warnings, but a weaker model is still allowed. */
interface AssignTaskModelPort {
    fun execute(
        task: AiTask,
        provider: ProviderId,
        model: String,
        actor: Actor,
    ): SetupResult<TaskAssignmentView>
}
