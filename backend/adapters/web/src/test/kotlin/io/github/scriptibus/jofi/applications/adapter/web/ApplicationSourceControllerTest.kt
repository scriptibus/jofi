// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.AddApplicationSourceUseCase
import io.github.scriptibus.jofi.applications.application.DiffDescriptionSnapshotsUseCase
import io.github.scriptibus.jofi.applications.application.GetDescriptionSnapshotUseCase
import io.github.scriptibus.jofi.applications.application.ListDescriptionSnapshotsUseCase
import io.github.scriptibus.jofi.applications.application.RecordDescriptionSnapshotUseCase
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Adding a source (#96) and the description endpoints (#86) over the real use cases with mocked repositories:
 * recording (added or unchanged), the version list, one version with its text, the diff, and their 404s and 400s.
 * Security (session, CSRF) is the filter chain's job, tested in bootstrap.
 */
@WebMvcTest(ApplicationSourceController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(ApplicationSourceControllerTest.UseCases::class)
class ApplicationSourceControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: ApplicationControllerTest.Ports,
) {
    @TestConfiguration
    class UseCases {
        private val clock = Clock.systemUTC()

        @Bean
        fun ports() = ApplicationControllerTest.Ports()

        @Bean
        fun addSource(ports: ApplicationControllerTest.Ports) =
            AddApplicationSourceUseCase(ports.applications, ports.sources, ports.changelog, ports.transactions, clock)

        @Bean
        fun record(ports: ApplicationControllerTest.Ports) =
            RecordDescriptionSnapshotUseCase(
                ports.applications,
                ports.snapshots,
                ports.changelog,
                ports.transactions,
                clock,
            )

        @Bean
        fun list(ports: ApplicationControllerTest.Ports) =
            ListDescriptionSnapshotsUseCase(ports.applications, ports.snapshots)

        @Bean
        fun get(ports: ApplicationControllerTest.Ports) =
            GetDescriptionSnapshotUseCase(ports.applications, ports.snapshots)

        @Bean
        fun diff(ports: ApplicationControllerTest.Ports) =
            DiffDescriptionSnapshotsUseCase(ports.applications, ports.snapshots)
    }

    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val applicationId = ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val source =
        ApplicationSource(
            SourceId(UUID.fromString("00000000-0000-0000-0000-0000000000d1")),
            applicationId,
            SourceKind.MANUAL_CHAT,
            null,
            at,
        )
    private val stored =
        Application
            .create(applicationId, ApplicationDetails("Backend Engineer", CompanyRef(UUID(0, 12))), at)
            .copy(sources = listOf(source))
    private val base = "/api/applications/${applicationId.value}"
    private val sourcePath = "$base/sources/${source.id.value}/snapshots"
    private val old = snapshot(1, "Kotlin\nBerlin")
    private val new = snapshot(2, "Kotlin\nHamburg")

    private fun snapshot(
        n: Long,
        text: String,
    ) = DescriptionSnapshot(
        SnapshotId(UUID(0, 0xe0 + n)),
        source.id,
        DescriptionText(text),
        SnapshotReason.MANUAL,
        at.plusSeconds(n),
    )

    @BeforeEach
    fun storeOne() {
        clearMocks(ports.applications, ports.snapshots, ports.sources, ports.changelog)
        every { ports.sources.add(any(), any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.applications.findById(any()) } returns ApplicationStoreResult.NotFound
        every { ports.applications.findById(applicationId) } returns ApplicationStoreResult.Success(stored)
        every { ports.snapshots.latest(source.id) } returns ApplicationStoreResult.Success(old)
        every { ports.snapshots.add(any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.snapshots.listBySource(source.id) } returns
            ApplicationStoreResult.Success(listOf(old.summary(), new.summary()))
        every { ports.snapshots.findById(any(), any()) } returns ApplicationStoreResult.NotFound
        every { ports.snapshots.findById(applicationId, old.id) } returns ApplicationStoreResult.Success(old)
        every { ports.snapshots.findById(applicationId, new.id) } returns ApplicationStoreResult.Success(new)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    @Test
    fun `recording a changed text answers the new version, the same text answers the newest one`() {
        json(mvc.post().uri(sourcePath), """{"description":"Kotlin\nHamburg"}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"added":true,"snapshot":{"sourceId":"${source.id.value}","reason":"MANUAL"}}""")
        verify { ports.snapshots.add(match { it.text.value == "Kotlin\nHamburg" && it.frozenAt == null }) }
        verify { ports.changelog.append(match { it.actor == Actor.User }) }

        json(mvc.post().uri(sourcePath), """{"description":"Kotlin\r\nBerlin"}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"added":false,
                 "snapshot":{"id":"${old.id.value}","contentHash":"${old.contentHash.hex}","length":13}}
                """.trimIndent(),
            )
        verify(exactly = 1) { ports.snapshots.add(any()) }
    }

    @Test
    fun `an empty text is 400, an unknown application or source 404 with its type`() {
        json(mvc.post().uri(sourcePath), """{"description":"  "}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${ApplicationProblems.INVALID}",
                 "violations":[{"field":"description","problem":"REQUIRED"}]}
                """.trimIndent(),
            )
        notFound(
            json(mvc.post().uri("$base/sources/${UUID(0, 9)}/snapshots"), """{"description":"x"}"""),
            ApplicationProblems.SOURCE_NOT_FOUND,
        )
        notFound(
            mvc.get().uri("/api/applications/${UUID(0, 9)}/sources/${source.id.value}/snapshots"),
            ApplicationProblems.NOT_FOUND,
        )
    }

    @Test
    fun `the versions are listed without their texts, one version with its text`() {
        mvc
            .get()
            .uri(sourcePath)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """{"snapshots":[{"id":"${old.id.value}","length":13},{"id":"${new.id.value}","length":14}]}""",
            )
        mvc
            .get()
            .uri("$base/snapshots/${old.id.value}")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"id":"${old.id.value}","description":"Kotlin\nBerlin","frozenAt":null}""")
        notFound(mvc.get().uri("$base/snapshots/${UUID(0, 9)}"), ApplicationProblems.SNAPSHOT_NOT_FOUND)
    }

    @Test
    fun `the diff answers line segments, and 404 for a version the application does not have`() {
        mvc
            .get()
            .uri("$base/description-diff?from=${old.id.value}&to=${new.id.value}")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo(
                """
                {"from":"${old.id.value}","to":"${new.id.value}","segments":[
                  {"operation":"UNCHANGED","text":"Kotlin\n"},
                  {"operation":"REMOVED","text":"Berlin"},
                  {"operation":"ADDED","text":"Hamburg"}]}
                """.trimIndent(),
            )
        notFound(
            mvc.get().uri("$base/description-diff?from=${old.id.value}&to=${UUID(0, 9)}"),
            ApplicationProblems.SNAPSHOT_NOT_FOUND,
        )
    }

    @Test
    fun `adding a source answers it, with its text stored as the first description`() {
        json(
            mvc.post().uri("$base/sources"),
            """{"kind":"URL","originalUrl":"https://jobs.example/1","discoveredAt":"2026-09-30T08:00:00Z",""" +
                """"description":"Kotlin"}""",
        ).assertThat()
            .hasStatus(201)
            .bodyJson()
            .extractingPath("$.kind")
            .isEqualTo("URL")
        verify {
            ports.sources.add(
                match { it.application == applicationId && it.originalUrl?.value == "https://jobs.example/1" },
                match { it.text.value == "Kotlin" && it.reason == SnapshotReason.DISCOVERY },
            )
        }
        notFound(
            json(mvc.post().uri("/api/applications/${UUID(0, 9)}/sources"), """{"kind":"SCANNER"}"""),
            ApplicationProblems.NOT_FOUND,
        )
        json(mvc.post().uri("$base/sources"), """{"kind":"URL"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .extractingPath("$.violations[0].field")
            .isEqualTo("originalUrl")
    }

    @Test
    fun `requests that break the contract are rejected`() {
        badRequest(json(mvc.post().uri("$base/sources"), """{"originalUrl":"https://jobs.example/1"}"""))
        badRequest(json(mvc.post().uri("$base/sources"), """{"kind":"LINKEDIN"}"""))
        badRequest(json(mvc.post().uri(sourcePath), """{}"""))
        badRequest(mvc.get().uri("$base/sources/not-a-uuid/snapshots"))
        badRequest(mvc.get().uri("$base/description-diff?from=${old.id.value}"))
    }

    private fun json(
        request: MockMvcTester.MockMvcRequestBuilder,
        body: String,
    ): MockMvcTester.MockMvcRequestBuilder = request.contentType(MediaType.APPLICATION_JSON).content(body)

    private fun notFound(
        request: MockMvcTester.MockMvcRequestBuilder,
        type: String,
    ) {
        request
            .assertThat()
            .hasStatus(404)
            .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
            .bodyJson()
            .isLenientlyEqualTo("""{"type":"$type"}""")
    }

    private fun badRequest(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }
}
