// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.system.application.port.ExpiredSessionsPort
import io.github.scriptibus.jofi.system.domain.SessionCleanupResult
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Deletes expired Spring Session JDBC sessions (`spring_session`; the attributes follow by
 * `ON DELETE CASCADE`), the same statement Spring Session's own cleanup runs. That cleanup is
 * switched off in `app` (`spring.session.jdbc.cleanup-cron: "-"`), so it runs as a worker job.
 */
@Component
class ExpiredSessionsRepository(
    private val dsl: DSLContext,
) : ExpiredSessionsPort {
    override fun deleteExpired(now: Instant): SessionCleanupResult =
        try {
            val expired = SPRING_SESSION.EXPIRY_TIME.lt(now.toEpochMilli())
            SessionCleanupResult.Cleaned(dsl.deleteFrom(SPRING_SESSION).where(expired).execute())
        } catch (exception: RuntimeException) {
            // Session ids are bearer credentials: only the exception type is logged.
            logger.error("Deleting expired sessions failed: {}", exception.javaClass.name)
            SessionCleanupResult.StorageFailure
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ExpiredSessionsRepository::class.java)
    }
}
