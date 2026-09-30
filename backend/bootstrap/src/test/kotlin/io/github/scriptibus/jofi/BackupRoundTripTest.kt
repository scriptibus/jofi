// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.system.application.InstallBackupUseCase
import io.github.scriptibus.jofi.system.application.RecoverRestoreUseCase
import io.github.scriptibus.jofi.system.application.port.BackupArchivePort
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.BackupRestoreResult
import io.github.scriptibus.jofi.system.domain.backup.RestoreRecoveryResult
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Backup and restore through the real app (spec §3.1, ADR-0042): behind the security filter chain,
 * against PostgreSQL and the data volume. Export, change things, upload, confirm, restore, compare;
 * the restore is in the changelog as the user's, and every session has ended.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class BackupRoundTripTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
    @param:Autowired private val transactionManager: PlatformTransactionManager,
) {
    private val json = JsonMapper.builder().build()
    private val dataDirectory = Path.of(requireNotNull(System.getProperty("jofi.data-dir")))
    private val profile = dataDirectory.resolve("knowledge/profile.md")

    @BeforeEach
    fun startWithoutUser() {
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
    }

    private fun owner(): Browser =
        Browser(mvc, "203.0.113.${addresses.incrementAndGet()}").open().also {
            val body = """{"password":"$PASSWORD","setupToken":"${SetupTokens.read()}"}"""
            it.post("/api/auth/first-run", body).response.status shouldBe 204
        }

    private fun Browser.upload(zip: ByteArray) = upload("/api/system/backup/restores", "application/zip", zip)

    private fun Browser.restore(
        id: String,
        token: String? = null,
    ) = exchange(
        HttpMethod.POST,
        "/api/system/backup/restores/$id",
        json = PASSWORD_BODY,
        headers = token?.let { mapOf(Confirmations.HEADER to it) }.orEmpty(),
    )

    private fun changelog(): List<String> =
        dsl
            .select(CHANGELOG_ENTRY.ID, CHANGELOG_ENTRY.ACTOR_KIND, CHANGELOG_ENTRY.DESCRIPTION)
            .from(CHANGELOG_ENTRY)
            .orderBy(CHANGELOG_ENTRY.ID)
            .fetch { "${it.value1()} ${it.value2()} ${it.value3()}" }

    @Test
    fun `export, change, restore - data and files are back, logged as the user, and the session has ended`() {
        val browser = owner()
        profile.parent.createDirectories()
        profile.writeText("# Profil\nvom Backup")
        val before = changelog()
        val account = dsl.fetchOne(USER_ACCOUNT)?.accountId
        val zip = export(browser)
        val exported = changelog().last()

        changeEverything()
        restore(browser, zip)

        profile.readText() shouldBe "# Profil\nvom Backup"
        dataDirectory.resolve("knowledge/later.md").exists() shouldBe false
        dsl.fetchOne(USER_ACCOUNT)?.accountId shouldBe account
        val after = changelog()
        after.dropLast(1) shouldBe before
        (exported in after) shouldBe false
        after.last() shouldContain "USER Restored the backup of"
        browser.get("/api/system/info").response.status shouldBe 401
        browser
            .open()
            .post("/api/auth/login", """{"password":"$PASSWORD"}""")
            .response.status shouldBe 204
    }

    private fun export(browser: Browser): ByteArray {
        val export = browser.post("/api/system/backup/exports", PASSWORD_BODY)
        export.response.status shouldBe 200
        changelog().last() shouldContain "USER Exported a backup"
        export.response.getHeader("Content-Disposition").orEmpty() shouldContain "attachment"
        val zip = export.response.contentAsByteArray
        entries(zip) shouldContainAll
            listOf(
                "manifest.json",
                "secrets/master-keyset.json",
                "files/knowledge/profile.md",
                "database/user_account.csv",
            )
        return zip
    }

    private fun changeEverything() {
        profile.writeText("changed after the backup")
        dataDirectory.resolve("knowledge/later.md").writeText("new")
        dsl.execute(
            "insert into changelog_entry (entity_type, entity_id, actor_kind, occurred_at, description) " +
                "values ('thing', '1', 'USER', now(), 'after the backup')",
        )
    }

    private fun restore(
        browser: Browser,
        zip: ByteArray,
    ) {
        val staged = browser.upload(zip)
        staged.response.status shouldBe 201
        val id = json.readTree(staged.response.contentAsString)["id"].asString()
        val first = browser.restore(id)
        first.response.status shouldBe 428
        val token = json.readTree(first.response.contentAsString)["confirmationToken"].asString()
        browser.restore(id, token).response.status shouldBe 204
    }

    @Test
    fun `export and restore need the current password`() {
        val browser = owner()
        val before = changelog()

        browser.post("/api/system/backup/exports", """{"password":"not the password"}""").response.status shouldBe 403
        val staged = browser.upload(export(browser))
        val id = json.readTree(staged.response.contentAsString)["id"].asString()
        browser
            .exchange(HttpMethod.POST, "/api/system/backup/restores/$id", json = """{"password":"not the password"}""")
            .response.status shouldBe 403

        changelog().dropLast(1) shouldBe before
    }

    @Test
    fun `a restore interrupted before its commit is undone by the recovery every start runs`() {
        val browser = owner()
        profile.parent.createDirectories()
        profile.writeText("in the backup")
        val zip = export(browser)
        profile.writeText("live")
        val before = changelog()
        val backup = staged(browser, zip)

        // The crash: files and keyset in place, but the transaction never commits.
        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            install().execute(backup, confirmed(backup), Actor.User).shouldBeInstanceOf<BackupRestoreResult.Restored>()
            status.setRollbackOnly()
        }
        profile.readText() shouldBe "in the backup"

        recover().execute() shouldBe RestoreRecoveryResult.ROLLED_BACK

        profile.readText() shouldBe "live"
        changelog() shouldBe before
        recover().execute() shouldBe RestoreRecoveryResult.NOTHING_TO_DO
    }

    @Test
    fun `a restore interrupted after its commit is finished by the recovery`() {
        val browser = owner()
        profile.parent.createDirectories()
        profile.writeText("in the backup")
        val zip = export(browser)
        profile.writeText("live")
        val backup = staged(browser, zip)

        // The crash: committed, but the work directory was never cleaned up.
        TransactionTemplate(transactionManager).executeWithoutResult {
            install().execute(backup, confirmed(backup), Actor.User).shouldBeInstanceOf<BackupRestoreResult.Restored>()
        }

        recover().execute() shouldBe RestoreRecoveryResult.ROLLED_FORWARD

        profile.readText() shouldBe "in the backup"
        changelog().last() shouldContain "USER Restored the backup of"
        dataDirectory.resolve("backup-work/${backup.id}").exists() shouldBe false
    }

    private fun staged(
        browser: Browser,
        zip: ByteArray,
    ): StagedBackup {
        val staged = browser.upload(zip)
        staged.response.status shouldBe 201
        val id = BackupId(UUID.fromString(json.readTree(staged.response.contentAsString)["id"].asString()))
        return requireNotNull(context.getBean(BackupArchivePort::class.java).find(id))
    }

    private fun install() = context.getBean(InstallBackupUseCase::class.java)

    private fun recover() = context.getBean(RecoverRestoreUseCase::class.java)

    // The gate's own two steps, for a session of the test.
    private fun confirmed(backup: StagedBackup): ConfirmationResult.Confirmed {
        val gate = context.getBean(ConfirmActionUseCase::class.java)
        val requester = ConfirmationRequester(Actor.User, "crash-test")
        val action = BackupRestore.action(backup)
        val token = (gate.execute(ConfirmationRequest(requester, action, null)) as ConfirmationResult.Required).token
        return gate.execute(ConfirmationRequest(requester, action, token)) as ConfirmationResult.Confirmed
    }

    @Test
    fun `hostile or foreign uploads are refused before anything changes`() {
        val browser = owner()
        val before = changelog()

        val escaping = browser.upload(zipOf("../../escape.txt" to "x"))
        escaping.response.status shouldBe 422
        escaping.response.contentAsString shouldContain "unsafe-path"
        browser.upload("not a zip".toByteArray()).response.status shouldBe 422
        browser.restore("00000000-0000-0000-0000-000000000000").response.status shouldBe 404

        changelog() shouldBe before
        dataDirectory.resolve("../escape.txt").normalize().exists() shouldBe false
    }

    @Test
    fun `backup and restore need a session and the CSRF token`() {
        val anonymous = Browser(mvc, "203.0.113.${addresses.incrementAndGet()}").open()
        anonymous.post("/api/system/backup/exports", PASSWORD_BODY).response.status shouldBe 401
        anonymous.upload(zipOf("manifest.json" to "{}")).response.status shouldBe 401

        val browser = owner()
        browser
            .upload("/api/system/backup/restores", "application/zip", ByteArray(1), csrf = null)
            .response.status shouldBe 403
    }

    private fun entries(zip: ByteArray): List<String> =
        ZipInputStream(ByteArrayInputStream(zip))
            .use { input ->
                generateSequence { input.nextEntry }.map { it.name }.toList()
            }.also { it shouldContain "manifest.json" }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val PASSWORD_BODY = """{"password":"$PASSWORD"}"""
        val addresses = AtomicInteger(100)
    }
}
