// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";

/** The backend's rule (ADR-0035, NIST SP 800-63B-4): 15 to 256 characters, no composition rules. */
export const PASSWORD_MIN_LENGTH = 15;
export const PASSWORD_MAX_LENGTH = 256;

/**
 * Counts like the backend's `Password` (domain), so the form never accepts what the server
 * refuses: the minimum in code points (an emoji is one character), the maximum in UTF-16 units.
 */
export function newPasswordError(password: string): string | null {
  if ([...password].length < PASSWORD_MIN_LENGTH) return m.password_too_short();
  if (password.length > PASSWORD_MAX_LENGTH) return m.password_too_long();
  return null;
}

export function repeatPasswordError(password: string, repeated: string): string | null {
  return password === repeated ? null : m.password_mismatch();
}
