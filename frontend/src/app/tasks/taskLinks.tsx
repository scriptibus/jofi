// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  type TaskLinkDto,
  useGetApplication,
  useGetCompany,
  useGetContact,
  useSearchApplications,
  useSearchContacts,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { ApplicationsIcon, ChipLink, CompaniesIcon, ContactsIcon, type SelectOption } from "../../ui";
import { useCompanyChoices } from "../contacts/companyChoices";
import { linkTypeLabels, type TaskLinkType } from "./task";

/** The most records one search returns (backend `MAX_SIZE`); the picker offers these. */
const MAX_CHOICES = 200;

const local = { meta: { errorHandledLocally: true } } as const;

/**
 * What a task can be about, for the link picker: the 200 most recently changed applications (with their
 * company), the first 200 companies or contacts by name, plus `selected` when it is further down. Only the
 * chosen type is asked for.
 */
export function useLinkChoices(type: TaskLinkType | "", selected: string): SelectOption[] {
  const companies = useCompanyChoices(type === "COMPANY" && selected ? selected : undefined);
  const applications = useSearchApplications(
    { page: 0, size: MAX_CHOICES },
    { query: { enabled: type === "APPLICATION", ...local } },
  );
  const contacts = useSearchContacts(
    { page: 0, size: MAX_CHOICES },
    { query: { enabled: type === "CONTACT", ...local } },
  );
  const companyName = (id: string) => companies.choices.find((company) => company.id === id)?.name;

  let options: SelectOption[] = [];
  if (type === "COMPANY") options = companies.choices.map(({ id, name }) => ({ id, label: name }));
  if (type === "APPLICATION")
    options = (applications.data?.applications ?? []).map(({ id, title, companyId }) => {
      const company = companyName(companyId);
      return { id, label: company ? m.task_link_application_label({ title, company }) : title };
    });
  if (type === "CONTACT")
    options = (contacts.data?.contacts ?? []).map(({ id, name }) => ({ id, label: name }));

  const missing =
    selected !== "" && type !== "" && type !== "COMPANY" && !options.some(({ id }) => id === selected);
  const name = useLinkName({ type: type === "" ? "APPLICATION" : type, id: selected }, missing);
  return missing && name ? [...options, { id: selected, label: name }] : options;
}

/** The name of what `link` points to (an application's title, a company's or contact's name). */
function useLinkName(link: TaskLinkDto, enabled = true): string | undefined {
  const application = useGetApplication(link.id, {
    query: { enabled: enabled && link.type === "APPLICATION", ...local },
  });
  const company = useGetCompany(link.id, {
    query: { enabled: enabled && link.type === "COMPANY", ...local },
  });
  const contact = useGetContact(link.id, {
    query: { enabled: enabled && link.type === "CONTACT", ...local },
  });
  if (link.type === "APPLICATION") return application.data?.title;
  if (link.type === "COMPANY") return company.data?.name;
  return contact.data?.name;
}

const icons = { APPLICATION: ApplicationsIcon, COMPANY: CompaniesIcon, CONTACT: ContactsIcon } as const;

/** What a task is about, as a chip linking to that record; its kind is in the accessible name, not only the icon. */
export function TaskLinkChip({ link }: { link: TaskLinkDto }) {
  const name = useLinkName(link) ?? m.loading();
  const Icon = icons[link.type];
  const label = m.task_link_chip({ kind: linkTypeLabels[link.type](), name });
  const common = { "aria-label": label, className: "self-start" };
  const content = (
    <>
      <Icon className="size-4 shrink-0 text-muted" aria-hidden="true" />
      <span className="truncate">{name}</span>
    </>
  );
  if (link.type === "APPLICATION")
    return (
      <ChipLink to="/applications/$applicationId" params={{ applicationId: link.id }} {...common}>
        {content}
      </ChipLink>
    );
  if (link.type === "COMPANY")
    return (
      <ChipLink to="/companies/$companyId" params={{ companyId: link.id }} {...common}>
        {content}
      </ChipLink>
    );
  return (
    <ChipLink to="/contacts/$contactId" params={{ contactId: link.id }} {...common}>
      {content}
    </ChipLink>
  );
}
