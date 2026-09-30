<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0042: Backup archive format, restore, and the master keyset in backups

- Status: accepted
- Date: 2026-09-30
- Source: issue #26 (M0-11) and its comments; spec §3.1, §13 (portability); 04-tech-stack-proposal §4.5;
  threat model T4, T8; refines ADR-0013, ADR-0030, ADR-0035, ADR-0038, ADR-0039

## Context

Spec §3.1 asks for a one-click backup (database, knowledge files, documents) and restore; §13 for full
export/import; §4.5 for a CI round-trip test. ADR-0035 left open how the master keyset travels with a
backup: the `secret` table is useless without it, and the app refuses to start when the keyset does not
decrypt the recorded check value (`master_key_check`). A restore replaces all data, so it is destructive
and needs the server-enforced confirmation (ADR-0039). An uploaded archive is untrusted input (zip slip,
zip bombs, links).

## Decision

### Archive format, version 1

A zip file (`jofi-backup-<yyyyMMdd-HHmmss>.zip`, UTC) with these entries, all `/`-separated:

| Entry | Content |
|---|---|
| `database/<table>.csv` | one per backed-up table: PostgreSQL `COPY <table> (<columns>) TO STDOUT (FORMAT csv, HEADER true)`, UTF-8, header row with the column names |
| `secrets/master-keyset.json` | the master keyset (Tink JSON, as in the data volume), when there is one |
| `files/knowledge/...`, `files/documents/...` | the regular files below `<data>/knowledge` and `<data>/documents` (including the knowledge git repository, ADR-0013); links and special files are skipped, empty directories are not kept |
| `manifest.json` | written last, once every checksum is known |

```json
{
  "format": "jofi-backup",
  "formatVersion": 1,
  "appVersion": "0.1.0",
  "schemaVersion": "20260930064000",
  "createdAt": "2026-09-30T12:00:00Z",
  "tables": [{ "name": "secret", "path": "database/secret.csv", "rows": 1 }],
  "entries": [{ "path": "database/secret.csv", "size": 123, "sha256": "<lowercase hex>" }]
}
```

`schemaVersion` is the latest applied Flyway migration, `appVersion` the build version. `entries` lists
every entry except the manifest, with size and SHA-256. CSV from `COPY` is exact for every column type
(bytea as hex, arrays, JSONB, `timestamptz` with microseconds, NULL versus empty string) and readable by
other tools; that is the portability of the format.

**What is in it** is decided per table in `BackupTables` (`adapters/persistence`): every table is either
`EXPORTED` or `EXCLUDED` with a reason. Excluded: `spring_session*` (bearer credentials; a restore ends
every session, ADR-0035) and the JobRunr job store `jobrunr_*` (operational state with ids only;
`app` registers its recurring schedules at every start, ADR-0038). Not in it: the setup token, the
password-reset marker, and `backup-work/`. `DatabaseBackupRepositoryTest` enumerates the generated jOOQ
schema and fails for a table in neither list, and its round-trip test fails for an exported table it has
not seeded with awkward values, dumped, restored and compared. So a new table cannot
be forgotten: its PR must export it (and seed it in the test) or exclude it with a reason.

### The master keyset travels in the clear; a backup grants full access

A backup contains the keyset in the clear next to the encrypted secrets, the argon2id password hash and
all personal data. We decided **against a passphrase** in this version:

- Encrypting only the keyset protects only the API keys, while the CV, applications, contacts and
  knowledge would still lie in the zip in plain text. Protecting a backup means encrypting all of it.
- Whole-archive encryption is what backup tools already do well (restic, borg, `age`, an encrypted
  disk), and a forgotten passphrase would turn the one copy of all data into noise.
- Leaving the keyset out would make every backup lose the API keys, which have to be re-entered; a
  restore on a new machine (the main use) would always be incomplete.

