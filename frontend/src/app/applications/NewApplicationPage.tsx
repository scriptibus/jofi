// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { useCreateApplication } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { PageHeader } from "../pages/PlaceholderPage";
import { ApplicationForm } from "./ApplicationForm";
import { type ApplicationFormValues, FIELD_NAMES, formValues, toDetailsRequest } from "./application";
import { storeCreatedApplication } from "./applicationCache";
import { useApplicationFormErrors } from "./EditApplicationPage";

const newRoute = getRouteApi("/_app/applications/new");

/**
 * A new application (spec §6.1) with the edit form's groups; `?company=` preselects its company (from a
 * company's page). Status starts at "discovered" on the server; it changes on the detail page.
 */
export function NewApplicationPage() {
  const { company } = newRoute.useSearch();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const errors = useApplicationFormErrors();
  const create = useCreateApplication({ mutation: { meta: { errorHandledLocally: true } } });

  const submit = (values: ApplicationFormValues) => {
    errors.reset();
    // A Select has no native "required", and an empty id is no UUID the server could name in a violation.
    if (values.companyId === "") {
      errors.fieldErrors.set({ [FIELD_NAMES.companyId]: m.application_violation_company_required() });
      return;
    }
    create.mutate(
      { data: toDetailsRequest(values) },
      {
        onSuccess: (application) => {
          storeCreatedApplication(queryClient, application);
          void navigate({ to: "/applications/$applicationId", params: { applicationId: application.id } });
        },
        onError: errors.onError,
      },
    );
  };

  const cancel = () =>
    void (company
      ? navigate({ to: "/companies/$companyId", params: { companyId: company } })
      : navigate({ to: "/applications" }));

  return (
    <>
      <PageHeader title={m.application_new_heading()} />
      <ApplicationForm
        initial={formValues(undefined, company)}
        fieldErrors={errors.fieldErrors}
        feedback={<FailureMessage failure={errors.failure} />}
        submitLabel={m.application_create_submit()}
        isPending={create.isPending}
        onSubmit={submit}
        onCancel={cancel}
      />
    </>
  );
}
