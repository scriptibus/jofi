// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.config

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.system.application.ChangePasswordUseCase
import io.github.scriptibus.jofi.system.application.CompleteFirstRunUseCase
import io.github.scriptibus.jofi.system.application.GetAuthStatusUseCase
import io.github.scriptibus.jofi.system.application.GetSessionAccountUseCase
import io.github.scriptibus.jofi.system.application.GetSystemInfoUseCase
import io.github.scriptibus.jofi.system.application.LogInUseCase
import io.github.scriptibus.jofi.system.application.PrepareFirstRunUseCase
import io.github.scriptibus.jofi.system.application.VerifyPasswordUseCase
import io.github.scriptibus.jofi.system.application.port.BuildInfoPort
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.PasswordHasherPort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.application.port.UserSessionsPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Wires the `system` context's use cases; domain and application stay free of Spring. */
@Configuration(proxyBeanMethods = false)
class SystemConfiguration {
    @Bean
    fun getSystemInfoUseCase(buildInfo: BuildInfoPort): GetSystemInfoUseCase = GetSystemInfoUseCase(buildInfo)

    @Bean
    fun getAuthStatusUseCase(users: UserAccountPort): GetAuthStatusUseCase = GetAuthStatusUseCase(users)

    @Bean
    fun getSessionAccountUseCase(users: UserAccountPort): GetSessionAccountUseCase = GetSessionAccountUseCase(users)

    @Bean
    fun prepareFirstRunUseCase(
        users: UserAccountPort,
        setupToken: SetupTokenPort,
    ): PrepareFirstRunUseCase = PrepareFirstRunUseCase(users, setupToken)

    @Bean
    fun completeFirstRunUseCase(
        auth: AuthPorts,
        setupToken: SetupTokenPort,
        changelog: ChangelogPort,
        transactions: TransactionPort,
    ): CompleteFirstRunUseCase =
        CompleteFirstRunUseCase(auth.users, auth.hasher, setupToken, auth.throttle, changelog, transactions, auth.clock)

    @Bean
    fun logInUseCase(auth: AuthPorts): LogInUseCase = LogInUseCase(auth.users, auth.hasher, auth.throttle, auth.clock)

    @Bean
    fun verifyPasswordUseCase(auth: AuthPorts): VerifyPasswordUseCase =
        VerifyPasswordUseCase(auth.users, auth.hasher, auth.throttle, auth.clock)

    @Bean
    fun changePasswordUseCase(
        verifyPassword: VerifyPasswordUseCase,
        auth: AuthPorts,
        sessions: UserSessionsPort,
        changelog: ChangelogPort,
        transactions: TransactionPort,
    ): ChangePasswordUseCase =
        ChangePasswordUseCase(verifyPassword, auth.users, auth.hasher, sessions, changelog, transactions, auth.clock)

    @Bean
    fun authPorts(
        users: UserAccountPort,
        hasher: PasswordHasherPort,
        throttle: LoginThrottlePort,
        clock: Clock,
    ): AuthPorts = AuthPorts(users, hasher, throttle, clock)

    /** The ports every password check needs, grouped to keep the bean methods short. */
    class AuthPorts(
        val users: UserAccountPort,
        val hasher: PasswordHasherPort,
        val throttle: LoginThrottlePort,
        val clock: Clock,
    )
}
