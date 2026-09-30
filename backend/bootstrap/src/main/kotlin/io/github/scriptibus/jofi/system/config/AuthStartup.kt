// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.config

import io.github.scriptibus.jofi.system.application.PrepareFirstRunUseCase
import io.github.scriptibus.jofi.system.application.ResetPasswordUseCase
import io.github.scriptibus.jofi.system.application.VerifyMasterKeyUseCase
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.MasterKeyCheck
import io.github.scriptibus.jofi.system.domain.MasterKeyCheck.Reason
import io.github.scriptibus.jofi.system.domain.PasswordResetResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment

/**
 * Startup order (ADR-0035): check the master keyset (refuse to start rather than lose secrets), apply
 * a requested password reset, then issue or remove the setup token.
 */
class AuthStartup(
    private val verifyMasterKey: VerifyMasterKeyUseCase,
    private val resetPassword: ResetPasswordUseCase,
    private val prepareFirstRun: PrepareFirstRunUseCase,
    private val environment: Environment,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        checkMasterKey()
        reset()
        if (prepareFirstRun.execute() == AuthSideEffectResult.Failure) {
            logger.error("Preparing first run failed; see the errors above")
        }
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

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AuthStartup::class.java)
    }
}
