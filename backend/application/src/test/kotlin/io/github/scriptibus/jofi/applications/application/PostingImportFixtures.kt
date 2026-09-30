// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.PostingExtractionPort
import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingExtraction
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.companies.application.port.api.MatchCompanyPort
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.job.CronSchedule
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.RecurringJobId
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import java.util.UUID

/**
 * [ApplicationFixtures] plus in-memory sources, posting imports, a job queue, an extraction, a company match and the
 * AI assignment check, with a transaction that also restores the sources and imports.
 */
class PostingImportFixtures {
    val base = ApplicationFixtures()
    val imports = linkedMapOf<ImportId, PostingImport>()
    val queued = mutableListOf<JobRequest>()
    val extracted = mutableListOf<DescriptionText>()
    val matched = mutableListOf<Pair<String, Actor>>()
    var extraction: PostingExtraction = PostingExtraction.Failed(ImportFailure.AI_UNAVAILABLE)
    var match: MatchCompanyPort.Match = MatchCompanyPort.Match.Found(ApplicationFixtures.ACME.value)
    var assignment: CheckAiTaskAssignedPort.Assignment = CheckAiTaskAssignedPort.Assignment.Assigned
    var failingQueue = false
    var failingImports = false

    /** A version another run stored between this use case's read and its write. */
    var concurrentAttempt: Int? = null

    val sources =
        object : ApplicationSourceRepositoryPort {
            override fun add(
                source: ApplicationSource,
                discovery: DescriptionSnapshot?,
            ): ApplicationStoreResult<Unit> {
                val application = base.applications[source.application]
                return when {
                    application == null -> {
                        ApplicationStoreResult.NotFound
                    }

                    application.sources.size >= Application.MAX_SOURCES -> {
                        ApplicationStoreResult.SourceLimitReached
                    }

                    base.failingStore -> {
                        ApplicationStoreResult.StorageFailure("add source")
                    }

                    else -> {
                        base.applications[source.application] = application.copy(sources = application.sources + source)
                        discovery?.let { base.descriptions += it }
                        ApplicationStoreResult.Success(Unit)
                    }
                }
            }

            override fun findById(
                application: ApplicationId,
                id: SourceId,
            ): ApplicationStoreResult<ApplicationSource> = error("Not used by these use cases")

            override fun updateAvailability(source: ApplicationSource): ApplicationStoreResult<Unit> =
                error("Not used by these use cases")

            override fun findByOriginalUrl(url: WebAddress): ApplicationStoreResult<List<ApplicationSource>> =
                error("Not used by these use cases")
        }

    val importPort =
        object : PostingImportRepositoryPort {
            override fun add(postingImport: PostingImport): ApplicationStoreResult<Unit> {
                if (failingImports) return ApplicationStoreResult.StorageFailure("add import")
                imports[postingImport.id] = postingImport
                return ApplicationStoreResult.Success(Unit)
            }

            override fun findById(id: ImportId): ApplicationStoreResult<PostingImport> =
                imports[id]?.let { ApplicationStoreResult.Success(it) } ?: ApplicationStoreResult.NotFound

            override fun update(
                current: PostingImport,
                next: PostingImport,
            ): ApplicationStoreResult<Unit> {
                val stored = imports[current.id]
                return when {
                    stored == null -> {
                        ApplicationStoreResult.NotFound
                    }

                    failingImports -> {
                        ApplicationStoreResult.StorageFailure("update import")
                    }

                    stored.status != current.status || (concurrentAttempt ?: stored.attempt) != current.attempt -> {
                        ApplicationStoreResult.VersionConflict
                    }

                    else -> {
                        imports[current.id] = next
                        ApplicationStoreResult.Success(Unit)
                    }
                }
            }
        }

    val jobs =
        object : JobSchedulerPort {
            override fun enqueue(request: JobRequest): JobResult<JobId> {
                if (failingQueue) return JobResult.StorageFailure("enqueue")
                queued += request
                return JobResult.Success(JobId(UUID.randomUUID()))
            }

            override fun scheduleRecurring(
                id: RecurringJobId,
                schedule: CronSchedule,
                request: JobRequest,
            ): JobResult<Unit> = error("Not used by these use cases")

            override fun cancel(id: JobId): JobResult<Unit> = error("Not used by these use cases")

            override fun cancelRecurring(id: RecurringJobId): JobResult<Unit> = error("Not used by these use cases")
        }

    val extractionPort =
        object : PostingExtractionPort {
            override fun extract(text: DescriptionText): PostingExtraction {
                extracted += text
                return extraction
            }
        }

    val companies =
        object : MatchCompanyPort {
            override fun execute(
                name: String,
                actor: Actor,
            ): MatchCompanyPort.Match {
                matched += name to actor
                return match
            }
        }

    val ai =
        object : CheckAiTaskAssignedPort {
            override fun execute(task: AiTask): CheckAiTaskAssignedPort.Assignment {
                check(task == AiTask.EXTRACTION) { "Only the extraction task is asked" }
                return assignment
            }
        }

    val transactions =
        object : TransactionPort {
            override fun <T> inTransaction(
                commitIf: (T) -> Boolean,
                work: () -> T,
            ): T {
                val importsBefore = imports.toMap()
                return base.transactions.inTransaction(commitIf) {
                    work().also {
                        if (!commitIf(it)) {
                            imports.clear()
                            imports.putAll(importsBefore)
                        }
                    }
                }
            }
        }

    val discovered = AddDiscoveredApplicationUseCase(base.repository, sources, base.changelog, transactions, CLOCK)
}
