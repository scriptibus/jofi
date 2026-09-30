// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class ChangelogEntryTest {
    private val now = Instant.parse("2026-09-30T08:00:00Z")
    private val application = EntityRef("application", "42")

    @Test
    fun `an entry records entity, actor, time, change and reason`() {
        val change = ChangeSummary("Status changed", listOf(FieldChange("status", "Applied", "Interview")))

        val entry = ChangelogEntry(application, Actor.Ai, now, change, reason = "Email from recruiter")

        entry.entity shouldBe application
        entry.actor shouldBe Actor.Ai
        entry.occurredAt shouldBe now
        entry.change.fieldChanges
            .single()
            .after shouldBe "Interview"
        entry.reason shouldBe "Email from recruiter"
    }

    @Test
    fun `reason and field changes are optional`() {
        val entry = ChangelogEntry(application, Actor.User, now, ChangeSummary("Created"))

        entry.reason shouldBe null
        entry.change.fieldChanges.shouldBeEmpty()
    }

    @Test
    fun `a given reason must not be blank`() {
        shouldThrow<IllegalArgumentException> {
            ChangelogEntry(application, Actor.User, now, ChangeSummary("Created"), reason = " ")
        }
    }

    @Test
    fun `entity type and id must not be blank`() {
        shouldThrow<IllegalArgumentException> { EntityRef("", "1") }
        shouldThrow<IllegalArgumentException> { EntityRef("application", " ") }
    }

    @Test
    fun `a change needs a description`() {
        shouldThrow<IllegalArgumentException> { ChangeSummary("  ") }
    }

    @Test
    fun `a field change names the field and changes the value`() {
        FieldChange("note", before = null, after = "call back").before shouldBe null
        FieldChange("note", before = "call back", after = null).after shouldBe null
        shouldThrow<IllegalArgumentException> { FieldChange("", "a", "b") }
        shouldThrow<IllegalArgumentException> { FieldChange("status", "Applied", "Applied") }
        shouldThrow<IllegalArgumentException> { FieldChange("status", null, null) }
    }
}
