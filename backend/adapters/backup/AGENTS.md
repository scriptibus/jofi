<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# adapters/backup

Backup archives (ADR-0042, which documents the format). An **export/import protected path**: every change
is reviewed by Lucas. Package: `io.github.scriptibus.jofi.system.adapter.backup`.

| Class | What |
|---|---|
| `ZipBackupArchiveAdapter` | `BackupArchivePort`: work directories, writing and unpacking archives, the one staged restore, swapping the data volume's files |
| `BackupZipWriter` | entries with size and SHA-256, the manifest last; regular files only, links are never followed |
| `BackupZipReader` | unpacks under the limits (upload, real unpacked bytes, entries, keyset), format paths only (zip slip), no duplicates, regular files only |
| `ManifestJson` | `manifest.json` of format version 1 (Jackson 3 tree model, strict) |
| `WorkDirectories` | `<data>/backup-work/<id>`, owner-only, leftovers older than a day removed |
| `DataFileSwap` | renames `<data>/knowledge` and `<data>/documents` out and the backup's in, and back; `swap-<root>` markers make the undo exact after a crash |
| `BackupLockAdapter` | `BackupLockPort`: one in-memory read/write lock, `tryLock` only (uploads and restores exclusive, exports shared) |

Rules:
- An upload is untrusted input: check it completely before anything outside its work directory changes,
  and count real bytes, never sizes a zip header claims. `BackupRefusal` stays inside this module; the
  port answers `BackupUnpackResult`.
- A restore marks its work directory (`restore-in-progress`, with the previous keyset owner-only in
  `previous/master-keyset.json`) before it changes files; `RecoverRestoreUseCase` settles every marked
  directory (after failures, a failed commit, and at every start). Never delete a marked directory.
- Work directories live in the data volume, on the same filesystem as the data they replace, so files
  move into place by renaming.
- Log operations and exception types only: file names and contents are personal data.
- Tests (`ZipBackupArchiveAdapterTest`) cover the round trip and every hostile archive; add a case for
  every new refusal.
