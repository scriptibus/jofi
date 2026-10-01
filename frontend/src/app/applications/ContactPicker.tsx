// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { keepPreviousData } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";
import {
  type ApplicationResponse,
  type ContactResponse,
  type SearchContactsParams,
  useGetCompany,
  useSearchContacts,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AddIcon, Button, Dialog, LinkIcon, TextField, TextLink } from "../../ui";
import { useDebouncedValue } from "../useDebouncedValue";
import { pickerChoices } from "./contactLinks";

/** One request's worth of candidates per group; the search narrows them down. */
const PICKER_SIZE = 50;
const SEARCH_DELAY_MS = 300;
const quietly = { meta: { errorHandledLocally: true } } as const;

/** Role and company of a contact, e.g. "Recruiter · ACME GmbH"; undefined when neither is known. */
export function useContactFacts(contact: ContactResponse): string | undefined {
  const company = useGetCompany(contact.companyId ?? "", {
    query: { ...quietly, enabled: Boolean(contact.companyId) },
  });
  const facts = [contact.role, company.data?.name].filter((fact): fact is string => Boolean(fact));
  return facts.length > 0 ? facts.join(" · ") : undefined;
}

/** Where "create a new contact" goes: the contact form with the company preselected, back here after. */
export function NewContactLink({ application }: { application: ApplicationResponse }) {
  return (
    <TextLink
      to="/contacts/new"
      search={{ company: application.companyId, application: application.id }}
      className="inline-flex items-center gap-2 self-start"
    >
      <AddIcon className="size-4" aria-hidden="true" />
      {m.application_contacts_create()}
    </TextLink>
  );
}

/** What the picker is for: its title and the words on each contact's button. */
export interface PickerTexts {
  title: string;
  /** The button's accessible name, e.g. "Link Grace Hopper". */
  pickNamed: (name: string) => string;
  /** The button's visible text, e.g. "Link". */
  pick: string;
}

const linkTexts = (): PickerTexts => ({
  title: m.application_contacts_picker_title(),
  pickNamed: (name) => m.application_contacts_link_named({ name }),
  pick: m.application_contacts_link_short(),
});

export interface ContactPickerProps {
  application: ApplicationResponse;
  isOpen: boolean;
  onClose: () => void;
  onPick: (contact: ContactResponse) => void;
  /** Contacts not to offer; by default the ones linked to the application. */
  excludeIds?: readonly string[];
  /** By default the words for linking a contact to the application. */
  texts?: PickerTexts;
  /** Offers "create a new contact", which leaves the page (default true). */
  allowCreate?: boolean;
}

/** A dialog to find a contact by name and pick it: the application's company's contacts first, then all. */
export function ContactPicker({ isOpen, onClose, texts = linkTexts(), ...props }: ContactPickerProps) {
  return (
    <Dialog isOpen={isOpen} title={texts.title} onClose={onClose}>
      {isOpen ? <PickerContent {...props} texts={texts} onClose={onClose} /> : null}
    </Dialog>
  );
}

function PickerContent({
  application,
  onClose,
  onPick,
  excludeIds = application.contactIds,
  texts,
  allowCreate = true,
}: Omit<ContactPickerProps, "isOpen" | "texts"> & { texts: PickerTexts }) {
  const [text, setText] = useState("");
  const query = useDebouncedValue(text.trim(), SEARCH_DELAY_MS);
  const params: SearchContactsParams = { size: PICKER_SIZE };
  if (query) params.search = query;
  const options = { query: { ...quietly, placeholderData: keepPreviousData } };
  const atCompany = useSearchContacts({ ...params, companyId: application.companyId }, options);
  const all = useSearchContacts(params, options);
  const company = useGetCompany(application.companyId, { query: quietly });

  let results: ReactNode;
  if (atCompany.isError || all.isError) {
    results = <p role="alert">{m.application_contacts_picker_failed()}</p>;
  } else if (atCompany.data === undefined || all.data === undefined) {
    results = <p role="status">{m.loading()}</p>;
  } else {
    const choices = pickerChoices(atCompany.data.contacts, all.data.contacts, excludeIds);
    results =
      choices.atCompany.length + choices.others.length === 0 ? (
        <p role="status" className="text-muted">
          {allowCreate ? m.application_contacts_picker_empty() : m.application_interview_picker_empty()}
        </p>
      ) : (
        <div className="flex max-h-96 flex-col gap-4 overflow-y-auto">
          <ChoiceGroup
            id="picker-company"
            title={
              company.data
                ? m.application_contacts_picker_company({ company: company.data.name })
                : m.application_contacts_picker_this_company()
            }
            contacts={choices.atCompany}
            texts={texts}
            onPick={onPick}
          />
          <ChoiceGroup
            id="picker-others"
            title={m.application_contacts_picker_others()}
            contacts={choices.others}
            texts={texts}
            onPick={onPick}
          />
        </div>
      );
  }

  return (
    <div className="flex flex-col gap-4">
      <TextField
        type="search"
        label={m.contacts_search_label()}
        description={m.contacts_search_hint()}
        value={text}
        onChange={setText}
      />
      {results}
      <div className="flex flex-wrap items-center justify-between gap-3">
        {allowCreate ? <NewContactLink application={application} /> : null}
        <Button variant="secondary" onPress={onClose}>
          {m.application_contacts_picker_close()}
        </Button>
      </div>
    </div>
  );
}

interface ChoiceGroupProps {
  id: string;
  title: string;
  contacts: ContactResponse[];
  texts: PickerTexts;
  onPick: (contact: ContactResponse) => void;
}

function ChoiceGroup({ id, title, contacts, texts, onPick }: ChoiceGroupProps) {
  if (contacts.length === 0) return null;
  return (
    <section aria-labelledby={id} className="flex flex-col gap-2">
      <h3 id={id} className="font-data text-eyebrow text-muted uppercase">
        {title}
      </h3>
      <ul className="flex flex-col divide-y divide-line">
        {contacts.map((contact) => (
          <Choice key={contact.id} contact={contact} texts={texts} onPick={onPick} />
        ))}
      </ul>
    </section>
  );
}

function Choice({
  contact,
  texts,
  onPick,
}: {
  contact: ContactResponse;
  texts: PickerTexts;
  onPick: (contact: ContactResponse) => void;
}) {
  const facts = useContactFacts(contact);
  return (
    <li className="flex items-center justify-between gap-3 py-2">
      <div className="flex min-w-0 flex-col">
        <span className="break-words font-semibold">{contact.name}</span>
        {facts ? <span className="text-muted">{facts}</span> : null}
      </div>
      <Button variant="secondary" aria-label={texts.pickNamed(contact.name)} onPress={() => onPick(contact)}>
        <LinkIcon className="size-4" aria-hidden="true" />
        {texts.pick}
      </Button>
    </li>
  );
}
