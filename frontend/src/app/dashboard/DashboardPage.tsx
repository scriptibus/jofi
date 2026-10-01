// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { PageHeader } from "../pages/PlaceholderPage";
import { ActivityWidget } from "./ActivityWidget";
import { AiCostWidget } from "./AiCostWidget";
import { FunnelWidget, PipelineWidget } from "./PipelineWidgets";
import { TasksWidget } from "./TasksWidget";

/**
 * The dashboard (spec §10.1), the start page: a grid of widgets, one per figure, each asking its own endpoint and
 * failing on its own (ADR-0052). One column on phones, two from `md`, three from `xl`.
 */
export function DashboardPage() {
  return (
    <>
      <PageHeader title={m.dashboard_heading()} eyebrow={m.dashboard_eyebrow()} />
      <div className="grid items-start gap-6 md:grid-cols-2 xl:grid-cols-3">
        {/* The countdowns widget (#115) goes here, first in the grid: what is due soonest comes first. */}
        <TasksWidget />
        <PipelineWidget />
        <FunnelWidget />
        <AiCostWidget />
        <ActivityWidget className="md:col-span-2" />
      </div>
    </>
  );
}
