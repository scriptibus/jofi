// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  deleteProvider,
  getListProviderModelsQueryKey,
  getListProvidersQueryKey,
  getListTaskAssignmentsQueryKey,
  type ProviderResponse,
  useListProviderModels,
  useListProviders,
  useRefreshProviderModels,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AddIcon, Alert, Button, DeleteIcon, EditIcon, KeyIcon, RefreshIcon } from "../../ui";
import { formatCount } from "../backup/files";
import type { ErrorDescription } from "../problems";
import { useConfirmation } from "../useConfirmation";
import { FailureAlert } from "./FailureAlert";
import { ProviderForm } from "./ProviderForm";
import { describeSetupError } from "./setupProblems";
import { PROVIDER_KIND_LABELS } from "./tasks";

/** Operation of the provider delete's server-side confirmation (backend `ProviderId.DELETE_OPERATION`). */
export const DELETE_PROVIDER_OPERATION = "setup.delete-provider";

/** The configured providers, each with its connection test, edit and delete, plus "add a provider". */
export function ProviderList() {
  const providers = useListProviders();
  const [adding, setAdding] = useState(false);
  const list = providers.data ?? [];
  const showForm = adding || (providers.isSuccess && list.length === 0);

  return (
    <div className="flex flex-col gap-4">
      {list.length > 0 ? (
        <ul className="flex flex-col gap-4">
          {list.map((provider) => (
            <li key={provider.id}>
              <ProviderCard provider={provider} />
            </li>
          ))}
        </ul>
      ) : null}
      {showForm ? (
        <section
          aria-labelledby="provider-add-heading"
          className="flex flex-col gap-4 rounded border border-line p-4"
        >
          <h3 id="provider-add-heading" className="text-h3">
            {m.ai_provider_add_heading()}
          </h3>
          <ProviderForm
            onSaved={() => setAdding(false)}
            {...(list.length > 0 ? { onCancel: () => setAdding(false) } : {})}
          />
        </section>
      ) : providers.isSuccess ? (
        <Button variant="secondary" className="self-start" onPress={() => setAdding(true)}>
          <AddIcon className="size-4" aria-hidden="true" />
          {m.ai_provider_add_another()}
        </Button>
      ) : null}
    </div>
  );
}

function ProviderCard({ provider }: { provider: ProviderResponse }) {
  const queryClient = useQueryClient();
  const models = useListProviderModels(provider.id);
  const [editing, setEditing] = useState(false);
  const [tested, setTested] = useState<number | null>(null);
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const { confirmed, dialog } = useConfirmation();
  const local = { meta: { errorHandledLocally: true } };
  const refresh = useRefreshProviderModels({ mutation: local });
  const remove = useMutation({
    ...local,
    mutationFn: () =>
      confirmed((options) => deleteProvider(provider.id, options), {
        expect: { operation: DELETE_PROVIDER_OPERATION, targets: [provider.id] },
        describe: (effect) => m.ai_provider_delete_confirm({ name: effect.name }),
        title: m.ai_provider_delete_title(),
        confirmLabel: m.ai_provider_delete_action(),
      }),
  });

  const test = () => {
    setFailure(null);
    setTested(null);
    refresh.mutate(
      { id: provider.id },
      {
        onSuccess: async (listed) => {
          setTested(listed.length);
          queryClient.setQueryData(getListProviderModelsQueryKey(provider.id), listed);
          await queryClient.invalidateQueries({ queryKey: getListTaskAssignmentsQueryKey() });
        },
        onError: (error) => setFailure(describeSetupError(error)),
      },
    );
  };

  const onDelete = () => {
    setFailure(null);
    remove.mutate(undefined, {
      onSuccess: async (outcome) => {
        if (outcome.status === "done")
          await queryClient.invalidateQueries({ queryKey: getListProvidersQueryKey() });
      },
      onError: (error) => setFailure(describeSetupError(error)),
    });
  };

  const headingId = `provider-${provider.id}`;
  return (
    <article aria-labelledby={headingId} className="flex flex-col gap-3 rounded border border-line p-4">
      <div className="flex flex-col gap-1">
        <h3 id={headingId} className="text-h3">
          {provider.displayName}
        </h3>
        <p className="text-muted">
          {PROVIDER_KIND_LABELS[provider.kind]()}
          {provider.baseUrl ? (
            <>
              {" · "}
              <span className="break-all font-data">{provider.baseUrl}</span>
            </>
          ) : null}
        </p>
        <p className="flex items-center gap-2">
          <KeyIcon className="size-4 text-muted" aria-hidden="true" />
          {provider.apiKeySet ? m.ai_key_set() : m.ai_key_none()}
          {models.data ? ` · ${m.ai_models_count({ count: formatCount(models.data.length) })}` : null}
        </p>
      </div>
      {tested !== null ? (
        <Alert tone="success">{m.ai_test_success({ count: formatCount(tested) })}</Alert>
      ) : null}
      <FailureAlert failure={failure} />
      {editing ? (
        <ProviderForm
          provider={provider}
          onSaved={() => setEditing(false)}
          onCancel={() => setEditing(false)}
        />
      ) : (
        <div className="flex flex-wrap gap-3">
          <Button variant="secondary" onPress={test} isDisabled={refresh.isPending}>
            <RefreshIcon className="size-4" aria-hidden="true" />
            {refresh.isPending ? m.ai_test_pending() : m.ai_test()}
          </Button>
          <Button variant="secondary" onPress={() => setEditing(true)}>
            <EditIcon className="size-4" aria-hidden="true" />
            {m.ai_provider_edit()}
          </Button>
          <Button variant="secondary" onPress={onDelete} isDisabled={remove.isPending}>
            <DeleteIcon className="size-4" aria-hidden="true" />
            {m.ai_provider_delete()}
          </Button>
        </div>
      )}
      {dialog}
    </article>
  );
}
