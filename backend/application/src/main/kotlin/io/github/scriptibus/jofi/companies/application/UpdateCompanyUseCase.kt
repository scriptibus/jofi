// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.UpdateCompanyPort
import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanyView
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Replaces all details of a company. The version is checked first; unchanged details store nothing
 * and write no changelog entry. Read and write share one transaction, and the repository's version
 * check catches a change that slipped in between.
 */
class UpdateCompanyUseCase(
    private val companies: CompanyRepositoryPort,
    private val applications: ApplicationCountsPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : UpdateCompanyPort {
    override fun execute(
        id: CompanyId,
        input: CompanyInput,
        basedOnVersion: Long,
        actor: Actor,
    ): CompanyResult<CompanyView> =
        transactions.whenSuccessful {
            companies
                .findById(id)
                .toResult()
                .then { it.basedOn(basedOnVersion) }
                .then { current -> input.validate().toResult().then { edit(current, it, actor) } }
                .then(applications::viewOf)
        }

    private fun edit(
        current: Company,
        details: CompanyDetails,
        actor: Actor,
    ): CompanyResult<Company> {
        val edited = current.edit(details, clock.storedNow())
        if (edited == current) return CompanyResult.Success(current)
        return companies.update(edited).toResult().then {
            val recorded =
                changelog.record(
                    edited.id.toEntityRef(),
                    actor,
                    edited.updatedAt,
                    describe("Edited company", current.details, details),
                    detailChanges(current.details, details),
                )
            edited.onlyIf(recorded, "changelog")
        }
    }
}
