// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Public
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.backup.BackupEntry
import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupManifest
import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.BackupWorkspace
import io.github.scriptibus.jofi.system.domain.backup.DatabaseDump
import io.github.scriptibus.jofi.system.domain.backup.DatabaseMigrationResult
import io.github.scriptibus.jofi.system.domain.backup.DatabaseRestoreResult
import io.github.scriptibus.jofi.system.domain.backup.EntryDigest
import io.github.scriptibus.jofi.system.domain.backup.SchemaVersion
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import io.github.scriptibus.jofi.system.domain.backup.TableDump
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.Table
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The export/import round trip of every table against a real PostgreSQL (spec 4.5, ADR-0042): seed
 * every exported table with awkward values, dump, overwrite, restore, compare. A new table fails
 * here until it is seeded (and so proven to round-trip) or excluded with a reason.
 */
class DatabaseBackupRepositoryTest {
    private val source =
        PostgresTestDatabase.container.let { DriverManagerDataSource(it.jdbcUrl, it.username, it.password) }
    private val transactionManager = DataSourceTransactionManager(source)
    private val transaction = TransactionTemplate(transactionManager)

    // Like Spring Boot's jOOQ auto-configuration: jOOQ joins the Spring transaction.
    private val dsl: DSLContext = DSL.using(TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES)
    private val connection =
        object : JdbcConnectionDetails {
            override fun getUsername(): String = PostgresTestDatabase.container.username

            override fun getPassword(): String = PostgresTestDatabase.container.password

            override fun getJdbcUrl(): String = PostgresTestDatabase.container.jdbcUrl
        }
    private val repository = DatabaseBackupRepository(dsl, transactionManager, connection)

    @TempDir
    lateinit var directory: Path

    @BeforeEach
    fun migrateFromZero() {
        PostgresTestDatabase.migratedFromZero()
    }

    @Test
    fun `every table of the schema is either exported or excluded with a reason, and nothing else is listed`() {
        val tables = Public.PUBLIC.tables.map { it.name }

        tables.filterNot { it in BackupTables.EXPORTED || it in BackupTables.EXCLUDED }.shouldBeEmpty()
        (BackupTables.EXPORTED intersect BackupTables.EXCLUDED.keys).shouldBeEmpty()
        (BackupTables.EXPORTED + BackupTables.EXCLUDED.keys).filterNot { it in tables }.shouldBeEmpty()
        BackupTables.EXCLUDED.values.forEach { it.isNotBlank() shouldBe true }
    }

    @Test
    fun `tables come after the tables they reference`() {
        val order = BackupTables.exported.map { it.name }

        BackupTables.exported.forEach { table ->
            table.references.map { it.key.table.name }.filter { it in order }.forEach { referenced ->
                order.indexOf(referenced) shouldBe minOf(order.indexOf(referenced), order.indexOf(table.name))
            }
        }
    }

    @Test
    fun `every table round-trips exactly, identities continue, and sessions end`() {
        seedEveryTable()
        val before = contents()
        val dump = repository.dump(directory).shouldBeInstanceOf<SystemStoreResult.Success<DatabaseDump>>().value
        dump.tables.map { it.name } shouldBe BackupTables.exported.map { it.name }
        dump.tables.forEach { it.rows.toInt() shouldBeGreaterThan 0 }

        overwrite()
        val result = transaction.execute { repository.replaceAll(staged(dump), confirmed(staged(dump))) }

        result shouldBe DatabaseRestoreResult.Restored
        contents() shouldBe before
        dsl.fetchCount(SPRING_SESSION) shouldBe 0
        dsl.execute(CHANGELOG_INSERT, "USER", null, "after the restore", null) shouldBe 1
        dsl.fetchValue("select max(id) from changelog_entry") shouldBe 3L
    }

    @Test
    fun `a backup of an older schema is migrated in a scratch database and then restores`() {
        seedEveryTable(olderSchema = true)
        val before = contents()
        val older = olderDump()

        val migrated = repository.migrate(staged(older)).shouldBeInstanceOf<DatabaseMigrationResult.Migrated>().dump

        migrated.schemaVersion shouldBe (repository.runningSchema() as SystemStoreResult.Success).value.version
        migrated.tables.map { it.name } shouldBe BackupTables.exported.map { it.name }
        LATER_TABLES.forEach { name -> migrated.tables.single { it.name == name }.rows shouldBe 0 }
        dsl.fetchValues("select datname from pg_database").none { it.toString().startsWith("jofi_restore_") } shouldBe
            true
        transaction.execute { repository.replaceAll(staged(migrated), confirmed(staged(migrated))) } shouldBe
            DatabaseRestoreResult.Restored
        contents() - LATER_TABLES shouldBe before - LATER_TABLES
        LATER_TABLES.forEach { dsl.fetchCount(DSL.table(it)) shouldBe 0 }
    }

