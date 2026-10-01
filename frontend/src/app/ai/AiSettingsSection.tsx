// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useNavigate } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { m } from "../../paraglide/messages.js";
import { Button } from "../../ui";
import { BudgetForm } from "./BudgetForm";
import { CostsCard } from "./CostsCard";
import { ProviderList } from "./ProviderList";
import { TaskAssignments } from "./TaskAssignments";

/**
 * Settings > AI: the same providers, task models and budget as the setup guide, as three cards of the
 * settings grid (`className`), plus a way back to the guide.
 */
export function AiSettingsSection({ className }: { className: string }) {
  const navigate = useNavigate();
  return (
    <>
      <Card id="ai-providers-heading" title={m.ai_providers_heading()} className={className}>
        <p className="max-w-prose text-muted">{m.ai_intro()}</p>
        <Button
          variant="secondary"
          className="self-start"
          onPress={() => navigate({ to: "/setup", search: { step: "welcome" } })}
        >
          {m.ai_open_guide()}
        </Button>
        <ProviderList />
      </Card>
      <Card id="ai-tasks-heading" title={m.ai_tasks_heading()} className={className}>
        <TaskAssignments />
      </Card>
      <Card id="ai-budget-heading" title={m.ai_budget_heading()} className={className}>
        <BudgetForm />
      </Card>
      <Card id="ai-costs-heading" title={m.ai_costs_heading()} className={className}>
        <CostsCard />
      </Card>
    </>
  );
}

interface CardProps {
  id: string;
  title: string;
  className: string;
  children: ReactNode;
}

function Card({ id, title, className, children }: CardProps) {
  return (
    // min-w-0: a grid item otherwise grows to its widest table and makes the whole page scroll sideways on a phone.
    <section aria-labelledby={id} className={`${className} min-w-0`}>
      <h2 id={id} className="text-h2">
        {title}
      </h2>
      {children}
    </section>
  );
}
