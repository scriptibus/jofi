// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.config

import io.github.scriptibus.jofi.setup.application.CheckAiTaskAssignedUseCase
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** What the setup context offers other contexts (named interface `api`, ADR-0051): reads only, never a change. */
@Configuration(proxyBeanMethods = false)
class SetupApiConfiguration {
    /** Whether a task has a model, before another context queues AI work the user waits for (#96). */
    @Bean
    fun checkAiTaskAssignedUseCase(assignments: ModelAssignmentPort): CheckAiTaskAssignedUseCase =
        CheckAiTaskAssignedUseCase(assignments)
}