    @Test
    fun `an older backup whose dumps do not fit its schema is refused, and nothing else changes`() {
        seedEveryTable(olderSchema = true)
        val before = contents()
        val older = olderDump()
        val claimingCompany = older.copy(tables = older.tables + TableDump("company", 0))
        Files.writeString(directory.resolve("company.csv"), "id\n")

        repository.migrate(staged(claimingCompany)) shouldBe
            DatabaseMigrationResult.Refused(BackupProblem.TABLES_MISMATCH)
        val file = directory.resolve("secret.csv")
        Files.writeString(file, Files.readString(file) + "not,a,row\n")
        repository.migrate(staged(older)) shouldBe DatabaseMigrationResult.Refused(BackupProblem.DATA_INVALID)
        contents() shouldBe before
    }

    // A dump as the schema before the company table (#130) made it: the same tables without `company`.
    private fun olderDump(): DatabaseDump {
        val dump = (repository.dump(directory) as SystemStoreResult.Success).value
        LATER_TABLES.forEach { Files.delete(directory.resolve("$it.csv")) }
        return DatabaseDump(
            SchemaVersion(OLDER_SCHEMA),
            dump.tables.filterNot { it.name in LATER_TABLES },
            dump.takenAt,
        )
    }

    @Test
    fun `leftover scratch databases of crashed migrations are dropped, other databases stay`() {
        val leftover = "jofi_restore_" + "0".repeat(32)
        dsl.execute("create database " + leftover)
        dsl.execute("create database jofi_restore_keep")

        repository.dropScratchDatabases() shouldBe true

        val names = dsl.fetchValues("select datname from pg_database").map { it.toString() }
        (leftover in names) shouldBe false
        ("jofi_restore_keep" in names) shouldBe true
        dsl.execute("drop database jofi_restore_keep")
    }

    @Test
    fun `a dump knows when its snapshot was taken`() {
        seedEveryTable()
        val before = Instant.now().minusSeconds(60)

        val dump = (repository.dump(directory) as SystemStoreResult.Success).value

        (dump.takenAt.isAfter(before) && dump.takenAt.isBefore(Instant.now().plusSeconds(60))) shouldBe true
    }

    @Test
    fun `the running schema names the latest migration and the exported tables`() {
        val schema = (repository.runningSchema() as SystemStoreResult.Success).value

        schema.tables shouldBe BackupTables.exported.map { it.name }
        schema.version.value shouldBe dsl.fetchValue("select max(version) from flyway_schema_history").toString()
    }

    @Test
    fun `a dump with other columns is refused and, rolled back, leaves the data as it was`() {
        seedEveryTable()
        val dump = (repository.dump(directory) as SystemStoreResult.Success).value
        val before = contents()
        val file = directory.resolve("ai_monthly_budget.csv")
        Files.writeString(file, Files.readString(file).replaceFirst("cap_micros", "cap"))

        val result =
            transaction.execute { status ->
                repository.replaceAll(staged(dump), confirmed(staged(dump))).also { status.setRollbackOnly() }
            }

        result shouldBe DatabaseRestoreResult.DataInvalid
        contents() shouldBe before
    }

    @Test
    fun `row counts that differ from the manifest are refused`() {
        seedEveryTable()
        val dump = (repository.dump(directory) as SystemStoreResult.Success).value
        val lying = dump.copy(tables = dump.tables.map { if (it.name == "secret") it.copy(rows = 5) else it })

        transaction.execute { repository.replaceAll(staged(lying), confirmed(staged(lying))) } shouldBe
            DatabaseRestoreResult.DataInvalid
    }

    @Test
    fun `nothing is replaced without the confirmation of exactly this backup`() {
        seedEveryTable()
        val dump = (repository.dump(directory) as SystemStoreResult.Success).value
        val other = staged(dump).copy(workspace = BackupWorkspace(BackupId(UUID.randomUUID()), directory))
        val before = contents()

        repository.replaceAll(staged(dump), confirmed(other)) shouldBe DatabaseRestoreResult.StorageFailure

        contents() shouldBe before
    }

