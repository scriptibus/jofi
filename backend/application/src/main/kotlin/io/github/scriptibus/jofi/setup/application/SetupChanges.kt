// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.domain.CapabilityCheck
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.setup.domain.TaskAssignmentView
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Where prompts go is decided by the user alone (#20 security review): the AI, scanners and external
 * clients never change the provider setup, whatever path reaches a use case.
 */
internal fun Actor.mayChangeSetup(): Boolean = this == Actor.User

/** "Now" at the precision of `timestamptz` (ADR-0041). */
internal fun Clock.storedNow(): Instant = instant().truncatedTo(ChronoUnit.MICROS)

/** Appends one changelog entry; false if the store refused it (the caller then rolls back). */
internal fun ChangelogPort.record(
    entity: EntityRef,
    actor: Actor,
    at: Instant,
    description: String,
    fields: List<FieldChange> = emptyList(),
): Boolean = append(ChangelogEntry(entity, actor, at, ChangeSummary(description, fields))) is ChangelogResult.Success

/** A field change, or nothing when the value stayed the same. */
internal fun changeOf(
    field: String,
    before: Any?,
    after: Any?,
): FieldChange? =
    if (before?.toString() ==
        after?.toString()
    ) {
        null
    } else {
        FieldChange(field, before?.toString(), after?.toString())
    }

/** What changed between two versions of a provider; the key only as "set", never its value. */
internal fun providerChanges(
    before: ProviderConfig?,
    after: ProviderConfig?,
): List<FieldChange> =
    listOfNotNull(
        changeOf("displayName", before?.displayName, after?.displayName),
        changeOf("kind", before?.kind, after?.kind),
        changeOf("baseUrl", before?.baseUri, after?.baseUri),
        changeOf("apiKey", before?.apiKey?.let { KEY_SET }, after?.apiKey?.let { KEY_SET }),
    )

private const val KEY_SET = "set"

/**
 * [task] with its [assignment] to a model of [provider] and the capability warnings for it; null if
 * the capability store failed.
 */
internal fun assignmentView(
    task: AiTask,
    assignment: ModelAssignment?,
    provider: ProviderConfig?,
    profiles: ModelCapabilityPort,
    catalog: ModelCatalogPort,
): TaskAssignmentView? {
    val required = CapabilityCheck.requiredFor(task)
    if (assignment == null || provider == null) return TaskAssignmentView(task, null, required, emptyList())
    return capabilitiesOf(provider, assignment.model, profiles, catalog)?.let {
        TaskAssignmentView(task, assignment, required, CapabilityCheck.warningsFor(task, it))
    }
}

/**
 * What [model] of [provider] can do: the stored profile (detected or corrected) wins, else the
 * catalog's table of known models, as the AI gateway decides it (ADR-0043). Null if the store failed.
 */
internal fun capabilitiesOf(
    provider: ProviderConfig,
    model: ModelName,
    profiles: ModelCapabilityPort,
    catalog: ModelCatalogPort,
): ModelCapabilities? =
    when (val stored = profiles.find(provider.id, model)) {
        is SetupStoreResult.Success -> stored.value.capabilities
        SetupStoreResult.NotFound -> catalog.knownCapabilities(provider.kind, model)
        else -> null
    }
