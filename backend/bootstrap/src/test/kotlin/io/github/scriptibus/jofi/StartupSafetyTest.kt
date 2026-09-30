// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.util.UUID

/**
 * Startup decisions that protect data (ADR-0035), each on its own database and data volume: a lost
 * or swapped master keyset stops the app instead of silently becoming a new key, the loss can only be
 * accepted explicitly, and `JOFI_RESET_PASSWORD` starts over with a new setup token.
 */
class StartupSafetyTest {
    @TempDir
    lateinit var dataDirectory: Path

    private val keyset: Path get() = dataDirectory.resolve("secrets/master-keyset.json")

    private fun freshDatabase(): String {
        val name = "startup_${UUID.randomUUID().toString().replace("-", "")}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().execute("CREATE DATABASE $name")
        }
        return postgres.jdbcUrl.replaceAfterLast("/", name)
    }

    private fun start(
        database: String,
        data: Path = dataDirectory,
        vararg extra: String,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(JofiApplication::class.java).run(
            "--JOFI_DB_URL=$database",
            "--JOFI_DB_USERNAME=${postgres.username}",
            "--JOFI_DB_PASSWORD=${postgres.password}",
            "--jofi.data-dir=$data",
            "--server.port=0",
            *extra,
        )

    private fun failure(block: () -> Unit): String {
        val thrown = shouldThrowAny(block)
        return generateSequence(thrown) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    @Test
    fun `a missing keyset stops the app until the loss is accepted`() {
        val database = freshDatabase()
        start(database).close()
        Files.exists(keyset) shouldBe true
        Files.delete(keyset)

        val message = failure { start(database).close() }

        message shouldContain "master keyset"
        message shouldContain "is missing"
        message shouldContain "JOFI_ACCEPT_SECRET_LOSS=true"
        Files.exists(keyset) shouldBe false
        start(database, dataDirectory, "--jofi.secrets.accept-loss=true").close()
        Files.exists(keyset) shouldBe true
        start(database).close()
    }

    @Test
    fun `a forgotten JOFI_ACCEPT_SECRET_LOSS stops the app while the keyset is intact`() {
        val database = freshDatabase()
        start(database).close()

        failure { start(database, dataDirectory, "--jofi.secrets.accept-loss=true").close() } shouldContain
            "Remove JOFI_ACCEPT_SECRET_LOSS"
    }

    @Test
    fun `another keyset than the recorded one stops the app`(
        @TempDir otherData: Path,
    ) {
        val database = freshDatabase()
        start(database).close()
        start(freshDatabase(), otherData).close()
        Files.copy(otherData.resolve("secrets/master-keyset.json"), keyset, StandardCopyOption.REPLACE_EXISTING)

        failure { start(database).close() } shouldContain "is not the one this database was used with"
    }

    @Test
    fun `stored secrets without any keyset stop the app`() {
        val database = freshDatabase()
        start(database).close()
        DriverManager.getConnection(database, postgres.username, postgres.password).use {
            it.createStatement().execute(
                "INSERT INTO secret VALUES ('${UUID.randomUUID()}', '\\x01', now(), now()); " +
                    "DELETE FROM master_key_check",
            )
        }
        Files.delete(keyset)

        failure { start(database).close() } shouldContain "the database holds secrets"
    }

    @Test
    fun `a relative data directory stops the app`() {
        failure { start(freshDatabase(), Path.of("relative/data")).close() } shouldContain "JOFI_DATA_DIR"
    }

    private fun insertAccount(database: String) =
        DriverManager.getConnection(database, postgres.username, postgres.password).use {
            it.createStatement().execute(
                "INSERT INTO user_account (account_id, password_hash, created_at, password_changed_at) " +
                    "VALUES ('${UUID.randomUUID()}', '\$argon2id\$x', now(), now())",
            )
        }

    private fun accounts(database: String): Int =
        DriverManager.getConnection(database, postgres.username, postgres.password).use {
            val rows = it.createStatement().executeQuery("SELECT count(*) FROM user_account")
            rows.next()
            rows.getInt(1)
        }

    @Test
    fun `a password reset happens once per setting of the flag`() {
        val database = freshDatabase()
        val tokenFile = dataDirectory.resolve("secrets/setup-token")
        start(database).close()
        insertAccount(database)
        start(database).close()
        Files.exists(tokenFile) shouldBe false

        start(database, dataDirectory, "--jofi.auth.reset-password=true").close()
        Files.exists(tokenFile) shouldBe true
        accounts(database) shouldBe 0

        // First run with the new token, then a restart with the flag still set: the new password stays.
        Files.delete(tokenFile)
        insertAccount(database)
        start(database, dataDirectory, "--jofi.auth.reset-password=true").close()
        accounts(database) shouldBe 1

        // Removing the flag re-arms it for a later recovery.
        start(database).close()
        start(database, dataDirectory, "--jofi.auth.reset-password=true").close()
        accounts(database) shouldBe 0
    }

    @Test
    fun `the worker never runs the startup checks, so its restart cannot reset the password`() {
        val database = freshDatabase()
        start(database).close()
        insertAccount(database)
        start(database).close()
        val tokenFile = dataDirectory.resolve("secrets/setup-token")

        start(database, dataDirectory, "--spring.profiles.active=worker", "--jofi.auth.reset-password=true").close()

        accounts(database) shouldBe 1
        Files.exists(tokenFile) shouldBe false
    }

    private companion object {
        val postgres = newPostgresContainer().apply { start() }
    }
}
