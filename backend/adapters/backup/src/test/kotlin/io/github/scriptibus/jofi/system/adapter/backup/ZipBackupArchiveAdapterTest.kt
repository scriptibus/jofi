// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.system.domain.backup.BackupContents
import io.github.scriptibus.jofi.system.domain.backup.BackupExportResult
import io.github.scriptibus.jofi.system.domain.backup.BackupPath
import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.BackupUnpackResult
import io.github.scriptibus.jofi.system.domain.backup.MasterKeysetCopy
import io.github.scriptibus.jofi.system.domain.backup.RunningSchema
import io.github.scriptibus.jofi.system.domain.backup.SchemaVersion
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import io.github.scriptibus.jofi.system.domain.backup.TableDump
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.util.unit.DataSize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The archive format and every refusal of a hostile upload (ADR-0042, threat model T8). */
class ZipBackupArchiveAdapterTest {
    @TempDir
    lateinit var dataDirectory: Path

    private val adapter by lazy {
        ZipBackupArchiveAdapter(
            dataDirectory.toString(),
            DataSize.ofMegabytes(1),
            DataSize.ofMegabytes(2),
            MAX_ENTRIES,
            Clock.systemUTC(),
        )
    }
    private val schema = SchemaVersion("20260930064000")
    private val tables = listOf(TableDump("secret", 1), TableDump("changelog_entry", 2), TableDump("user_account", 1))
    private val keyset = MasterKeysetCopy("""{"primaryKeyId":1}""".toByteArray())

    private fun export(): ByteArray {
        val workspace = requireNotNull(adapter.newWorkspace())
        Files.writeString(workspace.databaseDirectory.resolve("secret.csv"), "id\n1\n")
        Files.writeString(workspace.databaseDirectory.resolve("changelog_entry.csv"), "id\n1\n2\n")
        Files.writeString(workspace.databaseDirectory.resolve("user_account.csv"), "id\n1\n")
        val output = ByteArrayOutputStream()
        val contents = BackupContents("0.1.0", schema, Instant.parse("2026-09-30T12:00:00Z"), tables, keyset)
        adapter.write(workspace, contents) { output }.shouldBeInstanceOf<BackupExportResult.Exported>()
        adapter.discard(workspace.id)
        return output.toByteArray()
    }

