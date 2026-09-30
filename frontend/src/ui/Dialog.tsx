// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import { Dialog as AriaDialog, Heading, Modal, ModalOverlay } from "react-aria-components";

export interface DialogProps {
  isOpen: boolean;
  title: string;
  /** The content, usually a form with its own buttons (Cancel first). */
  children: ReactNode;
  /** Escape closes the dialog; clicking outside does nothing, so a stray tap never loses input. */
  onClose: () => void;
}

/**
 * A modal `dialog` for a short task, such as asking for the password before a sensitive action.
 * Focus moves into it and stays there; it returns to the trigger when the dialog closes. For a yes/no
 * question about a destructive action use `ConfirmDialog`.
 */
export function Dialog({ isOpen, title, children, onClose }: DialogProps) {
  return (
    <ModalOverlay
      isOpen={isOpen}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-bg/80 p-4"
    >
      <Modal className="w-full max-w-md rounded border border-line bg-surface p-6 text-fg shadow-card">
        <AriaDialog className="flex flex-col gap-4 outline-none">
          <Heading slot="title" className="text-h3">
            {title}
          </Heading>
          {children}
        </AriaDialog>
      </Modal>
    </ModalOverlay>
  );
}
