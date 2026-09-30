// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.job

/**
 * Why a job run failed, as a short lower-case code such as `storage-failure`. The job log shows it
 * and the job store keeps it, so it is a code chosen by the handler and never carries data
 * (exception messages can hold personal data, threat model T4).
 */
@JvmInline
value class FailureReason(
    val code: String,
) {
    init {
        require(code.length <= MAX_LENGTH && SLUG.matches(code)) {
            "A failure reason is a lower-case slug of at most $MAX_LENGTH characters"
        }
    }

    companion object {
        const val MAX_LENGTH = 64

        /** A handler failed in a way it did not report (an exception); the worker retries it. */
        val UNEXPECTED = FailureReason("unexpected-error")

        /** No handler is registered for the job's type; retrying cannot help. */
        val UNKNOWN_TYPE = FailureReason("unknown-job-type")
    }
}

/**
 * What a job handler reports after one run. The worker retries [Retry] with an exponential backoff
 * until the retries are used up, and never retries [GiveUp]. The data a job was triggered for stays
 * where it is (arguments are ids), so a failed job loses nothing and can be triggered again.
 */
sealed interface JobOutcome {
    data object Done : JobOutcome

    /** A temporary failure (storage, network, provider): try again later. */
    data class Retry(
        val reason: FailureReason,
    ) : JobOutcome

    /** A permanent failure (the target no longer exists, the input is invalid): do not retry. */
    data class GiveUp(
        val reason: FailureReason,
    ) : JobOutcome
}
