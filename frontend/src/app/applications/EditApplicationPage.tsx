// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { type ApplicationResponse, useGetApplication, useUpdateApplication } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, RefreshIcon } from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { PageHeader } from "../pages/PlaceholderPage";
import type { ErrorDescription } from "../problems";
import { ApplicationForm } from "./ApplicationForm";
import { ApplicationLoadFailure } from "./ApplicationLoadFailure";
import {
  type ApplicationFormValues,
  applicationFieldErrorsOf,
  FIELD_NAMES,
  formValues,
  toDetailsRequest,
} from "./application";
import { storeSavedApplication } from "./applicationCache";
import { describeApplicationError, isApplicationVersionConflict } from "./applicationProblems";

const editRoute = getRouteApi("/_app/applications/$applicationId/edit");

const FORM_FIELDS = new Set(Object.values(FIELD_NAMES));

/**
 * Field errors next to the fields, anything else above the form. A violation of a field the form does not
 * show (a newer server rule) must not vanish, so it also shows above the form.
 */
export function useApplicationFormErrors() {
  const fieldErrors = useFieldErrors();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const onError = (error: unknown) => {
    const fields = applicationFieldErrorsOf(error);
    if (fields === undefined) {
      setFailure(describeApplicationError(error));
      return;
    }
    fieldErrors.set(fields);
    if (Object.keys(fields).some((name) => !FORM_FIELDS.has(name)))
      setFailure({ message: m.application_error_invalid() });
  };
  const reset = () => {
    setFailure(null);
    fieldErrors.set({});
  };
  return { fieldErrors, failure, onError, reset };
}

export function EditApplicationPage() {
  const { applicationId } = editRoute.useParams();
  const application = useGetApplication(applicationId, { query: { meta: { errorHandledLocally: true } } });
  // The form edits the version it was opened with: a background refetch must not replace the user's
  // input, and that version goes back as `basedOnVersion`, so a change made elsewhere meanwhile is a 409.
  const [opened, setOpened] = useState<ApplicationResponse | undefined>(undefined);
  if (opened === undefined && application.data) setOpened(application.data);

  if (opened === undefined) {
    return application.isError ? (
      <ApplicationLoadFailure error={application.error} onRetry={() => void application.refetch()} />
    ) : (
      <p role="status">{m.loading()}</p>
    );
  }
  const reload = async () => {
    const latest = await application.refetch();
    if (latest.data) setOpened(latest.data);
  };
  return <EditApplicationForm key={opened.version} application={opened} onReload={reload} />;
}

interface EditApplicationFormProps {
  application: ApplicationResponse;
  onReload: () => Promise<void>;
}

function EditApplicationForm({ application, onReload }: EditApplicationFormProps) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const errors = useApplicationFormErrors();
  const [conflict, setConflict] = useState(false);
  const update = useUpdateApplication({ mutation: { meta: { errorHandledLocally: true } } });
  const toDetail = () =>
    void navigate({ to: "/applications/$applicationId", params: { applicationId: application.id } });

  const submit = (values: ApplicationFormValues) => {
    errors.reset();
    setConflict(false);
    update.mutate(
      {
        id: application.id,
        data: { details: toDetailsRequest(values), basedOnVersion: application.version },
      },
      {
        onSuccess: (saved) => {
          storeSavedApplication(queryClient, saved);
          toDetail();
        },
        onError: (error) => (isApplicationVersionConflict(error) ? setConflict(true) : errors.onError(error)),
      },
    );
  };

  const feedback = conflict ? (
    <Alert tone="error" title={m.company_conflict_title()}>
      <p>{m.application_conflict_edit()}</p>
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
      <PageHeader title={m.application_edit_heading({ title: application.title })} />
      <ApplicationForm
        initial={formValues(application)}
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
