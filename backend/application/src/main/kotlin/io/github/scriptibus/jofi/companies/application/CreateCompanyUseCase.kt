// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.CreateCompanyPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanyView
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock
import java.util.UUID

/** Adds a company (spec §5); the company and its changelog entry are stored together. */
class CreateCompanyUseCase(
    private val companies: CompanyRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : CreateCompanyPort {
    override fun execute(
        input: CompanyInput,
        actor: Actor,
    ): CompanyResult<CompanyView> =
        input.validate().toResult().then { details ->
            val company = Company.create(CompanyId(UUID.randomUUID()), details, clock.storedNow())
            transactions.whenSuccessful {
                companies.add(company).toResult().then {
                    val recorded =
                        changelog.record(
                            company.id.toEntityRef(),
                            actor,
                            company.createdAt,
                            describe("Created company", null, details),
                            detailChanges(null, details),
                        )
                    // A new company has no applications yet.
                    CompanyView(company, 0).onlyIf(recorded, "changelog")
                }
            }
        }
}