Therefore the API documentation, the controller and the UI (#131) warn that **a backup grants full
access** and must be stored like a password. Whole-archive encryption can come later as format version 2
without breaking version 1.

### Re-authentication, changelog and the backup lock

A backup grants full access, and a restore replaces everything, so a session alone is not enough:
export and restore take the **current password** in the request body, checked by
`VerifyPasswordUseCase` exactly like a password change (the same per-client and global backoff,
ADR-0035; the first confirmation step already needs it). Every export is recorded in the changelog
(entity `backup`, actor `User`) before anything leaves; an export that cannot be recorded does not
happen. All backup work shares one lock (`BackupLockPort`, in memory: only `app` serves it): uploads and
restores run alone, exports may run together; nothing waits, a call that would have to answers `409`
(`urn:jofi:problem:system:backup-busy`). A migrated upload replaces the staged backup only while its id
is still the staged one.

### Export: streamed, synchronous

`POST /api/system/backup/exports` with `{"password": ...}` (session and CSRF required). The database is
dumped first, from one `REPEATABLE READ, READ ONLY` transaction (a consistent snapshot
while the app keeps writing), into a work directory in the data volume; only then is the response opened
and the zip streamed from there, so nothing is held in memory and a failed dump is a clean `503`. A
failure while streaming leaves the zip unfinished (no central directory, no manifest), so a broken
download is never a valid backup. The manifest's `createdAt` is the snapshot's time (the database clock).
It runs in the request, not as a job (ADR-0038 allowed either): the download is the result, and a job
would need somewhere to keep the archive.

### Restore: upload and check, then confirm, then one transaction

1. `POST /api/system/backup/restores` (`application/zip`) unpacks the upload into
   `<data>/backup-work/<id>` and checks it **before anything is touched**: upload size
   (`JOFI_BACKUP_MAX_UPLOAD_SIZE`, default 2 GB), unpacked size counted on the real bytes, not the sizes
   the zip claims (`JOFI_BACKUP_MAX_UNPACKED_SIZE`, 8 GB, zip bombs), entry count
   (`JOFI_BACKUP_MAX_ENTRIES`, 100,000), keyset at most 64 KiB; only paths of the format (no absolute
   paths, `..`, empty or `.` segments, backslashes or control characters; the resolved path must stay in
   the work directory: zip slip); no entry twice; only regular files are created, so an entry can never
   become a link, and directory entries create nothing. Then the manifest (at most 32 MiB, 2 million JSON
   tokens, strings of at most 4 KiB, `appVersion` at most 64 and `schemaVersion` at most 32 characters):
   format and version, a schema version not newer than the running one (an older one is migrated, see
   "Schema versions"), entries exactly as listed with matching sizes and checksums, tables exactly the
   backed-up ones of its schema, exactly one `user_account` row (nobody could log in otherwise), the
   keyset present when `secret` or `master_key_check` has rows. A refusal
   is `422` (`413` for the limits) with type `urn:jofi:problem:system:backup-refused` and a `reason`
   such as `schema-older`. What passes is described (`201`) and waits; one staged backup at a time, a new
   upload replaces it.
2. `POST /api/system/backup/restores/{id}` is the two-step confirmation (ADR-0039): operation
   `system.backup.restore`, target the staged id, effect `backup` named by its creation time with counts
   `rows`, `files`, `keyset`. The endpoint is listed in `ConfirmationRules.OUTWARD_FACING_ENDPOINTS`.
3. With the password and the confirmation, in **one transaction** (`RestoreBackupUseCase`, `InstallBackupUseCase`):
   `TRUNCATE` every exported table and the session tables (the append-only triggers of ADR-0030 do not
   fire on `TRUNCATE`), `COPY ... FROM STDIN (FORMAT csv, HEADER match)` in foreign-key order (the header
   must name exactly the table's columns), row counts must match the manifest, identity sequences
   continue after the highest restored id, the restored `master_key_check` must be decrypted by the
   backup's keyset, then the changelog entry (entity `backup`, actor = the requester, `Actor.User` in
   the UI). The steps outside the database come last, see "Crashes and a failed commit": the work
   directory is marked as a restore in progress, the data volume's directories are swapped by renaming,
   and the keyset is replaced by one atomic rename at the very end. The database's sessions are gone
   with the commit, so the user logs in again, with the backup's password.

`TinkSecretCipherAdapter` reloads the keyset when the file changes (file key, modification time, size),
so `app` and the separately running `worker` both switch to the restored keyset without a restart.

### Crashes and a failed commit

Files and keyset cannot be part of the database transaction, so a crash or a failed commit could leave
them and the database disagreeing (and a keyset that does not match the database stops the start,
ADR-0035). Therefore:

- Before any file or the keyset changes, the restore copies the keyset in use (owner-only) to
  `backup-work/<id>/previous/master-keyset.json` and then writes the marker `restore-in-progress`.
  Before each data directory is swapped, `swap-<root>` records whether the backup has that directory, so
  an undo can tell every state apart, also a crash between the two renames of one swap.
- The restore's **changelog entry is the commit marker**: written in the restore's transaction, it
  exists exactly when the database was replaced.
- `RecoverRestoreUseCase` settles every marked restore: with the entry, it rolls forward (the leftovers
  go); without it, it moves the previous directories back and reinstates the previous keyset, then
  removes the marker. It runs when a restore returns anything but success, **when the commit throws**
  (the entry then tells whether it took effect), and at **every start of `app`, before the master
  keyset is checked** (`AuthStartup`). If it cannot decide or undo, the marker stays, the request
  answers `500 backup-restore-incomplete`, and the next start tries again and refuses to start rather
  than run on inconsistent data.
- Stale work directories are removed after a day, never a marked one. An undone restore can run again
  (moves never go onto an existing directory, and empty leftovers of an undone attempt are cleared).

### Schema versions

A backup of a **newer** schema is refused (`schema-newer`): upgrade Jofi first. A backup of an **older**
schema is migrated while it is staged, before anything is confirmed or touched: `ScratchMigration`
creates a scratch database next to the app's (`jofi_restore_<id>`, same server and credentials), lets
Flyway create the backup's schema version there (`target`), loads the dumps (`HEADER match` checks them
against that schema; the table set must be exactly that version's backed-up tables), lets Flyway migrate
to the latest version exactly as at an upgrade, dumps the tables again into the work directory and drops
the scratch database. The staged backup then carries the running schema version and the migrated row
counts (`migratedFrom` names the original version), and the restore continues as for a current backup.
The app's own database is never involved, so the all-or-nothing guarantee holds. Scratch databases a
crash left behind are dropped at every start and before each migration (only names
`jofi_restore_<32 hex>`). This needs the right to create databases (the compose database user has it); where it is missing, or a migration itself
fails, the upload is refused with `schema-older`: restore it with the Jofi version its manifest names,
then upgrade. Dumps that do not fit their schema are `data-invalid`.

Docs consulted: PostgreSQL 18 `COPY` (CSV format, `HEADER MATCH`), pgjdbc `CopyManager`, Java
`java.util.zip` (`ZipInputStream`, `ZipOutputStream`), Tink Java (keyset formats), Spring Framework 7
(`TransactionTemplate`, `ResourceHttpMessageConverter` streaming of `InputStreamResource`),
OWASP File Upload Cheat Sheet (archive handling).

## Consequences

- New tables need a decision in `BackupTables` and a seed row in `DatabaseBackupRepositoryTest`; new
  files in the data volume belong below `knowledge/` or `documents/`, or need their own entry here.
- A restore replaces the password and ends every session; a restored instance on a new machine needs
  the whole backup, nothing else.
- An export needs free space in the data volume for the database dump, an upload for its unpacked size.
- **A restored backup is fully trusted**: it replaces the password, all data and the keyset, and its
  content (knowledge files, documents) is used as the user's own. Only restore your own backups (threat
  model T8); the UI says so (#131).
- The knowledge-git PR (ADR-0013) must treat a restored `.git` directory as untrusted: sanitise or
  regenerate `.git/config` and remove hooks on restore, or run JGit with filters, hooks and external
  commands disabled; a crafted backup must not become code execution.
- Jobs the worker had blocked on the restore's `TRUNCATE` continue once it commits and may write into
  the restored database (their ids may no longer exist); job handlers must treat a missing id as done.
- Pending JobRunr jobs survive a restore and may refer to ids that no longer exist; job handlers must
  treat a missing id as done (they already must, since a user may delete things while a job waits).
- `adapters/persistence` compiles against the PostgreSQL driver for its `COPY` API.
- The UI (Settings > Backup) is #131.
- Migrations must keep working on data restored from any older backup (they already must for upgrades).
