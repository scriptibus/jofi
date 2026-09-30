// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application.port

import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.PreferenceInput
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken

// Inbound ports: what the REST controllers and MCP tools may ask of the companies context. Each is
// implemented by the use case of the same name (`CreateCompanyPort` by `CreateCompanyUseCase`, #88).
// Mutations take the acting `Actor` and record it in the changelog (spec §13); `basedOnVersion` is the
// `Company.version` the caller last saw, so concurrent edits end as `VersionConflict`, never lost.

interface CreateCompanyPort {
    fun execute(
        input: CompanyInput,
        actor: Actor,
    ): CompanyResult<Company>
}

interface UpdateCompanyPort {
    fun execute(
        id: CompanyId,
        input: CompanyInput,
        basedOnVersion: Long,
        actor: Actor,
    ): CompanyResult<Company>
}

interface GetCompanyPort {
    fun execute(id: CompanyId): CompanyResult<Company>
}

interface SearchCompaniesPort {
    fun execute(search: CompanySearch): CompanyResult<CompanyPage>
}

/** Publishes `CompanyPreferenceChanged` once the change is stored; an unchanged preference is a no-op. */
interface SetCompanyPreferencePort {
    fun execute(
        id: CompanyId,
        input: PreferenceInput,
        basedOnVersion: Long,
        actor: Actor,
    ): CompanyResult<Company>
}

/**
 * Deletes a company in two steps (ADR-0039): without [token] it answers [CompanyResult.Unconfirmed]
 * with a token bound to the operation [Company.DELETE_OPERATION], the company id and the effect
 * `ConfirmationEffect("company", <name>, counts of what goes with it, e.g. "contacts")`; a rename in
 * between invalidates the token, other edits do not. With the token, it deletes. The actor is
 * [requester]'s. A company with applications is [CompanyResult.HasApplications], before any token.
 */
interface DeleteCompanyPort {
    fun execute(
        id: CompanyId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): CompanyResult<Unit>
}
