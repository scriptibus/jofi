// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import org.jobrunr.jobs.Job
import org.jobrunr.jobs.JobDetails
import org.jobrunr.jobs.RecurringJob
import org.jobrunr.jobs.mappers.JobMapper
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider
import org.jobrunr.utils.mapper.JsonMapper
import org.jobrunr.utils.mapper.jackson3.Jackson3JsonMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.DeserializationConfig
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.deser.ValueDeserializerModifier
import tools.jackson.databind.module.SimpleModule
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import tools.jackson.databind.json.JsonMapper as JacksonJsonMapper

/**
 * The job store: JobRunr's tables in our PostgreSQL, created by our Flyway migration (ADR-0009),
 * read through a JSON mapper that refuses every class Jofi does not write (threat model T4/T6: the
 * job JSON names the classes to instantiate and the methods to call).
 */
object JobStore {
    /**
     * JobRunr on PostgreSQL without its own table creation or validation: Flyway owns the schema
     * (`JobRunrSchemaTest` proves it matches JobRunr's), and nothing connects while the context
     * starts, so the image's AOT training run works without a database.
     */
    fun storageProvider(
        dataSource: DataSource,
        jobMapper: JobMapper,
    ): StorageProvider =
        PostgresStorageProvider(dataSource, null, DatabaseOptions.NO_VALIDATE).apply { setJobMapper(jobMapper) }

    /** JobRunr's Jackson 3 mapper that builds no bean outside [ALLOWED_PREFIXES] (see [AllowlistModule]). */
    fun jsonMapper(): JsonMapper = Jackson3JsonMapper(JacksonJsonMapper.builder().addModule(AllowlistModule()))

    /** Classes JobRunr's mapper may build: JobRunr's own model, JDK types and our one job request. */
    val ALLOWED_PREFIXES = listOf("org.jobrunr.", "java.")
    val ALLOWED_CLASSES = setOf(JofiJobRequest::class.java.name)

    fun isAllowed(type: Class<*>): Boolean =
        when {
            type.isPrimitive -> true
            type.isArray -> isAllowed(type.componentType)
            else -> type.name in ALLOWED_CLASSES || ALLOWED_PREFIXES.any { type.name.startsWith(it) }
        }
}

/**
 * Replaces the deserializer of every bean class outside the allowlist by one that refuses to build
 * it. JobRunr reads a job parameter's class name from the stored JSON; a refused parameter becomes
 * "not deserializable" and the job fails instead of instantiating an arbitrary class.
 */
internal class AllowlistModule : SimpleModule("jofi-job-allowlist") {
    init {
        setDeserializerModifier(
            object : ValueDeserializerModifier() {
                override fun modifyDeserializer(
                    config: DeserializationConfig,
                    description: BeanDescription.Supplier,
                    deserializer: ValueDeserializer<*>,
                ): ValueDeserializer<*> =
                    if (JobStore.isAllowed(description.beanClass)) deserializer else Refused(description.beanClass.name)
            },
        )
    }

    private class Refused(
        private val className: String,
    ) : ValueDeserializer<Any>() {
        override fun deserialize(
            parser: JsonParser,
            context: DeserializationContext,
        ): Any = error("Class $className is not allowed in the job store")
    }
}

/**
 * Reads jobs and recurring jobs, and quarantines any whose details were not written by Jofi (a
 * lambda, a static method, another class): they keep id, state and history, but run as the
 * [JofiJobRequest.REJECTED_TYPE] job, which fails without retry. A tampered row therefore neither
 * runs nor blocks the queue.
 */
class AllowlistJobMapper(
    jsonMapper: JsonMapper,
) : JobMapper(jsonMapper) {
    override fun deserializeJob(serializedJobAsString: String): Job {
        val job = super.deserializeJob(serializedJobAsString)
        if (JofiJobRequest.isJofiJob(job.jobDetails)) return job
        logger.error("Job {} in the job store was not written by Jofi and will not run", job.id)
        return Job(job.id, job.version, rejected(), job.jobStates, ConcurrentHashMap(job.metadata)).also {
            it.jobName = JofiJobRequest.REJECTED_TYPE
            job.recurringJobId.ifPresent(it::setRecurringJobId)
        }
    }

    override fun deserializeRecurringJob(serializedJobAsString: String): RecurringJob {
        val job = super.deserializeRecurringJob(serializedJobAsString)
        if (JofiJobRequest.isJofiJob(job.jobDetails)) return job
        logger.error("Recurring job {} in the job store was not written by Jofi and will not run", job.id)
        return RecurringJob(
            job.id,
            job.version,
            rejected(),
            job.schedule,
            ZoneId.of(job.zoneId),
            job.createdBy,
            job.createdAt,
        ).also { it.jobName = JofiJobRequest.REJECTED_TYPE }
    }

    private fun rejected() = JobDetails(JofiJobRequest(type = JofiJobRequest.REJECTED_TYPE))

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AllowlistJobMapper::class.java)
    }
}
