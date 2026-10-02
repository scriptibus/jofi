// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.CreateApplicationUseCase
import io.github.scriptibus.jofi.applications.application.UpdateApplicationUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.EmploymentType
import io.github.scriptibus.jofi.applications.domain.EstimateConfidence
import io.github.scriptibus.jofi.applications.domain.HowApplied
import io.github.scriptibus.jofi.applications.domain.PayPeriod
import io.github.scriptibus.jofi.applications.domain.PaySource
import io.github.scriptibus.jofi.applications.domain.Seniority
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolTestPorts
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `create_application` and `update_application` over the real use cases with a mocked repository. */
class ApplicationWriteToolsTest {
    private val applications = mockk<ApplicationRepositoryPort>()
    private val changelog = ToolTestPorts.RecordingChangelog()
    private val create =
        CreateApplicationTool(
            CreateApplicationUseCase(applications, changelog, ToolTestPorts.transactions, ToolTestPorts.clock),
        )
    private val update =
        UpdateApplicationTool(
            UpdateApplicationUseCase(applications, changelog, ToolTestPorts.transactions, ToolTestPorts.clock),
        )

    private val company = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val id = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val stored =
        Application.create(
            ApplicationId(id),
            ApplicationDetails("Backend Engineer", CompanyRef(company), location = "Berlin"),
            Instant.parse("2026-09-30T08:00:00Z"),
        )

    @Test
    fun `create_application stores every argument as validated details, logged with the caller as actor`() {
        val added = slot<Application>()
        every { applications.add(capture(added), any()) } returns ApplicationStoreResult.Success(Unit)

        val answer = create.call(call(*everyArgument()))

        val details = added.captured.details
        details.title shouldBe "Kotlin Engineer"
        details.location shouldBe "Berlin"
        details.remoteShare?.percent shouldBe 60
        details.employmentType shouldBe EmploymentType.FULL_TIME
        details.seniority shouldBe Seniority.SENIOR
        details.deadline shouldBe LocalDate.of(2026, 11, 1)
        details.howApplied shouldBe HowApplied.PORTAL
        details.portalNotes shouldBe "ref 42"
        details.payBand?.min shouldBe BigDecimal("70000.00")
        details.payBand?.currency?.value shouldBe "EUR"
        details.payBand?.source shouldBe PaySource.Estimated("levels.fyi", EstimateConfidence.LOW)
        details.languageAndTone.applicationLanguage?.value shouldBe "de-CH"
        details.offer?.salary?.amount shouldBe BigDecimal("80000.00")
        details.offer?.noticePeriod shouldBe "3 months"
        changelog.entries.single().actor shouldBe Actor.Ai
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<ApplicationDetailResult>()
        result.version shouldBe 0
        result.posting shouldBe Untrusted(PostingFields("Kotlin Engineer", "Berlin"))
        result.notes.content.portalNotes shouldBe "ref 42"
        result.notes.content.payEstimateBasis shouldBe "levels.fyi"
        result.notes.content.offer shouldBe OfferTexts(null, null, "3 months")
        result.languageAndTone.content.applicationLanguage shouldBe "de-CH"
        result.payBand?.estimateConfidence shouldBe EstimateConfidence.LOW
        result.offer?.salary?.period shouldBe PayPeriod.YEAR
    }

    @Test
    fun `explicit nulls for the optional arguments mean not set`() {
        val added = slot<Application>()
        every { applications.add(capture(added), any()) } returns ApplicationStoreResult.Success(Unit)

        val nulls =
            listOf(
                "remoteSharePercent",
                "employmentType",
                "seniority",
                "deadline",
                "howApplied",
                "payBand",
                "languageAndTone",
                "offer",
                "notes",
            ).map { it to null }.toTypedArray()
        create
            .call(call("posting" to mapOf("title" to "T", "location" to null), "companyId" to "$company", *nulls))
            .shouldBeInstanceOf<ToolAnswer.Result>()

        added.captured.details shouldBe ApplicationDetails("T", CompanyRef(company))
    }

