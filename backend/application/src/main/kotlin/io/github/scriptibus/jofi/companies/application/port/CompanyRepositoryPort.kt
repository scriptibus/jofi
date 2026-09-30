// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application.port

import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult

/**
 * Stores companies (table `company`, `CompanyRepository`). The use case that
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

    fun search(search: CompanySearch): CompanyStoreResult<CompanyPage<Company>>

    /**
     * The ids of [id]'s contacts, in id order: [delete] removes them with the company (`ON DELETE
     * CASCADE`), so the delete counts them in its confirmation effect and announces each as
     * `ContactDeleted`. The repository that runs the cascade reports it, which keeps the contact
     * repository (#89) out of the company delete.
     */
    fun findContactIds(id: CompanyId): CompanyStoreResult<List<ContactId>>

    /**
     * Deletes the company and, by `ON DELETE CASCADE`, its contacts. [proof] is what the confirmation
     * gate returned (ADR-0039): the adapter answers [CompanyStoreResult.NotConfirmed] unless
     * `proof.covers(Company.DELETE_OPERATION, id.value.toString())`. While applications refer to the
     * company, their `ON DELETE RESTRICT` foreign key rejects the delete; the adapter maps that
     * violation **by constraint name** (`application_company_fk`, ADR-0041), never by SQL state alone,
     * to [CompanyStoreResult.HasApplications]. Any other failure is a `StorageFailure`.
     */
    fun delete(
        id: CompanyId,
        proof: ConfirmationResult.Confirmed,
    ): CompanyStoreResult<Unit>
}
