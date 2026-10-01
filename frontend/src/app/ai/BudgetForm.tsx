// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { type SyntheticEvent, useState } from "react";
import {
  getGetCostSummaryQueryKey,
  getGetMonthlyBudgetQueryKey,
  type MonthlyBudgetResponse,
  useGetMonthlyBudget,
  useSetMonthlyBudget,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, Form, NumberField } from "../../ui";
import { formatDateTime } from "../backup/files";
import type { ErrorDescription } from "../problems";
import { FailureAlert } from "./FailureAlert";
import { formatUsd, microsToUsd, usdToMicros } from "./money";
import { describeSetupError, fieldErrorsOf } from "./setupProblems";

/** The server's limit: at most one million USD a month (backend `MonthlyBudget`). */
const MAX_CAP_USD = 1_000_000;
/** One cent: the field shows cents, so a smaller cap could not be seen. */
const MIN_CAP_USD = 0.01;

function spentLine(budget: MonthlyBudgetResponse): string {
  const spent = formatUsd(budget.spentMicros);
  if (budget.capMicros == null) return m.ai_budget_spent_no_cap({ spent });
  return m.ai_budget_spent({
    spent,
    cap: formatUsd(budget.capMicros),
    remaining: formatUsd(budget.remainingMicros ?? 0),
  });
}

/**
 * The optional monthly budget cap (spec §3.2): when reached, non-essential AI jobs pause until the next
 * month. Amounts travel as integer USD micros; the field reads and writes dollars in the user's locale.
 * Removing the cap is a `PUT` with `capMicros: null`, never a request without it.
 */
export function BudgetForm() {
  const queryClient = useQueryClient();
  const budget = useGetMonthlyBudget();
  const [cap, setCap] = useState<number | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const save = useSetMonthlyBudget({ mutation: { meta: { errorHandledLocally: true } } });
  const current = budget.data?.capMicros ?? null;
  const shown = cap ?? (current === null ? Number.NaN : microsToUsd(current));

  const put = (capMicros: number | null) => {
    setFailure(null);
    setSaved(null);
    setFieldErrors({});
    save.mutate(
      { data: { capMicros } },
      {
        onSuccess: (result) => {
          queryClient.setQueryData(getGetMonthlyBudgetQueryKey(), result);
          // The Costs card and the dashboard read the cap from the cost summary: all its month variants.
          void queryClient.invalidateQueries({ queryKey: getGetCostSummaryQueryKey() });
          setCap(null);
          setSaved(
            result.capMicros == null
              ? m.ai_budget_removed()
              : m.ai_budget_saved({ cap: formatUsd(result.capMicros) }),
          );
        },
        onError: (error) => {
          const errors = fieldErrorsOf(error);
          if (Object.keys(errors).length > 0) setFieldErrors(errors);
          else setFailure(describeSetupError(error));
        },
      },
    );
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!Number.isNaN(shown)) put(usdToMicros(shown));
  };

  return (
    <div className="flex flex-col gap-4">
      {budget.data ? <p>{spentLine(budget.data)}</p> : null}
      {budget.data?.state === "REACHED" ? (
        <Alert tone="warning" title={m.ai_budget_reached_title()}>
          <p>{m.ai_budget_reached({ until: formatDateTime(budget.data.pausedUntil ?? "") })}</p>
        </Alert>
      ) : null}
      {saved ? <Alert tone="success">{saved}</Alert> : null}
      <FailureAlert failure={failure} />
      <Form onSubmit={submit} validationErrors={fieldErrors} className="flex max-w-md flex-col gap-4">
        <NumberField
          name="capMicros"
          label={m.ai_budget_label()}
          description={m.ai_budget_description()}
          value={shown}
          onChange={(value) => {
            setFieldErrors({});
            setCap(value);
          }}
          minValue={MIN_CAP_USD}
          maxValue={MAX_CAP_USD}
          formatOptions={{ style: "currency", currency: "USD" }}
          isRequired
        />
        <div className="flex flex-wrap gap-3">
          <Button type="submit" isDisabled={save.isPending}>
            {m.ai_budget_save()}
          </Button>
          {current !== null ? (
            <Button variant="secondary" onPress={() => put(null)} isDisabled={save.isPending}>
              {m.ai_budget_remove()}
            </Button>
          ) : null}
        </div>
      </Form>
    </div>
  );
}
