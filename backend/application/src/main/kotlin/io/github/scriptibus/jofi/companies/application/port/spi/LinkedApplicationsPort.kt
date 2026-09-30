// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application.port.spi

import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.util.UUID

/**
 * The applications a contact is linked to (ADR-0041), implemented by the applications context. The
 * contact delete reads them in its transaction **before** it deletes, counts them in the confirmation
 * effect and writes one changelog entry per application (ids only); `application_contact_contact_fk`
 * (`ON DELETE CASCADE`) then removes the links. Never throws.
 */
interface LinkedApplicationsPort {
    /** The applications linked to [contact], in id order; none for a contact without links. */
    fun linkedTo(contact: UUID): Linked

    /**
     * Outcome of [linkedTo]. A sealed class rather than an interface, since every interface in a port
     * package is a port (`*Port`, `SourceConventionsTest`).
     */
    @Suppress("AbstractClassCanBeInterface")
    sealed class Linked {
        /** The changelog references of the linked applications, built by the context that owns them. */
        data class Found(
            val applications: List<EntityRef>,
        ) : Linked()

        /** The links could not be read; the caller answers a storage failure and deletes nothing. */
        data object Unavailable : Linked()
    }
}
