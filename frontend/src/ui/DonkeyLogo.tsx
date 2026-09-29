// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

export interface DonkeyLogoProps {
  /**
   * Accessible name. Omit only when the logo is decorative (e.g. next to the
   * visible "Jofi" wordmark); it is then hidden from assistive technology.
   */
  label?: string;
  /** Loading variant: the donkey bobs its head and flicks its ears. Still under reduced motion. */
  loading?: boolean;
  /** Size utility classes, e.g. `size-8`. */
  className?: string;
}

/** Jofi's donkey. Doubles as the loading indicator. */
export function DonkeyLogo({ label, loading = false, className = "size-8" }: DonkeyLogoProps) {
  const ear = loading ? "origin-bottom transform-fill motion-safe:animate-ear" : "";

  return (
    <svg
      viewBox="0 0 64 64"
      className={className}
      data-loading={loading || undefined}
      focusable="false"
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      <g className={loading ? "origin-bottom transform-fill motion-safe:animate-bob" : ""}>
        <polygon className={`fill-fg ${ear}`} points="17,29 20,3 28,26" />
        <polygon className={`fill-fg ${ear}`} points="33,25 43,2 42,29" />
        <polygon className="fill-fg" points="13,30 46,27 53,44 47,59 22,61 11,46" />
        <polygon className="fill-accent" points="31,46 53,44 47,59 29,61" />
        <polygon className="fill-bg" points="22.5,36 27,35 26,39.5" />
        <circle className="fill-bg" cx="46" cy="52" r="1.6" />
      </g>
    </svg>
  );
}
