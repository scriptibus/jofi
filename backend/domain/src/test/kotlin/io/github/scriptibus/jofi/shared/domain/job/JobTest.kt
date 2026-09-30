// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.job

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

class JobTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `a job request names a handler and carries id arguments`() {
        val request = JobRequest(JobType("scanner-run"), mapOf("scannerId" to "7"))

        request.type.name shouldBe "scanner-run"
        request.arguments shouldBe mapOf("scannerId" to "7")
        shouldThrow<IllegalArgumentException> { JobRequest(JobType("scanner-run"), mapOf(" " to "7")) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "Scanner", "scanner run", "-scanner", "scanner-", "scanner--run", "1scanner"])
    fun `job types and recurring ids are lower-case slugs`(invalid: String) {
        shouldThrow<IllegalArgumentException> { JobType(invalid) }
        shouldThrow<IllegalArgumentException> { RecurringJobId(invalid) }
    }

    @Test
    fun `valid slugs are accepted`() {
        JobType("housekeeping").name shouldBe "housekeeping"
        RecurringJobId("scanner-bundesagentur-2").value shouldBe "scanner-bundesagentur-2"
    }

    @Test
    fun `a cron schedule has five fields and a zone`() {
        CronSchedule("0 7 * * *", berlin).zone shouldBe berlin
        CronSchedule("  */15   * * * 1-5 ", berlin).expression shouldBe "  */15   * * * 1-5 "
        shouldThrow<IllegalArgumentException> { CronSchedule("0 7 * *", berlin) }
        shouldThrow<IllegalArgumentException> { CronSchedule("0 0 7 * * *", berlin) }
        shouldThrow<IllegalArgumentException> { CronSchedule(" ", berlin) }
    }

    @Test
    fun `a cron schedule may delay each run by up to an hour`() {
        CronSchedule("0 7 * * *", berlin).maxRandomDelay shouldBe Duration.ZERO
        CronSchedule("0 7 * * *", berlin, Duration.ofMinutes(15)).maxRandomDelay shouldBe Duration.ofMinutes(15)
        CronSchedule("0 7 * * *", berlin, CronSchedule.MAX_RANDOM_DELAY).maxRandomDelay shouldBe Duration.ofHours(1)
        shouldThrow<IllegalArgumentException> { CronSchedule("0 7 * * *", berlin, Duration.ofSeconds(-1)) }
        shouldThrow<IllegalArgumentException> { CronSchedule("0 7 * * *", berlin, Duration.ofMinutes(61)) }
    }

    @Test
    fun `every scheduler outcome is a value`() {
        val id = JobId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val results: List<JobResult<JobId>> =
            listOf(
                JobResult.Success(id),
                JobResult.NotFound,
                JobResult.InvalidSchedule,
                JobResult.StorageFailure("enqueue"),
            )

        val described =
            results.map {
                when (it) {
                    is JobResult.Success -> it.value.value.toString()
                    JobResult.NotFound -> "not found"
                    JobResult.InvalidSchedule -> "invalid schedule"
                    is JobResult.StorageFailure -> it.operation
                }
            }

        described shouldBe listOf("00000000-0000-0000-0000-000000000001", "not found", "invalid schedule", "enqueue")
    }
}
