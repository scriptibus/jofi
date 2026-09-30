// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.jobs.AllowlistJobMapper
import io.github.scriptibus.jofi.shared.adapter.jobs.JofiJobRequestHandler
import io.github.scriptibus.jofi.shared.application.port.JobHandlerPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobLogEntry
import io.github.scriptibus.jofi.shared.domain.job.JobLogPage
import io.github.scriptibus.jofi.shared.domain.job.JobLogQuery
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.JobStatus
import io.github.scriptibus.jofi.shared.domain.job.JobType
import io.github.scriptibus.jofi.system.application.ListJobsUseCase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jobrunr.dashboard.JobRunrDashboardWebServer
import org.jobrunr.server.BackgroundJobServer
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.sql.common.DefaultSqlStorageProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import java.io.IOException
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Background jobs across two processes, like compose's `app` and `worker` (ADR-0010, ADR-0038):
 * `app` only writes jobs, the `worker` profile runs them with retries, and a missed recurring run
 * runs once when the worker comes back. Each test gets its own database.
 */
@ExtendWith(OutputCaptureExtension::class)
class BackgroundJobsTest {
    @TempDir
    lateinit var dataDirectory: Path

    private val contexts = mutableListOf<ConfigurableApplicationContext>()

    @AfterEach
    fun stopAll() {
        contexts.asReversed().forEach(ConfigurableApplicationContext::close)
        TestJobs.runs.clear()
    }

    @Test
    fun `app only enqueues, the worker runs the jobs with retries and records failures without data`(
        output: CapturedOutput,
    ) {
        val database = freshDatabase()
        val app = start(database, worker = false)
        app.getBeanProvider(BackgroundJobServer::class.java).ifAvailable shouldBe null
        val jobs = app.getBean(JobSchedulerPort::class.java)
        val echo = enqueue(jobs, "echo-test")
        val flaky = enqueue(jobs, "flaky-test")
        val broken = enqueue(jobs, "broken-test")
        val exploding = enqueue(jobs, "exploding-test")
        count(database, "SELECT count(*) FROM jobrunr_backgroundjobservers") shouldBe 0

        start(database, worker = true)
        val log = app.getBean(ListJobsUseCase::class.java)
        waitUntil({ allJobs(log) }) { finished(log).size == 4 }

        val byId = finished(log).associateBy { it.id }
        byId.getValue(echo).let { it.status to it.attempts } shouldBe (JobStatus.SUCCEEDED to 1)
        byId.getValue(flaky).let { Triple(it.status, it.attempts, it.lastFailure) } shouldBe
            Triple(JobStatus.SUCCEEDED, 3, FailureReason("try-again"))
        byId.getValue(broken).let { Triple(it.status, it.attempts, it.lastFailure) } shouldBe
            Triple(JobStatus.FAILED, 1, FailureReason("broken-for-test"))
        byId.getValue(exploding).let { Triple(it.status, it.attempts, it.lastFailure) } shouldBe
            Triple(JobStatus.FAILED, 3, FailureReason.UNEXPECTED)
        TestJobs.runs.map { it.profiles } shouldContainOnly listOf(listOf("worker"))
        TestJobs.runs.first { it.type == "echo-test" }.arguments shouldBe mapOf("itemId" to "42")
        count(database, "SELECT count(*) FROM jobrunr_jobs WHERE jobasjson LIKE '%$PERSONAL_DATA%'") shouldBe 0
        output.all shouldNotContain PERSONAL_DATA
    }

