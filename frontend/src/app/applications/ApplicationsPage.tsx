// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { keepPreviousData } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { type ReactNode, useEffect, useState } from "react";
import {
  type ApplicationPageResponse,
  getSearchApplicationsQueryKey,
  useSearchApplications,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { AddIcon, Alert, Button, EmptyState, SegmentedControl, TextLink } from "../../ui";
import { type CompanyChoices, useCompanyChoices } from "../contacts/companyChoices";
import { PageHeader } from "../pages/PlaceholderPage";
import { describeError } from "../problems";
import { useDebouncedValue } from "../useDebouncedValue";
import { ApplicationBoard } from "./ApplicationBoard";
import { ApplicationFilters } from "./ApplicationFilters";
import { ApplicationList } from "./ApplicationList";
import {
  type ApplicationsSearch,
  currentOrder,
  currentView,
  isFiltered,
  PAGE_SIZE,
  type SortKey,
  sortedBy,
  toBoardSearchParams,
  toSearchParams,
  type View,
  withFilter,
  withOrder,
  withoutFilters,
  withPage,
  withView,
} from "./applicationsSearch";
import { TERMINAL } from "./statusMatrix";

const route = getRouteApi("/_app/applications");
const SEARCH_DELAY_MS = 300;

/**
 * The applications (spec §6.3): filters, then a sortable table (cards on phones) in pages of 50, or the
 * Kanban board by status. Filters, order and view live in the URL, so both views share them.
 */
export function ApplicationsPage() {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const go = (next: ApplicationsSearch, replace = false) => void navigate({ search: next, replace });
  const [text, setText] = useSearchText(search, (q) => go(withFilter(search, "q", q || undefined), true));
  const companies = useCompanyChoices(search.company);
  const views = { search, onSearch: go, companies };

  return (
    <>
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <PageHeader title={m.nav_applications()} />
        <NewApplicationLink company={search.company} />
      </div>
      <ApplicationFilters search={search} onSearch={go} text={text} onText={setText} companies={companies} />
      <SegmentedControl<View>
        label={m.applications_view_label()}
        options={[
          { value: "table", label: m.applications_view_table() },
          { value: "board", label: m.applications_view_board() },
        ]}
        value={currentView(search)}
        onChange={(view) => go(withView(search, view))}
      />
      {currentView(search) === "board" ? <BoardView {...views} /> : <TableView {...views} />}
    </>
  );
}

interface ViewProps {
  search: ApplicationsSearch;
  onSearch: (next: ApplicationsSearch) => void;
  companies: CompanyChoices;
}

function TableView({ search, onSearch, companies }: ViewProps) {
  const applications = useSearchApplications(toSearchParams(search), {
    query: { placeholderData: keepPreviousData, meta: { errorHandledLocally: true } },
  });
  if (!applications.data) return <Loading query={applications} />;
  return (
    <Results
      page={applications.data}
      search={search}
      onSearch={onSearch}
      list={
        <ApplicationList
          applications={applications.data.applications}
          order={currentOrder(search)}
          onSort={(sort: SortKey) => onSearch(sortedBy(search, sort))}
          onOrder={(order) => onSearch(withOrder(search, order))}
          companies={companies}
        />
      }
    />
  );
}

/** The board: every application matching the filters (up to the server's largest page), no paging. */
function BoardView({ search, onSearch, companies }: ViewProps) {
  const params = toBoardSearchParams(search);
  const applications = useSearchApplications(params, {
    query: { placeholderData: keepPreviousData, meta: { errorHandledLocally: true } },
  });
  if (!applications.data) return <Loading query={applications} />;
  const { total, applications: shown } = applications.data;
  return (
    <Results
      page={applications.data}
      search={search}
      onSearch={onSearch}
      list={
        <>
          {total > shown.length ? (
            <Alert tone="info">{m.applications_board_truncated({ shown: shown.length, total })}</Alert>
          ) : null}
          <ApplicationBoard
            page={applications.data}
            queryKey={getSearchApplicationsQueryKey(params)}
            companies={companies}
            showEnded={search.status?.some((status) => TERMINAL.includes(status)) ?? false}
          />
        </>
      }
    />
  );
}

interface LoadingProps {
  query: { isError: boolean; error: unknown; refetch: () => unknown };
}

/** Before the first answer: "Loading…", or why it failed with a retry. */
function Loading({ query }: LoadingProps) {
  if (!query.isError) return <p role="status">{m.loading()}</p>;
  return (
    <Alert tone="error" title={m.applications_load_failed()}>
      <span className="flex flex-col items-start gap-3">
        {describeError(query.error).message}
        <Button variant="secondary" onPress={() => void query.refetch()}>
          {m.error_retry()}
        </Button>
      </span>
    </Alert>
  );
}

/** To the create page; a company the list is filtered by is preselected there. */
function NewApplicationLink({ company }: { company?: string | undefined }) {
  return (
    <TextLink
      to="/applications/new"
      search={company ? { company } : {}}
      className="inline-flex items-center gap-2 self-start sm:self-auto"
    >
      <AddIcon className="size-4" aria-hidden="true" />
      {m.applications_new()}
    </TextLink>
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
    return (
      <EmptyState title={m.empty_heading()} action={<NewApplicationLink />}>
        {m.applications_empty()}
      </EmptyState>
    );
  const page = search.page ?? 0;
  // The board shows every match at once (up to its limit), so it has no pages.
  const pages = search.view === "board" ? 1 : Math.ceil(total / PAGE_SIZE);
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
