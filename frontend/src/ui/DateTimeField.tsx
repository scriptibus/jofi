// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type CalendarDateTime, parseDateTime } from "@internationalized/date";
import type { ReactNode } from "react";
import {
  DatePicker as AriaDatePicker,
  Button,
  Calendar,
  CalendarCell,
  CalendarGrid,
  DateInput,
  DateSegment,
  Dialog,
  FieldError,
  Group,
  Heading,
  Label,
  Popover,
  Text,
} from "react-aria-components";
import { CalendarIcon, NextIcon, PreviousIcon } from "./icons";

export interface DateTimeFieldProps {
  /** Visible label; also the accessible name of the segments and the calendar button. */
  label: string;
  /** A wall-clock date and time without zone or offset (`2026-10-05T10:00`), or null while empty. */
  value: string | null;
  /** Called with the complete date and time (minutes, no seconds), or null when the user clears it. */
  onChange: (value: string | null) => void;
  /** Help text below the field, linked with `aria-describedby`. */
  description?: ReactNode;
  /** The message for a value that is not acceptable (e.g. empty while required), or null. */
  validate?: (value: string | null) => string | null;
  isRequired?: boolean;
  isDisabled?: boolean;
  /** Form field name, so a surrounding `Form`'s `validationErrors` (server errors) show below it. */
  name?: string;
  className?: string;
}

/** A date and time as typed, or null for anything that does not parse (the field then starts empty). */
function parse(value: string | null): CalendarDateTime | null {
  if (!value) return null;
  try {
    return parseDateTime(value);
  } catch {
    return null;
  }
}

/** `2026-10-05T10:00:00` → `2026-10-05T10:00`: the field's precision is the minute. */
export function toMinutes(value: CalendarDateTime): string {
  return value.toString().replace(/:\d{2}(\.\d+)?$/, "");
}

const iconButton =
  "flex items-center justify-center rounded p-1.5 text-fg data-hovered:bg-sunken data-disabled:opacity-50 " +
  "data-focus-visible:outline-2 data-focus-visible:outline-offset-1 data-focus-visible:outline-accent";

/**
 * A labelled date and time on a clock, without a time zone (React Aria DatePicker, minute granularity):
 * segments to type into in the user's locale (`10/05/2026, 10:00 AM`, `05.10.2026, 10:00`) and a button
 * that opens a month calendar. The zone the time belongs to is a separate choice of the feature.
 */
export function DateTimeField({
  label,
  value,
  onChange,
  description,
  validate,
  isRequired,
  isDisabled,
  name,
  className,
}: DateTimeFieldProps) {
  return (
    <AriaDatePicker<CalendarDateTime>
      value={parse(value)}
      onChange={(next) => onChange(next ? toMinutes(next) : null)}
      granularity="minute"
      isRequired={isRequired ?? false}
      isDisabled={isDisabled ?? false}
      {...(validate
        ? { validate: (next: CalendarDateTime | null) => validate(next ? toMinutes(next) : null) }
        : {})}
      {...(name ? { name } : {})}
      className={["flex flex-col gap-1.5", className].filter(Boolean).join(" ")}
    >
      <Label className="font-semibold text-body">{label}</Label>
      <Group
        className={
          "flex w-full items-center justify-between gap-2 rounded border border-line bg-surface py-1 pr-1 pl-3 " +
          "text-body text-fg data-hovered:border-muted data-invalid:border-bad data-disabled:opacity-50 " +
          "data-focus-within:border-accent data-focus-visible:outline-2 data-focus-visible:outline-offset-1 " +
          "data-focus-visible:outline-accent"
        }
      >
        <DateInput className="flex min-w-0 flex-wrap py-1 font-data">
          {(segment) => (
            <DateSegment
              segment={segment}
              className={
                "rounded px-0.5 tabular-nums outline-none data-placeholder:text-muted " +
                "data-focused:bg-accent data-focused:text-accent-fg"
              }
            />
          )}
        </DateInput>
        <Button className={iconButton}>
          <CalendarIcon className="size-4" aria-hidden="true" />
        </Button>
      </Group>
      {description ? (
        <Text slot="description" className="text-muted text-body">
          {description}
        </Text>
      ) : null}
      <FieldError className="font-medium text-bad text-body" />
      <Popover className="rounded border border-line bg-surface p-3 text-fg shadow-card">
        <Dialog className="outline-none">
          <MonthCalendar />
        </Dialog>
      </Popover>
    </AriaDatePicker>
  );
}

function MonthCalendar() {
  return (
    <Calendar className="flex flex-col gap-2">
      <header className="flex items-center justify-between gap-2">
        <Button slot="previous" className={iconButton}>
          <PreviousIcon className="size-4" aria-hidden="true" />
        </Button>
        <Heading className="font-semibold text-body" />
        <Button slot="next" className={iconButton}>
          <NextIcon className="size-4" aria-hidden="true" />
        </Button>
      </header>
      <CalendarGrid className="border-separate border-spacing-0.5">
        {(date) => (
          <CalendarCell
            date={date}
            className={
              "flex size-9 cursor-default items-center justify-center rounded font-data text-body outline-none " +
              "data-hovered:bg-sunken data-outside-month:hidden data-disabled:opacity-50 " +
              "data-selected:bg-accent data-selected:font-semibold data-selected:text-accent-fg " +
              "data-focus-visible:outline-2 data-focus-visible:outline-offset-1 data-focus-visible:outline-accent"
            }
          />
        )}
      </CalendarGrid>
    </Calendar>
  );
}
