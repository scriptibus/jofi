// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.config

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.tasks.application.CompleteTaskUseCase
import io.github.scriptibus.jofi.tasks.application.CreateTaskUseCase
import io.github.scriptibus.jofi.tasks.application.DeleteTaskUseCase
import io.github.scriptibus.jofi.tasks.application.GetTaskDashboardUseCase
import io.github.scriptibus.jofi.tasks.application.GetTaskUseCase
import io.github.scriptibus.jofi.tasks.application.ListDoneTasksUseCase
import io.github.scriptibus.jofi.tasks.application.ListTaskGroupsUseCase
import io.github.scriptibus.jofi.tasks.application.ReopenTaskUseCase
import io.github.scriptibus.jofi.tasks.application.UpdateTaskUseCase
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The task use cases: create, read, edit, complete, reopen and delete (#93), the grouped list (#94), the dashboard's
 * tasks (#113); the suggestions are wired in [TaskSuggestionsConfiguration].
 */
@Configuration(proxyBeanMethods = false)
class TasksConfiguration {
    @Bean
    fun createTaskUseCase(
        tasks: TaskRepositoryPort,
        audit: TaskAudit,
    ): CreateTaskUseCase = CreateTaskUseCase(tasks, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun getTaskUseCase(tasks: TaskRepositoryPort): GetTaskUseCase = GetTaskUseCase(tasks)

    @Bean
    fun listTaskGroupsUseCase(
        tasks: TaskRepositoryPort,
        clock: Clock,
    ): ListTaskGroupsUseCase = ListTaskGroupsUseCase(tasks, clock)

    @Bean
    fun listDoneTasksUseCase(tasks: TaskRepositoryPort): ListDoneTasksUseCase = ListDoneTasksUseCase(tasks)

    @Bean
    fun getTaskDashboardUseCase(
        tasks: TaskRepositoryPort,
        clock: Clock,
    ): GetTaskDashboardUseCase = GetTaskDashboardUseCase(tasks, clock)

    @Bean
    fun updateTaskUseCase(
        tasks: TaskRepositoryPort,
        audit: TaskAudit,
    ): UpdateTaskUseCase = UpdateTaskUseCase(tasks, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun completeTaskUseCase(
        tasks: TaskRepositoryPort,
        audit: TaskAudit,
    ): CompleteTaskUseCase = CompleteTaskUseCase(tasks, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun reopenTaskUseCase(
        tasks: TaskRepositoryPort,
        audit: TaskAudit,
    ): ReopenTaskUseCase = ReopenTaskUseCase(tasks, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun deleteTaskUseCase(
        tasks: TaskRepositoryPort,
        confirmation: ConfirmActionUseCase,
        audit: TaskAudit,
    ): DeleteTaskUseCase = DeleteTaskUseCase(tasks, confirmation, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun taskAudit(
        changelog: ChangelogPort,
        transactions: TransactionPort,
        clock: Clock,
    ): TaskAudit = TaskAudit(changelog, transactions, clock)

    /** What every task mutation writes with: the changelog, in one transaction, at the clock's time. */
    class TaskAudit(
        val changelog: ChangelogPort,
        val transactions: TransactionPort,
        val clock: Clock,
    )
}
