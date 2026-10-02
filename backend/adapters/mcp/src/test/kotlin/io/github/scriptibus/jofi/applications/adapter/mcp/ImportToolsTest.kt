// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.GetPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.StartPostingImportUseCase
import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.ImportStatus
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolTestPorts
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.job.CronSchedule
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.RecurringJobId
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The import tools over the real use cases with fake ports (hand-written: a mock matcher cannot build the placeholder
 * of a value class such as `WebAddress`): arguments in, results out.
 */
class ImportToolsTest {
    private val imports = FakeImports()
    private val ai = FakeAssignment()
    private val jobs = FakeJobs()
    private val changelog = ToolTestPorts.RecordingChangelog()
    private val transactions = ToolTestPorts.transactions
    private val clock = ToolTestPorts.clock
    private val started = Instant.parse("2026-10-01T08:00:00Z")

    private val text = StartTextImportTool(StartPostingImportUseCase(imports, ai, jobs, changelog, transactions, clock))
    private val status = GetImportStatusTool(GetPostingImportUseCase(imports))

    private val link = WebAddress("https://jobs.example/42")
    private val pending = PostingImport.start(IMPORT, DescriptionText("Senior Kotlin Engineer"), started, link)

    @Test
    fun `start_text_import stores a pending import as the caller, queues its job and answers at once`() {
        val answer = text.call(call("text" to "  Senior Kotlin Engineer\r\n\r\nACME  "))

        val stored = imports.stored.values.single()
        stored.text?.value shouldBe "Senior Kotlin Engineer\n\nACME"
        changelog.entries.single().actor shouldBe Actor.Ai
        jobs.requests.single().arguments shouldBe mapOf("import" to stored.id.value.toString())
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<PostingImportResult>()
        result.status shouldBe ImportStatus.PENDING
        result.id shouldBe stored.id.value
        result.applicationId shouldBe null
    }

    @Test
    fun `the same text submitted twice answers the same import`() {
        val first = text.call(call("text" to "A posting")).shouldBeInstanceOf<ToolAnswer.Result>()
        val second = text.call(call("text" to "A posting")).shouldBeInstanceOf<ToolAnswer.Result>()

        second.value shouldBe first.value
        imports.stored.size shouldBe 1
        jobs.requests.size shouldBe 1
    }

    @Test
    fun `start_text_import refuses a withheld marker and stores nothing`() {
        text.call(call("text" to "A posting [withheld]")).shouldBeInstanceOf<ToolAnswer.Error>().problems shouldBe
            listOf(ArgumentProblem("text", "withheld-value"))
        imports.stored shouldBe emptyMap()
    }

    @Test
    fun `start_text_import refuses blank text and answers no model, storing nothing`() {
        text.call(call("text" to " \n ")) shouldBe
            ToolAnswer.Error(
                "invalid-arguments",
                "The arguments are invalid.",
                listOf(ArgumentProblem("text", "required")),
            )
        text.call(call()).shouldBeInstanceOf<ToolAnswer.Error>().problems shouldBe
            listOf(ArgumentProblem("text", "required"))
        ai.answer = CheckAiTaskAssignedPort.Assignment.NotAssigned
        text.call(call("text" to "A posting")).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "ai-not-configured"
        ai.answer = CheckAiTaskAssignedPort.Assignment.Unavailable
        text.call(call("text" to "A posting")).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "unavailable"
        imports.stored shouldBe emptyMap()
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `get_import_status answers the import, a failure with its reason, and an unknown id`() {
        imports.stored[IMPORT] = pending.failed(ImportFailure.NOT_A_POSTING, started)

        val result =
            status
                .call(call("id" to "${IMPORT.value}"))
                .shouldBeInstanceOf<ToolAnswer.Result>()
                .value
                .shouldBeInstanceOf<PostingImportResult>()

        result.status shouldBe ImportStatus.FAILED
        result.failure shouldBe ImportFailure.NOT_A_POSTING
        status.call(call("id" to "${UUID.randomUUID()}")) shouldBe
            ToolAnswer.Error("not-found", "No import has this id.")
        shouldThrow<InvalidToolArgument> { status.call(call()) }.argument shouldBe "id"
    }

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)

    private class FakeImports : PostingImportRepositoryPort {
        val stored = linkedMapOf<ImportId, PostingImport>()

        override fun add(postingImport: PostingImport): ApplicationStoreResult<Unit> {
            stored[postingImport.id] = postingImport
            return ApplicationStoreResult.Success(Unit)
        }

        override fun findById(id: ImportId) =
            stored[id]?.let { ApplicationStoreResult.Success(it) } ?: ApplicationStoreResult.NotFound

        override fun update(
            current: PostingImport,
            next: PostingImport,
        ): ApplicationStoreResult<Unit> {
            stored[next.id] = next
            return ApplicationStoreResult.Success(Unit)
        }

        override fun findPendingByText(text: DescriptionText) =
            ApplicationStoreResult.Success(
                stored.values.firstOrNull { it.status == ImportStatus.PENDING && it.text == text },
            )

        override fun findPendingBySourceUrl(sourceUrl: WebAddress) = ApplicationStoreResult.Success(null)

        override fun lockForStart(key: String) = ApplicationStoreResult.Success(Unit)
    }

    private class FakeAssignment : CheckAiTaskAssignedPort {
        var answer: CheckAiTaskAssignedPort.Assignment = CheckAiTaskAssignedPort.Assignment.Assigned

        override fun execute(task: AiTask) = answer
    }

    private class FakeJobs : JobSchedulerPort {
        val requests = mutableListOf<JobRequest>()

        override fun enqueue(request: JobRequest): JobResult<JobId> {
            requests += request
            return JobResult.Success(JobId(UUID.randomUUID()))
        }

        override fun scheduleRecurring(
            id: RecurringJobId,
            schedule: CronSchedule,
            request: JobRequest,
        ): JobResult<Unit> = JobResult.Success(Unit)

        override fun cancel(id: JobId): JobResult<Unit> = JobResult.Success(Unit)

        override fun cancelRecurring(id: RecurringJobId): JobResult<Unit> = JobResult.Success(Unit)
    }

    private companion object {
        val IMPORT = ImportId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"))
    }
}
