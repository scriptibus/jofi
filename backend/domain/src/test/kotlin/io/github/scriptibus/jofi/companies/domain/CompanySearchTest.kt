// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CompanySearchTest {
    @Test
    fun `a search defaults to the first page of every company`() {
        CompanySearch() shouldBe
            CompanySearch(text = null, preference = null, page = 0, size = CompanySearch.DEFAULT_SIZE)
    }

    @Test
    fun `a search has a non-blank text, a page from zero and a bounded size`() {
        CompanySearch("acme", PreferenceKind.FAVOURITE, page = 2, size = CompanySearch.MAX_SIZE).text shouldBe "acme"
        shouldThrow<IllegalArgumentException> { CompanySearch(text = " ") }
        shouldThrow<IllegalArgumentException> { CompanySearch(page = -1) }
        shouldThrow<IllegalArgumentException> { CompanySearch(size = 0) }
        shouldThrow<IllegalArgumentException> { CompanySearch(size = CompanySearch.MAX_SIZE + 1) }
    }

    @Test
    fun `query parameters become a search, or nothing when the page is out of range`() {
        CompanySearch.of("  acme ", PreferenceKind.BLACKLISTED, 1, 20) shouldBe
            CompanySearch("acme", PreferenceKind.BLACKLISTED, 1, 20)
        CompanySearch.of(" ", null, 0, CompanySearch.DEFAULT_SIZE) shouldBe CompanySearch()
        CompanySearch.of(null, null, -1, 20) shouldBe null
        CompanySearch.of(null, null, 0, 0) shouldBe null
        CompanySearch.of(null, null, 0, CompanySearch.MAX_SIZE + 1) shouldBe null
    }

    @Test
    fun `a page never counts fewer matches than it holds`() {
        val company =
            Company.create(CompanyId(UUID.randomUUID()), CompanyDetails("ACME"), Instant.parse("2026-09-30T08:00:00Z"))

        CompanyPage(listOf(company), total = 7).total shouldBe 7
        shouldThrow<IllegalArgumentException> { CompanyPage(listOf(company), total = 0) }
    }
}
