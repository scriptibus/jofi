// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.FetchPostingTextUseCase
import io.github.scriptibus.jofi.applications.application.GetPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.RetryPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.StartPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.StartUrlImportUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.http.BlockReason
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.FetchedResource
import io.github.scriptibus.jofi.shared.domain.http.ResponseBody
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
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
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The posting import endpoints (#96) over the real use cases with mocked ports: start (202, 400, 409 without an AI
 * model), poll (200, 404) and retry (202, 404, 409). Security (session, CSRF) is the filter chain's job, tested in
 * bootstrap.
 */
@WebMvcTest(PostingImportController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(PostingImportControllerTest.UseCases::class)
class PostingImportControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: ImportPorts,
) {
    class ImportPorts {
        val applications = ApplicationControllerTest.Ports()
        val imports = mockk<PostingImportRepositoryPort>()
        val sources = mockk<ApplicationSourceRepositoryPort>()
        val ai = mockk<CheckAiTaskAssignedPort>()
        val jobs = mockk<JobSchedulerPort>()
        val http = mockk<OutboundHttpPort>()
    }

    @TestConfiguration
    class UseCases {
        private val clock = Clock.systemUTC()

        @Bean
        fun ports() = ImportPorts()

        @Bean
        fun start(ports: ImportPorts) =
            StartPostingImportUseCase(
                ports.imports,
                ports.ai,
                ports.jobs,
                ports.applications.changelog,
                ports.applications.transactions,
                clock,
            )

        @Bean
        fun startUrl(ports: ImportPorts) =
            StartUrlImportUseCase(
                ports.imports,
                ports.sources,
                FetchPostingTextUseCase(ports.ai, ports.http),
                ports.jobs,
                ports.applications.changelog,
                ports.applications.transactions,
                clock,
            )

        @Bean
        fun get(ports: ImportPorts) = GetPostingImportUseCase(ports.imports)

        @Bean
        fun retry(ports: ImportPorts) =
            RetryPostingImportUseCase(
                ports.imports,
                ports.ai,
                ports.jobs,
                ports.applications.changelog,
                ports.applications.transactions,
                clock,
            )
    }

    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val pending =
        PostingImport.start(
            ImportId(UUID.fromString("00000000-0000-0000-0000-0000000000f1")),
            DescriptionText("Text"),
            at,
        )
    private val failed = pending.failed(ImportFailure.AI_UNAVAILABLE, at.plusSeconds(5))
    private val application = ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val postingUrl = WebAddress("https://jobs.example/posting")

    @BeforeEach
    fun reset() {
        clearMocks(ports.imports, ports.sources, ports.ai, ports.jobs, ports.http, ports.applications.changelog)
        every { ports.ai.execute(AiTask.EXTRACTION) } returns CheckAiTaskAssignedPort.Assignment.Assigned
        every { ports.imports.add(any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.imports.update(any(), any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.imports.findById(any()) } returns ApplicationStoreResult.NotFound
        // mockk needs a real value (not a matcher) to probe a method with a validating value-class parameter, so
        // these cover exactly the text and link the tests below submit.
        every { ports.imports.findPendingByText(DescriptionText("Kotlin Developer at ACME")) } returns
            ApplicationStoreResult.Success(null)
        every { ports.imports.findPendingBySourceUrl(postingUrl) } returns ApplicationStoreResult.Success(null)
        every { ports.imports.lockForStart(any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.sources.findByOriginalUrl(postingUrl) } returns ApplicationStoreResult.Success(emptyList())
        every { ports.jobs.enqueue(any()) } returns JobResult.Success(JobId(UUID.randomUUID()))
        every { ports.applications.changelog.append(any()) } returns ChangelogResult.Success(Unit)
        every { ports.http.fetch(any()) } returns
            FetchResult.Success(
                FetchedResource(
                    URI.create("https://jobs.example/posting"),
                    200,
                    "text/html",
                    ResponseBody("<h1>Senior Kotlin Developer</h1>".toByteArray()),
                ),
            )
    }

    @Test
    fun `starting answers the pending import at once and queues its job`() {
        json(mvc.post().uri("/api/applications/imports/text"), """{"description":"Kotlin Developer at ACME"}""")
            .assertThat()
            .hasStatus(202)
            .bodyJson()
            .isLenientlyEqualTo("""{"status":"PENDING","failure":null,"applicationId":null,"attempt":1}""")
        verify { ports.imports.add(match { it.text?.value == "Kotlin Developer at ACME" }) }
        verify { ports.jobs.enqueue(any()) }
    }

    @Test
    fun `starting a URL import fetches it and answers the pending import at once, queuing its job`() {
        json(mvc.post().uri("/api/applications/imports/url"), """{"url":"https://jobs.example/posting"}""")
            .assertThat()
            .hasStatus(202)
            .bodyJson()
            .isLenientlyEqualTo("""{"status":"PENDING","failure":null,"applicationId":null,"attempt":1}""")
        verify { ports.http.fetch(any()) }
        verify { ports.imports.add(match { it.text?.value == "Senior Kotlin Developer" }) }
    }

    @Test
    fun `a URL already imported answers 200 with the existing application, fetching nothing`() {
        val application = ApplicationId(UUID.randomUUID())
        val source = ApplicationSource(SourceId(UUID.randomUUID()), application, SourceKind.URL, postingUrl, at)
        every { ports.sources.findByOriginalUrl(postingUrl) } returns ApplicationStoreResult.Success(listOf(source))

        json(mvc.post().uri("/api/applications/imports/url"), """{"url":"${postingUrl.value}"}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"status":"SUCCEEDED","failure":null,"applicationId":"${application.value}"}""")
        verify(exactly = 0) { ports.http.fetch(any()) }
    }

    @Test
    fun `a disallowed, invalid or unreachable URL is a 400, fetching nothing for the first two`() {
        json(mvc.post().uri("/api/applications/imports/url"), """{"url":"https://www.linkedin.com/jobs/view/1"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """{"type":"${ApplicationProblems.INVALID}",""" +
                    """"violations":[{"field":"originalUrl","problem":"NOT_ALLOWED"}]}""",
            )
        json(mvc.post().uri("/api/applications/imports/url"), """{"url":"not a url"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """{"type":"${ApplicationProblems.INVALID}",""" +
                    """"violations":[{"field":"originalUrl","problem":"INVALID_URL"}]}""",
            )
        every { ports.http.fetch(any()) } returns FetchResult.Blocked(BlockReason.ADDRESS_NOT_ALLOWED)
        json(mvc.post().uri("/api/applications/imports/url"), """{"url":"${postingUrl.value}"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """{"type":"${ApplicationProblems.INVALID}",""" +
                    """"violations":[{"field":"originalUrl","problem":"UNREACHABLE"}]}""",
            )
        verify(exactly = 0) { ports.imports.add(any()) }
    }

    @Test
    fun `a blank text is invalid, and without an extraction model the user is told to set up AI`() {
        json(mvc.post().uri("/api/applications/imports/text"), """{"description":"  "}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """{"type":"${ApplicationProblems.INVALID}",""" +
                    """"violations":[{"field":"description","problem":"REQUIRED"}]}""",
            )
        every { ports.ai.execute(AiTask.EXTRACTION) } returns CheckAiTaskAssignedPort.Assignment.NotAssigned

        json(mvc.post().uri("/api/applications/imports/text"), """{"description":"Kotlin"}""")
            .assertThat()
            .hasStatus(409)
            .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
            .bodyJson()
            .isLenientlyEqualTo("""{"type":"${ApplicationProblems.AI_NOT_CONFIGURED}"}""")
        json(mvc.post().uri("/api/applications/imports/text"), """{}""").assertThat().hasStatus(400)
        verify(exactly = 0) { ports.imports.add(any()) }
    }

    @Test
    fun `polling answers the import without its text, or 404`() {
        val done = pending.succeeded(application, at.plusSeconds(9))
        every { ports.imports.findById(pending.id) } returns ApplicationStoreResult.Success(done)

        mvc
            .get()
            .uri("/api/applications/imports/${pending.id.value}")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo(
                """
                {"id":"${pending.id.value}","status":"SUCCEEDED","failure":null,"applicationId":"${application.value}",
                 "attempt":1,"createdAt":"2026-09-30T08:00:00Z","updatedAt":"2026-09-30T08:00:09Z"}
                """.trimIndent(),
            )
        mvc
            .get()
            .uri("/api/applications/imports/${UUID(0, 9)}")
            .assertThat()
            .hasStatus(404)
            .bodyJson()
            .isLenientlyEqualTo("""{"type":"${ApplicationProblems.IMPORT_NOT_FOUND}"}""")
    }

    @Test
    fun `retrying a failed import queues it again, anything else is a conflict`() {
        every { ports.imports.findById(pending.id) } returns ApplicationStoreResult.Success(failed)

        mvc
            .post()
            .uri("/api/applications/imports/${pending.id.value}/retry")
            .assertThat()
            .hasStatus(202)
            .bodyJson()
            .isLenientlyEqualTo("""{"status":"PENDING","failure":null,"attempt":2}""")
        every { ports.imports.findById(pending.id) } returns
            ApplicationStoreResult.Success(pending.succeeded(application, at))
        mvc
            .post()
            .uri("/api/applications/imports/${pending.id.value}/retry")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .isLenientlyEqualTo("""{"type":"${ApplicationProblems.IMPORT_NOT_RETRYABLE}"}""")
        mvc
            .post()
            .uri("/api/applications/imports/${UUID(0, 9)}/retry")
            .assertThat()
            .hasStatus(404)
    }

    private fun json(
        request: MockMvcTester.MockMvcRequestBuilder,
        body: String,
    ): MockMvcTester.MockMvcRequestBuilder = request.contentType(MediaType.APPLICATION_JSON).content(body)
}
