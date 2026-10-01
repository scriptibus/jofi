// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import {
  type ContactResponse,
  useCreateContact,
  useGetContact,
  useUpdateContact,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, RefreshIcon } from "../../ui";
import { useLinkCreatedContact } from "../applications/applicationContacts";
import { useFieldErrors } from "../auth/useFieldErrors";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { PageHeader } from "../pages/PlaceholderPage";
import type { ErrorDescription } from "../problems";
import { ContactForm } from "./ContactForm";
import { ContactLoadFailure } from "./ContactLoadFailure";
import { type ContactFormValues, contactFieldErrorsOf, formValues, toDetailsRequest } from "./contact";
import { storeSavedContact } from "./contactCache";
import { describeContactError, isContactVersionConflict } from "./contactProblems";

const newRoute = getRouteApi("/_app/contacts/new");
const editRoute = getRouteApi("/_app/contacts/$contactId/edit");

/** Field errors next to the fields, anything else above the form. */
function useFormErrors() {
  const fieldErrors = useFieldErrors();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const onError = (error: unknown) => {
    const fields = contactFieldErrorsOf(error);
    if (fields) fieldErrors.set(fields);
    else setFailure(describeContactError(error));
  };
  const reset = () => {
    setFailure(null);
    fieldErrors.set({});
  };
  return { fieldErrors, failure, onError, reset };
}

/**
 * A new contact; `?company=` preselects its company (from a company's page). From an application's
 * Contacts tab, `?application=` links the new contact to it and returns there (also on cancel).
 */
export function NewContactPage() {
  const { company, application } = newRoute.useSearch();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const errors = useFormErrors();
  const create = useCreateContact({ mutation: { meta: { errorHandledLocally: true } } });
  const linkToApplication = useLinkCreatedContact(application ?? "");
  /** Back to the application's Contacts tab when opened from there, else to the contact (or the list). */
  const back = (contactId?: string) => {
    if (application) {
      const params = { applicationId: application };
      return navigate({ to: "/applications/$applicationId", params, search: { tab: "contacts" } });
    }
    if (contactId) return navigate({ to: "/contacts/$contactId", params: { contactId } });
    return navigate({ to: "/contacts" });
  };

  const submit = (values: ContactFormValues) => {
    errors.reset();
    create.mutate(
      { data: toDetailsRequest(values) },
      {
        onSuccess: (contact) => {
          storeSavedContact(queryClient, contact);
          // The contact is saved either way; a failed link shows as a notice on the application's page.
          if (application) linkToApplication.mutate(contact.id, { onSettled: () => void back() });
          else void back(contact.id);
        },
        onError: errors.onError,
      },
    );
  };

  return (
    <>
      <PageHeader title={m.contact_new_heading()} />
      {application ? <p className="text-muted">{m.contact_new_for_application()}</p> : null}
      <ContactForm
        initial={formValues(undefined, company)}
        fieldErrors={errors.fieldErrors}
        feedback={<FailureMessage failure={errors.failure} />}
        submitLabel={m.contact_create_submit()}
        isPending={create.isPending || linkToApplication.isPending}
        onSubmit={submit}
        onCancel={() => void back()}
      />
    </>
  );
}

export function EditContactPage() {
  const { contactId } = editRoute.useParams();
  const contact = useGetContact(contactId, { query: { meta: { errorHandledLocally: true } } });
  // The form edits the version it was opened with: a background refetch must not replace the user's
  // input, and that version goes back as `basedOnVersion`, so a change made elsewhere meanwhile is a 409.
  const [opened, setOpened] = useState<ContactResponse | undefined>(undefined);
  if (opened === undefined && contact.data) setOpened(contact.data);

  if (opened === undefined) {
    return contact.isError ? (
      <ContactLoadFailure error={contact.error} onRetry={() => void contact.refetch()} />
    ) : (
      <p role="status">{m.loading()}</p>
    );
  }
  const reload = async () => {
    const latest = await contact.refetch();
    if (latest.data) setOpened(latest.data);
  };
  return <EditContactForm key={opened.version} contact={opened} onReload={reload} />;
}

function EditContactForm({ contact, onReload }: { contact: ContactResponse; onReload: () => Promise<void> }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const errors = useFormErrors();
  const [conflict, setConflict] = useState(false);
  const update = useUpdateContact({ mutation: { meta: { errorHandledLocally: true } } });
  const toDetail = () => void navigate({ to: "/contacts/$contactId", params: { contactId: contact.id } });

  const submit = (values: ContactFormValues) => {
    errors.reset();
    setConflict(false);
    update.mutate(
      { id: contact.id, data: { details: toDetailsRequest(values), basedOnVersion: contact.version } },
      {
        onSuccess: (saved) => {
          storeSavedContact(queryClient, saved);
          toDetail();
        },
        onError: (error) => (isContactVersionConflict(error) ? setConflict(true) : errors.onError(error)),
      },
    );
  };

  const feedback = conflict ? (
    <Alert tone="error" title={m.company_conflict_title()}>
      <p>{m.contact_conflict_edit()}</p>
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
      <PageHeader title={m.contact_edit_heading({ name: contact.name })} />
      <ContactForm
        initial={formValues(contact)}
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
