// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef

/**
 * What other contexts link to a contact, read in its delete's transaction before the delete (ADR-0041, ADR-0049):
 * the [applications] it is linked to, the [interviews] it takes part in and the [tasks] linked to it, each as the
 * changelog reference the owning context built. The delete counts them and records one entry per item.
 */
data class ContactLinks(
    val applications: List<EntityRef>,
    val interviews: List<EntityRef>,
    val tasks: List<EntityRef>,
)
