// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { TimelineFieldChangeDto } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { formatDate, formatPercent, languageName } from "./format";
import {
  employmentTypeLabels,
  formOfAddressLabels,
  howAppliedLabels,
  seniorityLabels,
  toneLabels,
} from "./labels";

// The changed fields of a changelog entry on the timeline (#87): the server names each field and sends values
// only for those that are no free text (flags, numbers, dates, codes). Ids (company, contacts) are named
// without values: an id says nothing to the user.

const fieldLabels: Record<string, () => string> = {
  title: m.application_timeline_field_title,
  company: m.application_timeline_field_company,
  location: m.application_field_location,
  remoteShare: m.application_field_remote_share,
  employmentType: m.application_field_employment_type,
  seniority: m.application_field_seniority,
  deadline: m.application_field_deadline,
  howApplied: m.application_field_how_applied,
  postingLanguage: m.application_field_posting_language,
  applicationLanguage: m.application_field_application_language,
  formOfAddress: m.application_field_form_of_address,
  tone: m.application_field_tone,
  unread: m.application_timeline_field_unread,
  contacts: m.application_tab_contacts,
  status: m.application_status_label,
  declineReason: m.application_decline_reason,
};

/** A code from the server in words; a code this version does not know stays as it is. */
const fromLabels =
  (labels: Record<string, () => string>) =>
  (code: string): string =>
    labels[code]?.() ?? code;

const valueFormats: Record<string, (value: string) => string> = {
  remoteShare: (value) => (Number.isNaN(Number(value)) ? value : formatPercent(Number(value))),
  employmentType: fromLabels(employmentTypeLabels),
  seniority: fromLabels(seniorityLabels),
  deadline: (value) => formatDate(value),
  howApplied: fromLabels(howAppliedLabels),
  postingLanguage: (value) => languageName(value) ?? value,
  applicationLanguage: (value) => languageName(value) ?? value,
  formOfAddress: fromLabels(formOfAddressLabels),
  tone: fromLabels(toneLabels),
  unread: (value) => (value === "true" ? m.application_unread_badge() : m.application_timeline_read()),
};

export interface ChangedField {
  /** The server's name of the field, unique within one change. */
  field: string;
  label: string;
  /** Both values in words, when the field shows values; "none" for an empty one. */
  values?: { before: string; after: string };
}

/** One changed field in the user's words; a field this version does not know is "other details". */
export function describeChangedField({ field, before, after }: TimelineFieldChangeDto): ChangedField {
  const label = fieldLabels[field]?.() ?? m.application_timeline_field_other();
  const format = valueFormats[field];
  if (format === undefined || (before == null && after == null)) return { field, label };
  const words = (value: string | null | undefined) =>
    value == null ? m.application_timeline_none() : format(value);
  return { field, label, values: { before: words(before), after: words(after) } };
}
