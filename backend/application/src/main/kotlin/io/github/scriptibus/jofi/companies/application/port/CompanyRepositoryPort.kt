// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application.port

import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult

/**
 * Stores companies (table `company`; implemented with the use cases in #88). The use case that
 * changes a company appends its changelog entry in the same transaction (`TransactionPort`).
 * Implementations never throw.
 */
interface CompanyRepositoryPort {
    fun add(company: Company): CompanyStoreResult<Unit>

    /**
     * Replaces the stored company if its version is exactly one below [company]'s (see
     * [Company.edit]); [CompanyStoreResult.VersionConflict] if someone else changed it meanwhile.
     */
    fun update(company: Company): CompanyStoreResult<Unit>

    fun findById(id: CompanyId): CompanyStoreResult<Company>

    fun search(search: CompanySearch): CompanyStoreResult<CompanyPage>

    /**
     * Deletes the company. [proof] is what the confirmation gate returned (ADR-0039): the adapter
     * answers [CompanyStoreResult.NotConfirmed] unless `proof.covers(Company.DELETE_OPERATION,
     * id.value.toString())`, and [CompanyStoreResult.HasApplications] while applications refer to it.
     */
    fun delete(
        id: CompanyId,
        proof: ConfirmationResult.Confirmed,
    ): CompanyStoreResult<Unit>
}
