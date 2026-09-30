// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Links for a contact's channels (spec §5, #109). Channel values are stored as entered by the user, the
// AI or an external client, so they are untrusted: a link is only ever built through these functions,
// and a value that does not fit its kind stays plain text. Nothing here fetches anything.

import { safeHref } from "../../ui";
import { isWebAddress } from "../companies/company";

const DECIMAL_DIGIT = /\p{Nd}/u;
/** Unicode encodes every decimal digit set as ten code points in a row, 0 to 9 (a stability guarantee). */
const DIGITS_PER_SET = 10;
/** The longest run of adjacent digit sets (the five mathematical ones), in code points. */
const LONGEST_DIGIT_RUN = 50;

/**
 * The ASCII digit for a decimal digit of any script (`٣` → `3`), or undefined for anything else. The
 * digit's value is its distance from the start of its run of digit code points, modulo ten.
 */
export function asciiDigit(character: string): string | undefined {
  if (!DECIMAL_DIGIT.test(character)) return undefined;
  const codePoint = character.codePointAt(0) ?? 0;
  let start = codePoint;
  while (codePoint - start < LONGEST_DIGIT_RUN && DECIMAL_DIGIT.test(String.fromCodePoint(start - 1)))
    start--;
  return String((codePoint - start) % DIGITS_PER_SET);
}

/**
 * A `mailto:` link for `address`, or undefined if it is no email address (an `@` with text on both
 * sides, no whitespace: the server's rule). Both parts are percent-encoded, so a stored value can never
 * add headers such as `?bcc=` or `&body=`.
 */
export function mailtoHref(address: string): string | undefined {
  const at = address.lastIndexOf("@");
  if (at <= 0 || at === address.length - 1 || /\s/u.test(address)) return undefined;
  const local = encodeURIComponent(address.slice(0, at));
  const domain = encodeURIComponent(address.slice(at + 1));
  return `mailto:${local}@${domain}`;
}

/**
 * A `tel:` link for a phone number as entered, or undefined if it has no digit. Digits of any script
 * become ASCII (after NFKC, which folds full-width forms); only `+0-9*#,;` survive, so letters, spaces
 * and anything that could leave the number are dropped. `#` is percent-encoded (RFC 3966), since in a
 * URL it would start a fragment. Show the number as entered; this is only the link.
 */
export function telHref(phone: string): string | undefined {
  let dialable = "";
  for (const character of phone.normalize("NFKC")) {
    const digit = asciiDigit(character);
    if (digit !== undefined) dialable += digit;
    else if ("+*#,;".includes(character)) dialable += character;
  }
  if (!/[0-9]/.test(dialable)) return undefined;
  return `tel:${dialable.replaceAll("#", "%23")}`;
}

/** The web address itself if it is an absolute http(s) URL (the server's rule), else undefined. */
export function webHref(address: string): string | undefined {
  if (!isWebAddress(address)) return undefined;
  return safeHref(address) || undefined;
}
