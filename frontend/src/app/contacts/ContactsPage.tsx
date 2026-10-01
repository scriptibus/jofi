// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { keepPreviousData } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { type ContactResponse, type SearchContactsParams, useSearchContacts } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AddIcon, Button, EmptyState, Select, TextField, TextLink } from "../../ui";
import { PageHeader } from "../pages/PlaceholderPage";
import { useDebouncedValue } from "../useDebouncedValue";
import { type CompanyChoices, useCompanyChoices, useCompanyName } from "./companyChoices";

const route = getRouteApi("/_app/contacts");

export const PAGE_SIZE = 50;
const SEARCH_DELAY_MS = 300;
const ALL_COMPANIES = "all";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The list's state in the URL: `?company=…&page=…`. Only ids and numbers: the search text is a
 * person's name (third-party data, spec §13), so it stays in the page and out of history and logs.
 */
export interface ContactsSearch {
  company?: string;
  page?: number;
}

/** Keeps only valid values: the query string is user-editable. */
export function parseContactsSearch(search: Record<string, unknown>): ContactsSearch {
  const result: ContactsSearch = {};
  if (typeof search.company === "string" && UUID.test(search.company)) result.company = search.company;
  if (typeof search.page === "number" && Number.isInteger(search.page) && search.page > 0)
    result.page = search.page;
  return result;
}

export interface NewContactSearch {
  /** The company to preselect. */
  company?: string;
  /** The application to link the new contact to, and to return to (from its Contacts tab). */
  application?: string;
}

/** `?company=…&application=…` for a new contact; only ids. */
export function parseNewContactSearch(search: Record<string, unknown>): NewContactSearch {
  const { company } = parseContactsSearch(search);
  const result: NewContactSearch = company ? { company } : {};
  if (typeof search.application === "string" && UUID.test(search.application))
    result.application = search.application;
  return result;
}

/** Contacts: fuzzy search by name (server-side, best match first), a company filter, pages of 50. */
export function ContactsPage() {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const [text, setText] = useState("");
  const query = useDebouncedValue(text.trim(), SEARCH_DELAY_MS);
  const companies = useCompanyChoices(search.company);

  // A new search starts on its first page.
  const [searched, setSearched] = useState(query);
  useEffect(() => {
    if (query === searched) return;
    setSearched(query);
    if (search.page !== undefined)
      void navigate({ search: (previous) => withPage(previous, 0), replace: true });
  }, [query, searched, search.page, navigate]);

  const params: SearchContactsParams = { page: search.page ?? 0, size: PAGE_SIZE };
  if (query) params.search = query;
  if (search.company) params.companyId = search.company;
  const contacts = useSearchContacts(params, { query: { placeholderData: keepPreviousData } });

  const setCompany = (company: string) =>
    void navigate({
      search: (previous) => {
        const { company: _old, page: _page, ...rest } = previous;
        return company === ALL_COMPANIES ? rest : { ...rest, company };
      },
    });

  return (
    <>
      <PageHeader title={m.nav_contacts()} />
      <div className="flex flex-col gap-4 md:flex-row md:flex-wrap md:items-start md:justify-between">
        <div className="flex flex-col gap-4 sm:flex-row sm:flex-wrap">
          <TextField
            type="search"
            label={m.contacts_search_label()}
            description={m.contacts_search_hint()}
            value={text}
            onChange={setText}
            className="sm:w-80"
          />
          <Select
            label={m.contacts_filter_company()}
            placeholder={m.contacts_filter_all()}
            value={search.company ?? ALL_COMPANIES}
            onChange={setCompany}
            groups={[
              { id: "any-company", options: [{ id: ALL_COMPANIES, label: m.contacts_filter_all() }] },
              {
                id: "companies",
                title: m.nav_companies(),
                options: companies.choices.map(({ id, name }) => ({ id, label: name })),
              },
            ]}
            className="sm:w-64"
          />
        </div>
        <TextLink
          to="/contacts/new"
          search={search.company ? { company: search.company } : {}}
          className="inline-flex items-center gap-2 self-start"
        >
          <AddIcon className="size-4" aria-hidden="true" />
          {m.contacts_new()}
        </TextLink>
      </div>
      {contacts.data ? (
        <Results
          contacts={contacts.data.contacts}
          total={contacts.data.total}
          page={search.page ?? 0}
          filtered={Boolean(query || search.company)}
          companies={companies}
          onPage={(page) => void navigate({ search: (previous) => withPage(previous, page) })}
        />
      ) : contacts.isPending ? (
        <p role="status">{m.loading()}</p>
      ) : null}
    </>
  );
}

function withPage(previous: ContactsSearch, page: number): ContactsSearch {
  const { page: _old, ...rest } = previous;
  return page === 0 ? rest : { ...rest, page };
}

interface ResultsProps {
  contacts: ContactResponse[];
  total: number;
  page: number;
  filtered: boolean;
  companies: CompanyChoices;
  onPage: (page: number) => void;
}

function Results({ contacts, total, page, filtered, companies, onPage }: ResultsProps) {
  if (total === 0 && !filtered) {
    return <EmptyState title={m.empty_heading()}>{m.contacts_empty()}</EmptyState>;
  }
  const pages = Math.ceil(total / PAGE_SIZE);
  return (
    <section aria-labelledby="contacts-results" className="flex flex-col gap-4">
      <h2 id="contacts-results" className="sr-only">
        {m.companies_results_heading()}
      </h2>
      <p role="status" className="text-muted">
        {filtered ? m.contacts_count_matching({ count: total }) : m.contacts_count({ count: total })}
      </p>
      {contacts.length > 0 ? (
        <ul className="grid gap-3 lg:grid-cols-2">
          {contacts.map((contact) => (
            <li key={contact.id}>
              <ContactCard contact={contact} companies={companies} />
            </li>
          ))}
        </ul>
      ) : null}
      {pages > 1 ? (
        <nav aria-label={m.companies_pages_label()} className="flex items-center gap-3">
          <Button variant="secondary" isDisabled={page === 0} onPress={() => onPage(page - 1)}>
            {m.companies_page_previous()}
          </Button>
          <span className="font-data text-muted">{m.companies_page_of({ page: page + 1, pages })}</span>
          <Button variant="secondary" isDisabled={page + 1 >= pages} onPress={() => onPage(page + 1)}>
            {m.companies_page_next()}
          </Button>
        </nav>
      ) : null}
    </section>
  );
}

function ContactCard({ contact, companies }: { contact: ContactResponse; companies: CompanyChoices }) {
  const company = useCompanyName(contact.companyId, companies);
  const facts = [contact.role, company].filter((fact): fact is string => Boolean(fact));
  return (
    <article className="flex h-full flex-col gap-2 rounded border border-line bg-surface p-4 shadow-card">
      <h3 className="font-display text-h3">
        <TextLink to="/contacts/$contactId" params={{ contactId: contact.id }}>
          {contact.name}
        </TextLink>
      </h3>
      {facts.length > 0 ? <p className="text-muted">{facts.join(" · ")}</p> : null}
      <p className="font-data text-eyebrow text-muted">
        {m.contact_channel_count({ count: contact.channels.length })}
      </p>
    </article>
  );
}
