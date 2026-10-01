// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

export type ShareBarTone = "accent" | "bad";

export interface ShareBarProps {
  /** The part, e.g. applications in one status. */
  value: number;
  /** The whole the part is measured against; zero or less draws an empty bar. */
  max: number;
  /** `bad` for a share that is a problem (a budget used up); the text next to the bar says so too. */
  tone?: ShareBarTone;
  className?: string;
}

const fills: Record<ShareBarTone, string> = { accent: "bg-accent", bad: "bg-bad" };

/** The share of `value` in `max` as a whole percentage, 0 to 100; a part above zero shows at least 1 %. */
export function sharePercent(value: number, max: number): number {
  if (max <= 0 || value <= 0) return 0;
  return Math.min(100, Math.max(1, Math.round((value / max) * 100)));
}

/**
 * A simple horizontal bar for a share (a count against the largest, money against a budget). Decorative: the
 * number always stands next to it as text, so the bar is hidden from assistive technology. It never animates.
 */
export function ShareBar({ value, max, tone = "accent", className }: ShareBarProps) {
  const percent = sharePercent(value, max);
  return (
    <div
      aria-hidden="true"
      className={["h-2 w-full overflow-hidden rounded-full bg-sunken", className].filter(Boolean).join(" ")}
    >
      {/* The width is data, not a design value, so it is an inline style. */}
      <div className={`h-full rounded-full ${fills[tone]}`} style={{ inlineSize: `${percent}%` }} />
    </div>
  );
}
