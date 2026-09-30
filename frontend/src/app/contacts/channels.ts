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

/** A trailing extension: `x89`, `ext. 89`, `;ext=89` (any script's digits). */
const EXTENSION = /(?:\s*;\s*ext\s*=|\s*ext\.?|\s*x)\s*(\p{Nd}+)\s*$/iu;
/** What a dialable number may contain besides its digits: separators and the dial characters. */
const NUMBER_PART = /^[\p{Nd}\s+\-.()/*#,]*$/u;

/** The digits of `text` as ASCII, keeping `+*#,` and dropping separators. */
function dialable(text: string): string {
  let result = "";
  for (const character of text) {
    const digit = asciiDigit(character);
    if (digit !== undefined) result += digit;
    else if ("+*#,".includes(character)) result += character;
  }
  return result;
}

/**
 * A `tel:` link for a phone number as entered, or undefined if it would not dial exactly that number.
 * Digits of any script become ASCII (after NFKC, which folds full-width forms); only `+0-9*#,` and an
 * extension (`x89`, `ext. 89` → `;ext=89`, RFC 3966) survive. A value with anything else, such as
 * vanity letters (`1-800-FLOWERS`) or URL syntax, is no link at all, since dropping those characters
 * would dial another number. `#` is percent-encoded, since in a URL it would start a fragment. Show the
 * number as entered; this is only the link.
 */
export function telHref(phone: string): string | undefined {
  const normalized = phone.normalize("NFKC").trim();
  const extension = EXTENSION.exec(normalized);
  const number = extension ? normalized.slice(0, extension.index) : normalized;
  if (!NUMBER_PART.test(number)) return undefined;
  const main = dialable(number);
  if (!/[0-9]/.test(main)) return undefined;
  const suffix = extension?.[1] ? `;ext=${dialable(extension[1])}` : "";
  return `tel:${main.replaceAll("#", "%23")}${suffix}`;
}

/** The web address itself if it is an absolute http(s) URL (the server's rule), else undefined. */
export function webHref(address: string): string | undefined {
  if (!isWebAddress(address)) return undefined;
  return safeHref(address) || undefined;
}
