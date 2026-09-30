// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application.port.spi

import java.util.UUID

// What the companies context needs from the applications context (ADR-0041, "References across
// contexts"). Dependencies run applications -> companies only: companies declares these ports, the
// applications context implements them. This package is the Spring Modulith named interface `spi` of
// `companies` (its `ModuleMetadata` lives in bootstrap), so its ports name companies by UUID and nest
// their own result types: the applications context sees nothing else of companies through it.

/** How many applications refer to each company, for `CompanyView.applicationCount`. Never throws. */
interface ApplicationCountsPort {
    /** The number of applications of each of [companies]; a company without applications may be absent. */
    fun countByCompany(companies: Set<UUID>): Counts

    /**
     * Outcome of [countByCompany]. A sealed class rather than an interface, since every interface in a
     * port package is a port (`*Port`, `SourceConventionsTest`).
     */
    @Suppress("AbstractClassCanBeInterface")
    sealed class Counts {
        data class Counted(
            val byCompany: Map<UUID, Int>,
        ) : Counts() {
            /** The count of [company], 0 if it has no applications. */
            fun of(company: UUID): Int = byCompany[company] ?: 0
        }

        /** The applications could not be counted; the caller answers a storage failure. */
        data object Unavailable : Counts()
    }
}