    @Test
    fun `create_application without a company id or with a company that does not exist`() {
        shouldThrow<InvalidToolArgument> { create.call(call("posting" to mapOf("title" to "T"))) }
            .argument shouldBe "companyId"
        every { applications.add(any(), any()) } returns ApplicationStoreResult.CompanyNotFound

        create.call(call("posting" to mapOf("title" to "T"), "companyId" to "$company")) shouldBe
            ToolAnswer.Error(
                "invalid-arguments",
                "The arguments are invalid.",
                listOf(ArgumentProblem("companyId", "not-found")),
            )
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `invalid details name the arguments they belong to and store nothing`() {
        val answer =
            create.call(
                call(
                    "posting" to mapOf("title" to " "),
                    "companyId" to "$company",
                    "remoteSharePercent" to 101,
                    "payBand" to mapOf("min" to 5, "currency" to "euro", "period" to "YEAR", "source" to "ESTIMATED"),
                    "offer" to mapOf("vacationDays" to 999),
                ),
            )

        answer.shouldBeInstanceOf<ToolAnswer.Error>().problems.map { "${it.argument}:${it.problem}" } shouldBe
            listOf(
                "posting.title:required",
                "remoteSharePercent:out-of-range",
                "payBand.currency:invalid-currency",
                "notes.payEstimateBasis:required",
                "payBand.estimateConfidence:required",
                "offer.vacationDays:out-of-range",
            )
        verify(exactly = 0) { applications.add(any(), any<StatusChange>()) }
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `the offer's typed details and its texts come from their two places`() {
        val added = slot<Application>()
        every { applications.add(capture(added), any()) } returns ApplicationStoreResult.Success(Unit)

        val texts = "notes" to mapOf("offer" to mapOf("bonus" to "10 %"))
        create.call(call("posting" to mapOf("title" to "T"), "companyId" to "$company", texts))
        added.captured.details.offer
            ?.bonus shouldBe "10 %"

        val typed = "offer" to mapOf("vacationDays" to 30)
        create.call(call("posting" to mapOf("title" to "T"), "companyId" to "$company", typed, texts))
        added.captured.details.offer
            ?.vacationDays shouldBe 30
        added.captured.details.offer
            ?.bonus shouldBe "10 %"
    }

    @Test
    fun `update_application replaces the details based on the given version, logged with the caller`() {
        val edited = slot<Application>()
        every { applications.findById(any()) } returns ApplicationStoreResult.Success(stored)
        every { applications.updateDetails(capture(edited)) } returns ApplicationStoreResult.Success(Unit)

        val answer = update.call(call("id" to "$id", "version" to 0, "companyId" to "$company", *staff()))

        edited.captured.details shouldBe ApplicationDetails("Staff", CompanyRef(company))
        edited.captured.version shouldBe 1
        changelog.entries.single().actor shouldBe Actor.Ai
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<ApplicationDetailResult>()
        result.version shouldBe 1
    }

    @Test
    fun `update_application answers a stale version and an unknown application without storing`() {
        every { applications.findById(ApplicationId(id)) } returns ApplicationStoreResult.Success(stored)
        every { applications.findById(ApplicationId(MISSING)) } returns ApplicationStoreResult.NotFound

        update.call(call("id" to "$id", "version" to 3, "companyId" to "$company", *staff())) shouldBe
            ToolAnswer.Error(
                "version-conflict",
                "The entity changed since it was read. Read it again and retry with its current version.",
            )
        update.call(call("id" to "$MISSING", "version" to 0, "companyId" to "$company", *staff())) shouldBe
            ToolAnswer.Error("not-found", "No application has this id.")
        verify(exactly = 0) { applications.updateDetails(any()) }
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `a withheld marker is refused in every text field of both tools, naming the nested argument`() {
        val texts =
            mapOf(
                "posting.title" to ("posting" to mapOf("title" to "a [withheld]")),
                "posting.location" to ("posting" to mapOf("title" to "T", "location" to "[withheld]")),
                "notes.portalNotes" to ("notes" to mapOf("portalNotes" to "call [withheld]")),
                "notes.payEstimateBasis" to ("notes" to mapOf("payEstimateBasis" to "[withheld]")),
                "notes.offer.bonus" to ("notes" to mapOf("offer" to mapOf("bonus" to "[withheld]"))),
                "notes.offer.benefits" to ("notes" to mapOf("offer" to mapOf("benefits" to "[withheld]"))),
                "notes.offer.noticePeriod" to ("notes" to mapOf("offer" to mapOf("noticePeriod" to "[withheld]"))),
                "languageAndTone.postingLanguage" to ("languageAndTone" to mapOf("postingLanguage" to "[withheld]")),
            )

        texts.forEach { (path, field) ->
            val base = arrayOf("posting" to mapOf("title" to "T"), "companyId" to "$company")
            val problems = ArgumentProblem(path, "withheld-value")
            update.call(call("id" to "$id", "version" to 0, *base, field)).problemsOf() shouldBe listOf(problems)
            create.call(call(*base, field)).problemsOf() shouldBe listOf(problems)
        }
        verify(exactly = 0) { applications.findById(any()) }
        verify(exactly = 0) { applications.add(any(), any<StatusChange>()) }
    }

    @Test
    fun `an offer cleared in one place only and a basis without an estimated band are refused, storing nothing`() {
        val identity = arrayOf("id" to "$id", "version" to 0, "companyId" to "$company")
        val texts = mapOf("portalNotes" to null, "payEstimateBasis" to null, "offer" to mapOf("bonus" to "10 %"))
        val onlyTexts = update.call(call(*identity, *staff(), "notes" to texts))
        val onlyDetails =
            update.call(
                call(*identity, *staff(), "offer" to mapOf("vacationDays" to 30), "notes" to texts + ("offer" to null)),
            )
        val basis = mapOf("portalNotes" to null, "payEstimateBasis" to "levels.fyi", "offer" to null)
        val band = mapOf("currency" to "EUR", "period" to "YEAR", "source" to "POSTING", "min" to 1)
        val unestimated = update.call(call(*identity, *staff(), "payBand" to band, "notes" to basis))
        val noBand = create.call(call("companyId" to "$company", "posting" to mapOf("title" to "T"), "notes" to basis))

        onlyTexts.problemsOf() shouldBe
            listOf(ArgumentProblem("offer", "inconsistent"), ArgumentProblem("notes.offer", "inconsistent"))
        onlyDetails.problemsOf() shouldBe onlyTexts.problemsOf()
        unestimated.problemsOf() shouldBe listOf(ArgumentProblem("notes.payEstimateBasis", "not-applicable"))
        noBand.problemsOf() shouldBe listOf(ArgumentProblem("notes.payEstimateBasis", "not-applicable"))
        verify(exactly = 0) { applications.findById(any()) }
        verify(exactly = 0) { applications.add(any(), any<StatusChange>()) }
    }

    @Test
    fun `create_application accepts null for notes and languageAndTone, the schema says so`() {
        every { applications.add(any(), any()) } returns ApplicationStoreResult.Success(Unit)

        create
            .call(
                call(
                    "companyId" to "$company",
                    "posting" to mapOf("title" to "T"),
                    "notes" to null,
                    "languageAndTone" to null,
                ),
            ).shouldBeInstanceOf<ToolAnswer.Result>()

        val json = JsonMapper.builder().build()
        listOf("notes", "languageAndTone").forEach { key ->
            json.readTree(create.inputSchema)["properties"][key]["type"].toString() shouldBe "[\"object\",\"null\"]"
            json.readTree(update.inputSchema)["properties"][key]["type"].toString() shouldBe "\"object\""
        }
    }

    @Test
    fun `update_application needs its id and version`() {
        shouldThrow<InvalidToolArgument> { update.call(call("version" to 0)) }.argument shouldBe "id"
        shouldThrow<InvalidToolArgument> { update.call(call("id" to "$id")) }.argument shouldBe "version"
    }

    @Test
    fun `the update schema requires every property, the create schema only the company and the title`() {
        val updateRequired = requiredOf(update.inputSchema)
        val createRequired = requiredOf(create.inputSchema)

        updateRequired shouldBe
            setOf(
                "id",
                "version",
                "companyId",
                "remoteSharePercent",
                "employmentType",
                "seniority",
                "deadline",
                "howApplied",
                "payBand",
                "offer",
                "languageAndTone",
                "posting",
                "notes",
            )
        createRequired shouldBe setOf("companyId", "posting")
    }

    private fun requiredOf(schema: String): Set<String> =
        Regex("\"required\": \\[([^\\]]*)]")
            .let { checkNotNull(it.find(schema)) }
            .groupValues[1]
            .split(",")
            .map { it.trim().trim('"') }
            .toSet()

    private fun ToolAnswer.problemsOf() = shouldBeInstanceOf<ToolAnswer.Error>().problems

    private fun staff(): Array<Pair<String, Any?>> =
        arrayOf(
            "posting" to mapOf("title" to "Staff", "location" to null),
            "remoteSharePercent" to null,
            "employmentType" to null,
            "seniority" to null,
            "deadline" to null,
            "howApplied" to null,
            "payBand" to null,
            "offer" to null,
            "languageAndTone" to null,
            "notes" to null,
        )

    private fun everyArgument(): Array<Pair<String, Any?>> =
        arrayOf(
            "companyId" to "$company",
            "posting" to mapOf("title" to " Kotlin Engineer ", "location" to "Berlin"),
            "remoteSharePercent" to 60,
            "employmentType" to "FULL_TIME",
            "seniority" to "SENIOR",
            "deadline" to "2026-11-01",
            "howApplied" to "PORTAL",
            "notes" to
                mapOf(
                    "portalNotes" to "ref 42",
                    "payEstimateBasis" to "levels.fyi",
                    "offer" to mapOf("noticePeriod" to "3 months"),
                ),
            "payBand" to
                mapOf(
                    "min" to 70000,
                    "max" to 90000.5,
                    "currency" to "eur",
                    "period" to "YEAR",
                    "source" to "ESTIMATED",
                    "estimateConfidence" to "LOW",
                ),
            "languageAndTone" to mapOf("applicationLanguage" to "de-ch", "tone" to "PROFESSIONAL"),
            "offer" to mapOf("salary" to mapOf("amount" to 80000, "currency" to "EUR", "period" to "YEAR")),
        )

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)

    private companion object {
        val MISSING: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
