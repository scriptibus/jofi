// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { getRouteApi } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { m } from "../../paraglide/messages.js";
import { Button, DownloadIcon, EmptyState } from "../../ui";
import { ImportPostingDialog } from "../applications/import/ImportPostingDialog";
import { draftFromShare, type SharedContent } from "../applications/import/importModel";
import { PageHeader } from "./PlaceholderPage";

const route = getRouteApi("/_app/share");

/** Keeps only non-empty strings: the query string is untrusted input from another app. */
export function parseSharedContent(search: Record<string, unknown>): SharedContent {
  const shared: SharedContent = {};
  for (const key of ["title", "text", "url"] as const) {
    const value = search[key];
    if (typeof value === "string" && value.trim() !== "") shared[key] = value.trim();
  }
  return shared;
}

function hasContent(shared: SharedContent): boolean {
  return Boolean(shared.title || shared.text || shared.url);
}

/**
 * The share target (manifest `share_target`, GET): shows what another app shared and opens the import dialog with it
 * filled in (spec §8.1). Nothing is imported until the user presses "Import" in the dialog. The shared parts are
 * untrusted input from another app: shown as plain text, never as a link or markup, and moved out of the address
 * bar (replaced by `/share`) as soon as the page holds them, so they do not stay in the history. A new share while
 * the page is open replaces the old one.
 */
export function SharePage() {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const [shared, setShared] = useState<SharedContent>(search);
  const [dialog, setDialog] = useState({ open: hasContent(search), received: 0 });

  useEffect(() => {
    if (!hasContent(search)) return;
    setShared(search);
    setDialog((current) => ({ open: true, received: current.received + 1 }));
    void navigate({ search: {}, replace: true });
  }, [search, navigate]);

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
          <Button className="self-start" onPress={() => setDialog((current) => ({ ...current, open: true }))}>
            <DownloadIcon className="size-4" aria-hidden="true" />
            {m.share_import()}
          </Button>
        </section>
      )}
      <ImportPostingDialog
        key={dialog.received}
        isOpen={dialog.open && rows.length > 0}
        onClose={() => setDialog((current) => ({ ...current, open: false }))}
        initial={draftFromShare(shared)}
      />
    </>
  );
}
