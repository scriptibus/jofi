// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { type SyntheticEvent, useState } from "react";
import {
  getListProvidersQueryKey,
  type ProviderResponse,
  type ProviderResponseKind,
  useCreateProvider,
  useUpdateProvider,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, Form, SegmentedControl, TextField } from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import type { ErrorDescription } from "../problems";
import { checkBaseUrl, sameOrigin } from "./baseUrl";
import { FailureAlert } from "./FailureAlert";
import { ProviderPrivacyInfo } from "./ProviderPrivacyInfo";
import { describeSetupError, fieldErrorsOf, violationsOf } from "./setupProblems";
import { PROVIDER_KIND_LABELS } from "./tasks";

const KINDS: readonly ProviderResponseKind[] = [
  "ANTHROPIC",
  "OPENAI",
  "GEMINI",
  "MISTRAL",
  "OPENAI_COMPATIBLE",
];

export interface ProviderFormProps {
  /** The provider to change; without one the form adds a new provider. */
  provider?: ProviderResponse;
  onSaved: (provider: ProviderResponse) => void;
  onCancel?: () => void;
}

function baseUrlError(value: string): string | null {
  switch (checkBaseUrl(value).status) {
    case "empty":
      return m.ai_base_url_required();
    case "too-long":
      return m.ai_violation_too_long();
    case "invalid":
      return m.ai_base_url_invalid();
    case "credentials":
      return m.ai_base_url_credentials();
    default:
      return null;
  }
}

/**
 * Adds or changes an AI provider. The key is write-only: it lives in this form's state until the
 * request is sent, is never read back (the server only says whether one is stored) and never reaches
 * browser storage. A base URL that moves to another origin needs the key again (backend rule).
 */
export function ProviderForm({ provider, onSaved, onCancel }: ProviderFormProps) {
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<ProviderResponseKind>(provider?.kind ?? "ANTHROPIC");
  const [displayName, setDisplayName] = useState(provider?.displayName ?? PROVIDER_KIND_LABELS.ANTHROPIC());
  const [nameEdited, setNameEdited] = useState(provider !== undefined);
  const [baseUrl, setBaseUrl] = useState(provider?.baseUrl ?? "");
  const [apiKey, setApiKey] = useState("");
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const fieldErrors = useFieldErrors();
  const options = { mutation: { meta: { errorHandledLocally: true } } };
  const create = useCreateProvider(options);
  const update = useUpdateProvider(options);
  const pending = create.isPending || update.isPending;

  const compatible = kind === "OPENAI_COMPATIBLE";
  const url = checkBaseUrl(baseUrl);
  const keyMustBeReentered =
    provider?.apiKeySet === true &&
    compatible &&
    url.status === "ok" &&
    !sameOrigin(provider.baseUrl, baseUrl);
  const keyRequired = keyMustBeReentered || (provider === undefined && !compatible);

  const chooseKind = (next: ProviderResponseKind) => {
    setKind(next);
    if (!nameEdited) setDisplayName(PROVIDER_KIND_LABELS[next]());
    fieldErrors.set({});
  };

  const onError = (error: unknown) => {
    if (violationsOf(error).length > 0) fieldErrors.set(fieldErrorsOf(error, provider !== undefined));
    else setFailure(describeSetupError(error));
  };

  const onSuccess = async (saved: ProviderResponse) => {
    setApiKey("");
    await queryClient.invalidateQueries({ queryKey: getListProvidersQueryKey() });
    onSaved(saved);
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFailure(null);
    // Checked here, not with each field's `validate`: errors go through `validationErrors`, which editing
    // the field clears at once, so a corrected form always submits (native validity can lag a render).
    const errors = clientErrors();
    fieldErrors.set(errors);
    if (Object.keys(errors).length > 0) return;
    const key = apiKey.trim() === "" ? null : apiKey;
    const url = compatible ? baseUrl.trim() : null;
    if (provider) {
      update.mutate(
        { id: provider.id, data: { displayName, baseUrl: url, apiKey: key } },
        { onSuccess, onError },
      );
    } else {
      create.mutate({ data: { kind, displayName, baseUrl: url, apiKey: key } }, { onSuccess, onError });
    }
  };

  const clientErrors = (): Record<string, string> => {
    const errors: Record<string, string> = {};
    if (displayName.trim() === "") errors.displayName = m.ai_violation_required();
    const urlError = compatible ? baseUrlError(baseUrl) : null;
    if (urlError) errors.baseUrl = urlError;
    if (keyRequired && apiKey.trim() === "")
      errors.apiKey = keyMustBeReentered ? m.ai_key_reenter() : m.ai_key_required();
    return errors;
  };

  const keyDescription = keyMustBeReentered
    ? m.ai_key_reenter()
    : provider?.apiKeySet
      ? m.ai_key_stored()
      : compatible
        ? m.ai_key_optional()
        : m.ai_key_description();

  return (
    <Form onSubmit={submit} validationErrors={fieldErrors.errors} className="flex flex-col gap-4">
      {provider ? null : (
        <>
          <SegmentedControl
            label={m.ai_kind_label()}
            value={kind}
            onChange={chooseKind}
            options={KINDS.map((value) => ({ value, label: PROVIDER_KIND_LABELS[value]() }))}
          />
          <ProviderPrivacyInfo kind={kind} />
        </>
      )}
      <FailureAlert failure={failure} />
      <TextField
        name="displayName"
        label={m.ai_display_name_label()}
        value={displayName}
        onChange={fieldErrors.clearing("displayName", (value: string) => {
          setNameEdited(true);
          setDisplayName(value);
        })}
      />
      {compatible ? (
        <TextField
          name="baseUrl"
          type="url"
          mono
          label={m.ai_base_url_label()}
          description={m.ai_base_url_description()}
          value={baseUrl}
          onChange={fieldErrors.clearing("baseUrl", setBaseUrl)}
        />
      ) : null}
      {url.status === "ok" && url.insecure ? (
        <Alert tone="warning" title={m.ai_base_url_insecure_title()}>
          <p>{m.ai_base_url_insecure()}</p>
        </Alert>
      ) : null}
      <TextField
        name="apiKey"
        type="password"
        mono
        autoComplete="off"
        label={m.ai_key_label()}
        description={keyDescription}
        value={apiKey}
        onChange={fieldErrors.clearing("apiKey", setApiKey)}
      />
      <div className="flex flex-wrap justify-end gap-3">
        {onCancel ? (
          <Button variant="secondary" onPress={onCancel} isDisabled={pending}>
            {m.ai_cancel()}
          </Button>
        ) : null}
        <Button type="submit" isDisabled={pending}>
          {provider ? m.ai_provider_save() : m.ai_provider_add()}
        </Button>
      </div>
    </Form>
  );
}
