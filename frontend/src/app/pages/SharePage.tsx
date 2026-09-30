// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { getRouteApi } from "@tanstack/react-router";
import { m } from "../../paraglide/messages.js";
import { EmptyState } from "../../ui";
import { PageHeader } from "./PlaceholderPage";

const route = getRouteApi("/_app/share");

/** What the Web Share Target sends (manifest `share_target.params`); each part is optional. */
export interface SharedContent {
  title?: string;
  text?: string;
  url?: string;
}

/** Keeps only non-empty strings: the query string is untrusted input from another app. */
export function parseSharedContent(search: Record<string, unknown>): SharedContent {
  const shared: SharedContent = {};
  for (const key of ["title", "text", "url"] as const) {
    const value = search[key];
    if (typeof value === "string" && value.trim() !== "") shared[key] = value.trim();
  }
  return shared;
}

/**
 * The share target: shows what another app shared with Jofi. Shown as plain text, never as a
 * link or markup (untrusted input). Importing it is #34.
 */
export function SharePage() {
  const shared = route.useSearch();
  const rows: { label: string; value: string }[] = [];
  if (shared.title) rows.push({ label: m.share_title(), value: shared.title });
  if (shared.text) rows.push({ label: m.share_text(), value: shared.text });
  if (shared.url) rows.push({ label: m.share_url(), value: shared.url });

  return (
    <>
      <PageHeader title={m.share_heading()} />
      {rows.length === 0 ? (
        <EmptyState title={m.empty_heading()}>{m.share_empty()}</EmptyState>
      ) : (
        <section className="flex max-w-prose flex-col gap-5 rounded border border-line bg-surface p-6 shadow-card">
          <p className="text-muted">{m.share_intro()}</p>
          <dl className="flex flex-col gap-4">
            {rows.map((row) => (
              <div key={row.label} className="flex flex-col gap-1">
                <dt className="font-data text-eyebrow text-muted uppercase">{row.label}</dt>
                <dd className="whitespace-pre-line break-words">{row.value}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}
    </>
  );
}
