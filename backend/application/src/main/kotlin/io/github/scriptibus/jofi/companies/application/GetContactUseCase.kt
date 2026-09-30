// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.ContactRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.GetContactPort
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactResult

/** One contact with its channels. */
class GetContactUseCase(
    private val contacts: ContactRepositoryPort,
) : GetContactPort {
    override fun execute(id: ContactId): ContactResult<Contact> = contacts.findById(id).toResult()
}
