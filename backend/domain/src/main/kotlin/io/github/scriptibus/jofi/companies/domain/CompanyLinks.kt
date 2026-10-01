// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef

/**
 * What other contexts link to a company and to the contacts that go with it, read in its delete's transaction before
 * the delete (ADR-0041, ADR-0048, ADR-0049): the [tasks] linked to the company itself and, per contact, the
 * [ContactLinks] a single contact delete would read. The cascade removes the contacts' links without a trace, so the
 * delete records them from this read.
 */
data class CompanyLinks(
    val tasks: List<EntityRef>,
    val contacts: Map<ContactId, ContactLinks>,
) {
    /** Tasks whose link the delete clears: those of the company and those of its contacts. */
    val taskCount: Int get() = tasks.size + contacts.values.sumOf { it.tasks.size }

    /** The contacts deleted with the company per application they were linked to, once each, in id order. */
    val contactsByApplication: Map<EntityRef, List<ContactId>> get() = contacts.groupedBy { it.applications }

    /** The contacts deleted with the company per interview they took part in, once each, in id order. */
    val contactsByInterview: Map<EntityRef, List<ContactId>> get() = contacts.groupedBy { it.interviews }

    private fun Map<ContactId, ContactLinks>.groupedBy(refs: (ContactLinks) -> List<EntityRef>) =
        entries
            .flatMap { (contact, links) -> refs(links).distinct().map { it to contact } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(compareBy { it.id })
}
