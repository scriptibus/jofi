// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SavedViewTest {
    private val now = Instant.parse("2026-09-30T10:00:00.123456Z")
    private val company = CompanyRef(UUID.randomUUID())
    private val everyFilter =
        ApplicationSearchInput(
            text = "Kotlin",
            company = company,
            contact = ContactRef(UUID.randomUUID()),
            statuses = setOf(ApplicationStatus.APPLIED, ApplicationStatus.OFFER),
            unread = true,
            languages = listOf("DE-ch"),
            sourceKinds = setOf(SourceKind.URL),
            createdFrom = now.minusSeconds(60),
            createdTo = now,
            updatedFrom = now.minusSeconds(60),
            updatedTo = now,
            wantMin = BigDecimal("3.5"),
            wantMax = BigDecimal("5"),
            fitMin = BigDecimal("0"),
            fitMax = BigDecimal("4.0"),
            sort = ApplicationSortKey.DEADLINE,
            direction = SortDirection.DESCENDING,
        )

    private fun details(input: SavedViewInput): SavedViewDetails =
        input.validate().shouldBeInstanceOf<SavedViewValidation.Valid>().details

    private fun violations(input: SavedViewInput): List<SavedViewViolation> =
        input.validate().shouldBeInstanceOf<SavedViewValidation.Invalid>().violations

    @Test
    fun `a view keeps exactly the filters of the list's search, without paging`() {
        val searchFields = fieldsOf(ApplicationSearch::class.java) - setOf("page", "size")

        fieldsOf(SavedViewFilter::class.java) shouldBe searchFields
    }

    @Test
    fun `a view's filter is what the list's validation makes of it, page and size aside`() {
        val saved = details(SavedViewInput(" Åctive views ", everyFilter.copy(page = -1, size = 0)))

        saved.name shouldBe "Åctive views"
        val search = everyFilter.validate().shouldBeInstanceOf<SearchValidation.Valid>().search
        saved.filter.toSearch() shouldBe search
        saved.filter.toSearch(page = 2, size = 10) shouldBe search.copy(page = 2, size = 10)
        SavedViewFilter.of(search) shouldBe saved.filter
        saved.filter.languages shouldBe setOf(LanguageTag("de-CH"))
    }

    @Test
    fun `an empty filter lists everything`() {
        details(SavedViewInput("All", ApplicationSearchInput())).filter shouldBe SavedViewFilter()
    }

    @Test
    fun `every violation of the name and the filter is reported at once`() {
        violations(
            SavedViewInput(
                " ",
                ApplicationSearchInput(text = "a\u0000b", languages = listOf("no tag"), wantMin = BigDecimal("6")),
            ),
        ) shouldContainExactly
            listOf(
                SavedViewViolation(SavedViewField.Name, ApplicationProblem.REQUIRED),
                SavedViewViolation(SavedViewField.Filter(SearchField.TEXT), ApplicationProblem.INVALID_CHARACTER),
                SavedViewViolation(SavedViewField.Filter(SearchField.LANGUAGES), ApplicationProblem.INVALID_LANGUAGE),
                SavedViewViolation(SavedViewField.Filter(SearchField.WANT_MIN), ApplicationProblem.OUT_OF_RANGE),
            )
        violations(SavedViewInput("x".repeat(SavedViewDetails.MAX_NAME_LENGTH + 1), everyFilter)) shouldBe
            listOf(SavedViewViolation(SavedViewField.Name, ApplicationProblem.TOO_LONG))
        violations(SavedViewInput("a\u0000b", everyFilter)) shouldBe
            listOf(SavedViewViolation(SavedViewField.Name, ApplicationProblem.INVALID_CHARACTER))
    }

    @Test
    fun `a name may be exactly as long as the limit`() {
        details(SavedViewInput("ü".repeat(SavedViewDetails.MAX_NAME_LENGTH), everyFilter)).name.length shouldBe
            SavedViewDetails.MAX_NAME_LENGTH
    }

    @Test
    fun `a filter the list would refuse cannot be built`() {
        shouldThrow<IllegalArgumentException> { SavedViewFilter(text = " ") }
        shouldThrow<IllegalArgumentException> { SavedViewDetails(" padded", SavedViewFilter()) }
    }

    @Test
    fun `a stored filter the rules still accept comes back as it was`() {
        val restored = SavedViewFilter.restore(everyFilter)

        restored.adjusted shouldBe false
        restored.filter shouldBe details(SavedViewInput("v", everyFilter)).filter
        SavedViewFilter.restore(everyFilter, dropped = true).adjusted shouldBe true
    }

    @Test
    fun `what today's rules refuse is left out, ranges as a whole, and the view is adjusted`() {
        val stored =
            everyFilter.copy(
                text = "x".repeat(ApplicationSearch.MAX_TEXT_LENGTH + 1),
                languages = listOf("de", "not a tag"),
                createdFrom = now,
                createdTo = now.minusSeconds(1),
                updatedFrom = now,
                updatedTo = now.minusSeconds(1),
                wantMin = BigDecimal("4.25"),
                fitMax = BigDecimal("9"),
            )

        val restored = SavedViewFilter.restore(stored)

        restored.adjusted shouldBe true
        restored.filter shouldBe
            SavedViewFilter(
                company = company,
                contact = everyFilter.contact,
                statuses = everyFilter.statuses,
                unread = true,
                sourceKinds = everyFilter.sourceKinds,
                order = ApplicationOrder(ApplicationSortKey.DEADLINE, SortDirection.DESCENDING),
            )
    }

    @Test
    fun `an edit is a new version, an unchanged one keeps the view unless it was adjusted`() {
        val view = SavedView.create(SavedViewId(UUID.randomUUID()), details(SavedViewInput("Mine", everyFilter)), now)
        val later = now.plusSeconds(1)

        view.edit(view.details, later) shouldBeSameInstanceAs view
        val renamed = view.edit(view.details.copy(name = "Ours"), later)
        renamed.version shouldBe 1
        renamed.updatedAt shouldBe later
        view.copy(adjusted = true).edit(view.details, later) shouldBe view.copy(version = 1, updatedAt = later)
    }

    @Test
    fun `names are the same ignoring case, in every locale`() {
        val view =
            SavedView.create(
                SavedViewId(UUID.randomUUID()),
                details(SavedViewInput("İstanbul I", everyFilter)),
                now,
            )

        view.isNamed("İSTANBUL i") shouldBe true
        view.isNamed("Ankara") shouldBe false
    }

    @Test
    fun `invariants and entity type`() {
        val id = SavedViewId(UUID.randomUUID())
        val details = details(SavedViewInput("Mine", everyFilter))

        shouldThrow<IllegalArgumentException> { SavedView(id, details, -1, now, now) }
        shouldThrow<IllegalArgumentException> { SavedView(id, details, 0, now, now.minusSeconds(1)) }
        id.toEntityRef().type shouldBe "saved_view"
        id.toEntityRef().id shouldBe id.value.toString()
    }

    @Test
    fun `nothing prints the name or the search text`() {
        val view =
            SavedView.create(
                SavedViewId(UUID.randomUUID()),
                details(SavedViewInput("Secret name", everyFilter)),
                now,
            )

        view.toString() shouldNotContain "Secret"
        view.toString() shouldNotContain "Kotlin"
        SavedViewInput("Secret name", everyFilter).toString() shouldNotContain "Secret"
    }

    private fun fieldsOf(type: Class<*>): Set<String> =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
}