    @Test
    fun `both profiles read the job store through the allowlist, and a tampered row never runs`() {
        val database = freshDatabase()
        val app = start(database, worker = false)
        val tampered = enqueue(app.getBean(JobSchedulerPort::class.java), "echo-test")
        // A lambda-style job calling System.exit, as a SQL injection elsewhere could write it.
        sql(
            database,
            "UPDATE jobrunr_jobs SET jobasjson = replace(replace(jobasjson, '$HANDLER', 'java.lang.System'), " +
                "'\"methodName\":\"run\"', '\"methodName\":\"exit\"') WHERE id = '${tampered.value}'",
        )
        val worker = start(database, worker = true)

        listOf(app, worker).forEach { context ->
            jobMapperOf(context).shouldBeInstanceOf<AllowlistJobMapper>()
            context.getBeanProvider(JobRunrDashboardWebServer::class.java).ifAvailable shouldBe null
        }
        val log = app.getBean(ListJobsUseCase::class.java)
        waitUntil { finished(log).isNotEmpty() }
        finished(log).single().let { Triple(it.id, it.status, it.lastFailure) } shouldBe
            Triple(tampered, JobStatus.FAILED, FailureReason("rejected-job"))
        TestJobs.runs.shouldBeEmpty()
    }

    /** The job mapper the real storage provider bean reads rows with. */
    private fun jobMapperOf(context: ConfigurableApplicationContext): Any? {
        val field = DefaultSqlStorageProvider::class.java.getDeclaredField("jobMapper").apply { isAccessible = true }
        return field.get(context.getBean(StorageProvider::class.java))
    }

    @Test
    fun `the job log API needs a login and then shows the jobs`() {
        val database = freshDatabase()
        val app = start(database, worker = false)
        enqueue(app.getBean(JobSchedulerPort::class.java), "echo-test")
        val http = Http(port(app))

        val anonymous = http.get("/api/system/jobs")
        anonymous.statusCode() shouldBe 401
        anonymous.headers().firstValue("Content-Type").orElse("") shouldContain "application/problem+json"

        http.firstRun(Files.readString(dataDirectory.resolve("secrets/setup-token")).trim())
        val page = http.get("/api/system/jobs?status=ENQUEUED")
        page.statusCode() shouldBe 200
        page.body() shouldContain "\"name\":\"echo-test\""
        page.body() shouldContain "\"total\":1"
        http.get("/api/system/jobs?size=0").statusCode() shouldBe 400
    }

    @Test
    fun `a missed hourly run runs once when the worker starts again, not once per missed slot`() {
        val database = freshDatabase()
        start(database, worker = false)
        val firstWorker = start(database, worker = true)
        waitUntil { count(database, "$CLEANUP_JOBS AND state = 'SCHEDULED'") == 1 }
        firstWorker.close()
        // Restarting `app` registers the unchanged schedule again: the run scheduled ahead must stay.
        contexts.first().close()
        start(database, worker = false)
        count(database, "$CLEANUP_JOBS AND state = 'SCHEDULED'") shouldBe 1
        // Five hours of downtime: the run scheduled ahead is now overdue, four more slots passed.
        sql(
            database,
            "UPDATE jobrunr_jobs SET scheduledat = scheduledat - interval '5 hours' " +
                "WHERE recurringjobid = 'session-cleanup'",
        )
        insertSession(database, "00000000-0000-0000-0000-00000000000e", expiresInMinutes = -10)
        insertSession(database, "00000000-0000-0000-0000-00000000000f", expiresInMinutes = 60)

        start(database, worker = true)
        waitUntil { count(database, "$CLEANUP_JOBS AND state = 'SUCCEEDED'") == 1 }
        Thread.sleep(POLL_INTERVAL.toMillis() * 2)

        count(database, "$CLEANUP_JOBS AND state <> 'SCHEDULED'") shouldBe 1
        count(database, "SELECT count(*) FROM spring_session WHERE primary_id LIKE '%e'") shouldBe 0
        count(database, "SELECT count(*) FROM spring_session WHERE primary_id LIKE '%f'") shouldBe 1
        count(
            database,
            "SELECT count(*) FROM changelog_entry WHERE actor_kind = 'SYSTEM' AND actor_name = 'session-cleanup' " +
                "AND description = 'Deleted 1 expired login session(s)'",
        ) shouldBe 1
    }

