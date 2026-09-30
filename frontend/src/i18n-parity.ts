// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Compile-time check that DE and EN define exactly the same message keys.
// Using a key that exists in neither file is already a type error via the
// generated `m` object; this closes the gap of a key missing in only one locale.
// `pnpm typecheck` fails with the missing key names in the error message.

import type de from "../messages/de.json";
import type en from "../messages/en.json";

type MissingIn<Source, Target> = Exclude<keyof Source, keyof Target>;

export const deHasEveryEnKey: [MissingIn<typeof en, typeof de>] extends [never]
  ? true
  : MissingIn<typeof en, typeof de> = true;

export const enHasEveryDeKey: [MissingIn<typeof de, typeof en>] extends [never]
  ? true
  : MissingIn<typeof de, typeof en> = true;
