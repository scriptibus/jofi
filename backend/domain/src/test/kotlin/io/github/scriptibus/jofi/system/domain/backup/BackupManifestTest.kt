// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain.backup

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class BackupManifestTest {
    private val digest = EntryDigest(3, "a".repeat(64))
    private val running =
        RunningSchema(SchemaVersion("20260930064000"), listOf("secret", "changelog_entry", "user_account"))
    private val account = TableDump("user_account", 1)
    private val tables = listOf(TableDump("secret", 1), TableDump("changelog_entry", 2), account)
    private val keyset = BackupEntry(BackupPath.KEYSET, digest)
    private val file = BackupEntry(requireNotNull(BackupPath.dataFile("knowledge", "profile.md")), digest)

    private fun manifest(
        formatVersion: Int = BackupManifest.FORMAT_VERSION,
        schema: String = "20260930064000",
        tables: List<TableDump> = this.tables,
        extra: List<BackupEntry> = listOf(keyset, file),
    ) = BackupManifest(
        formatVersion,
        "0.1.0",
        SchemaVersion(schema),
        Instant.parse("2026-09-30T12:00:00Z"),
        tables,
        tables.map { BackupEntry(it.path, digest) } + extra,
    )

    private fun found(manifest: BackupManifest) = manifest.entries.associate { it.path to it.digest }

    @Test
    fun `a complete backup of the running schema passes`() {
        val manifest = manifest()

        manifest.problemWith(found(manifest), running).shouldBeNull()
        manifest.rowCount shouldBe 4
        manifest.dataFileCount shouldBe 1
        manifest.includesKeyset shouldBe true
    }

    @Test
    fun `the format must match, a newer schema is refused, an older one needs a migration`() {
        manifest(formatVersion = 2).let { it.problemWith(found(it), running) } shouldBe BackupProblem.UNSUPPORTED_FORMAT
        manifest(schema = "20261001000000").let { it.problemWith(found(it), running) } shouldBe
            BackupProblem.SCHEMA_NEWER
        val older = manifest(schema = "20260930010056", tables = listOf(TableDump("secret", 1), account))

        older.problemWith(found(older), running).shouldBeNull()
        older.needsMigrationTo(running) shouldBe true
        manifest().needsMigrationTo(running) shouldBe false
        older.migratedTo(running.version, tables).let {
            it.schemaVersion shouldBe running.version
            it.tables shouldBe tables
            it.entries shouldBe older.entries
        }
    }

    @Test
    fun `the archive must hold exactly what the manifest lists, with the same checksums`() {
        val manifest = manifest()
        val tampered = found(manifest) + (file.path to EntryDigest(3, "b".repeat(64)))
        val missing = found(manifest) - file.path
        val extra = found(manifest) + (requireNotNull(BackupPath.dataFile("documents", "x.pdf")) to digest)

        manifest.problemWith(tampered, running) shouldBe BackupProblem.CONTENT_MISMATCH
        manifest.problemWith(missing, running) shouldBe BackupProblem.CONTENT_MISMATCH
        manifest.problemWith(extra, running) shouldBe BackupProblem.CONTENT_MISMATCH
    }

    @Test
    fun `a manifest listing an entry twice is refused`() {
        val twice = manifest().let { it.copy(entries = it.entries + file) }

        twice.problemWith(found(twice), running) shouldBe BackupProblem.CONTENT_MISMATCH
    }

    @Test
    fun `the tables must be exactly the running schema's`() {
        val fewer = manifest(tables = listOf(TableDump("secret", 1), account))
        val more = manifest(tables = tables + TableDump("unknown", 0))

        fewer.problemWith(found(fewer), running) shouldBe BackupProblem.TABLES_MISMATCH
        more.problemWith(found(more), running) shouldBe BackupProblem.TABLES_MISMATCH
    }

    @Test
    fun `secrets need the keyset, an instance without secrets does not`() {
        val withoutKeyset = manifest(extra = listOf(file))
        val noSecrets =
            manifest(
                tables = listOf(TableDump("secret", 0), TableDump("changelog_entry", 2), account),
                extra = emptyList(),
            )

        withoutKeyset.problemWith(found(withoutKeyset), running) shouldBe BackupProblem.KEYSET_MISSING
        noSecrets.problemWith(found(noSecrets), running).shouldBeNull()
    }

    @Test
    fun `a backup needs exactly one user account`() {
        val none =
            manifest(
                tables = listOf(TableDump("secret", 1), TableDump("changelog_entry", 2), TableDump("user_account", 0)),
            )
        val missing = manifest(tables = listOf(TableDump("secret", 1), TableDump("changelog_entry", 2)))
        val runningWithout = running.copy(tables = listOf("secret", "changelog_entry"))

        none.problemWith(found(none), running) shouldBe BackupProblem.ACCOUNT_MISSING
        missing.problemWith(found(missing), runningWithout) shouldBe BackupProblem.ACCOUNT_MISSING
    }

    @Test
    fun `versions are bounded`() {
        shouldThrow<IllegalArgumentException> { SchemaVersion("1".repeat(33)) }
        SchemaVersion("1".repeat(32)).value.length shouldBe 32
        shouldThrow<IllegalArgumentException> { manifest().copy(appVersion = "0.1.0; rm -rf /") }
        shouldThrow<IllegalArgumentException> { manifest().copy(appVersion = "1".repeat(65)) }
        manifest().copy(appVersion = "0.1.0-SNAPSHOT+build.7").appVersion shouldBe "0.1.0-SNAPSHOT+build.7"
    }

    @Test
    fun `schema versions compare part by part as numbers`() {
        SchemaVersion("20260930064000") shouldBeGreaterThan SchemaVersion("20260930010056")
        SchemaVersion("1.10") shouldBeGreaterThan SchemaVersion("1.9")
        SchemaVersion("1_2") shouldBeLessThan SchemaVersion("1.2.1")
        SchemaVersion("1.0").compareTo(SchemaVersion("1")) shouldBe 0
        shouldThrow<IllegalArgumentException> { SchemaVersion("1.a") }
    }

    @Test
    fun `digests and dumps reject impossible values`() {
        shouldThrow<IllegalArgumentException> { EntryDigest(-1, "a".repeat(64)) }
        shouldThrow<IllegalArgumentException> { EntryDigest(1, "A".repeat(64)) }
        shouldThrow<IllegalArgumentException> { TableDump("secret", -1) }
    }

    @Test
    fun `the restore action binds the staged backup and its effect`() {
        val backup =
            StagedBackup(BackupWorkspace(BackupId(UUID.randomUUID()), Path.of("/data/backup-work")), manifest(), null)

        val action = BackupRestore.action(backup)

        action.operation shouldBe BackupRestore.OPERATION
        action.targets shouldBe listOf(backup.id.toString())
        action.effect shouldBe
            ConfirmationEffect("backup", "2026-09-30T12:00:00Z", mapOf("rows" to 4, "files" to 1, "keyset" to 1))
        BackupRestore.confirms(ConfirmationResult.Confirmed(action), backup) shouldBe true
        val other = backup.copy(workspace = backup.workspace.copy(id = BackupId(UUID.randomUUID())))
        BackupRestore.confirms(ConfirmationResult.Confirmed(action), other) shouldBe false
        val changed = backup.copy(manifest = manifest(extra = listOf(keyset)))
        BackupRestore.confirms(ConfirmationResult.Confirmed(action), changed) shouldBe false
    }

    @Test
    fun `a keyset copy never shows its content`() {
        val copy = MasterKeysetCopy("""{"primaryKeyId":1}""".toByteArray())

        copy.toString() shouldNotContain "primaryKeyId"
        String(copy.bytes()) shouldBe """{"primaryKeyId":1}"""
        shouldThrow<IllegalArgumentException> { MasterKeysetCopy(ByteArray(0)) }
    }
}
