// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.session

import io.github.scriptibus.jofi.system.application.port.UserSessionsPort
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.SessionRef
import io.github.scriptibus.jofi.system.domain.UserAccount
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.stereotype.Component

/**
 * The user's sessions in Spring Session JDBC (tables `spring_session*`), found by principal name.
 * Without a web server (e.g. a non-web startup) there is no session store and nothing to end.
 */
@Component
class SpringSessionUserSessionsAdapter(
    private val sessionStore: ObjectProvider<FindByIndexNameSessionRepository<*>>,
) : UserSessionsPort {
    override fun endAll(): AuthSideEffectResult = endSessions { true }

    override fun endAllExcept(keep: SessionRef): AuthSideEffectResult = endSessions { it != keep.value }

    private fun endSessions(selected: (String) -> Boolean): AuthSideEffectResult {
        val sessions = sessionStore.ifAvailable ?: return AuthSideEffectResult.Success
        return try {
            sessions
                .findByPrincipalName(UserAccount.PRINCIPAL)
                .keys
                .filter(selected)
                .forEach(sessions::deleteById)
            AuthSideEffectResult.Success
        } catch (exception: RuntimeException) {
            // Session ids are bearer credentials: only the exception type is logged.
            logger.error("Ending other sessions failed: {}", exception.javaClass.name)
            AuthSideEffectResult.Failure
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SpringSessionUserSessionsAdapter::class.java)
    }
}
