// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { Button as AriaButton } from "react-aria-components";
import { CloseIcon, ErrorIcon, type Icon, InfoIcon, SuccessIcon, WarningIcon } from "./icons";

export type AlertTone = "error" | "info" | "success" | "warning";

export interface AlertProps {
  tone: AlertTone;
  /** Short first line in bold; the icon and this text carry the tone, never colour alone. */
  title?: string;
  children?: ReactNode;
  /** Shows a close button with this accessible label. */
  dismissLabel?: string;
  onDismiss?: () => void;
  className?: string;
}

const tones: Record<AlertTone, { icon: Icon; frame: string; iconColour: string }> = {
  error: { icon: ErrorIcon, frame: "border-bad", iconColour: "text-bad" },
  info: { icon: InfoIcon, frame: "border-line", iconColour: "text-muted" },
  success: { icon: SuccessIcon, frame: "border-good", iconColour: "text-good" },
  warning: { icon: WarningIcon, frame: "border-warn", iconColour: "text-warn" },
};

const roles: Record<AlertTone, "alert" | "status" | "note"> = {
  error: "alert",
  info: "status",
  success: "status",
  // A standing warning next to an action (not an event), so no live region: read in place.
  warning: "note",
};

/**
 * A message box. Errors use `role="alert"` (announced at once), info and success `role="status"`
 * (announced politely); render those only when there is something to say, so the announcement fires.
 * A warning is a standing `note` next to what it warns about.
 */
export function Alert({ tone, title, children, dismissLabel, onDismiss, className }: AlertProps) {
  const { icon: ToneIcon, frame, iconColour } = tones[tone];
  return (
    <div
      role={roles[tone]}
      className={[
        "flex items-start gap-3 rounded border border-l-4 bg-surface p-4 text-body shadow-card",
        frame,
        className,
      ]
        .filter(Boolean)
        .join(" ")}
    >
      <ToneIcon className={`mt-0.5 size-5 shrink-0 ${iconColour}`} aria-hidden="true" />
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        {title ? <p className="font-semibold">{title}</p> : null}
        {children}
      </div>
      {onDismiss && dismissLabel ? (
        <AriaButton
          aria-label={dismissLabel}
          onPress={onDismiss}
          className={
            "-m-1 rounded p-1 text-muted data-hovered:text-fg " +
            "data-focus-visible:outline-2 data-focus-visible:outline-offset-2 data-focus-visible:outline-accent"
          }
        >
          <CloseIcon className="size-4" aria-hidden="true" />
        </AriaButton>
      ) : null}
    </div>
  );
}
