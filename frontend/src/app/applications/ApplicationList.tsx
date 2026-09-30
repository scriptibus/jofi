// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { type ApplicationResponse, useSetApplicationUnread } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Button, EmailIcon, ReadIcon, Select, Table, TableCell, type TableColumn, TextLink } from "../../ui";
import { type CompanyChoices, useCompanyName } from "../contacts/companyChoices";
import { storeSavedApplication } from "./applicationCache";
import type { Direction, Order, SortKey } from "./applicationsSearch";
import { formatDate, formatInstantDate } from "./format";
import { statusLabels } from "./labels";
import { applicationLanguage, formatBareScore, languageLabel, sourceText } from "./listFormat";

type Column = SortKey | "SCORES" | "SOURCE" | "LANGUAGE" | "READ";

/** The columns; labels are functions, so they follow the user's language. */
const COLUMNS: readonly (Omit<TableColumn<Column>, "label"> & { label: () => string })[] = [
  { id: "TITLE", label: m.applications_column_title, sortable: true },
  { id: "COMPANY", label: m.applications_column_company, sortable: true },
  { id: "STATUS", label: m.applications_column_status, sortable: true },
  { id: "SCORES", label: m.applications_column_scores },
  { id: "SOURCE", label: m.applications_column_source },
  { id: "LANGUAGE", label: m.applications_column_language },
  { id: "DEADLINE", label: m.applications_column_deadline, sortable: true },
  { id: "UPDATED", label: m.applications_column_updated, sortable: true },
  { id: "READ", label: m.applications_column_read, hideLabel: true },
];

export interface ApplicationListProps {
  applications: ApplicationResponse[];
  order: Order | null;
  /** A press on a column heading. */
  onSort: (sort: SortKey) => void;
  /** A choice in the phone's sort picker (null: best match first, while searching). */
  onOrder: (order: Order | null) => void;
  companies: CompanyChoices;
}

/** A table from `md` on; cards with a sort picker on phones, where nine columns do not fit. */
export function ApplicationList({ applications, order, onSort, onOrder, companies }: ApplicationListProps) {
  const columns = COLUMNS.map((column) => ({ ...column, label: column.label() }));
  return (
    <>
      <div className="hidden md:block">
        <Table<Column>
          label={m.nav_applications()}
          columns={columns}
          sort={
            order
              ? { column: order.sort, direction: order.dir === "ASCENDING" ? "ascending" : "descending" }
              : null
          }
          onSort={(column) => onSort(column as SortKey)}
        >
          {applications.map((application) => (
            <Row key={application.id} application={application} companies={companies} />
          ))}
        </Table>
      </div>
      <div className="flex flex-col gap-3 md:hidden">
        <SortPicker order={order} onOrder={onOrder} />
        <ul className="grid gap-3">
          {applications.map((application) => (
            <li key={application.id}>
              <Card application={application} companies={companies} />
            </li>
          ))}
        </ul>
      </div>
    </>
  );
}

const SORTABLE = COLUMNS.filter((column) => column.sortable) as { id: SortKey; label: () => string }[];
const BEST_MATCH = "best-match";

/** Phones have no column headings: the order is a choice of column and direction. */
function SortPicker({ order, onOrder }: Pick<ApplicationListProps, "order" | "onOrder">) {
  const options = SORTABLE.flatMap(({ id, label }) => [
    { id: `${id}:ASCENDING`, label: m.applications_sort_ascending({ column: label() }) },
    { id: `${id}:DESCENDING`, label: m.applications_sort_descending({ column: label() }) },
  ]);
  const bestMatch = {
    id: "relevance",
    options: [{ id: BEST_MATCH, label: m.applications_sort_best_match() }],
  };
  return (
    <Select
      label={m.applications_sort_label()}
      placeholder={m.applications_sort_best_match()}
      value={order ? `${order.sort}:${order.dir}` : BEST_MATCH}
      onChange={(id) => {
        const [sort, dir] = id.split(":") as [SortKey, Direction];
        onOrder(id === BEST_MATCH ? null : { sort, dir });
      }}
      groups={[...(order ? [] : [bestMatch]), { id: "columns", options }]}
    />
  );
}

interface ItemProps {
  application: ApplicationResponse;
  companies: CompanyChoices;
}

