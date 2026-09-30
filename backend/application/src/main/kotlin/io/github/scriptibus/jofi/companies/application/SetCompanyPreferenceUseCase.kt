// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.SetCompanyPreferencePort
import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPreference
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanyView
import io.github.scriptibus.jofi.companies.domain.PreferenceInput
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Marks a company as favourite or blacklisted (with an optional reason) or clears the mark, and
 * announces the change as `CompanyPreferenceChanged` for scanners and knockouts. The reason is the
 * user's free text, so the changelog says only that it changed. An unchanged preference is a no-op.
 */
class SetCompanyPreferenceUseCase(
    private val companies: CompanyRepositoryPort,
    private val applications: ApplicationCountsPort,
    private val events: DomainEventPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : SetCompanyPreferencePort {
    override fun execute(
        id: CompanyId,
        input: PreferenceInput,
        basedOnVersion: Long,
        actor: Actor,
    ): CompanyResult<CompanyView> =
        transactions.whenSuccessful {
            companies
                .findById(id)
                .toResult()
                .then { it.basedOn(basedOnVersion) }
                .then { current -> input.validate().toResult().then { change(current, it, actor) } }
                .then(applications::viewOf)
        }

    private fun change(
        current: Company,
        preference: CompanyPreference,
        actor: Actor,
    ): CompanyResult<Company> {
        val update =
            current.changePreference(preference, actor, clock.storedNow())
                ?: return CompanyResult.Success(current)
        val changed = update.company
        val description =
            if (current.preference.reason == preference.reason) {
                "Changed company preference"
            } else {
                "Changed company preference; reason changed"
            }
        return companies
            .update(changed)
            .toResult()
            .then {
                val recorded =
                    changelog.record(
                        changed.id.toEntityRef(),
                        actor,
                        changed.updatedAt,
                        description,
                        listOfNotNull(changeOf("preference", current.preference.kind, preference.kind)),
                    )
                changed.onlyIf(recorded, "changelog")
            }.then { it.onlyIf(events.publish(update.event), "publish event") }
    }
}
