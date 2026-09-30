// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ApplicationSourceTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val id = ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val sourceId = SourceId(UUID.fromString("00000000-0000-0000-0000-0000000000d1"))
    private val link = WebAddress("https://jobs.example/1?who=secret")
    private val application =
        Application.create(id, ApplicationDetails("Backend Engineer", CompanyRef(UUID.randomUUID())), at)

    private fun source(
        sourceId: SourceId = this.sourceId,
        kind: SourceKind = SourceKind.URL,
    ) = ApplicationSource(sourceId, id, kind, link, at)

    @Test
    fun `changelog entries refer to it as an application source`() {
        sourceId.toEntityRef() shouldBe EntityRef("application_source", sourceId.value.toString())
    }

    @Test
    fun `a URL source has a link and goes offline only after it was found`() {
        shouldThrow<IllegalArgumentException> { ApplicationSource(sourceId, id, SourceKind.URL, null, at) }
        shouldThrow<IllegalArgumentException> { source().copy(offlineSince = at.minusSeconds(1)) }
        ApplicationSource(sourceId, id, SourceKind.MANUAL_CHAT, null, at).online shouldBe true
    }

    @Test
    fun `a source goes offline once and comes back online`() {
        val offline = source().markOffline(at.plusSeconds(60))

        offline.online shouldBe false
        offline.offlineSince shouldBe at.plusSeconds(60)
        offline.markOffline(at.plusSeconds(120)) shouldBeSameInstanceAs offline
        source().markOffline(at.minusSeconds(60)).offlineSince shouldBe at
        offline.markOnline() shouldBe source()
        source().markOnline() shouldBe source()
    }

    @Test
    fun `adding a source is not a new version, and there is a limit`() {
        val added = application.addSource(source()).shouldBeInstanceOf<ApplicationValidation.Valid<Application>>().value

        added.sources shouldBe listOf(source())
        added.version shouldBe application.version
        added.updatedAt shouldBe application.updatedAt
        val full =
            (1..Application.MAX_SOURCES).fold(application) { app, _ ->
                (app.addSource(source(SourceId(UUID.randomUUID()))) as ApplicationValidation.Valid).value
            }
        full.addSource(source(SourceId(UUID.randomUUID()))) shouldBe
            ApplicationValidation.Invalid(
                listOf(ApplicationViolation(ApplicationField.SOURCES, ApplicationProblem.TOO_MANY)),
            )
    }

    @Test
    fun `an application holds only its own sources, each once`() {
        val foreign = ApplicationSource(sourceId, ApplicationId(UUID.randomUUID()), SourceKind.SCANNER, null, at)

        shouldThrow<IllegalArgumentException> { application.addSource(foreign) }
        shouldThrow<IllegalArgumentException> { application.copy(sources = listOf(source(), source())) }
    }

    @Test
    fun `input is normalized, and the text at discovery becomes a description`() {
        val draft =
            SourceInput(
                SourceKind.URL,
                " https://bücher.example/stellen/köln ",
                at.minusSeconds(60),
                " Zeile\r\nzwei\r ",
            ).validate(at)
                .shouldBeInstanceOf<ApplicationValidation.Valid<SourceDraft>>()
                .value

        draft shouldBe
            SourceDraft(
                SourceKind.URL,
                WebAddress("https://bücher.example/stellen/köln"),
                at.minusSeconds(60),
                DescriptionText("Zeile\nzwei"),
            )
        draft.toSource(sourceId, id) shouldBe
            ApplicationSource(sourceId, id, SourceKind.URL, draft.originalUrl, at.minusSeconds(60))
        SourceInput(SourceKind.MANUAL_CHAT, " ", description = " \n ").validate(at) shouldBe
            ApplicationValidation.Valid(SourceDraft(SourceKind.MANUAL_CHAT, null, at, null))
    }

    @Test
    fun `input reports every problem`() {
        SourceInput(SourceKind.URL, discoveredAt = at.plusSeconds(1), description = "a\u0000").validate(at) shouldBe
            ApplicationValidation.Invalid(
                listOf(
                    ApplicationViolation(ApplicationField.SOURCE_URL, ApplicationProblem.REQUIRED),
                    ApplicationViolation(ApplicationField.DISCOVERED_AT, ApplicationProblem.OUT_OF_RANGE),
                    ApplicationViolation(ApplicationField.DESCRIPTION, ApplicationProblem.INVALID_CHARACTER),
                ),
            )
        SourceInput(
            SourceKind.SCANNER,
            "https://me:pw@jobs.example",
            description = "x".repeat(DescriptionText.MAX_LENGTH + 1),
        ).validate(at) shouldBe
            ApplicationValidation.Invalid(
                listOf(
                    ApplicationViolation(ApplicationField.SOURCE_URL, ApplicationProblem.INVALID_URL),
                    ApplicationViolation(ApplicationField.DESCRIPTION, ApplicationProblem.TOO_LONG),
                ),
            )
    }

    @Test
    fun `nothing prints the link or the text`() {
        val input = SourceInput(SourceKind.URL, link.value, description = "Secret text")

        listOf(source(), input, (input.validate(at) as ApplicationValidation.Valid).value)
            .forEach { it.toString().lowercase() shouldNotContain "secret" }
    }
}
