// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ProgressBar as AriaProgressBar } from "react-aria-components";

export interface ProgressBarProps {
  /** Accessible name of the bar; the figures belong in `valueText`. */
  label: string;
  /** Filled share, 0 to 100 (values outside are clamped). */
  percent: number;
  /** What a screen reader hears and what the page should show next to the bar ("$3 of $10 (30%)"). */
  valueText: string;
  /** `critical` marks a limit that is used up; the text next to the bar still has to say so. */
  tone?: "normal" | "critical";
  className?: string;
}

/**
 * A determinate bar for a share of a limit (React Aria ProgressBar). It only adds a picture: colour
 * and length never carry the meaning alone, so always show `valueText` as visible text as well.
 */
export function ProgressBar({ label, percent, valueText, tone = "normal", className }: ProgressBarProps) {
  const clamped = Math.min(100, Math.max(0, percent));
  return (
    <AriaProgressBar
      aria-label={label}
      value={clamped}
      valueLabel={valueText}
      className={["h-3 w-full overflow-hidden rounded border border-line bg-sunken", className]
        .filter(Boolean)
        .join(" ")}
    >
      <div
        className={`h-full ${tone === "critical" ? "bg-bad" : "bg-accent"}`}
        style={{ width: `${clamped}%` }}
      />
    </AriaProgressBar>
  );
}
