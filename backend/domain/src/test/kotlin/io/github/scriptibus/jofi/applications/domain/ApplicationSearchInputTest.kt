// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ApplicationSearchInputTest {
    private val monday = Instant.parse("2026-09-28T00:00:00Z")
    private val friday = Instant.parse("2026-10-02T00:00:00Z")
    private val company = CompanyRef(UUID.randomUUID())
    private val contact = ContactRef(UUID.randomUUID())
    private val statuses = setOf(ApplicationStatus.APPLIED, ApplicationStatus.OFFER)

    private fun valid(input: ApplicationSearchInput): ApplicationSearch =
        input.validate().shouldBeInstanceOf<SearchValidation.Valid>().search

    private fun violations(input: ApplicationSearchInput): List<SearchViolation> =
        input.validate().shouldBeInstanceOf<SearchValidation.Invalid>().violations

    @Test
    fun `no parameters search everything, most recently updated first, on the first page`() {
        valid(ApplicationSearchInput()) shouldBe ApplicationSearch()
    }

    @Test
    fun `every filter is carried over`() {
        val input =
            ApplicationSearchInput(
                company = company,
                contact = contact,
                statuses = statuses,
                unread = false,
                sourceKinds = setOf(SourceKind.URL),
                createdFrom = monday,
                updatedTo = friday,
                wantMin = BigDecimal("3.5"),
                fitMax = BigDecimal("5"),
                page = 3,
                size = ApplicationSearch.MAX_SIZE,
            )

        valid(input) shouldBe
            ApplicationSearch(
                company = company,
                contact = contact,
                statuses = statuses,
                unread = false,
                sourceKinds = setOf(SourceKind.URL),
                created = TimeRange(monday, null),
                updated = TimeRange(null, friday),
                wantScore = ScoreRange(Score(35), null),
                fitScore = ScoreRange(null, Score(50)),
                page = 3,
                size = ApplicationSearch.MAX_SIZE,
            )
    }

    @Test
    fun `the text is NFC-normalised and trimmed, a blank one searches everything`() {
        valid(ApplicationSearchInput(text = "  Entwickler München ")).text shouldBe "Entwickler München"
        valid(ApplicationSearchInput(text = " \t ")).text shouldBe null
    }

    @Test
    fun `a text longer than a title or with U+0000 is refused`() {
        val longest = "x".repeat(ApplicationSearch.MAX_TEXT_LENGTH)
        valid(ApplicationSearchInput(text = longest)).text shouldBe longest
        violations(ApplicationSearchInput(text = longest + "x")) shouldContainExactly
            listOf(SearchViolation(SearchField.TEXT, ApplicationProblem.TOO_LONG))
        violations(ApplicationSearchInput(text = "a\u0000b")) shouldContainExactly
            listOf(SearchViolation(SearchField.TEXT, ApplicationProblem.INVALID_CHARACTER))
    }

    @Test
    fun `languages are canonical BCP 47 tags, at most twenty`() {
        valid(ApplicationSearchInput(languages = listOf(" DE-ch ", "en", "", "de-CH"))).languages shouldBe
            setOf(LanguageTag("de-CH"), LanguageTag("en"))
        violations(ApplicationSearchInput(languages = listOf("de", "german!"))) shouldContainExactly
            listOf(SearchViolation(SearchField.LANGUAGES, ApplicationProblem.INVALID_LANGUAGE))
        val twenty = (0 until ApplicationSearch.MAX_LANGUAGES).map { "de-x$it" }
        valid(ApplicationSearchInput(languages = twenty)).languages.size shouldBe ApplicationSearch.MAX_LANGUAGES
        violations(ApplicationSearchInput(languages = twenty + "en")) shouldContainExactly
            listOf(SearchViolation(SearchField.LANGUAGES, ApplicationProblem.TOO_MANY))
    }

    @Test
    fun `a time range must end after it starts, named by its end`() {
        valid(ApplicationSearchInput(createdFrom = monday, createdTo = friday)).created shouldBe
            TimeRange(monday, friday)
        violations(
            ApplicationSearchInput(createdFrom = friday, createdTo = monday, updatedFrom = monday, updatedTo = monday),
        ) shouldContainExactly
            listOf(
                SearchViolation(SearchField.CREATED_TO, ApplicationProblem.OUT_OF_RANGE),
                SearchViolation(SearchField.UPDATED_TO, ApplicationProblem.OUT_OF_RANGE),
            )
    }

    @Test
    fun `scores are 0 to 5 with one decimal, the minimum not above the maximum`() {
        valid(ApplicationSearchInput(wantMin = BigDecimal("0"), wantMax = BigDecimal("5.00"))).wantScore shouldBe
            ScoreRange(Score(0), Score(50))
        valid(ApplicationSearchInput(fitMin = BigDecimal("2.5"), fitMax = BigDecimal("2.5"))).fitScore shouldBe
            ScoreRange(Score(25), Score(25))
        violations(
            ApplicationSearchInput(
                wantMin = BigDecimal("-0.1"),
                wantMax = BigDecimal("5.1"),
                fitMin = BigDecimal("4.25"),
            ),
        ) shouldContainExactly
            listOf(
                SearchViolation(SearchField.WANT_MIN, ApplicationProblem.OUT_OF_RANGE),
                SearchViolation(SearchField.WANT_MAX, ApplicationProblem.OUT_OF_RANGE),
                SearchViolation(SearchField.FIT_MIN, ApplicationProblem.TOO_PRECISE),
            )
        violations(ApplicationSearchInput(fitMin = BigDecimal("4"), fitMax = BigDecimal("3.9"))) shouldContainExactly
            listOf(SearchViolation(SearchField.FIT_MAX, ApplicationProblem.OUT_OF_RANGE))
    }

    @Test
    fun `paging out of range is named, together with every other problem`() {
        violations(ApplicationSearchInput(text = "a\u0000", page = -1, size = 0)) shouldContainExactly
            listOf(
                SearchViolation(SearchField.TEXT, ApplicationProblem.INVALID_CHARACTER),
                SearchViolation(SearchField.PAGE, ApplicationProblem.OUT_OF_RANGE),
                SearchViolation(SearchField.SIZE, ApplicationProblem.OUT_OF_RANGE),
            )
        violations(ApplicationSearchInput(size = ApplicationSearch.MAX_SIZE + 1)) shouldContainExactly
            listOf(SearchViolation(SearchField.SIZE, ApplicationProblem.OUT_OF_RANGE))
    }

    @Test
    fun `a sort takes its own default direction, a direction alone sorts by update`() {
        valid(ApplicationSearchInput(sort = ApplicationSortKey.TITLE)).order shouldBe
            ApplicationOrder(ApplicationSortKey.TITLE, SortDirection.ASCENDING)
        valid(ApplicationSearchInput(sort = ApplicationSortKey.CREATED)).order shouldBe
            ApplicationOrder(ApplicationSortKey.CREATED, SortDirection.DESCENDING)
        valid(
            ApplicationSearchInput(sort = ApplicationSortKey.DEADLINE, direction = SortDirection.DESCENDING),
        ).order shouldBe
            ApplicationOrder(ApplicationSortKey.DEADLINE, SortDirection.DESCENDING)
        valid(ApplicationSearchInput(direction = SortDirection.ASCENDING)).order shouldBe
            ApplicationOrder(ApplicationSortKey.UPDATED, SortDirection.ASCENDING)
    }

    @Test
    fun `the search text never shows in logs`() {
        ApplicationSearchInput(text = "Secret Title").toString() shouldNotContain "Secret"
        ApplicationSearch(text = "Secret Title").toString() shouldNotContain "Secret"
    }

    @Test
    fun `a search, its ranges and its paging keep their invariants`() {
        shouldThrow<IllegalArgumentException> { ApplicationSearch(text = " ") }
        shouldThrow<IllegalArgumentException> { ApplicationSearch(page = -1) }
        shouldThrow<IllegalArgumentException> { ApplicationSearch(size = 0) }
        shouldThrow<IllegalArgumentException> { TimeRange(null, null) }
        shouldThrow<IllegalArgumentException> { TimeRange(friday, monday) }
        shouldThrow<IllegalArgumentException> { ScoreRange(null, null) }
        shouldThrow<IllegalArgumentException> { ScoreRange(Score(2), Score(1)) }
    }
}
