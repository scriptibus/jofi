// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type ReactNode, useRef } from "react";
import { FileTrigger } from "react-aria-components";
import { Button, type ButtonVariant } from "./Button";

export interface FilePickerProps {
  /** The button's content; its text is the accessible name. */
  children: ReactNode;
  /** File extensions or MIME types the browser offers, e.g. `[".zip", "application/zip"]`. */
  acceptedFileTypes?: readonly string[];
  /** Called with the one chosen file. */
  onSelect: (file: File) => void;
  isDisabled?: boolean;
  variant?: ButtonVariant;
  className?: string;
}

/** A button that opens the browser's file chooser for one file (React Aria FileTrigger). */
export function FilePicker({
  children,
  acceptedFileTypes = [],
  onSelect,
  isDisabled = false,
  variant = "secondary",
  className = "",
}: FilePickerProps) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <FileTrigger
      ref={input}
      acceptedFileTypes={acceptedFileTypes}
      onSelect={(files) => {
        const file = files?.item(0);
        // Cleared, so choosing the same file again (e.g. after a failed upload) selects it again.
        if (input.current) input.current.value = "";
        if (file) onSelect(file);
      }}
    >
      <Button variant={variant} isDisabled={isDisabled} className={className}>
        {children}
      </Button>
    </FileTrigger>
  );
}
