// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.PasswordHasherPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.LoginBackoff
import io.github.scriptibus.jofi.system.domain.Password
import io.github.scriptibus.jofi.system.domain.PasswordHash
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Hand-written fakes for the auth use cases: they record what happened instead of mocking it. */
object AuthFixtures {
    val NOW: Instant = Instant.parse("2026-09-30T10:00:00Z")
    val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    val client = ThrottleKey.Client("192.0.2.10")
    const val PASSWORD = "correct horse battery staple"

    fun account(password: String = PASSWORD) =
        UserAccount(FakeHasher.hashOf(password), NOW.minusSeconds(3600), NOW.minusSeconds(3600))
}

/** "Hashes" by prefixing, so tests can see which password a stored hash belongs to. */
object FakeHasher : PasswordHasherPort {
    fun hashOf(password: String) = PasswordHash("fake:$password")

    override fun hash(password: Password) = hashOf(password.reveal())

    override fun matches(
        password: Password,
        hash: PasswordHash,
    ) = hash == hashOf(password.reveal())
}

class FakeUsers(
    var account: UserAccount? = null,
    var failing: Boolean = false,
) : UserAccountPort {
    override fun find(): UserAccountStoreResult<UserAccount?> =
        if (failing) UserAccountStoreResult.StorageFailure("find") else UserAccountStoreResult.Success(account)

    override fun create(account: UserAccount): UserAccountStoreResult<Unit> {
        if (this.account != null) return UserAccountStoreResult.AlreadyExists
        this.account = account
        return UserAccountStoreResult.Success(Unit)
    }

    override fun update(account: UserAccount): UserAccountStoreResult<Unit> {
        if (this.account == null) return UserAccountStoreResult.NotFound
        this.account = account
        return UserAccountStoreResult.Success(Unit)
    }
}

class FakeThrottle(
    var throttled: ThrottleDecision.Throttled? = null,
) : LoginThrottlePort {
    val attempts = mutableListOf<ThrottleKey>()
    val resets = mutableListOf<ThrottleKey>()

    override fun attempt(
        key: ThrottleKey,
        policy: LoginBackoff,
        now: Instant,
    ): ThrottleDecision {
        attempts += key
        return throttled ?: ThrottleDecision.Allowed
    }

    override fun reset(key: ThrottleKey) {
        resets += key
    }
}

class FakeChangelog(
    var failing: Boolean = false,
) : ChangelogPort {
    val entries = mutableListOf<ChangelogEntry>()

    override fun append(entry: ChangelogEntry): ChangelogResult<Unit> {
        if (failing) return ChangelogResult.StorageFailure("append")
        entries += entry
        return ChangelogResult.Success(Unit)
    }

    override fun listByEntity(
        entity: EntityRef,
        limit: ChangelogLimit,
    ) = ChangelogResult.Success(entries.filter { it.entity == entity })

    override fun listRecent(limit: ChangelogLimit) = ChangelogResult.Success(entries.toList())
}

/** Commits or "rolls back" by restoring the users' previous account and the changelog. */
class FakeTransactions(
    private val users: FakeUsers,
    private val changelog: FakeChangelog,
) : TransactionPort {
    var rolledBack = false

    override fun <T> inTransaction(
        commitIf: (T) -> Boolean,
        work: () -> T,
    ): T {
        val before = users.account
        val entries = changelog.entries.size
        val result = work()
        if (!commitIf(result)) {
            rolledBack = true
            users.account = before
            while (changelog.entries.size > entries) changelog.entries.removeLast()
        }
        return result
    }
}
