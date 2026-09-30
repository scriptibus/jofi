// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useNavigate } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { useGetMonthlyBudget, useListProviders, useListTaskAssignments } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Button, DonkeyLogo } from "../../ui";
import { PageHeader } from "../pages/PlaceholderPage";
import { BudgetForm } from "./BudgetForm";
import { formatUsd } from "./money";
import { ProviderList } from "./ProviderList";
import { dismissSetupGuide, SETUP_STEPS, type SetupStep } from "./setupGuide";
import { TaskAssignments } from "./TaskAssignments";

interface StepText {
  title: () => string;
  /** What the guide says at this step: plain language, like a person walking you through it. */
  guide: () => string;
}

const STEPS: Record<SetupStep, StepText> = {
  welcome: { title: m.wizard_welcome_title, guide: m.wizard_welcome_guide },
  providers: { title: m.wizard_providers_title, guide: m.wizard_providers_guide },
  tasks: { title: m.wizard_tasks_title, guide: m.wizard_tasks_guide },
  budget: { title: m.wizard_budget_title, guide: m.wizard_budget_guide },
  done: { title: m.wizard_done_title, guide: m.wizard_done_guide },
};

/** The guide's line at each step: the donkey and a speech bubble, a scripted conversation without AI. */
function Guide({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-start gap-3">
      <DonkeyLogo className="size-10 shrink-0" />
      <p className="max-w-prose rounded border border-line bg-sunken px-4 py-3 text-lede">{children}</p>
    </div>
  );
}

/**
 * The first-run setup guide (spec §3.2): a step-by-step conversation that works before any AI exists.
 * Providers (with privacy info, key or base URL, connection test), then per-task models, an optional
 * monthly budget and a summary. Every step saves at once, so skipping or leaving keeps what is done;
 * Settings > AI opens the guide again.
 */
export function SetupWizard({ step }: { step: SetupStep }) {
  const navigate = useNavigate();
  const index = SETUP_STEPS.indexOf(step);
  const go = (next: SetupStep) => navigate({ to: "/setup", search: { step: next } });
  const leave = () => {
    dismissSetupGuide();
    return navigate({ to: "/" });
  };
  const next = SETUP_STEPS[index + 1];
  const previous = SETUP_STEPS[index - 1];

  return (
    <>
      <PageHeader
        title={m.wizard_heading()}
        eyebrow={m.wizard_progress({ step: index + 1, total: SETUP_STEPS.length })}
      />
      <nav aria-label={m.wizard_steps_label()}>
        <ol className="flex flex-wrap gap-2">
          {SETUP_STEPS.map((id, position) => (
            <li
              key={id}
              aria-current={id === step ? "step" : undefined}
              className={
                "rounded border px-3 py-1 font-data text-eyebrow uppercase " +
                (id === step ? "border-fg bg-surface text-fg" : "border-line text-muted")
              }
            >
              {position + 1}. {STEPS[id].title()}
            </li>
          ))}
        </ol>
      </nav>
      <section
        aria-labelledby="wizard-step-heading"
        className="flex flex-col gap-6 rounded border border-line bg-surface p-6 shadow-card"
      >
        <Guide>{STEPS[step].guide()}</Guide>
        <h2 id="wizard-step-heading" className="text-h2">
          {STEPS[step].title()}
        </h2>
        <StepContent step={step} />
        <div className="flex flex-wrap items-center justify-between gap-3 border-line border-t pt-4">
          <Button variant="secondary" onPress={leave}>
            {step === "done" ? m.wizard_finish() : m.wizard_skip()}
          </Button>
          <div className="flex flex-wrap gap-3">
            {previous ? (
              <Button variant="secondary" onPress={() => go(previous)}>
                {m.wizard_back()}
              </Button>
            ) : null}
            {next ? <ContinueButton step={step} onPress={() => go(next)} /> : null}
          </div>
        </div>
      </section>
    </>
  );
}

/** Continue; on the providers step only once a provider exists, since everything after needs one. */
function ContinueButton({ step, onPress }: { step: SetupStep; onPress: () => void }) {
  const providers = useListProviders();
  const blocked = step === "providers" && (providers.data?.length ?? 0) === 0;
  return (
    <Button onPress={onPress} isDisabled={blocked}>
      {step === "welcome" ? m.wizard_start() : m.wizard_continue()}
    </Button>
  );
}

function StepContent({ step }: { step: SetupStep }) {
  switch (step) {
    case "welcome":
      return (
        <ul className="flex max-w-prose list-disc flex-col gap-2 pl-5">
          <li>{m.wizard_welcome_point_providers()}</li>
          <li>{m.wizard_welcome_point_privacy()}</li>
          <li>{m.wizard_welcome_point_later()}</li>
        </ul>
      );
    case "providers":
      return <ProviderList />;
    case "tasks":
      return <TaskAssignments />;
    case "budget":
      return <BudgetForm />;
    case "done":
      return <Summary />;
  }
}

function Summary() {
  const providers = useListProviders().data ?? [];
  const assignments = useListTaskAssignments().data ?? [];
  const budget = useGetMonthlyBudget().data;
  const assigned = assignments.filter((entry) => entry.model).length;
  const rows: [string, string][] = [
    [m.wizard_summary_providers(), providers.map((provider) => provider.displayName).join(", ") || "–"],
    [m.wizard_summary_tasks(), m.wizard_summary_tasks_value({ assigned, total: assignments.length })],
    [
      m.wizard_summary_budget(),
      budget?.capMicros == null ? m.wizard_summary_no_cap() : formatUsd(budget.capMicros),
    ],
  ];
  return (
    <dl className="grid max-w-prose gap-x-6 gap-y-2 sm:grid-cols-2">
      {rows.map(([term, value]) => (
        <div key={term} className="contents">
          <dt className="font-semibold">{term}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}
