// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import {
  type CompanyResponse,
  useCreateCompany,
  useGetCompany,
  useUpdateCompany,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, RefreshIcon } from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import { PageHeader } from "../pages/PlaceholderPage";
import type { ErrorDescription } from "../problems";
import { CompanyForm } from "./CompanyForm";
import { CompanyLoadFailure, FailureMessage } from "./CompanyLoadFailure";
import { type CompanyFormValues, fieldErrorsOf, formValues, toDetailsRequest } from "./company";
import { storeSaved } from "./companyCache";
import { describeCompanyError, isVersionConflict } from "./companyProblems";

const editRoute = getRouteApi("/_app/companies/$companyId/edit");

/** Field errors next to the fields, anything else above the form. */
function useFormErrors() {
  const fieldErrors = useFieldErrors();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const onError = (error: unknown) => {
    const fields = fieldErrorsOf(error);
    if (fields) fieldErrors.set(fields);
    else setFailure(describeCompanyError(error));
  };
  const reset = () => {
    setFailure(null);
    fieldErrors.set({});
  };
  return { fieldErrors, failure, onError, reset };
}

export function NewCompanyPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const errors = useFormErrors();
  const create = useCreateCompany({ mutation: { meta: { errorHandledLocally: true } } });

  const submit = (values: CompanyFormValues) => {
    errors.reset();
    create.mutate(
      { data: toDetailsRequest(values) },
      {
        onSuccess: (company) => {
          storeSaved(queryClient, company);
          void navigate({ to: "/companies/$companyId", params: { companyId: company.id } });
        },
        onError: errors.onError,
      },
    );
  };

  return (
    <>
      <PageHeader title={m.company_new_heading()} />
      <CompanyForm
        initial={formValues()}
        fieldErrors={errors.fieldErrors}
        feedback={<FailureMessage failure={errors.failure} />}
        submitLabel={m.company_create_submit()}
        isPending={create.isPending}
        onSubmit={submit}
        onCancel={() => void navigate({ to: "/companies" })}
      />
    </>
  );
}

export function EditCompanyPage() {
  const { companyId } = editRoute.useParams();
  const company = useGetCompany(companyId, { query: { meta: { errorHandledLocally: true } } });
  // The form edits the version it was opened with: a background refetch must not replace the user's
  // input, and that version goes back as `basedOnVersion`, so a change made elsewhere meanwhile is a 409.
  const [opened, setOpened] = useState<CompanyResponse | undefined>(undefined);
  if (opened === undefined && company.data) setOpened(company.data);

  if (opened === undefined) {
    return company.isError ? (
      <CompanyLoadFailure error={company.error} onRetry={() => void company.refetch()} />
    ) : (
      <p role="status">{m.loading()}</p>
    );
  }
  const reload = async () => {
    const latest = await company.refetch();
    if (latest.data) setOpened(latest.data);
  };
  return <EditCompanyForm key={opened.version} company={opened} onReload={reload} />;
}

function EditCompanyForm({ company, onReload }: { company: CompanyResponse; onReload: () => Promise<void> }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const errors = useFormErrors();
  const [conflict, setConflict] = useState(false);
  const update = useUpdateCompany({ mutation: { meta: { errorHandledLocally: true } } });
  const toDetail = () => void navigate({ to: "/companies/$companyId", params: { companyId: company.id } });

  const submit = (values: CompanyFormValues) => {
    errors.reset();
    setConflict(false);
    update.mutate(
      { id: company.id, data: { details: toDetailsRequest(values), basedOnVersion: company.version } },
      {
        onSuccess: (saved) => {
          storeSaved(queryClient, saved);
          toDetail();
        },
        onError: (error) => (isVersionConflict(error) ? setConflict(true) : errors.onError(error)),
      },
    );
  };

  const feedback = conflict ? (
    <Alert tone="error" title={m.company_conflict_title()}>
      <p>{m.company_conflict_edit()}</p>
      <Button variant="secondary" className="self-start" onPress={() => void onReload()}>
        <RefreshIcon className="size-4" aria-hidden="true" />
        {m.company_conflict_reload()}
      </Button>
    </Alert>
  ) : (
    <FailureMessage failure={errors.failure} />
  );

  return (
    <>
      <PageHeader title={m.company_edit_heading({ name: company.name })} />
      <CompanyForm
        initial={formValues(company)}
        fieldErrors={errors.fieldErrors}
        feedback={feedback}
        submitLabel={m.company_save_submit()}
        isPending={update.isPending}
        onSubmit={submit}
        onCancel={toDetail}
      />
    </>
  );
}