    @Test
    fun `the daily Ghosted suggestion is scheduled by app and suggests once per silence, never changing a status`() {
        val database = freshDatabase()
        val app = start(database, worker = false)
        count(database, "SELECT count(*) FROM jobrunr_recurring_jobs WHERE id = 'ghosted-suggestion'") shouldBe 1
        val company = UUID.randomUUID()
        sql(
            database,
            "INSERT INTO company (id, name, created_at, updated_at) VALUES ('$company', 'ACME', now(), now())",
        )
        insertApplication(database, company, "00000000-0000-0000-0000-0000000000a1", appliedWeeksAgo = 20)
        insertApplication(database, company, "00000000-0000-0000-0000-0000000000a2", appliedWeeksAgo = 2)
        start(database, worker = true)
        val jobs = app.getBean(JobSchedulerPort::class.java)
        val log = app.getBean(ListJobsUseCase::class.java)

        repeat(2) { run ->
            jobs.enqueue(JobRequest(JobType("ghosted-suggestion")))
            waitUntil({ allJobs(log) }) {
                finished(log).count { it.name == "ghosted-suggestion" && it.status == JobStatus.SUCCEEDED } == run + 1
            }
        }

        assertOneGhostedSuggestionAndNoStatusChange(database)
    }

    private fun assertOneGhostedSuggestionAndNoStatusChange(database: String) {
        count(
            database,
            "SELECT count(*) FROM task WHERE state = 'SUGGESTED' AND suggestion_rule = 'ghosted-suggestion' " +
                "AND application_id = '00000000-0000-0000-0000-0000000000a1'",
        ) shouldBe 1
        count(database, "SELECT count(*) FROM task") shouldBe 1
        count(database, "SELECT count(*) FROM application WHERE status = 'APPLIED'") shouldBe 2
        count(database, "SELECT count(*) FROM application_status_change") shouldBe 4
        count(
            database,
            "SELECT count(*) FROM changelog_entry WHERE actor_kind = 'SYSTEM' AND actor_name = 'ghosted-suggestion' " +
                "AND entity_type = 'task'",
        ) shouldBe 1
        count(database, "SELECT count(*) FROM changelog_entry WHERE entity_type = 'application'") shouldBe 0
    }

    private fun insertApplication(
        database: String,
        company: UUID,
        id: String,
        appliedWeeksAgo: Int,
    ) {
        val applied = "now() - interval '$appliedWeeksAgo weeks'"
        sql(
            database,
            "INSERT INTO application (id, company_id, title, status, created_at, updated_at) " +
                "VALUES ('$id', '$company', 'Backend Engineer', 'APPLIED', $applied - interval '1 day', $applied)",
        )
        sql(
            database,
            "INSERT INTO application_status_change (application_id, from_status, to_status, actor_kind, changed_at) " +
                "VALUES ('$id', NULL, 'DISCOVERED', 'USER', $applied - interval '1 day'), " +
                "('$id', 'DISCOVERED', 'APPLIED', 'USER', $applied)",
        )
    }

    private fun enqueue(
        jobs: JobSchedulerPort,
        type: String,
    ): JobId = (jobs.enqueue(JobRequest(JobType(type), mapOf("itemId" to "42"))) as JobResult.Success).value

    private fun allJobs(log: ListJobsUseCase) = log.execute(JobLogQuery(size = 100))

    private fun finished(log: ListJobsUseCase): List<JobLogEntry> {
        val statuses = setOf(JobStatus.SUCCEEDED, JobStatus.FAILED)
        return (log.execute(JobLogQuery(size = 100)) as JobResult.Success<JobLogPage>).value.entries.filter {
            it.status in statuses
        }
    }

