<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# adapters/crypto

Owns the cryptography of Jofi (ADR-0017, ADR-0035). A **protected path** (auth/crypto): every change
is reviewed by Lucas. Package: `io.github.scriptibus.jofi.system.adapter.crypto`.

| Class | Port | What |
|---|---|---|
| `Argon2PasswordHasherAdapter` | `PasswordHasherPort` | argon2id, OWASP parameters m = 19 MiB, t = 2, p = 1; at most 2 hashes at once |
| `TinkSecretCipherAdapter` | `SecretCipherPort` | Tink AES-256-GCM under the master keyset, associated data `jofi:secret:<id>` |
| `SetupTokenFileAdapter` | `SetupTokenPort` | one-time first-run token while Jofi is bound beyond loopback |
| `OwnerOnlyFiles` | – | `0600` files / `0700` directories, written atomically (temp file + hard link) |

Files live in the data volume (`jofi.data-dir`, `JOFI_DATA_DIR`, `/data` in the image):
`secrets/master-keyset.json` and `secrets/setup-token`.

Rules:
- Never log, return or put into an exception message a password, token, key, plaintext or
  ciphertext. Log the operation and the exception type only.
- Nothing may touch the data volume or generate keys during the context refresh: the image build's
  AOT training run exits on refresh, and a key generated there would be baked into the image. Use an
  `ApplicationRunner` (or lazy initialisation) instead.
- Adapters never throw across their port; key-loading failures are `SecretResult.StorageFailure`,
  tampered or foreign ciphertexts `SecretResult.Undecryptable`.
- Use Tink's current API (`KeysetHandle.generateNew(PredefinedAeadParameters...)`,
  `TinkJsonProtoKeysetFormat`, `getPrimitive(RegistryConfiguration.get(), ...)`); no deprecated calls.
- Key rotation (not automated yet): add a new primary key to the keyset, re-encrypt every `secret`
  row through `SecretStorePort.put`, then drop the old key (ADR-0035).
- Tests: unit tests with `@TempDir` data directories cover round trips, tampering, moved
  ciphertexts, foreign keysets and file permissions; the app-level tests are in `bootstrap`
  (`SecretStoreTest`, `ExposedInstanceTest`).
