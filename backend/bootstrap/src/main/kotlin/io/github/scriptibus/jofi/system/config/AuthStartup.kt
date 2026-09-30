// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.config

import io.github.scriptibus.jofi.system.application.PrepareFirstRunUseCase
import io.github.scriptibus.jofi.system.application.RecoverRestoreUseCase
import io.github.scriptibus.jofi.system.application.ResetPasswordUseCase
import io.github.scriptibus.jofi.system.application.VerifyMasterKeyUseCase
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.MasterKeyCheck
import io.github.scriptibus.jofi.system.domain.MasterKeyCheck.Reason
import io.github.scriptibus.jofi.system.domain.PasswordResetResult
import io.github.scriptibus.jofi.system.domain.backup.RestoreRecoveryResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.core.env.Environment

/**
 * Startup order (ADR-0035, ADR-0042): finish or undo a restore a crash interrupted (so database, files
 * and keyset belong together again), check the master keyset (refuse to start rather than lose
 * secrets), apply a requested password reset, then issue or remove the setup token.
 *
 * A [SmartLifecycle] in the phase just before the web server's: lifecycle beans start after the
 * context refresh, so the image build's AOT training run (`spring.context.exit=onRefresh`) exits
 * before this runs, and the server binds its port only after the checks passed. A failure here stops
 * the startup before any request is answered.
 */
class AuthStartup(
    private val recoverRestore: RecoverRestoreUseCase,
    private val verifyMasterKey: VerifyMasterKeyUseCase,
    private val resetPassword: ResetPasswordUseCase,
    private val prepareFirstRun: PrepareFirstRunUseCase,
    private val environment: Environment,
) : SmartLifecycle {
    @Volatile
    private var running = false

    @Volatile
    private var checked = false

    override fun start() {
        // A context restart (stop, then start) must not apply the reset or touch the keyset again.
        if (!checked) {
            recoverRestores()
            checkMasterKey()
            reset()
            if (prepareFirstRun.execute() == AuthSideEffectResult.Failure) {
                logger.error("Preparing first run failed; see the errors above")
            }
            checked = true
        }
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = PHASE

    private fun recoverRestores() {
        val outcome = recoverRestore.execute()
        check(outcome != RestoreRecoveryResult.FAILED) {
            "Refusing to start: a restore was interrupted and could not be finished or undone; see the errors " +
                "above. Its work directory (JOFI_DATA_DIR/backup-work) is kept, and the next start tries again."
        }
        if (outcome ==
            RestoreRecoveryResult.ROLLED_FORWARD
        ) {
            logger.warn("Finished a restore interrupted after its commit")
        }
        if (outcome == RestoreRecoveryResult.ROLLED_BACK) logger.warn("Undid a restore interrupted before its commit")
    }

    private fun checkMasterKey() {
        val acceptLoss = flag("jofi.secrets.accept-loss")
        val check = verifyMasterKey.execute(acceptLoss)
        if (check is MasterKeyCheck.Refused) error(refusal(check.reason))
        // A forgotten flag would silently accept the next loss: refuse until it is removed.
        check(!(acceptLoss && check == MasterKeyCheck.Ready)) {
            "Refusing to start: JOFI_ACCEPT_SECRET_LOSS=true is set, but the master keyset is intact. " +
                "Remove JOFI_ACCEPT_SECRET_LOSS, so a future loss is never accepted silently."
        }
        if (check == MasterKeyCheck.LossAccepted) {
            logger.error(
                "JOFI_ACCEPT_SECRET_LOSS: the previous master keyset is gone and a new one is in use. Every " +
                    "stored secret (API keys) is unreadable: enter them again, then remove JOFI_ACCEPT_SECRET_LOSS.",
            )
        }
    }

    private fun reset() {
        val outcome =
            when (resetPassword.execute(requested = flag("jofi.auth.reset-password"))) {
                PasswordResetResult.NotRequested -> {
                    return
                }

                PasswordResetResult.Reset -> {
                    "the password was reset and every session ended"
                }

                PasswordResetResult.NothingToReset -> {
                    "there was no password to reset"
                }

                PasswordResetResult.ResetWithFailures -> {
                    "the password was reset, but see the errors above"
                }

                PasswordResetResult.AlreadyApplied -> {
                    "IGNORED: this reset was already applied or first run is still pending; nothing was reset"
                }

                PasswordResetResult.StorageFailure -> {
                    "the reset failed, see the errors above"
                }
            }
        logger.warn("JOFI_RESET_PASSWORD: {}. Remove JOFI_RESET_PASSWORD now.", outcome)
    }

    private fun refusal(reason: Reason): String {
        val keyset = "${environment.getProperty("jofi.data-dir")}/secrets/master-keyset.json"
        val cause =
            when (reason) {
                Reason.KEYSET_MISSING -> "the master keyset $keyset is missing, but this database was used with one"
                Reason.KEYSET_MISMATCH -> "the master keyset $keyset is not the one this database was used with"
                Reason.SECRETS_WITHOUT_KEYSET -> "the database holds secrets, but there is no master keyset at $keyset"
                Reason.KEYSET_UNREADABLE -> "the master keyset $keyset cannot be read or written; see the errors above"
                Reason.STORAGE_FAILURE -> "the database could not be read; see the errors above"
            }
        return "Refusing to start: $cause. Restore the data volume (JOFI_DATA_DIR) from the backup that belongs " +
            "to this database. To start over with a new keyset instead, set JOFI_ACCEPT_SECRET_LOSS=true once: " +
            "stored API keys become unreadable and must be entered again (ADR-0035)."
    }

    private fun flag(name: String): Boolean = environment.getProperty(name, Boolean::class.java, false)

    companion object {
        /** Lower phases start first: this one starts immediately before the web server binds its port. */
        const val PHASE: Int = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1

        private val logger: Logger = LoggerFactory.getLogger(AuthStartup::class.java)
    }
}
