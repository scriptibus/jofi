// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application.port.spi

import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.util.UUID

/**
 * The tasks linked to companies and contacts (ADR-0049, #168), implemented by the tasks context, which depends on
 * this named interface, never the reverse. The company and contact deletes read them in their transaction **before**
 * they delete, count them in the confirmation effect and write one changelog entry per task (ids only); the foreign
 * keys `task_company_fk` and `task_contact_fk` (`ON DELETE SET NULL`) then clear the links. Every task counts,
 * suggestions too, since each row loses its link. Never throws.
 */
interface TaskLinksPort {
    /** The tasks linked to one of [companies] or one of [contacts], each list ordered by target, then task id. */
    fun linkedTo(
        companies: Set<UUID>,
        contacts: Set<UUID>,
    ): Links

    /** A task ([task], its changelog reference built by the tasks context) linked to [target]. */
    data class LinkedTask(
        val target: UUID,
        val task: EntityRef,
    )

    /**
     * Outcome of [linkedTo]. A sealed class rather than an interface, since every interface in a port package is a
     * port (`*Port`, `SourceConventionsTest`).
     */
    @Suppress("AbstractClassCanBeInterface")
    sealed class Links {
        /** The tasks linked to the companies and those linked to the contacts. */
        data class Found(
            val toCompanies: List<LinkedTask>,
            val toContacts: List<LinkedTask>,
        ) : Links() {
            /** How many tasks lose their link. */
            val count: Int get() = toCompanies.size + toContacts.size
        }

        /** The links could not be read; the caller answers a storage failure and deletes nothing. */
        data object Unavailable : Links()
    }
}
