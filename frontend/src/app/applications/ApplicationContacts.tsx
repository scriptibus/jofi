// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueries, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  type ApplicationResponse,
  type ContactResponse,
  getGetApplicationQueryKey,
  getGetContactQueryOptions,
  useLinkApplicationContacts,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, LinkIcon, RefreshIcon, TextLink, UnlinkIcon } from "../../ui";
import { FailureMessage } from "../companies/CompanyLoadFailure";
import { fieldErrorsOf } from "../companies/company";
import { sectionCard } from "../companies/RelatedRecords";
import { ChannelList } from "../contacts/ChannelList";
import type { ErrorDescription } from "../problems";
import { storeSavedApplication } from "./applicationCache";
import { MAX_CONTACTS, withContact, withoutContact } from "./applicationContacts";
import { describeApplicationError, isApplicationVersionConflict } from "./applicationProblems";
import { ContactPicker, NewContactLink, useContactFacts } from "./ContactPicker";

/** What the last link or unlink did, for the message under the heading. */
type Outcome =
  | { kind: "linked" | "unlinked"; name: string }
  | { kind: "conflict" }
  | { kind: "failed"; failure: ErrorDescription };

const linkViolationMessages: Readonly<Record<string, () => string>> = {
  NOT_FOUND: m.application_contacts_error_not_found,
  TOO_MANY: m.application_contacts_error_too_many,
};

/** A failed link or unlink in the user's language: a gone contact or too many, else the application's errors. */
function describeLinkError(error: unknown): ErrorDescription {
  const message = fieldErrorsOf(error, linkViolationMessages)?.contactIds;
  return message ? { message } : describeApplicationError(error);
}

/**
 * The Contacts tab (spec §6.3): the linked contacts with their ways to reach them, a picker to link one,
 * a way to create one for this application, and unlink. Each change sends the whole set with the version
 * this page shows (ADR-0041); unlinking deletes nothing, so it needs no confirmation.
 */
export function ApplicationContacts({ application }: { application: ApplicationResponse }) {
  const queryClient = useQueryClient();
  const [picking, setPicking] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const save = useLinkApplicationContacts({ mutation: { meta: { errorHandledLocally: true } } });

  const change = (contactIds: string[], done: Outcome) => {
    setOutcome(null);
    save.mutate(
      { id: application.id, data: { contactIds, basedOnVersion: application.version } },
      {
        onSuccess: (saved) => {
          storeSavedApplication(queryClient, saved);
          setOutcome(done);
        },
        onError: (error) =>
          setOutcome(
            isApplicationVersionConflict(error)
              ? { kind: "conflict" }
              : { kind: "failed", failure: describeLinkError(error) },
          ),
      },
    );
  };
  const link = (contact: ContactResponse) => {
    setPicking(false);
    change(withContact(application.contactIds, contact.id), { kind: "linked", name: contact.name });
  };
  const unlink = (contactId: string, name: string) =>
    change(withoutContact(application.contactIds, contactId), { kind: "unlinked", name });
  const reload = () => {
    setOutcome(null);
    void queryClient.invalidateQueries({ queryKey: getGetApplicationQueryKey(application.id), exact: true });
  };
  const full = application.contactIds.length >= MAX_CONTACTS;

  return (
    <section aria-labelledby="application-contacts-heading" className={sectionCard}>
      <h2 id="application-contacts-heading" className="text-h2">
        {m.application_contacts_heading()}
      </h2>
      <p className="text-muted">{m.application_contacts_unlink_hint()}</p>
      <OutcomeMessage outcome={outcome} onReload={reload} />
      <div className="flex flex-wrap items-center gap-4">
        <Button onPress={() => setPicking(true)} isDisabled={full || save.isPending}>
          <LinkIcon className="size-4" aria-hidden="true" />
          {m.application_contacts_link()}
        </Button>
        <NewContactLink application={application} />
      </div>
      {full ? <p className="text-muted">{m.application_contacts_full({ max: MAX_CONTACTS })}</p> : null}
      <LinkedContacts contactIds={application.contactIds} isPending={save.isPending} onUnlink={unlink} />
      <ContactPicker
        application={application}
        isOpen={picking}
        onClose={() => setPicking(false)}
        onPick={link}
      />
    </section>
  );
}

