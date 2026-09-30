// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.applications.domain.ApplicationSettingsInput
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewField
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.applications.domain.SavedViewValidation
import io.github.scriptibus.jofi.applications.domain.SavedViewViolation
import io.github.scriptibus.jofi.applications.domain.SearchField
import io.github.scriptibus.jofi.applications.domain.SearchValidation
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.util.UUID

class SavedViewDtosTest {
    private val now = Instant.parse("2026-09-30T10:00:00.123456Z")
    private val everyParameter =
        ApplicationListQuery(
            search = "Kotlin",
            companyId = UUID.randomUUID(),
            contactId = UUID.randomUUID(),
            status = listOf(PipelineStatus.APPLIED, PipelineStatus.OFFER),
            unread = false,
            language = listOf("de-CH"),
            sourceKind = listOf(PostingSourceKind.URL),
            createdFrom = now.minusSeconds(60),
            createdTo = now,
            updatedFrom = now.minusSeconds(60),
            updatedTo = now,
            wantMin = BigDecimal("3.5"),
            wantMax = BigDecimal("5.0"),
            fitMin = BigDecimal("0.0"),
            fitMax = BigDecimal("4.5"),
            sort = ApplicationListSort.DEADLINE,
            direction = ApplicationListDirection.DESCENDING,
        )

    @Test
    fun `a view's filter goes out as exactly the list's query parameters, which the list accepts again`() {
        val details =
            SavedViewRequest(
                "Mine",
                everyParameter,
            ).toInput().validate().shouldBeInstanceOf<SavedViewValidation.Valid>().details
        val view = SavedView.create(SavedViewId(UUID.randomUUID()), details, now)

        val response = SavedViewResponse.from(view)

        response.filter shouldBe everyParameter
        response.name shouldBe "Mine"
        response.adjusted shouldBe false
        response.filter.toInput(0, ApplicationSearch.DEFAULT_SIZE).validate() shouldBe
            SearchValidation.Valid(details.filter.toSearch())
        SavedViewListResponse.from(listOf(view)).views shouldBe listOf(response)
        response.toString() shouldNotContain "Mine"
        SavedViewRequest("Mine", everyParameter).toString() shouldNotContain "Kotlin"
    }

    @Test
    fun `an empty filter goes out without parameters`() {
        val details =
            SavedViewRequest("All")
                .toInput()
                .validate()
                .shouldBeInstanceOf<SavedViewValidation.Valid>()
                .details

        ApplicationListQuery.from(details.filter) shouldBe ApplicationListQuery()
    }

    @Test
    fun `view violations name the request field, filter parameters under filter`() {
        val problem =
            ApplicationProblems.of(
                ApplicationResult.InvalidView(
                    listOf(
                        SavedViewViolation(SavedViewField.Name, ApplicationProblem.TAKEN),
                        SavedViewViolation(
                            SavedViewField.Filter(SearchField.WANT_MAX),
                            ApplicationProblem.OUT_OF_RANGE,
                        ),
                        SavedViewViolation(SavedViewField.Filter(SearchField.TEXT), ApplicationProblem.TOO_LONG),
                    ),
                ),
            )

        problem.statusCode shouldBe HttpStatus.BAD_REQUEST
        problem.body.type shouldBe URI.create(ApplicationProblems.INVALID_VIEW)
        problem.body.shouldBeInstanceOf<ValidationProblem>().violations shouldBe
            listOf(
                FieldViolation("name", "TAKEN"),
                FieldViolation("filter.wantMax", "OUT_OF_RANGE"),
                FieldViolation("filter.search", "TOO_LONG"),
            )
        ApplicationProblems.of(ApplicationResult.SavedViewNotFound).body.type shouldBe
            URI.create(ApplicationProblems.SAVED_VIEW_NOT_FOUND)
    }

    @Test
    fun `settings map both ways`() {
        ApplicationSettingsRequest(10, 7, 0).toInput() shouldBe ApplicationSettingsInput(10, 7)
        ApplicationSettingsResponse.from(ApplicationSettings.DEFAULT) shouldBe
            ApplicationSettingsResponse(14, 14, 0, null)
        ApplicationSettingsResponse.from(ApplicationSettings(ApplicationSettings.Values(26, 7), 1, now)) shouldBe
            ApplicationSettingsResponse(26, 7, 1, now)
    }
}
