// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import org.jobrunr.jobs.JobDetails
import org.jobrunr.jobs.lambdas.JobRequest as JobRunrJobRequest

/**
 * The only job payload in the job store: the job [type] and its [arguments] (ids and small
 * settings, never secrets or personal data), as JSON in `jobrunr_jobs`. Every job runs through
 * [JofiJobRequestHandler], which dispatches on [type]. A recurring schedule with a random delay
 * stores [maxRandomDelaySeconds]; its run then only schedules the real work (see the handler).
 *
 * The no-argument constructor (all parameters have defaults) and the concrete map type are what
 * JobRunr's Jackson mapper needs to read it back without type ids.
 */
data class JofiJobRequest(
    val type: String = "",
    val arguments: LinkedHashMap<String, String> = LinkedHashMap(),
    val maxRandomDelaySeconds: Long = 0,
) : JobRunrJobRequest {
    override fun getJobRequestHandler(): Class<JofiJobRequestHandler> = JofiJobRequestHandler::class.java

    // JobRunr may print the request (e.g. in debug logs): names only, never argument values.
    override fun toString(): String = "JofiJobRequest(type=$type, arguments=${arguments.keys})"

    companion object {
        /** The type a job gets when its stored details were not written by Jofi (see [AllowlistJobMapper]). */
        const val REJECTED_TYPE = "rejected-job"

        fun of(
            request: JobRequest,
            maxRandomDelaySeconds: Long = 0,
        ): JofiJobRequest = JofiJobRequest(request.type.name, LinkedHashMap(request.arguments), maxRandomDelaySeconds)

        /**
         * Whether [details] are exactly what Jofi writes: [JofiJobRequestHandler.run] with one
         * [JofiJobRequest]. Anything else (a lambda, a static method, another class) came from
         * outside Jofi and must never run.
         */
        fun isJofiJob(details: JobDetails): Boolean =
            details.className == JofiJobRequestHandler::class.java.name &&
                details.staticFieldName == null &&
                details.methodName == "run" &&
                details.jobParameters.singleOrNull()?.let { it.isDeserializable && it.getObject() is JofiJobRequest } ==
                true
    }
}
