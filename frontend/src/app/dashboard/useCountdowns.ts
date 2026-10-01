// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import {
  createCountdown,
  type DashboardCountdownResponse,
  deleteCountdown,
  getListCountdownsQueryKey,
  getListDashboardCountdownsQueryKey,
  useListDashboardCountdowns,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { todayIn } from "../tasks/task";
import { useConfirmation } from "../useConfirmation";
import { DELETE_OPERATION } from "./countdowns";
import { widgetQuery } from "./Widget";

/** How often the widget looks whether the viewer's day has changed. */
const DAY_CHECK_INTERVAL_MS = 60_000;

/** Today on the viewer's calendar (ISO date); it changes at the viewer's midnight while the page stays open. */
export function useToday(zone: string): string {
  const [today, setToday] = useState(() => todayIn(zone));
  useEffect(() => {
    setToday(todayIn(zone));
    const timer = setInterval(() => setToday(todayIn(zone)), DAY_CHECK_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [zone]);
  return today;
}

/**
 * The dashboard countdowns (spec §10.1) in the viewer's zone, soonest first, and the viewer's today. When the day
 * changes they load again, because the server leaves out deadlines that have passed.
 */
export function useDashboardCountdowns(viewerZone: string) {
  const list = useListDashboardCountdowns({ timeZone: viewerZone }, { query: widgetQuery });
  const today = useToday(viewerZone);
  const shownFor = useRef(today);
  const { refetch } = list;
  useEffect(() => {
    if (shownFor.current === today) return;
    shownFor.current = today;
    void refetch();
  }, [today, refetch]);
  return { list, today };
}

/** Both lists that show custom countdowns load again after a change. */
function useReloadCountdowns() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: getListDashboardCountdownsQueryKey() });
    void queryClient.invalidateQueries({ queryKey: getListCountdownsQueryKey() });
  };
}

/** Adds a custom countdown; the form shows its own errors. */
export function useAddCountdown() {
  const reload = useReloadCountdowns();
  return useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: (details: { title: string; targetDate: string }) => createCountdown(details),
    onSuccess: reload,
  });
}

export interface DeleteCallbacks {
  /** Told once the countdown is gone (not when the user said no). */
  onDeleted: (countdown: DashboardCountdownResponse) => void;
  onFailure: (error: unknown) => void;
}

/**
 * Deletes a custom countdown with the server's two-step confirmation (ADR-0039). Render `dialog` once. The callbacks
 * sit on the mutation, not the call, so they still run when the row is gone before the answer is handled.
 */
export function useDeleteCountdown({ onDeleted, onFailure }: DeleteCallbacks) {
  const reload = useReloadCountdowns();
  const { confirmed, dialog } = useConfirmation();
  const mutation = useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: (countdown: DashboardCountdownResponse) =>
      confirmed((options) => deleteCountdown(countdown.subjectId, options), {
        expect: { operation: DELETE_OPERATION, targets: [countdown.subjectId] },
        describe: (effect) => m.countdown_delete_confirm({ title: effect.name }),
        title: m.countdown_delete_title(),
        confirmLabel: m.countdown_delete_action(),
      }),
    onSuccess: (outcome, countdown) => {
      if (outcome.status !== "done") return;
      onDeleted(countdown);
      reload();
    },
    // Gone or changed elsewhere meanwhile (404, 412): show what is there now, not a row that fails again.
    onError: (error) => {
      reload();
      onFailure(error);
    },
  });
  return { mutation, dialog };
}