    private val backupId = BackupId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))

    private fun staged(dump: DatabaseDump): StagedBackup {
        val digest = EntryDigest(0, "0".repeat(64))
        val manifest =
            BackupManifest(
                BackupManifest.FORMAT_VERSION,
                "test",
                dump.schemaVersion,
                Instant.parse("2026-09-30T12:00:00Z"),
                dump.tables,
                dump.tables.map { BackupEntry(it.path, digest) },
            )
        return StagedBackup(BackupWorkspace(backupId, directory), manifest, null)
    }

    private fun confirmed(backup: StagedBackup): ConfirmationResult.Confirmed =
        TestConfirmations.confirm(BackupRestore.action(backup))

    // Values that break naive dumps: quotes, commas, line breaks, NULL next to text, bytes, arrays, JSON, µs.
    // [olderSchema]: only values the schema before #130 accepts (e.g. no cost without an amount).
    private fun seedEveryTable(olderSchema: Boolean = false) {
        dsl.execute(CHANGELOG_INSERT, "USER", null, "Said \"hi\", then\nleft; ü€", null)
        dsl.execute(CHANGELOG_INSERT, "SCANNER", "rss", "found, \"quoted\"", "why,\r\nnot")
        seedSetup()
        if (!olderSchema) seedUnknownCost()
        dsl.execute(
            "insert into user_account (account_id, password_hash, created_at, password_changed_at) " +
                "values (?, '\$argon2id\$v=19\$m=19456,t=2,p=1\$c2FsdA\$aGFzaA', ?::timestamptz, ?::timestamptz)",
            UUID.randomUUID(),
            AT,
            AT,
        )
        dsl.execute(
            "insert into master_key_check (check_value, recorded_at) values (?, ?::timestamptz)",
            byteArrayOf(9, 8),
            AT,
        )
        BackupDomainSeeds(dsl, AT).seedCompanies(COMPANY)
        dsl.execute(
            "insert into spring_session values ('11111111-1111-1111-1111-111111111111', " +
                "'22222222-2222-2222-2222-222222222222', 1, 1, 60, 9999999999999, 'owner')",
        )
        BackupTables.exported.forEach { table ->
            check(dsl.fetchCount(table) > 0) { "Seed ${table.name}: every exported table must be proven to round-trip" }
        }
    }

    private fun seedUnknownCost() {
        dsl.execute(
            "insert into ai_cost_entry (task, provider_id, provider_kind, model, input_tokens, output_tokens, " +
                "cost_micros, currency, occurred_at) " +
                "values ('EMBEDDING', ?, 'OPENAI', 'gpt', 5, 0, null, 'USD', now())",
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
        )
    }

    private fun seedSetup() {
        val secret = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val provider = UUID.fromString("00000000-0000-0000-0000-000000000002")
        dsl.execute(
            "insert into secret values (?, ?, ?::timestamptz, ?::timestamptz)",
            secret,
            byteArrayOf(0, 1, 44, 10, -1),
            AT,
            AT,
        )
        dsl.execute(
            "insert into ai_provider_config values (?, 'Mine, \"quoted\"', 'OPENAI', ?, null)",
            provider,
            secret,
        )
        dsl.execute(
            "insert into ai_model_capability values (?, 'gpt', '{TOOL_USE,STREAMING}', 128000, 'USER', ?::timestamptz)",
            provider,
            AT,
        )
        dsl.execute("insert into ai_model_assignment values ('CHAT', ?, 'gpt')", provider)
        dsl.execute(
            "insert into ai_cost_entry (task, provider_id, provider_kind, model, input_tokens, output_tokens, " +
                "cost_micros, currency, occurred_at) " +
                "values ('CHAT', ?, 'OPENAI', 'gpt', 10, 20, 30, 'USD', ?::timestamptz)",
            provider,
            AT,
        )
        dsl.execute("insert into ai_monthly_budget (cap_micros, currency) values (5000000, 'USD')")
    }

    private fun overwrite() {
        dsl.execute("truncate ai_monthly_budget")
        dsl.execute("insert into ai_monthly_budget (cap_micros, currency) values (1, 'USD')")
        dsl.execute(CHANGELOG_INSERT, "AI", null, "after the backup", null)
        dsl.execute(CHANGELOG_INSERT, "USER", null, "and another", null)
    }

    private fun contents(): Map<String, List<String>> =
        BackupTables.exported.associate { table: Table<*> ->
            table.name to
                dsl
                    .fetch("select * from {0}", table)
                    .formatCSV()
                    .lines()
                    .drop(1)
                    .sorted()
        }

    private object TestConfirmations {
        private val store =
            object : ConfirmationStorePort {
                private val pending = mutableMapOf<String, PendingConfirmation>()

                override fun issue(
                    pending: PendingConfirmation,
                    now: Instant,
                ) = ConfirmationToken(UUID.randomUUID().toString()).also { this.pending[it.value] = pending }

                override fun redeem(token: ConfirmationToken) = pending.remove(token.value)
            }
        private val gate = ConfirmActionUseCase(store, Clock.systemUTC(), Duration.ofMinutes(1))
        private val requester = ConfirmationRequester(Actor.User, "session")

        fun confirm(action: ConfirmableAction): ConfirmationResult.Confirmed {
            val required = gate.execute(ConfirmationRequest(requester, action, null)) as ConfirmationResult.Required
            return gate.execute(ConfirmationRequest(requester, action, required.token)) as ConfirmationResult.Confirmed
        }
    }

    private companion object {
        const val AT = "2026-09-30 10:00:00.123456+00"
        const val OLDER_SCHEMA = "20260930064000"

        /** Tables the schema before the company table (#130) did not have yet (and the applications tables). */
        val LATER_TABLES =
            setOf(
                "company",
                "contact",
                "contact_channel",
                "application",
                "application_contact",
                "application_status_change",
                "application_source",
                "application_description_snapshot",
                "interview",
                "interview_participant",
                "task",
                "countdown",
                "saved_view",
                "application_settings",
            )
        val COMPANY: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        const val CHANGELOG_INSERT =
            "insert into changelog_entry (entity_type, entity_id, actor_kind, actor_name, occurred_at, description, " +
                "field_changes, reason) values ('thing', '1', ?, ?, now(), ?, " +
                "'[{\"field\":\"a,b\",\"before\":null,\"after\":\"\\\"x\\\"\"}]', ?)"
    }
}
