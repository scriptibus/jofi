// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { m } from "../../paraglide/messages.js";
import { EmptyState } from "../../ui";
import { usePageTitle } from "../preferences";

export interface PageHeaderProps {
  title: string;
  eyebrow?: string;
}

export function PageHeader({ title, eyebrow }: PageHeaderProps) {
  usePageTitle(title);
  return (
    <div className="flex flex-col gap-2">
      {eyebrow ? <p className="font-data text-eyebrow text-muted uppercase">{eyebrow}</p> : null}
      <h1 className="text-display">{title}</h1>
    </div>
  );
}

export interface PlaceholderPageProps extends PageHeaderProps {
  /** What will appear here once the feature exists. */
  children: ReactNode;
}

/** An M1 area that does not exist yet: its heading and an empty state. */
export function PlaceholderPage({ title, eyebrow, children }: PlaceholderPageProps) {
  return (
    <>
      <PageHeader title={title} {...(eyebrow ? { eyebrow } : {})} />
      <EmptyState title={m.empty_heading()}>{children}</EmptyState>
    </>
  );
}
