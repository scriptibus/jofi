// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { keepPreviousData } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import {
  type CompanyResponse,
  type SearchCompaniesParams,
  useSearchCompanies,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AddIcon, Button, EmptyState, SegmentedControl, TextField, TextLink } from "../../ui";
import { PageHeader } from "../pages/PlaceholderPage";
import { useDebouncedValue } from "../useDebouncedValue";
import { PREFERENCES, type Preference, preferenceLabel } from "./company";
import { PreferenceBadge } from "./PreferenceBadge";

const route = getRouteApi("/_app/companies");

export const PAGE_SIZE = 50;
const SEARCH_DELAY_MS = 300;

/** The list's state in the URL, so back and reload keep it: `?q=…&preference=…&page=…`. */
export interface CompaniesSearch {
  q?: string;
  preference?: Preference;
  page?: number;
}

/** Keeps only valid values: the query string is user-editable. */
export function parseCompaniesSearch(search: Record<string, unknown>): CompaniesSearch {
  const result: CompaniesSearch = {};
  if (typeof search.q === "string" && search.q.trim() !== "") result.q = search.q.trim();
  if (PREFERENCES.includes(search.preference as Preference))
    result.preference = search.preference as Preference;
  if (typeof search.page === "number" && Number.isInteger(search.page) && search.page > 0)
    result.page = search.page;
  return result;
}

type Filter = Preference | "ALL";

/** Companies: fuzzy search by name (server-side, best match first), a preference filter, pages of 50. */
export function CompaniesPage() {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const [text, setText] = useState(search.q ?? "");
  const settled = useDebouncedValue(text.trim(), SEARCH_DELAY_MS);

  useEffect(() => {
    if (settled === (search.q ?? "")) return;
    void navigate({ search: (previous) => withQuery(previous, settled), replace: true });
  }, [settled, search.q, navigate]);

  const params: SearchCompaniesParams = { page: search.page ?? 0, size: PAGE_SIZE };
  if (search.q) params.search = search.q;
  if (search.preference) params.preference = search.preference;
  const companies = useSearchCompanies(params, { query: { placeholderData: keepPreviousData } });

  const setFilter = (filter: Filter) =>
    void navigate({
      search: (previous) => {
        const { preference: _old, page: _page, ...rest } = previous;
        return filter === "ALL" ? rest : { ...rest, preference: filter };
      },
    });

  return (
    <>
      <PageHeader title={m.nav_companies()} />
      <div className="flex flex-col gap-4 md:flex-row md:flex-wrap md:items-end md:justify-between">
        <TextField
          type="search"
          label={m.companies_search_label()}
          description={m.companies_search_hint()}
          value={text}
          onChange={setText}
          className="md:w-96"
        />
        <TextLink to="/companies/new" className="inline-flex items-center gap-2 self-start md:self-end">
          <AddIcon className="size-4" aria-hidden="true" />
          {m.companies_new()}
        </TextLink>
      </div>
      <SegmentedControl<Filter>
        label={m.companies_filter_label()}
        value={search.preference ?? "ALL"}
        onChange={setFilter}
        options={[
          { value: "ALL", label: m.companies_filter_all() },
          ...PREFERENCES.map((value) => ({ value, label: filterLabel(value) })),
        ]}
      />
      {companies.data ? (
        <Results
          companies={companies.data.companies}
          total={companies.data.total}
          page={search.page ?? 0}
          filtered={Boolean(search.q || search.preference)}
          onPage={(page) => void navigate({ search: (previous) => withPage(previous, page) })}
        />
      ) : companies.isPending ? (
        <p role="status">{m.loading()}</p>
      ) : null}
    </>
  );
}

function filterLabel(preference: Preference): string {
  if (preference === "NONE") return m.companies_filter_none();
  return preferenceLabel(preference);
}

function withQuery(previous: CompaniesSearch, q: string): CompaniesSearch {
  const { q: _old, page: _page, ...rest } = previous;
  return q === "" ? rest : { ...rest, q };
}

function withPage(previous: CompaniesSearch, page: number): CompaniesSearch {
  const { page: _old, ...rest } = previous;
  return page === 0 ? rest : { ...rest, page };
}

interface ResultsProps {
  companies: CompanyResponse[];
  total: number;
  page: number;
  filtered: boolean;
  onPage: (page: number) => void;
}

function Results({ companies, total, page, filtered, onPage }: ResultsProps) {
  if (total === 0 && !filtered) {
    return <EmptyState title={m.empty_heading()}>{m.companies_empty()}</EmptyState>;
  }
  const pages = Math.ceil(total / PAGE_SIZE);
  return (
    <section aria-labelledby="companies-results" className="flex flex-col gap-4">
      <h2 id="companies-results" className="sr-only">
        {m.companies_results_heading()}
      </h2>
      <p role="status" className="text-muted">
        {filtered ? m.companies_count_matching({ count: total }) : m.companies_count({ count: total })}
      </p>
      {companies.length > 0 ? (
        <ul className="grid gap-3 lg:grid-cols-2">
          {companies.map((company) => (
            <li key={company.id}>
              <CompanyCard company={company} />
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

function CompanyCard({ company }: { company: CompanyResponse }) {
  const facts = [company.industry, company.locations[0]].filter((fact): fact is string => Boolean(fact));
  return (
    <article className="flex h-full flex-col gap-2 rounded border border-line bg-surface p-4 shadow-card">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-display text-h3">
          <TextLink to="/companies/$companyId" params={{ companyId: company.id }}>
            {company.name}
          </TextLink>
        </h3>
        <PreferenceBadge preference={company.preference} />
      </div>
      {facts.length > 0 ? <p className="text-muted">{facts.join(" · ")}</p> : null}
      <p className="font-data text-eyebrow text-muted">
        {m.company_application_count({ count: company.applicationCount })}
      </p>
    </article>
  );
}
