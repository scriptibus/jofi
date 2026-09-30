// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

import io.github.scriptibus.jofi.system.domain.backup.BackupEntry
import io.github.scriptibus.jofi.system.domain.backup.BackupManifest
import io.github.scriptibus.jofi.system.domain.backup.BackupPath
import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.EntryDigest
import io.github.scriptibus.jofi.system.domain.backup.SchemaVersion
import io.github.scriptibus.jofi.system.domain.backup.TableDump
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeParseException

/** `manifest.json` of format version 1 (documented in ADR-0042). */
internal object ManifestJson {
    // The manifest is read as a tree: besides its byte limit (BackupLimits), a token limit bounds the
    // nodes a hostile one could make (`[{},{},...]`); 100,000 entries need about a million tokens. No
    // string of the format is longer than a path (1,024 characters).
    private const val MAX_TOKENS = 2_000_000L
    private const val MAX_STRING_LENGTH = 4096
    private val mapper =
        JsonMapper
            .builder(
                JsonFactory
                    .builder()
                    .streamReadConstraints(
                        StreamReadConstraints
                            .builder()
                            .maxTokenCount(MAX_TOKENS)
                            .maxStringLength(MAX_STRING_LENGTH)
                            .build(),
                    ).build(),
            ).build()

    fun write(manifest: BackupManifest): ByteArray {
        val root = mapper.createObjectNode()
        root.put("format", BackupManifest.FORMAT_NAME)
        root.put("formatVersion", manifest.formatVersion)
        root.put("appVersion", manifest.appVersion)
        root.put("schemaVersion", manifest.schemaVersion.value)
        root.put("createdAt", manifest.createdAt.toString())
        val tables = root.putArray("tables")
        manifest.tables.forEach {
            tables
                .addObject()
                .put(
                    "name",
                    it.name,
                ).put("path", it.path.value)
                .put("rows", it.rows)
        }
        val entries = root.putArray("entries")
        manifest.entries.forEach { entry ->
            entries
                .addObject()
                .put("path", entry.path.value)
                .put("size", entry.digest.size)
                .put("sha256", entry.digest.sha256)
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root)
    }

    /** The manifest in [content]; throws [BackupRefusal] when it is not one this version reads. */
    fun read(content: InputStream): BackupManifest =
        try {
            val root = mapper.readTree(content)
            if (root.required("format").stringValue() != BackupManifest.FORMAT_NAME) invalid()
            val formatVersion = root.required("formatVersion").intValue()
            if (formatVersion != BackupManifest.FORMAT_VERSION) throw BackupRefusal(BackupProblem.UNSUPPORTED_FORMAT)
            BackupManifest(
                formatVersion = formatVersion,
                appVersion = root.required("appVersion").stringValue(),
                schemaVersion = SchemaVersion(root.required("schemaVersion").stringValue()),
                createdAt = Instant.parse(root.required("createdAt").stringValue()),
                tables = elements(root, "tables").map(::table),
                entries = elements(root, "entries").map(::entry),
            )
        } catch (_: JacksonException) {
            invalid()
        } catch (_: IllegalArgumentException) {
            invalid()
        } catch (_: DateTimeParseException) {
            invalid()
        }

    private fun elements(
        node: JsonNode,
        name: String,
    ): List<JsonNode> {
        val array = node.required(name)
        if (!array.isArray) invalid()
        return array.iterator().asSequence().toList()
    }

    private fun table(node: JsonNode): TableDump {
        val table = TableDump(node.required("name").stringValue(), node.required("rows").longValue())
        if (node.required("path").stringValue() != table.path.value) invalid()
        return table
    }

    private fun entry(node: JsonNode): BackupEntry =
        BackupEntry(
            BackupPath.parse(node.required("path").stringValue()) ?: invalid(),
            EntryDigest(node.required("size").longValue(), node.required("sha256").stringValue()),
        )

    private fun invalid(): Nothing = throw BackupRefusal(BackupProblem.MANIFEST_INVALID)
}

/** Why an upload is refused; thrown and caught inside this adapter only, never across the port. */
internal class BackupRefusal(
    val problem: BackupProblem,
) : RuntimeException(problem.name)
