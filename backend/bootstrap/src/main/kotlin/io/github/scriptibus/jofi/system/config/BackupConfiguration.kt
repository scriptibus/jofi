// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.config

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.system.application.ExportBackupUseCase
import io.github.scriptibus.jofi.system.application.InstallBackupUseCase
import io.github.scriptibus.jofi.system.application.RecoverRestoreUseCase
import io.github.scriptibus.jofi.system.application.RestoreBackupUseCase
import io.github.scriptibus.jofi.system.application.StageBackupUseCase
import io.github.scriptibus.jofi.system.application.VerifyPasswordUseCase
import io.github.scriptibus.jofi.system.application.port.BackupArchivePort
import io.github.scriptibus.jofi.system.application.port.BackupLockPort
import io.github.scriptibus.jofi.system.application.port.BuildInfoPort
import io.github.scriptibus.jofi.system.application.port.DatabaseBackupPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyBackupPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyRecordPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Wires backup and restore (ADR-0042). */
@Configuration(proxyBeanMethods = false)
class BackupConfiguration {
    @Bean
    fun exportBackupUseCase(
        ports: BackupPorts,
        buildInfo: BuildInfoPort,
        changelog: ChangelogPort,
        verifyPassword: VerifyPasswordUseCase,
    ): ExportBackupUseCase =
        ExportBackupUseCase(
            ports.archive,
            ports.database,
            ports.masterKey,
            buildInfo,
            changelog,
            verifyPassword,
            ports.lock,
        )

    @Bean
    fun stageBackupUseCase(ports: BackupPorts): StageBackupUseCase =
        StageBackupUseCase(ports.archive, ports.database, ports.lock)

    @Bean
    fun installBackupUseCase(
        ports: BackupPorts,
        keyRecords: MasterKeyRecordPort,
        changelog: ChangelogPort,
        clock: Clock,
    ): InstallBackupUseCase =
        InstallBackupUseCase(ports.archive, ports.database, ports.masterKey, keyRecords, changelog, clock)

    @Bean
    fun recoverRestoreUseCase(
        ports: BackupPorts,
        changelog: ChangelogPort,
    ): RecoverRestoreUseCase = RecoverRestoreUseCase(ports.archive, ports.masterKey, changelog, ports.database)

    @Bean
    fun restoreBackupUseCase(
        ports: BackupPorts,
        use: BackupUseCases,
        transactions: TransactionPort,
    ): RestoreBackupUseCase =
        RestoreBackupUseCase(
            ports.archive,
            use.install,
            use.recover,
            use.confirmAction,
            transactions,
            use.verifyPassword,
            ports.lock,
        )

    @Bean
    fun backupPorts(
        archive: BackupArchivePort,
        database: DatabaseBackupPort,
        masterKey: MasterKeyBackupPort,
        lock: BackupLockPort,
    ): BackupPorts = BackupPorts(archive, database, masterKey, lock)

    @Bean
    fun backupUseCases(
        install: InstallBackupUseCase,
        recover: RecoverRestoreUseCase,
        confirmAction: ConfirmActionUseCase,
        verifyPassword: VerifyPasswordUseCase,
    ): BackupUseCases = BackupUseCases(install, recover, confirmAction, verifyPassword)

    /** The ports every backup use case needs, grouped to keep the bean methods short. */
    class BackupPorts(
        val archive: BackupArchivePort,
        val database: DatabaseBackupPort,
        val masterKey: MasterKeyBackupPort,
        val lock: BackupLockPort,
    )

    /** The use cases a restore builds on. */
    class BackupUseCases(
        val install: InstallBackupUseCase,
        val recover: RecoverRestoreUseCase,
        val confirmAction: ConfirmActionUseCase,
        val verifyPassword: VerifyPasswordUseCase,
    )
}
