// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { type ReactNode, useState } from "react";
import type { ConfirmationEffect } from "../../api/confirmation";
import { type ContactResponse, deleteContact, useGetCompany, useGetContact } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { BackIcon, Button, DeleteIcon, EditIcon, Markdown, TextLink } from "../../ui";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { ContactApplications, sectionCard } from "../companies/RelatedRecords";
import { PageHeader } from "../pages/PlaceholderPage";
import type { ErrorDescription } from "../problems";
import { useConfirmation } from "../useConfirmation";
import { ChannelList } from "./ChannelList";
import { ContactLoadFailure } from "./ContactLoadFailure";
import { DELETE_OPERATION } from "./contact";
import { forgetDeletedContact } from "./contactCache";
import { describeContactError } from "./contactProblems";

const route = getRouteApi("/_app/contacts/$contactId");

/** The delete question, from the server's effect: the contact, and how many applications lose the link. */
export function describeContactDelete(effect: ConfirmationEffect): string {
  const applications = effect.counts.applications ?? 0;
  return applications === 0
    ? m.contact_delete_confirm_alone({ name: effect.name })
    : m.contact_delete_confirm_linked({ name: effect.name, applications });
}

export function ContactDetailPage() {
  const { contactId } = route.useParams();
  const contact = useGetContact(contactId, { query: { meta: { errorHandledLocally: true } } });
  if (contact.data) return <ContactDetail contact={contact.data} />;
  if (contact.isError)
    return <ContactLoadFailure error={contact.error} onRetry={() => void contact.refetch()} />;
  return <p role="status">{m.loading()}</p>;
}

function ContactDetail({ contact }: { contact: ContactResponse }) {
  return (
    <>
      <TextLink to="/contacts" className="inline-flex items-center gap-2 self-start">
        <BackIcon className="size-4" aria-hidden="true" />
        {m.contact_back_to_list()}
      </TextLink>
      <PageHeader title={contact.name} eyebrow={m.contact_eyebrow()} />
      <div className="flex flex-wrap gap-3">
        <TextLink
          to="/contacts/$contactId/edit"
          params={{ contactId: contact.id }}
          className="inline-flex items-center gap-2 self-center"
        >
          <EditIcon className="size-4" aria-hidden="true" />
          {m.company_edit()}
        </TextLink>
        <DeleteContact contact={contact} />
      </div>
      <div className="grid gap-6 lg:grid-cols-2">
        <ContactFacts contact={contact} />
        <section aria-labelledby="contact-channels-heading" className={sectionCard}>
          <h2 id="contact-channels-heading" className="text-h2">
            {m.contact_field_channels()}
          </h2>
          <ChannelList channels={contact.channels} />
        </section>
        <section aria-labelledby="contact-notes-heading" className={sectionCard}>
          <h2 id="contact-notes-heading" className="text-h2">
            {m.contact_field_notes()}
          </h2>
          {contact.relationshipNotes ? (
            <Markdown>{contact.relationshipNotes}</Markdown>
          ) : (
            <p className="text-muted">{m.contact_notes_empty()}</p>
          )}
        </section>
        <ContactApplications contactId={contact.id} />
      </div>
    </>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="font-data text-eyebrow text-muted uppercase">{label}</dt>
      <dd className="break-words">{children}</dd>
    </div>
  );
}

function ContactFacts({ contact }: { contact: ContactResponse }) {
  const none = <span className="text-muted">{m.company_value_none()}</span>;
  return (
    <section aria-labelledby="contact-facts-heading" className={sectionCard}>
      <h2 id="contact-facts-heading" className="text-h2">
        {m.company_facts_heading()}
      </h2>
      <dl className="flex flex-col gap-4">
        <Fact label={m.contact_field_role()}>{contact.role ?? none}</Fact>
        <Fact label={m.contact_field_company()}>
          {contact.companyId ? <CompanyLink companyId={contact.companyId} /> : none}
        </Fact>
      </dl>
    </section>
  );
}

function CompanyLink({ companyId }: { companyId: string }) {
  const company = useGetCompany(companyId, { query: { meta: { errorHandledLocally: true } } });
  if (company.isError) return <span className="text-muted">{m.company_not_found_heading()}</span>;
  return (
    <TextLink to="/companies/$companyId" params={{ companyId }}>
      {company.data?.name ?? m.loading()}
    </TextLink>
  );
}

/** Delete with the server's two-step confirmation (ADR-0039): all personal data goes, links too. */
function DeleteContact({ contact }: { contact: ContactResponse }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { confirmed, dialog } = useConfirmation();
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const remove = useMutation({
    meta: { errorHandledLocally: true },
    mutationFn: () =>
      confirmed((options) => deleteContact(contact.id, options), {
        expect: { operation: DELETE_OPERATION, targets: [contact.id] },
        describe: describeContactDelete,
        title: m.contact_delete_title(),
        confirmLabel: m.contact_delete_action(),
      }),
  });

  const start = () => {
    setFailure(null);
    remove.mutate(undefined, {
      onSuccess: (outcome) => {
        if (outcome.status !== "done") return;
        forgetDeletedContact(queryClient, contact.id);
        void navigate({ to: "/contacts" });
      },
      onError: (error) => setFailure(describeContactError(error)),
    });
  };

  return (
    <>
      <Button variant="secondary" onPress={start} isDisabled={remove.isPending}>
        <DeleteIcon className="size-4" aria-hidden="true" />
        {m.company_delete()}
      </Button>
      {failure ? (
        <div className="basis-full">
          <FailureMessage failure={failure} />
        </div>
      ) : null}
      {dialog}
    </>
  );
}