    private fun dataFile(
        relative: String,
        text: String,
    ) {
        val file = dataDirectory.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun unpack(bytes: ByteArray) = adapter.unpack(ByteArrayInputStream(bytes))

    private fun refusal(bytes: ByteArray): BackupProblem? = (unpack(bytes) as? BackupUnpackResult.Refused)?.problem

    @Test
    fun `an export unpacks to the same manifest, checksums, keyset and files`() {
        dataFile("knowledge/profile.md", "# Profil\nÜberall")
        dataFile("knowledge/.git/HEAD", "ref: refs/heads/main\n")
        dataFile("documents/cv/Lebenslauf 2026.pdf", "%PDF-1.7")
        Files.createSymbolicLink(dataDirectory.resolve("knowledge/link"), Path.of("/etc/passwd"))

        val unpacked = unpack(export()).shouldBeInstanceOf<BackupUnpackResult.Unpacked>()

        val manifest = unpacked.backup.manifest
        manifest.problemWith(unpacked.found, RunningSchema(schema, tables.map { it.name })).shouldBeNull()
        manifest.tables shouldBe tables
        manifest.entries.filter { it.path.isDataFile }.map { it.path.value } shouldContainExactly
            listOf("files/knowledge/.git/HEAD", "files/knowledge/profile.md", "files/documents/cv/Lebenslauf 2026.pdf")
        String(requireNotNull(unpacked.backup.keyset).bytes()) shouldBe """{"primaryKeyId":1}"""
        unpacked.backup.workspace.databaseDirectory
            .resolve("changelog_entry.csv")
            .readText() shouldBe "id\n1\n2\n"
        adapter.find(unpacked.backup.id) shouldBe unpacked.backup
    }

    @Test
    fun `entries that could escape or are not part of the format are refused, and nothing is written`() {
        listOf("../evil.txt", "/tmp/evil.txt", "files/knowledge/../../evil.txt", "files\\knowledge\\x", "files/other/x")
            .forEach { name -> refusal(zip(name to "x")) shouldBe BackupProblem.UNSAFE_PATH }

        dataDirectory.parent.resolve("evil.txt").exists() shouldBe false
        workDirectories() shouldBe emptyList()
    }

    @Test
    fun `duplicates, too many entries and zip bombs are refused`() {
        // ZipOutputStream refuses to write a duplicate, so the second name is patched in afterwards.
        val twice = String(zip("files/knowledge/a" to "1", "files/knowledge/b" to "2"), Charsets.ISO_8859_1)
        refusal(twice.replace("files/knowledge/b", "files/knowledge/a").toByteArray(Charsets.ISO_8859_1)) shouldBe
            BackupProblem.DUPLICATE_ENTRY
        refusal(zip(*Array(MAX_ENTRIES + 1) { "files/knowledge/$it" to "x" })) shouldBe BackupProblem.TOO_MANY_ENTRIES
        // 3 MB of zeros compress to a few KB: the unpacked limit (2 MB) is counted on the real bytes.
        refusal(zip("files/documents/bomb" to "\u0000".repeat(3 * 1024 * 1024))) shouldBe BackupProblem.TOO_LARGE
        refusal(zip(BackupPath.KEYSET.value to "k".repeat(70 * 1024))) shouldBe BackupProblem.TOO_LARGE
        workDirectories() shouldBe emptyList()
    }

    @Test
    fun `a failed export is never a valid zip`() {
        dataFile("knowledge/ok.md", "fine")
        dataFile("knowledge/back\\slash.md", "a name the format cannot carry")
        val workspace = requireNotNull(adapter.newWorkspace())
        Files.writeString(workspace.databaseDirectory.resolve("secret.csv"), "id\n1\n")
        Files.writeString(workspace.databaseDirectory.resolve("changelog_entry.csv"), "id\n1\n2\n")
        Files.writeString(workspace.databaseDirectory.resolve("user_account.csv"), "id\n1\n")
        val output = ByteArrayOutputStream()
        val contents = BackupContents("0.1.0", schema, Instant.parse("2026-09-30T12:00:00Z"), tables, keyset)

        adapter.write(workspace, contents) { output } shouldBe BackupExportResult.Failed

        val file = Files.write(dataDirectory.resolve("broken.zip"), output.toByteArray())
        shouldThrow<ZipException> { ZipFile(file.toFile()).close() }
    }

    @Test
    fun `a manifest too large or with too many nodes is refused before it fills the memory`() {
        val roomy =
            ZipBackupArchiveAdapter(
                dataDirectory.toString(),
                DataSize.ofMegabytes(64),
                DataSize.ofMegabytes(64),
                MAX_ENTRIES,
                Clock.systemUTC(),
            )

        fun refusal(bytes: ByteArray) =
            (roomy.unpack(ByteArrayInputStream(bytes)) as? BackupUnpackResult.Refused)?.problem

        refusal(zip(BackupPath.MANIFEST.value to " ".repeat(33 * 1024 * 1024))) shouldBe BackupProblem.TOO_LARGE
        refusal(zip(BackupPath.MANIFEST.value to "[" + "{},".repeat(1_500_000) + "{}]")) shouldBe
            BackupProblem.MANIFEST_INVALID
    }

    @Test
    fun `an upload beyond its limit is refused while it is read`() {
        val noise = ByteArray(3 * 1024 * 1024).also { Random(1).nextBytes(it) }
        val entry = ByteArrayOutputStream()
        ZipOutputStream(entry).use { zip ->
            zip.putNextEntry(ZipEntry("files/documents/noise"))
            zip.write(noise)
            zip.closeEntry()
        }

        refusal(entry.toByteArray()) shouldBe BackupProblem.TOO_LARGE
    }

    @Test
    fun `what is not a backup is refused`() {
        refusal("not a zip at all".toByteArray()) shouldBe BackupProblem.NOT_A_BACKUP
        refusal(zip("files/knowledge/a" to "1")) shouldBe BackupProblem.MANIFEST_INVALID
        refusal(zip(BackupPath.MANIFEST.value to "{nope")) shouldBe BackupProblem.MANIFEST_INVALID
        refusal(zip(BackupPath.MANIFEST.value to """{"format":"jofi-backup","formatVersion":2}""")) shouldBe
            BackupProblem.UNSUPPORTED_FORMAT
        refusal(zip(BackupPath.MANIFEST.value to """{"format":"other","formatVersion":1}""")) shouldBe
            BackupProblem.MANIFEST_INVALID
    }

    @Test
    fun `a new upload replaces the staged one`() {
        val bytes = export()
        val first = (unpack(bytes) as BackupUnpackResult.Unpacked).backup
        val second = (unpack(bytes) as BackupUnpackResult.Unpacked).backup

        adapter.find(first.id).shouldBeNull()
        adapter.find(second.id) shouldBe second
        workDirectories() shouldBe listOf(second.id.toString())
        adapter.discard(second.id)
        adapter.find(second.id).shouldBeNull()
        workDirectories() shouldBe emptyList()
    }

    @Test
    fun `restored files replace the live ones and can be put back`() {
        dataFile("knowledge/new.md", "from the backup")
        val backup = (unpack(export()) as BackupUnpackResult.Unpacked).backup
        Files.walk(dataDirectory.resolve("knowledge")).sorted(reverseOrder()).forEach(Files::delete)
        dataFile("knowledge/old.md", "live")
        dataFile("documents/live.pdf", "live")

        adapter.installFiles(backup, confirm(backup)) shouldBe true

        dataDirectory.resolve("knowledge/new.md").readText() shouldBe "from the backup"
        dataDirectory.resolve("knowledge/old.md").exists() shouldBe false
        dataDirectory.resolve("documents").exists() shouldBe false

        adapter.revertFiles(backup.id) shouldBe true

        dataDirectory.resolve("knowledge/old.md").readText() shouldBe "live"
        dataDirectory.resolve("knowledge/new.md").exists() shouldBe false
        dataDirectory.resolve("documents/live.pdf").readText() shouldBe "live"
    }

    @Test
    fun `files are only swapped with the confirmation of this backup`() {
        val bytes = export()
        val first = (unpack(bytes) as BackupUnpackResult.Unpacked).backup
        dataFile("knowledge/old.md", "live")
        val second = (unpack(bytes) as BackupUnpackResult.Unpacked).backup

        adapter.installFiles(second, confirm(first)) shouldBe false

        dataDirectory.resolve("knowledge/old.md").readText() shouldBe "live"
    }

    @Test
    fun `an interrupted restore is found again after a restart, with the previous keyset, and put back`() {
        dataFile("knowledge/new.md", "from the backup")
        val backup = (unpack(export()) as BackupUnpackResult.Unpacked).backup
        Files.walk(dataDirectory.resolve("knowledge")).sorted(reverseOrder()).forEach(Files::delete)
        dataFile("knowledge/old.md", "live")
        adapter.beginRestore(backup, previous) shouldBe true
        adapter.installFiles(backup, confirm(backup)) shouldBe true

        // A crash here: a new process finds the marked restore.
        val restarted = adapter(dataDirectory)
        val interrupted = requireNotNull(restarted.interruptedRestores()).single()
        interrupted.id shouldBe backup.id
        String(requireNotNull(interrupted.previousKeyset).bytes()) shouldBe "previous keyset"
        val copy =
            dataDirectory
                .resolve(
                    WorkDirectories.WORK_DIRECTORY,
                ).resolve("${backup.id}/${WorkDirectories.PREVIOUS_KEYSET}")
        PosixFilePermissions.toString(Files.getPosixFilePermissions(copy)) shouldBe "rw-------"

        restarted.revertFiles(backup.id) shouldBe true
        restarted.endRestore(backup.id)

        dataDirectory.resolve("knowledge/old.md").readText() shouldBe "live"
        dataDirectory.resolve("knowledge/new.md").exists() shouldBe false
        restarted.interruptedRestores() shouldBe emptyList()
    }

    @Test
    fun `a crash in the middle of a swap is undone`() {
        dataFile("knowledge/new.md", "from the backup")
        val backup = (unpack(export()) as BackupUnpackResult.Unpacked).backup
        Files.walk(dataDirectory.resolve("knowledge")).sorted(reverseOrder()).forEach(Files::delete)
        dataFile("knowledge/old.md", "live")
        adapter.beginRestore(backup, previous) shouldBe true
        // What swap does first, then the crash: the live directory moved out, the backup's not yet in.
        val work = dataDirectory.resolve(WorkDirectories.WORK_DIRECTORY).resolve(backup.id.toString())
        Files.writeString(work.resolve("swap-knowledge"), "incoming")
        Files.createDirectories(work.resolve("previous"))
        Files.move(dataDirectory.resolve("knowledge"), work.resolve("previous/knowledge"))

        adapter.revertFiles(backup.id) shouldBe true

        dataDirectory.resolve("knowledge/old.md").readText() shouldBe "live"
        work.resolve("files/knowledge/new.md").readText() shouldBe "from the backup"
    }

    @Test
    fun `a restore undone once can run again`() {
        dataFile("knowledge/new.md", "from the backup")
        val backup = (unpack(export()) as BackupUnpackResult.Unpacked).backup
        Files.walk(dataDirectory.resolve("knowledge")).sorted(reverseOrder()).forEach(Files::delete)
        dataFile("knowledge/old.md", "live")
        dataFile("documents/live.pdf", "live")

        repeat(2) {
            adapter.beginRestore(backup, previous) shouldBe true
            adapter.installFiles(backup, confirm(backup)) shouldBe true
            adapter.revertFiles(backup.id) shouldBe true
            adapter.endRestore(backup.id)
        }
        adapter.installFiles(backup, confirm(backup)) shouldBe true

        dataDirectory.resolve("knowledge/new.md").readText() shouldBe "from the backup"
        dataDirectory.resolve("documents").exists() shouldBe false
    }

    @Test
    fun `stale work directories go, a marked restore stays for recovery`() {
        val backup = (unpack(export()) as BackupUnpackResult.Unpacked).backup
        adapter.beginRestore(backup, null) shouldBe true
        val root = dataDirectory.resolve(WorkDirectories.WORK_DIRECTORY)
        val stale = Files.createDirectory(root.resolve(UUID.randomUUID().toString()))
        val old = FileTime.from(Instant.now().minus(Duration.ofDays(2)))
        Files.setLastModifiedTime(stale, old)
        Files.setLastModifiedTime(root.resolve(backup.id.toString()), old)

        adapter.discard(requireNotNull(adapter.newWorkspace()).id)

        stale.exists() shouldBe false
        root.resolve(backup.id.toString()).exists() shouldBe true
    }

    @Test
    fun `a migrated backup is only kept while it is still the staged one`() {
        val bytes = export()
        val first = (unpack(bytes) as BackupUnpackResult.Unpacked).backup
        val second = (unpack(bytes) as BackupUnpackResult.Unpacked).backup

        adapter.keep(first.copy(migratedFrom = schema))
        adapter.find(first.id).shouldBeNull()
        adapter.keep(second.copy(migratedFrom = schema))
        adapter.find(second.id)?.migratedFrom shouldBe schema
    }

    private val previous = MasterKeysetCopy("previous keyset".toByteArray())

    private fun adapter(directory: Path) =
        ZipBackupArchiveAdapter(
            directory.toString(),
            DataSize.ofMegabytes(1),
            DataSize.ofMegabytes(2),
            MAX_ENTRIES,
            Clock.systemUTC(),
        )

    private fun workDirectories(): List<String> =
        dataDirectory.resolve(WorkDirectories.WORK_DIRECTORY).let { root ->
            if (!root.exists()) {
                emptyList()
            } else {
                Files.list(root).use {
                    it
                        .map { path ->
                            path.fileName.toString()
                        }.toList()
                }
            }
        }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
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

    private fun confirm(backup: StagedBackup): ConfirmationResult.Confirmed = confirm(BackupRestore.action(backup))

    private fun confirm(action: ConfirmableAction): ConfirmationResult.Confirmed {
        val store =
            object : ConfirmationStorePort {
                private var pending: PendingConfirmation? = null

                override fun issue(
                    pending: PendingConfirmation,
                    now: Instant,
                ) = ConfirmationToken("token").also { this.pending = pending }

                override fun redeem(token: ConfirmationToken) = pending.also { pending = null }
            }
        val gate = ConfirmActionUseCase(store, Clock.systemUTC(), Duration.ofMinutes(1))
        val requester = ConfirmationRequester(Actor.User, "session")
        val required = gate.execute(ConfirmationRequest(requester, action, null)) as ConfirmationResult.Required
        return gate.execute(ConfirmationRequest(requester, action, required.token)) as ConfirmationResult.Confirmed
    }

    private companion object {
        const val MAX_ENTRIES = 50
    }
}
