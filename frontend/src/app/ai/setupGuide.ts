// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { QueryClient } from "@tanstack/react-query";
import { getListProvidersQueryOptions } from "../../api/generated/jofi";

/**
 * Remembers in this browser that the user skipped or finished the setup guide, so it stops opening by
 * itself; Settings > AI opens it again. A preference, not data: it holds no key or setting.
 */
const DISMISSED_KEY = "jofi.setup-guide";

export const SETUP_STEPS = ["welcome", "providers", "tasks", "budget", "done"] as const;
export type SetupStep = (typeof SETUP_STEPS)[number];

export function parseSetupStep(value: unknown): SetupStep {
  return SETUP_STEPS.find((step) => step === value) ?? "welcome";
}

export function isSetupGuideDismissed(): boolean {
  try {
    return window.localStorage.getItem(DISMISSED_KEY) === "dismissed";
  } catch {
    return false;
  }
}

export function dismissSetupGuide(): void {
  try {
    window.localStorage.setItem(DISMISSED_KEY, "dismissed");
  } catch {
    // Storage blocked: the guide opens again next time, which is harmless.
  }
}

/**
 * Whether the dashboard should hand over to the setup guide: no AI provider configured yet and the guide
 * not skipped in this browser (spec §3.2, first run). If the check fails, the dashboard just opens.
 */
export async function shouldOpenSetupGuide(queryClient: QueryClient): Promise<boolean> {
  if (isSetupGuideDismissed()) return false;
  try {
    // Quietly and once: a failed check must neither hold up nor clutter the dashboard.
    const providers = await queryClient.fetchQuery(
      getListProvidersQueryOptions({ query: { retry: false, meta: { errorHandledLocally: true } } }),
    );
    return providers.length === 0;
  } catch {
    return false;
  }
}
