// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application.port.inbound

import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanyView
import io.github.scriptibus.jofi.companies.domain.PreferenceInput
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken

// Inbound ports (ADR-0041): what the REST controllers and MCP tools may ask of the companies context.
// Each is implemented by exactly one use case of the same name (`CreateCompanyPort` by
// `CreateCompanyUseCase`, #88; `InboundPortRulesTest`). Mutations take the acting `Actor` and record it
// in the changelog (spec §13). `basedOnVersion` is the `Company.version` the caller last read: a stale
// one is `VersionConflict`, checked before anything else, even if the change would be a no-op. Use
// cases take their timestamps as `clock.instant().truncatedTo(ChronoUnit.MICROS)`, the precision of
// `timestamptz`, so a company read back equals the one that was stored.

interface CreateCompanyPort {
    fun execute(
        input: CompanyInput,
        actor: Actor,
    ): CompanyResult<CompanyView>
}

/**
 * Replaces **all** details with [input] (a PUT, not a patch): a field left out is cleared. MCP and AI
 * tools therefore read the company, change what they mean to change and send everything back with the
 * version they read. Unchanged details store nothing and write no changelog entry.
 */
interface UpdateCompanyPort {
    fun execute(
        id: CompanyId,
        input: CompanyInput,
        basedOnVersion: Long,
        actor: Actor,
    ): CompanyResult<CompanyView>
}

interface GetCompanyPort {
    fun execute(id: CompanyId): CompanyResult<CompanyView>
}

interface SearchCompaniesPort {
    fun execute(search: CompanySearch): CompanyResult<CompanyPage<CompanyView>>
}

/** Publishes `CompanyPreferenceChanged` once the change is stored; an unchanged preference is a no-op. */
interface SetCompanyPreferencePort {
    fun execute(
        id: CompanyId,
        input: PreferenceInput,
        basedOnVersion: Long,
        actor: Actor,
    ): CompanyResult<CompanyView>
}

/**
 * Deletes a company in two steps (ADR-0039): without [token] it answers [CompanyResult.Unconfirmed]
 * with a token bound to the operation [Company.DELETE_OPERATION], the company id and the effect
 * `ConfirmationEffect("company", <name>, mapOf("contacts" to <n>, "tasks" to <m>))`: its contacts are
 * deleted with it (ADR-0041), so the user sees "and n contacts", and m tasks linked to the company or those
 * contacts lose their link (ADR-0049). A rename or a changed count in between invalidates the token, other
 * edits do not. With the token, it deletes; for each contact deleted with it
 * (`CompanyRepositoryPort.findContactIds`, read in the same transaction) it appends a changelog entry of its
 * own (entity type `contact`, ids only, no personal data) and publishes `ContactDeleted`, and each such task
 * gets an entry naming the cleared link.
 * The actor is [requester]'s.
 * A company with applications is [CompanyResult.HasApplications] (their foreign key restricts the
 * delete), before any token is issued.
 */
interface DeleteCompanyPort {
    fun execute(
        id: CompanyId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): CompanyResult<Unit>
}
