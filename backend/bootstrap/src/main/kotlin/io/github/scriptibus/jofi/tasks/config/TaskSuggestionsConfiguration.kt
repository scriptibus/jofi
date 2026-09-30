// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.config

import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.tasks.application.DismissTaskSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.ListSuggestedTasksUseCase
import io.github.scriptibus.jofi.tasks.application.ScheduleGhostedSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.SuggestGhostedApplicationsUseCase
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.config.TasksConfiguration.TaskAudit
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** Task suggestions: listing and dismissing them, and the daily Ghosted suggestion with its schedule (#85). */
@Configuration(proxyBeanMethods = false)
class TaskSuggestionsConfiguration {
    @Bean
    fun listSuggestedTasksUseCase(tasks: TaskRepositoryPort): ListSuggestedTasksUseCase =
        ListSuggestedTasksUseCase(tasks)

    @Bean
    fun dismissTaskSuggestionUseCase(
        tasks: TaskRepositoryPort,
        audit: TaskAudit,
    ): DismissTaskSuggestionUseCase =
        DismissTaskSuggestionUseCase(tasks, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun suggestGhostedApplicationsUseCase(
        candidates: FindGhostedCandidatesPort,
        tasks: TaskRepositoryPort,
        audit: TaskAudit,
    ): SuggestGhostedApplicationsUseCase =
        SuggestGhostedApplicationsUseCase(candidates, tasks, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun scheduleGhostedSuggestionUseCase(jobs: JobSchedulerPort): ScheduleGhostedSuggestionUseCase =
        ScheduleGhostedSuggestionUseCase(jobs)

    /** Only `app` registers the schedule; the worker runs it. */
    @Bean
    @Profile("!worker")
    fun ghostedSuggestionStartup(schedule: ScheduleGhostedSuggestionUseCase): GhostedSuggestionStartup =
        GhostedSuggestionStartup(schedule)
}