function OutcomeMessage({ outcome, onReload }: { outcome: Outcome | null; onReload: () => void }) {
  if (outcome?.kind === "conflict") {
    return (
      <Alert tone="error" title={m.company_conflict_title()}>
        <p>{m.application_contacts_conflict()}</p>
        <Button variant="secondary" className="self-start" onPress={onReload}>
          <RefreshIcon className="size-4" aria-hidden="true" />
          {m.company_conflict_reload()}
        </Button>
      </Alert>
    );
  }
  if (outcome?.kind === "failed") return <FailureMessage failure={outcome.failure} />;
  // Always there, so screen readers announce the next change.
  return (
    <p role="status" className="empty:hidden">
      {outcome?.kind === "linked" ? m.application_contacts_linked({ name: outcome.name }) : null}
      {outcome?.kind === "unlinked" ? m.application_contacts_unlinked({ name: outcome.name }) : null}
    </p>
  );
}

interface LinkedContactsProps {
  contactIds: string[];
  isPending: boolean;
  onUnlink: (contactId: string, name: string) => void;
}

/** The linked contacts, one request each (at most 50, and usually already cached from the contact pages). */
function LinkedContacts({ contactIds, isPending, onUnlink }: LinkedContactsProps) {
  const contacts = useQueries({
    queries: contactIds.map((id) =>
      getGetContactQueryOptions(id, { query: { meta: { errorHandledLocally: true } } }),
    ),
  });
  if (contactIds.length === 0) return <p className="text-muted">{m.application_contacts_empty()}</p>;
  return (
    <ul className="grid gap-3 lg:grid-cols-2">
      {contactIds.map((id, position) => {
        const contact = contacts[position];
        return (
          <li key={id}>
            {contact?.data ? (
              <LinkedContact
                contact={contact.data}
                isPending={isPending}
                onUnlink={() => onUnlink(id, contact.data.name)}
              />
            ) : (
              <MissingContact
                isError={contact?.isError ?? false}
                isPending={isPending}
                onUnlink={() => onUnlink(id, m.application_contacts_unknown())}
              />
            )}
          </li>
        );
      })}
    </ul>
  );
}

const card = "flex h-full flex-col gap-3 rounded border border-line bg-bg p-4";

interface LinkedContactProps {
  contact: ContactResponse;
  isPending: boolean;
  onUnlink: () => void;
}

function LinkedContact({ contact, isPending, onUnlink }: LinkedContactProps) {
  const facts = useContactFacts(contact);
  return (
    <article className={card}>
      <h3 className="font-display text-h3">
        <TextLink to="/contacts/$contactId" params={{ contactId: contact.id }}>
          {contact.name}
        </TextLink>
      </h3>
      {facts ? <p className="text-muted">{facts}</p> : null}
      <ChannelList channels={contact.channels} />
      <UnlinkButton name={contact.name} isPending={isPending} onUnlink={onUnlink} />
    </article>
  );
}

/** A linked contact that could not be loaded; it can still be unlinked. */
function MissingContact({
  isError,
  isPending,
  onUnlink,
}: Omit<LinkedContactProps, "contact"> & { isError: boolean }) {
  if (!isError) return <p role="status">{m.loading()}</p>;
  return (
    <article className={card}>
      <p className="text-muted">{m.application_contacts_unavailable()}</p>
      <UnlinkButton name={m.application_contacts_unknown()} isPending={isPending} onUnlink={onUnlink} />
    </article>
  );
}

function UnlinkButton({
  name,
  isPending,
  onUnlink,
}: {
  name: string;
  isPending: boolean;
  onUnlink: () => void;
}) {
  return (
    <Button
      variant="secondary"
      className="mt-auto self-start"
      aria-label={m.application_contacts_unlink_named({ name })}
      isDisabled={isPending}
      onPress={onUnlink}
    >
      <UnlinkIcon className="size-4" aria-hidden="true" />
      {m.application_contacts_unlink()}
    </Button>
  );
}
