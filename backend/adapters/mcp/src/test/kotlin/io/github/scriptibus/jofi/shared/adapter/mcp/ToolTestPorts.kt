// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.application.RedactForAiUseCase
import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Ports the use cases behind the tools need besides their repository, for the tools' translation tests. */
object ToolTestPorts {
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"), ZoneOffset.UTC)

    /** The AI redaction with nothing flagged. */
    val redaction =
        RedactForAiUseCase(
            object : AiVisibilityPort {
                override fun rulesFor(sources: Set<ContentSource>) = AiVisibilityResult.Known(NeverSendRules.NONE)
            },
        )

    /** Runs the work without a database; a real transaction is the persistence adapter's job. */
    val transactions =
        object : TransactionPort {
            override fun <T> inTransaction(
                commitIf: (T) -> Boolean,
                work: () -> T,
            ): T = work()
        }

    /** Keeps the entries, so a test can assert who the use case recorded as the actor. */
    class RecordingChangelog : ChangelogPort {
        val entries = mutableListOf<ChangelogEntry>()

        override fun append(entry: ChangelogEntry): ChangelogResult<Unit> {
            entries += entry
            return ChangelogResult.Success(Unit)
        }

        override fun listByEntity(
            entity: EntityRef,
            limit: ChangelogLimit,
        ) = ChangelogResult.Success(entries.filter { it.entity == entity })

        override fun listRecent(limit: ChangelogLimit) = ChangelogResult.Success(entries.toList())
    }
}
