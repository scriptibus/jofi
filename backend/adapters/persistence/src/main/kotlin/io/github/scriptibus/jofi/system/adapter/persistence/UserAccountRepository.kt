// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.AccountId
import io.github.scriptibus.jofi.system.domain.PasswordHash
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneOffset

/** jOOQ implementation of the single-row `user_account` table. */
@Component
class UserAccountRepository(
    private val dsl: DSLContext,
) : UserAccountPort {
    override fun find(): UserAccountStoreResult<UserAccount?> =
        guarded("find") {
            dsl.selectFrom(USER_ACCOUNT).fetchOne()?.let { record ->
                UserAccount(
                    accountId = AccountId(record.accountId),
                    passwordHash = PasswordHash(record.passwordHash),
                    createdAt = record.createdAt.toInstant(),
                    passwordChangedAt = record.passwordChangedAt.toInstant(),
                )
            }
        }

    override fun create(account: UserAccount): UserAccountStoreResult<Unit> =
        guarded("create") {
            val inserted =
                dsl
                    .insertInto(USER_ACCOUNT)
                    .set(USER_ACCOUNT.ACCOUNT_ID, account.accountId.value)
                    .set(USER_ACCOUNT.PASSWORD_HASH, account.passwordHash.encoded)
                    .set(USER_ACCOUNT.CREATED_AT, account.createdAt.atOffset(ZoneOffset.UTC))
                    .set(USER_ACCOUNT.PASSWORD_CHANGED_AT, account.passwordChangedAt.atOffset(ZoneOffset.UTC))
                    .onConflictDoNothing()
                    .execute()
            if (inserted == 0) return UserAccountStoreResult.AlreadyExists
        }

    override fun delete(): UserAccountStoreResult<Unit> =
        guarded("delete") {
            if (dsl.deleteFrom(USER_ACCOUNT).execute() == 0) return UserAccountStoreResult.NotFound
        }

    override fun update(account: UserAccount): UserAccountStoreResult<Unit> =
        guarded("update") {
            val updated =
                dsl
                    .update(USER_ACCOUNT)
                    .set(USER_ACCOUNT.PASSWORD_HASH, account.passwordHash.encoded)
                    .set(USER_ACCOUNT.PASSWORD_CHANGED_AT, account.passwordChangedAt.atOffset(ZoneOffset.UTC))
                    .execute()
            if (updated == 0) return UserAccountStoreResult.NotFound
        }

    // No exception crosses the port. Only the operation and the exception type are logged: messages
    // can carry bind values, here the password hash.
    private inline fun <T> guarded(
        operation: String,
        block: () -> T,
    ): UserAccountStoreResult<T> =
        try {
            UserAccountStoreResult.Success(block())
        } catch (exception: RuntimeException) {
            logger.error("User account {} failed: {}", operation, exception.javaClass.name)
            UserAccountStoreResult.StorageFailure(operation)
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(UserAccountRepository::class.java)
    }
}
