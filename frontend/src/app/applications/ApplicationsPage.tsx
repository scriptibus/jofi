// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { keepPreviousData } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { type ReactNode, useEffect, useState } from "react";
import { type ApplicationPageResponse, useSearchApplications } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, EmptyState } from "../../ui";
import { useCompanyChoices } from "../contacts/companyChoices";
import { PageHeader } from "../pages/PlaceholderPage";
import { describeError } from "../problems";
import { useDebouncedValue } from "../useDebouncedValue";
import { ApplicationFilters } from "./ApplicationFilters";
import { ApplicationList } from "./ApplicationList";
import {
  type ApplicationsSearch,
  currentOrder,
  isFiltered,
  PAGE_SIZE,
  type SortKey,
  sortedBy,
  toSearchParams,
  withFilter,
  withOrder,
  withoutFilters,
  withPage,
} from "./applicationsSearch";

const route = getRouteApi("/_app/applications");
const SEARCH_DELAY_MS = 300;

/** The applications (spec §6.3): filters, a sortable table (cards on phones), pages of 50. */
export function ApplicationsPage() {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const go = (next: ApplicationsSearch, replace = false) => void navigate({ search: next, replace });
  const [text, setText] = useSearchText(search, (q) => go(withFilter(search, "q", q || undefined), true));
  const companies = useCompanyChoices(search.company);
  const applications = useSearchApplications(toSearchParams(search), {
    query: { placeholderData: keepPreviousData, meta: { errorHandledLocally: true } },
  });

  return (
    <>
      <PageHeader title={m.nav_applications()} />
      <ApplicationFilters search={search} onSearch={go} text={text} onText={setText} companies={companies} />
      {applications.data ? (
        <Results
          page={applications.data}
          search={search}
          onSearch={go}
          list={
            <ApplicationList
              applications={applications.data.applications}
              order={currentOrder(search)}
              onSort={(sort: SortKey) => go(sortedBy(search, sort))}
              onOrder={(order) => go(withOrder(search, order))}
              companies={companies}
            />
          }
        />
      ) : applications.isError ? (
        <Alert tone="error" title={m.applications_load_failed()}>
          <span className="flex flex-col items-start gap-3">
            {describeError(applications.error).message}
            <Button variant="secondary" onPress={() => void applications.refetch()}>
              {m.error_retry()}
            </Button>
          </span>
        </Alert>
      ) : (
        <p role="status">{m.loading()}</p>
      )}
    </>
  );
}

/**
 * The search box: typing moves `?q=` once the text settles (replacing the history entry), and the
 * URL moving on its own (back, "Reset filters") moves the box. As on the companies page.
 */
function useSearchText(search: ApplicationsSearch, onSettled: (q: string) => void) {
  const current = search.q ?? "";
  const [text, setText] = useState(current);
  const settled = useDebouncedValue(text.trim(), SEARCH_DELAY_MS);
  const [typed, setTyped] = useState(settled);
  const [shown, setShown] = useState(current);
  if (current !== shown) {
    setShown(current);
    if (current !== settled) setText(current);
  }
  useEffect(() => {
    if (settled === typed) return;
    setTyped(settled);
    if (settled !== current) onSettled(settled);
  }, [settled, typed, current, onSettled]);
  return [text, setText] as const;
}

interface ResultsProps {
  page: ApplicationPageResponse;
  search: ApplicationsSearch;
  onSearch: (next: ApplicationsSearch) => void;
  list: ReactNode;
}

function Results({ page: { total }, search, onSearch, list }: ResultsProps) {
  const filtered = isFiltered(search);
  if (total === 0 && !filtered)
    return <EmptyState title={m.empty_heading()}>{m.applications_empty()}</EmptyState>;
  const page = search.page ?? 0;
  const pages = Math.ceil(total / PAGE_SIZE);
  return (
    <section aria-labelledby="applications-results" className="flex flex-col gap-4">
      <h2 id="applications-results" className="sr-only">
        {m.companies_results_heading()}
      </h2>
      <p role="status" className="text-muted">
        {filtered ? m.applications_count_matching({ count: total }) : m.applications_count({ count: total })}
      </p>
      {total === 0 ? (
        <div className="flex flex-col items-start gap-3">
          <p>{m.applications_no_matches()}</p>
          <Button variant="secondary" onPress={() => onSearch(withoutFilters(search))}>
            {m.applications_filters_reset()}
          </Button>
        </div>
      ) : (
        list
      )}
      {pages > 1 ? (
        <nav aria-label={m.companies_pages_label()} className="flex items-center gap-3">
          <Button
            variant="secondary"
            isDisabled={page === 0}
            onPress={() => onSearch(withPage(search, page - 1))}
          >
            {m.companies_page_previous()}
          </Button>
          <span className="font-data text-muted">{m.companies_page_of({ page: page + 1, pages })}</span>
          <Button
            variant="secondary"
            isDisabled={page + 1 >= pages}
            onPress={() => onSearch(withPage(search, page + 1))}
          >
            {m.companies_page_next()}
          </Button>
        </nav>
      ) : null}
    </section>
  );
}