    private fun start(
        database: String,
        worker: Boolean,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(JofiApplication::class.java, TestJobs::class.java)
            .profiles(*listOfNotNull("worker".takeIf { worker }).toTypedArray())
            .run(
                "--JOFI_DB_URL=$database",
                "--JOFI_DB_USERNAME=${postgres.username}",
                "--JOFI_DB_PASSWORD=${postgres.password}",
                "--jofi.data-dir=$dataDirectory",
                "--server.port=0",
                "--jobrunr.background-job-server.poll-interval-in-seconds=${POLL_INTERVAL.seconds}",
                "--jobrunr.jobs.retry-back-off-time-seed=1",
                "--jobrunr.jobs.default-number-of-retries=2",
            ).also { contexts += it }

    private fun port(context: ConfigurableApplicationContext): Int =
        requireNotNull((context as WebServerApplicationContext).webServer).port

    private fun freshDatabase(): String {
        val name = "jobs_${UUID.randomUUID().toString().replace("-", "")}"
        sql(postgres.jdbcUrl, "CREATE DATABASE $name")
        return postgres.jdbcUrl.replaceAfterLast("/", name)
    }

    private fun sql(
        database: String,
        statement: String,
    ) = DriverManager.getConnection(database, postgres.username, postgres.password).use {
        it.createStatement().execute(statement)
    }

    private fun count(
        database: String,
        query: String,
    ): Int =
        DriverManager.getConnection(database, postgres.username, postgres.password).use {
            val rows = it.createStatement().executeQuery(query)
            rows.next()
            rows.getInt(1)
        }

    private fun insertSession(
        database: String,
        id: String,
        expiresInMinutes: Long,
    ) {
        val now = System.currentTimeMillis()
        sql(
            database,
            "INSERT INTO spring_session VALUES " +
                "('$id', '$id', $now, $now, 3600, ${now + expiresInMinutes * 60_000}, 'owner')",
        )
    }

    private fun waitUntil(
        state: () -> Any? = { null },
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out after $TIMEOUT; last state: ${state()}" }
            Thread.sleep(250)
        }
    }

    /** The test's job handlers; they record where they ran. Not scanned: registered as a source. */
    class TestJobs {
        data class Run(
            val type: String,
            val profiles: List<String>,
            val arguments: Map<String, String>,
        )

        private fun handler(
            type: String,
            environment: Environment,
            outcome: (attempt: Int) -> JobOutcome,
        ) = object : JobHandlerPort {
            override val type = JobType(type)

            override fun run(arguments: Map<String, String>): JobOutcome {
                runs += Run(type, environment.activeProfiles.toList(), arguments)
                return outcome(runs.count { it.type == type })
            }
        }

        @Bean
        fun echoJob(environment: Environment): JobHandlerPort = handler("echo-test", environment) { JobOutcome.Done }

        @Bean
        fun flakyJob(environment: Environment): JobHandlerPort =
            handler("flaky-test", environment) {
                if (it <
                    3
                ) {
                    JobOutcome.Retry(FailureReason("try-again"))
                } else {
                    JobOutcome.Done
                }
            }

        @Bean
        fun brokenJob(environment: Environment): JobHandlerPort =
            handler("broken-test", environment) { JobOutcome.GiveUp(FailureReason("broken-for-test")) }

        @Bean
        fun explodingJob(environment: Environment): JobHandlerPort =
            handler("exploding-test", environment) { throw IOException("$PERSONAL_DATA was not found") }

        companion object {
            val runs: MutableList<Run> = CopyOnWriteArrayList()
        }
    }

    /** An HTTP client with cookies that echoes the CSRF cookie like the SPA. */
    private class Http(
        private val port: Int,
    ) {
        private val cookies = CookieManager()
        private val client = HttpClient.newBuilder().cookieHandler(cookies).build()

        fun get(path: String): HttpResponse<String> = send(HttpRequest.newBuilder(uri(path)).GET())

        fun firstRun(setupToken: String) {
            get("/api/auth/session")
            val csrf =
                cookies.cookieStore.cookies
                    .first { it.name == "XSRF-TOKEN" }
                    .value
            val body = """{"password":"a long enough test password","setupToken":"$setupToken"}"""
            val request =
                HttpRequest
                    .newBuilder(uri("/api/auth/first-run"))
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
            send(request).statusCode() shouldBe 204
        }

        private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")

        private fun send(request: HttpRequest.Builder): HttpResponse<String> =
            client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        val postgres = newPostgresContainer().apply { start() }
        val POLL_INTERVAL: Duration = Duration.ofSeconds(5)
        val TIMEOUT: Duration = Duration.ofSeconds(120)
        const val PERSONAL_DATA = "max.mustermann@example.org"
        val HANDLER: String = JofiJobRequestHandler::class.java.name
        const val CLEANUP_JOBS = "SELECT count(*) FROM jobrunr_jobs WHERE recurringjobid = 'session-cleanup'"
    }
}
