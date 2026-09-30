// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode, TdHTMLAttributes } from "react";
import { Button } from "react-aria-components";
import { SortAscendingIcon, SortableIcon, SortDescendingIcon } from "./icons";

export type SortDirection = "ascending" | "descending";

export interface TableSort<K extends string> {
  column: K;
  direction: SortDirection;
}

export interface TableColumn<K extends string> {
  id: K;
  /** Column heading; also the name of its sort button. */
  label: string;
  /** Offers a sort button in the heading. */
  sortable?: boolean;
  /** Keeps the heading for screen readers only (e.g. a column of icons). */
  hideLabel?: boolean;
}

export interface TableProps<K extends string> {
  /** The table's caption (screen readers only). */
  label: string;
  columns: readonly TableColumn<K>[];
  /** The column the rows are sorted by, if any (announced with `aria-sort`). */
  sort?: TableSort<K> | null;
  onSort?: (column: K) => void;
  /** The rows: `<tr>` elements with `TableCell`s. */
  children: ReactNode;
}

/**
 * A data table: a real `<table>` with a caption, column headings and, for sortable columns, a sort
 * button in the heading. The sorted column carries `aria-sort`; what a press does (which column,
 * which direction) is up to `onSort`, so the order can live in the URL and on the server.
 */
export function Table<K extends string>({ label, columns, sort, onSort, children }: TableProps<K>) {
  return (
    <div className="overflow-x-auto rounded border border-line bg-surface shadow-card">
      <table className="w-full border-collapse text-left text-body">
        <caption className="sr-only">{label}</caption>
        <thead className="bg-sunken">
          <tr>
            {columns.map((column) => (
              <HeaderCell
                key={column.id}
                column={column}
                direction={sort?.column === column.id ? sort.direction : undefined}
                onSort={onSort}
              />
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

interface HeaderCellProps<K extends string> {
  column: TableColumn<K>;
  direction: SortDirection | undefined;
  onSort: ((column: K) => void) | undefined;
}

function HeaderCell<K extends string>({ column, direction, onSort }: HeaderCellProps<K>) {
  const heading = "px-3 py-2 font-data text-eyebrow text-muted uppercase whitespace-nowrap";
  if (!column.sortable || !onSort) {
    return (
      <th scope="col" className={heading}>
        <span className={column.hideLabel ? "sr-only" : undefined}>{column.label}</span>
      </th>
    );
  }
  const Icon =
    direction === "ascending"
      ? SortAscendingIcon
      : direction === "descending"
        ? SortDescendingIcon
        : SortableIcon;
  return (
    <th scope="col" className={heading} {...(direction ? { "aria-sort": direction } : {})}>
      <Button
        onPress={() => onSort(column.id)}
        className={
          "inline-flex cursor-default items-center gap-1 rounded uppercase data-hovered:text-fg " +
          `${direction ? "text-fg" : ""} ` +
          "data-focus-visible:outline-2 data-focus-visible:outline-offset-2 data-focus-visible:outline-accent"
        }
      >
        {column.label}
        <Icon className="size-4 shrink-0" aria-hidden="true" />
      </Button>
    </th>
  );
}

/** A body cell with the table's spacing and row divider. */
export function TableCell({ className, ...props }: TdHTMLAttributes<HTMLTableCellElement>) {
  return (
    <td
      {...props}
      className={["border-line border-t px-3 py-2 align-top", className].filter(Boolean).join(" ")}
    />
  );
}
