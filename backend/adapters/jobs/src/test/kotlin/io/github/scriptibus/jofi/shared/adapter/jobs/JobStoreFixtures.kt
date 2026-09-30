// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import org.jobrunr.jobs.Job
import org.jobrunr.jobs.JobDetails
import org.jobrunr.jobs.states.JobState
import org.jobrunr.storage.InMemoryStorageProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** JobRunr's in-memory store behind the same JSON mapper and job mapper as production. */
fun inMemoryJobStore(): InMemoryStorageProvider =
    InMemoryStorageProvider().apply { setJobMapper(AllowlistJobMapper(JobStore.jsonMapper())) }

/** A stored job of [type] with the given state [history] (oldest first). */
fun jobWithHistory(
    type: String,
    vararg history: JobState,
): Job =
    Job(UUID.randomUUID(), 0, JobDetails(JofiJobRequest(type)), history.toList(), ConcurrentHashMap()).apply {
        jobName = type
    }
