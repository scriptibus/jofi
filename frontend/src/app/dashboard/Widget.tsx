// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { UseQueryResult } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { m } from "../../paraglide/messages.js";
import { Alert, Button } from "../../ui";
import { describeError } from "../problems";

/** Every dashboard query shows its own failure in its widget, so one slow or failing figure hides nothing else. */
export const widgetQuery = { meta: { errorHandledLocally: true } } as const;

export interface WidgetProps {
  /** The heading's id, which names the region. */
  id: string;
  title: string;
  /** What the figures mean, below the heading. */
  description?: string;
  /** The way to the matching filtered view, at the bottom. */
  footer?: ReactNode;
  className?: string;
  children: ReactNode;
}

/** One card of the dashboard grid: a region named by its heading. */
export function Widget({ id, title, description, footer, className, children }: WidgetProps) {
  return (
    <section
      aria-labelledby={id}
      className={["flex flex-col gap-4 rounded border border-line bg-surface p-6 shadow-card", className]
        .filter(Boolean)
        .join(" ")}
    >
      <div className="flex flex-col gap-1">
        <h2 id={id} className="text-h3">
          {title}
        </h2>
        {description ? <p className="text-muted">{description}</p> : null}
      </div>
      <div className="flex flex-1 flex-col gap-4">{children}</div>
      {footer ? <div className="flex flex-wrap gap-x-4 gap-y-2">{footer}</div> : null}
    </section>
  );
}

export interface WidgetContentProps<T> {
  query: UseQueryResult<T>;
  /** Why the widget is empty, in the widget's words. */
  failed: string;
  children: (data: T) => ReactNode;
}

/** A widget's figures once they are there; before that "Loading…", or why they failed with a retry. */
export function WidgetContent<T>({ query, failed, children }: WidgetContentProps<T>) {
  if (query.data !== undefined) return <>{children(query.data)}</>;
  if (!query.isError) return <p role="status">{m.loading()}</p>;
  return (
    <Alert tone="error" title={failed}>
      <span className="flex flex-col items-start gap-3">
        {describeError(query.error).message}
        <Button variant="secondary" onPress={() => void query.refetch()}>
          {m.error_retry()}
        </Button>
      </span>
    </Alert>
  );
}
