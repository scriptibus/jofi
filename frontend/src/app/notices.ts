// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useSyncExternalStore } from "react";
import type { ErrorDescription } from "./problems";

/** A failed request nobody handled locally, shown by `ProblemNotices` until dismissed. */
export interface Notice extends ErrorDescription {
  id: number;
}

/** Most notices a user sees at once; older ones give way (a burst of failures is one story). */
const MAX_NOTICES = 3;

/**
 * The global list of problem notices, filled by the QueryClient's error hooks. A plain external
 * store, so the query client (outside React) can publish and the shell can subscribe.
 */
export function createNoticeStore() {
  let notices: readonly Notice[] = [];
  let nextId = 1;
  const listeners = new Set<() => void>();
  const emit = () => {
    for (const listener of listeners) listener();
  };

  return {
    /** Adds a notice unless the same message is already shown. */
    publish(description: ErrorDescription): void {
      if (notices.some((notice) => notice.message === description.message)) return;
      notices = [...notices, { ...description, id: nextId++ }].slice(-MAX_NOTICES);
      emit();
    },
    dismiss(id: number): void {
      notices = notices.filter((notice) => notice.id !== id);
      emit();
    },
    clear(): void {
      if (notices.length === 0) return;
      notices = [];
      emit();
    },
    snapshot(): readonly Notice[] {
      return notices;
    },
    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export type NoticeStore = ReturnType<typeof createNoticeStore>;

export function useNotices(store: NoticeStore): readonly Notice[] {
  return useSyncExternalStore(store.subscribe, store.snapshot);
}
