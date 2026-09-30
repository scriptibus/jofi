// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/** The server's limit for a base URL (backend `ProviderInput.MAX_BASE_URL`). */
export const MAX_BASE_URL = 2_000;

export type BaseUrlCheck =
  | { status: "empty" }
  | { status: "too-long" }
  | { status: "invalid" }
  /** User info, a query or a fragment: keys belong in the key field, never in the URL (ADR-0017). */
  | { status: "credentials" }
  /** Valid; `insecure` when it is plain http to anything but this machine. */
  | { status: "ok"; insecure: boolean };

const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

function isLocal(hostname: string): boolean {
  return LOCAL_HOSTS.has(hostname) || hostname.endsWith(".localhost") || hostname.startsWith("127.");
}

/**
 * Checks an OpenAI-compatible base URL the way the server does (absolute http(s) with a host, no user
 * info, query or fragment), so the form can say what is wrong before sending. Plain http is allowed
 * (a local Ollama) but flagged when it leaves this machine: the key and every prompt travel unencrypted.
 */
export function checkBaseUrl(raw: string): BaseUrlCheck {
  const value = raw.trim();
  if (value === "") return { status: "empty" };
  if (value.length > MAX_BASE_URL) return { status: "too-long" };
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return { status: "invalid" };
  }
  if ((url.protocol !== "http:" && url.protocol !== "https:") || url.hostname === "")
    return { status: "invalid" };
  // `new URL` drops an empty "?" or "#", which the server still refuses, so look at the text too.
  if (url.username !== "" || url.password !== "" || value.includes("?") || value.includes("#"))
    return { status: "credentials" };
  return { status: "ok", insecure: url.protocol === "http:" && !isLocal(url.hostname) };
}

/**
 * Whether two base URLs share an origin (scheme, host, port). A stored key belongs to the origin it was
 * entered for; the server wants it again for any other (backend `ProviderConfig.keyMustBeReenteredFor`).
 */
export function sameOrigin(a: string | null | undefined, b: string | null | undefined): boolean {
  const origin = (value: string | null | undefined) => {
    if (!value?.trim()) return null;
    try {
      return new URL(value.trim()).origin;
    } catch {
      return value.trim();
    }
  };
  return origin(a) === origin(b);
}