function Row({ application, companies }: ItemProps) {
  const language = applicationLanguage(application);
  return (
    <tr>
      <TableCell>
        <Title application={application} />
      </TableCell>
      <TableCell>
        <CompanyLink application={application} companies={companies} />
      </TableCell>
      <TableCell>
        <StatusBadge application={application} />
      </TableCell>
      <TableCell className="font-data whitespace-nowrap">
        <Scores application={application} />
      </TableCell>
      <TableCell>{sourceText(application)}</TableCell>
      <TableCell>{language ? languageLabel(language) : <None />}</TableCell>
      <TableCell className="whitespace-nowrap">
        {application.deadline ? formatDate(application.deadline) : <None />}
      </TableCell>
      <TableCell className="whitespace-nowrap">{formatInstantDate(application.updatedAt)}</TableCell>
      <TableCell>
        <ReadToggle application={application} />
      </TableCell>
    </tr>
  );
}

function Card({ application, companies }: ItemProps) {
  const facts = [
    application.deadline ? m.applications_deadline({ date: formatDate(application.deadline) }) : undefined,
    m.applications_updated({ date: formatInstantDate(application.updatedAt) }),
  ].filter((fact) => fact !== undefined);
  return (
    <article className="flex flex-col gap-2 rounded border border-line bg-surface p-4 shadow-card">
      <div className="flex items-start justify-between gap-2">
        <h3 className="font-display text-h3">
          <Title application={application} />
        </h3>
        <ReadToggle application={application} />
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge application={application} />
        <CompanyLink application={application} companies={companies} />
      </div>
      <p className="font-data text-eyebrow text-muted">{facts.join(" · ")}</p>
    </article>
  );
}

/** The title as link; an unread one gets a dot (its presence, not its colour, is the signal) named "Unread". */
function Title({ application }: { application: ApplicationResponse }) {
  return (
    <span className="inline-flex items-center gap-2">
      {application.unread ? (
        <span
          className="size-2.5 shrink-0 rounded-full bg-accent"
          role="img"
          aria-label={m.application_unread_badge()}
        />
      ) : null}
      <TextLink to="/applications/$applicationId" params={{ applicationId: application.id }}>
        {application.title}
      </TextLink>
    </span>
  );
}

function CompanyLink({ application, companies }: ItemProps) {
  const name = useCompanyName(application.companyId, companies);
  return (
    <TextLink to="/companies/$companyId" params={{ companyId: application.companyId }}>
      {name ?? m.loading()}
    </TextLink>
  );
}

function StatusBadge({ application }: { application: ApplicationResponse }) {
  return (
    <span className="inline-flex w-fit whitespace-nowrap rounded border border-line bg-sunken px-2 py-0.5 font-semibold text-body">
      {statusLabels[application.status]()}
    </span>
  );
}

function Scores({ application: { wantScore, fitScore } }: { application: ApplicationResponse }) {
  if (wantScore == null && fitScore == null) return <None />;
  const show = (score: number | null | undefined) => (score == null ? "–" : formatBareScore(score));
  return <span>{m.applications_scores({ want: show(wantScore), fit: show(fitScore) })}</span>;
}

/** An empty cell: a dash to see, "none" to hear. */
function None() {
  return (
    <>
      <span aria-hidden="true" className="text-muted">
        –
      </span>
      <span className="sr-only">{m.applications_none()}</span>
    </>
  );
}

/** Marks one application read or unread; the list and its detail page ask the server again. */
function ReadToggle({ application }: { application: ApplicationResponse }) {
  const queryClient = useQueryClient();
  const mutation = useSetApplicationUnread({
    mutation: {
      onSuccess: (saved) => storeSavedApplication(queryClient, saved),
    },
  });
  const { unread, title } = application;
  const Icon = unread ? ReadIcon : EmailIcon;
  return (
    <Button
      variant="secondary"
      aria-label={unread ? m.applications_mark_read({ title }) : m.applications_mark_unread({ title })}
      isDisabled={mutation.isPending}
      onPress={() => mutation.mutate({ id: application.id, data: { unread: !unread } })}
    >
      <Icon className="size-4" aria-hidden="true" />
    </Button>
  );
}
