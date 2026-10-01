// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.config

import io.github.scriptibus.jofi.applications.application.port.api.DescribeApplicationEventPort
import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.applications.application.port.api.FindSuggestionFactsPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.tasks.adapter.events.TaskSuggestionEventListener
import io.github.scriptibus.jofi.tasks.application.AcceptTaskSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.DismissTaskSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.ListSuggestedTasksUseCase
import io.github.scriptibus.jofi.tasks.application.RequestTaskSuggestionsUseCase
import io.github.scriptibus.jofi.tasks.application.ScheduleGhostedSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.ScheduleTaskSuggestionsUseCase
import io.github.scriptibus.jofi.tasks.application.SuggestGhostedApplicationsUseCase
import io.github.scriptibus.jofi.tasks.application.SuggestTasksUseCase
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.config.TasksConfiguration.TaskAudit
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Task suggestions: listing, accepting and dismissing them, the daily Ghosted suggestion (#85) and the rules of #95
 * (follow-up, interview preparation, offer answer), with their schedules and the listener that runs the rules after the
 * applications context's events.
 */
@Configuration(proxyBeanMethods = false)
class TaskSuggestionsConfiguration {
    @Bean
    fun listSuggestedTasksUseCase(tasks: TaskRepositoryPort): ListSuggestedTasksUseCase =
        ListSuggestedTasksUseCase(tasks)

    @Bean
    fun acceptTaskSuggestionUseCase(
        tasks: TaskRepositoryPort,
        audit: TaskAudit,
    ): AcceptTaskSuggestionUseCase =
        AcceptTaskSuggestionUseCase(tasks, audit.changelog, audit.transactions, audit.clock)

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
    fun suggestTasksUseCase(
        facts: FindSuggestionFactsPort,
        tasks: TaskRepositoryPort,
        audit: TaskAudit,
    ): SuggestTasksUseCase = SuggestTasksUseCase(facts, tasks, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun scheduleGhostedSuggestionUseCase(jobs: JobSchedulerPort): ScheduleGhostedSuggestionUseCase =
        ScheduleGhostedSuggestionUseCase(jobs)

    @Bean
    fun scheduleTaskSuggestionsUseCase(jobs: JobSchedulerPort): ScheduleTaskSuggestionsUseCase =
        ScheduleTaskSuggestionsUseCase(jobs)

    @Bean
    fun requestTaskSuggestionsUseCase(
        events: DescribeApplicationEventPort,
        jobs: JobSchedulerPort,
    ): RequestTaskSuggestionsUseCase = RequestTaskSuggestionsUseCase(events, jobs)

    @Bean
    fun taskSuggestionEventListener(request: RequestTaskSuggestionsUseCase): TaskSuggestionEventListener =
        TaskSuggestionEventListener(request)

    /** Only `app` registers the schedules; the worker runs them. */
    @Bean
    @Profile("!worker")
    fun taskSuggestionsStartup(
        ghosted: ScheduleGhostedSuggestionUseCase,
        rules: ScheduleTaskSuggestionsUseCase,
    ): TaskSuggestionsStartup = TaskSuggestionsStartup(ghosted, rules)
}
