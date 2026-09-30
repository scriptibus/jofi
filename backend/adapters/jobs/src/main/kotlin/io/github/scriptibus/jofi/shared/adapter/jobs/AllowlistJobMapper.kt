// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import org.jobrunr.JobRunrException
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.JobDetails
import org.jobrunr.jobs.RecurringJob
import org.jobrunr.jobs.mappers.JobMapper
import org.jobrunr.jobs.states.DeletedState
import org.jobrunr.jobs.states.EnqueuedState
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.JobState
import org.jobrunr.jobs.states.ProcessingState
import org.jobrunr.jobs.states.ScheduledState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.jobs.states.SucceededState
import org.jobrunr.utils.mapper.JsonMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads jobs and recurring jobs so that a row Jofi did not write never runs and never blocks the queue
 * (ADR-0038). [JobJsonGuard] checks the raw JSON first. Foreign job details (a lambda, a static
 * method, another class) are replaced before JobRunr reads the JSON; foreign type ids, or JSON JobRunr
 * cannot read, make a minimal job from the id, version and current state. Either way the job runs as
 * [JofiJobRequest.REJECTED_TYPE], which fails without retry. Only a row that is not a JSON object
 * with an id is left to fail the read, as it would without this mapper.
 */
class AllowlistJobMapper(
    private val jsonMapper: JsonMapper,
) : JobMapper(jsonMapper) {
    private val rejectedDetails by lazy { JobJsonGuard.toTree(jsonMapper.serialize(rejected())) }

    override fun deserializeJob(serializedJobAsString: String): Job {
        val root = JobJsonGuard.parse(serializedJobAsString) ?: return super.deserializeJob(serializedJobAsString)
        val problem = JobJsonGuard.problem(root)
        if (problem !=
            null
        ) {
            logger.error("Job {} was not written by Jofi ({}) and will not run", root.text("id"), problem)
        }
        return when {
            problem == null -> {
                readOrQuarantine(root) { super.deserializeJob(serializedJobAsString) }
                    .takeIf { JofiJobRequest.isJofiJob(it.jobDetails) } ?: minimalJob(root)
            }

            JobJsonGuard.hasForeignTypeIds(root) -> {
                minimalJob(root)
            }

            else -> {
                readOrQuarantine(root) { super.deserializeJob(JobJsonGuard.withDetails(root, rejectedDetails)) }
            }
        }
    }

    override fun deserializeRecurringJob(serializedJobAsString: String): RecurringJob {
        val root =
            JobJsonGuard.parse(serializedJobAsString) ?: return super.deserializeRecurringJob(serializedJobAsString)
        val problem = JobJsonGuard.problem(root)
        if (problem != null) logger.error("Recurring job {} was not written by Jofi ({})", root.text("id"), problem)
        return when {
            problem == null -> {
                readOrQuarantine(root) { super.deserializeRecurringJob(serializedJobAsString) }
                    .takeIf { JofiJobRequest.isJofiJob(it.jobDetails) } ?: minimalRecurringJob(root)
            }

            JobJsonGuard.hasForeignTypeIds(root) -> {
                minimalRecurringJob(root)
            }

            else -> {
                readOrQuarantine(root) {
                    super.deserializeRecurringJob(JobJsonGuard.withDetails(root, rejectedDetails))
                }
            }
        }
    }

    private inline fun <reified T> readOrQuarantine(
        root: ObjectNode,
        read: () -> T,
    ): T =
        try {
            read()
        } catch (exception: RuntimeException) {
            // The message may quote the stored JSON: log the id and the exception type only.
            logger.error("Job {} could not be read ({}) and will not run", root.text("id"), exception.javaClass.name)
            when (T::class) {
                RecurringJob::class -> minimalRecurringJob(root) as T
                else -> minimalJob(root) as T
            }
        }

    /** A job with the stored id, version and state, rejected details and no history before that state. */
    private fun minimalJob(root: ObjectNode): Job {
        val id = UUID.fromString(root.text("id") ?: throw JobRunrException("A stored job has no id"))
        val state = currentState(root)
        return Job(id, root.get("version")?.asInt() ?: 0, rejected(), listOf(state), ConcurrentHashMap()).also {
            it.jobName = JofiJobRequest.REJECTED_TYPE
        }
    }

    private fun minimalRecurringJob(root: ObjectNode): RecurringJob {
        val id = root.text("id") ?: throw JobRunrException("A stored recurring job has no id")
        val version = root.get("version")?.asInt() ?: 0

        fun recurring(
            schedule: String?,
            zone: String?,
        ) = RecurringJob(id, version, rejected(), schedule, zone, RecurringJob.CreatedBy.API, Instant.now())
        // Keep the stored schedule when it is valid; its runs are rejected either way.
        return runCatching { recurring(root.text("scheduleExpression"), root.text("zoneId")) }
            .getOrElse { recurring(YEARLY, ZoneOffset.UTC.id) }
            .also { it.jobName = JofiJobRequest.REJECTED_TYPE }
    }

    /** The state the row is in (JobRunr queries by state), rebuilt without reading any stored class. */
    private fun currentState(root: ObjectNode): JobState {
        val history = root.get("jobHistory")
        val last = history?.takeIf { it.isArray && !it.isEmpty }?.get(history.size() - 1)
        val name = last?.text("state")?.let { runCatching { StateName.valueOf(it) }.getOrNull() } ?: StateName.ENQUEUED
        return when (name) {
            StateName.AWAITING, StateName.SCHEDULED -> {
                ScheduledState(Instant.now(), REASON)
            }

            StateName.ENQUEUED -> {
                EnqueuedState()
            }

            StateName.PROCESSING -> {
                ProcessingState(UUID(0, 0), REASON)
            }

            StateName.SUCCEEDED -> {
                SucceededState(Duration.ZERO, Duration.ZERO)
            }

            StateName.FAILED -> {
                FailedState(
                    REASON,
                    JobRunFailedException(FailureReason(JofiJobRequest.REJECTED_TYPE), false),
                )
            }

            StateName.DELETED -> {
                DeletedState(REASON)
            }
        }
    }

    private fun rejected() = JobDetails(JofiJobRequest(type = JofiJobRequest.REJECTED_TYPE))

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AllowlistJobMapper::class.java)
        const val REASON = "Rejected: not written by Jofi"

        /** A harmless schedule for a recurring job whose own could not be read; its runs are rejected anyway. */
        const val YEARLY = "0 0 1 1 *"
    }
}
