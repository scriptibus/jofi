// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.GhostedSuggestion
import io.github.scriptibus.jofi.tasks.domain.SuggestionRun
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import java.time.Clock

/**
 * The daily Ghosted suggestion run (spec §6.2, [GhostedSuggestion]). It asks the applications context for the silent
 * applications (its named interface `api`; the tasks context depends on applications, never the reverse) and
 * suggests a task for each silence not suggested yet. A waiting suggestion whose silence ended (an answer came, the
 * status moved on, the user marked it Ghosted) is obsolete and dismissed. Both as [reconcileSuggestions] does, with
 * the actor [GhostedSuggestion.ACTOR]. It never changes an application.
 */
class SuggestGhostedApplicationsUseCase(
    private val candidates: FindGhostedCandidatesPort,
    private val tasks: TaskRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) {
    fun execute(): TaskResult<SuggestionRun> {
        val now = clock.storedNow()
        val silent =
            candidates.execute(now) as? FindGhostedCandidatesPort.Candidates.Found
                ?: return TaskResult.StorageFailure("find ghosted candidates")
        val wanted =
            silent.candidates.associate {
                GhostedSuggestion.origin(it.id, it.silentSince) to GhostedSuggestion.details(it.id, it.title)
            }
        return tasks.reconcileSuggestions(wanted, setOf(GhostedSuggestion.RULE), now, changelog, transactions)
    }
}
