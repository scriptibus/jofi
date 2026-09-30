// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { DonkeyLogo } from "./DonkeyLogo";

export interface EmptyStateProps {
  /** Heading of the empty area (an h2: the page keeps its own h1). */
  title: string;
  children: ReactNode;
}

/** A calm placeholder for a list or page without content yet, with the donkey. */
export function EmptyState({ title, children }: EmptyStateProps) {
  return (
    <section className="flex flex-col items-start gap-4 rounded border border-line border-dashed bg-surface p-8 sm:flex-row sm:items-center">
      <DonkeyLogo className="size-14 shrink-0" />
      <div className="flex max-w-prose flex-col gap-1.5">
        <h2 className="text-h3">{title}</h2>
        <p className="text-muted">{children}</p>
      </div>
    </section>
  );
}
