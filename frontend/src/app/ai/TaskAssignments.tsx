// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueries, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  getListProviderModelsQueryOptions,
  getListTaskAssignmentsQueryKey,
  type TaskAssignmentResponse,
  useAssignTaskModel,
  useListProviders,
  useListTaskAssignments,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import { Alert, Button, Select, type SelectGroup } from "../../ui";
import type { ErrorDescription } from "../problems";
import { FailureAlert } from "./FailureAlert";
import { describeSetupError } from "./setupProblems";
import {
  type AiTask,
  type Candidate,
  describeCapabilities,
  optionId,
  parseOptionId,
  suggestModel,
  TASK_GROUPS,
  TASK_LABELS,
} from "./tasks";

/** Every model of every provider, as the last connection test listed them. */
function useCandidates(): { candidates: Candidate[]; groups: SelectGroup[] } {
  const providers = useListProviders().data ?? [];
  const models = useQueries({
    queries: providers.map((provider) => getListProviderModelsQueryOptions(provider.id)),
  });
  const groups = providers.map((provider, index) => ({
    id: provider.id,
    title: provider.displayName,
    options: (models[index]?.data ?? []).map((model) => ({
      id: optionId(provider.id, model.model),
      label: model.model,
    })),
  }));
  const candidates = providers.flatMap((provider, index) =>
    (models[index]?.data ?? []).map((model) => ({ providerId: provider.id, model })),
  );
  return { candidates, groups: groups.filter((group) => group.options.length > 0) };
}

/**
 * Per-task model selection (spec §3.2) in the groups cheap, strong, search and voice. A model that lacks
 * what a task needs is allowed, with a warning naming what is missing; the server decides (`missing`).
 */
export function TaskAssignments() {
  const queryClient = useQueryClient();
  const assignments = useListTaskAssignments();
  const { candidates, groups } = useCandidates();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const assign = useAssignTaskModel({ mutation: { meta: { errorHandledLocally: true } } });
  const byTask = new Map((assignments.data ?? []).map((entry) => [entry.task, entry]));

  const choose = async (task: AiTask, providerId: string, model: string) => {
    setFailure(null);
    try {
      await assign.mutateAsync({ task, data: { providerId, model } });
    } catch (error) {
      setFailure(describeSetupError(error));
    }
    await queryClient.invalidateQueries({ queryKey: getListTaskAssignmentsQueryKey() });
  };

  const suggestions = (assignments.data ?? []).flatMap((entry) => {
    const suggestion = entry.model ? undefined : suggestModel(entry.task, entry.needs, candidates);
    return suggestion ? [{ task: entry.task, suggestion }] : [];
  });
  const applySuggestions = async () => {
    for (const { task, suggestion } of suggestions)
      await choose(task, suggestion.providerId, suggestion.model.model);
  };

  if (groups.length === 0) return <p className="text-muted">{m.ai_tasks_no_models()}</p>;
  return (
    <div className="flex flex-col gap-6">
      <FailureAlert failure={failure} />
      {suggestions.length > 0 ? (
        <Button
          variant="secondary"
          className="self-start"
          onPress={applySuggestions}
          isDisabled={assign.isPending}
        >
          {m.ai_tasks_suggest({ count: suggestions.length })}
        </Button>
      ) : null}
      {TASK_GROUPS.map((group) => (
        <section key={group.id} aria-labelledby={`task-group-${group.id}`} className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <h3 id={`task-group-${group.id}`} className="text-h3">
              {group.title()}
            </h3>
            <p className="text-muted">{group.description()}</p>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            {group.tasks.map((task) => (
              <TaskChoice
                key={task}
                task={task}
                assignment={byTask.get(task)}
                groups={groups}
                isDisabled={assign.isPending}
                onChoose={(providerId, model) => choose(task, providerId, model)}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

interface TaskChoiceProps {
  task: AiTask;
  assignment: TaskAssignmentResponse | undefined;
  groups: SelectGroup[];
  isDisabled: boolean;
  onChoose: (providerId: string, model: string) => void;
}

function TaskChoice({ task, assignment, groups, isDisabled, onChoose }: TaskChoiceProps) {
  const current =
    assignment?.providerId && assignment.model ? optionId(assignment.providerId, assignment.model) : null;
  const missing = assignment?.model ? describeCapabilities(assignment.missing) : [];
  return (
    <div className="flex flex-col gap-2">
      <Select
        label={TASK_LABELS[task]()}
        placeholder={m.ai_tasks_choose()}
        groups={groups}
        value={current}
        isDisabled={isDisabled}
        onChange={(id) => {
          const choice = parseOptionId(id);
          if (choice && id !== current) onChoose(choice.providerId, choice.model);
        }}
      />
      {missing.length > 0 ? (
        <Alert tone="warning" title={m.ai_tasks_warning_title()}>
          <p>{m.ai_tasks_warning({ capabilities: new Intl.ListFormat(getLocale()).format(missing) })}</p>
        </Alert>
      ) : null}
    </div>
  );
}
