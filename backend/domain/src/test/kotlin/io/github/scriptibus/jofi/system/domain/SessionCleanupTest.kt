// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZoneOffset

class SessionCleanupTest {
    @Test
    fun `the session cleanup runs hourly in UTC without arguments and acts under its own name`() {
        SessionCleanup.TYPE.name shouldBe "session-cleanup"
        SessionCleanup.RECURRING_ID.value shouldBe "session-cleanup"
        SessionCleanup.REQUEST.arguments shouldBe emptyMap()
        SessionCleanup.SCHEDULE.expression shouldBe "0 * * * *"
        SessionCleanup.SCHEDULE.zone shouldBe ZoneOffset.UTC
        SessionCleanup.SCHEDULE.maxRandomDelay shouldBe Duration.ZERO
        SessionCleanup.ACTOR_NAME shouldBe "session-cleanup"
    }
}
