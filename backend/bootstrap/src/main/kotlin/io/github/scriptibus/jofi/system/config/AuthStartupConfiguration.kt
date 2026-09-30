// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.config

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.system.application.PrepareFirstRunUseCase
import io.github.scriptibus.jofi.system.application.ResetPasswordUseCase
import io.github.scriptibus.jofi.system.application.VerifyMasterKeyUseCase
import io.github.scriptibus.jofi.system.application.port.MasterKeyPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyRecordPort
import io.github.scriptibus.jofi.system.application.port.PasswordResetMarkerPort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.application.port.UserSessionsPort
import io.github.scriptibus.jofi.system.config.SystemConfiguration.AuthPorts
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import java.time.Clock

/** Wires what runs once at startup: master key check, password recovery and the setup token. */
@Configuration(proxyBeanMethods = false)
class AuthStartupConfiguration {
    @Bean
    fun resetPasswordUseCase(
        auth: AuthPorts,
        sessions: UserSessionsPort,
        setupToken: SetupTokenPort,
        marker: PasswordResetMarkerPort,
        audit: AuditPorts,
    ): ResetPasswordUseCase =
        ResetPasswordUseCase(auth.users, sessions, setupToken, marker, audit.changelog, audit.transactions, auth.clock)

    @Bean
    fun auditPorts(
        changelog: ChangelogPort,
        transactions: TransactionPort,
    ): AuditPorts = AuditPorts(changelog, transactions)

    @Bean
    fun verifyMasterKeyUseCase(
        masterKey: MasterKeyPort,
        records: MasterKeyRecordPort,
        audit: AuditPorts,
        clock: Clock,
    ): VerifyMasterKeyUseCase = VerifyMasterKeyUseCase(masterKey, records, audit.changelog, audit.transactions, clock)

    /**
     * The master key check, the optional password reset and the setup token, once the app is up. A
     * runner, so it neither blocks the context refresh nor runs in the image build's AOT training run.
     * Only in `app`: the `worker` container shares image and volume, and a worker restart must never
     * reset the password or touch the keyset.
     */
    @Bean
    @ConditionalOnWebApplication
    @Profile("!worker")
    fun authStartup(
        verifyMasterKey: VerifyMasterKeyUseCase,
        resetPassword: ResetPasswordUseCase,
        prepareFirstRun: PrepareFirstRunUseCase,
        environment: Environment,
    ): AuthStartup = AuthStartup(verifyMasterKey, resetPassword, prepareFirstRun, environment)

    /** The changelog and its transaction, grouped to keep the bean methods short. */
    class AuditPorts(
        val changelog: ChangelogPort,
        val transactions: TransactionPort,
    )
}
