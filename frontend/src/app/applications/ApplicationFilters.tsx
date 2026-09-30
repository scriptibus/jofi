// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { m } from "../../paraglide/messages.js";
import { Button, Disclosure, MultiSelect, NumberField, SegmentedControl, Select, TextField } from "../../ui";
import type { CompanyChoices } from "../contacts/companyChoices";
import {
  type ApplicationsSearch,
  type FilterKey,
  isFiltered,
  SOURCE_KINDS,
  type SourceKind,
  STATUSES,
  type Status,
  UPDATED_WITHIN,
  type UpdatedWithin,
  withFilter,
  withoutFilters,
} from "./applicationsSearch";
import { statusLabels } from "./labels";
import { languageLabel, sourceLabels } from "./listFormat";

const ANY = "any";
/** The languages offered to filter by; a tag from the URL outside these is offered too. */
const LANGUAGES = ["de", "en", "fr", "es", "it", "nl", "pl", "pt"];

export interface ApplicationFiltersProps {
  search: ApplicationsSearch;
  onSearch: (next: ApplicationsSearch) => void;
  /** The search box's text: typed now, reaches the URL once it settles. */
  text: string;
  onText: (text: string) => void;
  companies: CompanyChoices;
}

/** The less-used filters sit behind "More filters", open when one of them is set. */
function hasMoreFilters({ source, language, updated, wantMin, fitMin }: ApplicationsSearch): boolean {
  return [source, language, updated, wantMin, fitMin].some((value) => value !== undefined);
}

/** Every filter of the list; each change lands in the URL (and back on the first page). */
export function ApplicationFilters({ search, onSearch, text, onText, companies }: ApplicationFiltersProps) {
  const set = <K extends FilterKey>(key: K, value: ApplicationsSearch[K] | undefined) =>
    onSearch(withFilter(search, key, value));
  const languages =
    search.language && !LANGUAGES.includes(search.language) ? [...LANGUAGES, search.language] : LANGUAGES;
  const score = (value: number) => (Number.isNaN(value) || value <= 0 ? undefined : value);

  return (
    <section aria-label={m.applications_filters_label()} className="flex flex-col gap-4">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <TextField
          type="search"
          label={m.applications_search_label()}
          description={m.applications_search_hint()}
          value={text}
          onChange={onText}
          className="sm:col-span-2"
        />
        <MultiSelect
          label={m.application_status_label()}
          placeholder={m.applications_filter_status_any()}
          value={search.status ?? []}
          onChange={(ids) => set("status", STATUSES.filter((status) => ids.includes(status)) as Status[])}
          groups={[{ id: "statuses", options: STATUSES.map((id) => ({ id, label: statusLabels[id]() })) }]}
        />
        <Select
          label={m.applications_filter_company()}
          placeholder={m.applications_filter_company_all()}
          value={search.company ?? ANY}
          onChange={(id) => set("company", id === ANY ? undefined : id)}
          groups={[
            { id: "any-company", options: [{ id: ANY, label: m.applications_filter_company_all() }] },
            {
              id: "companies",
              title: m.nav_companies(),
              options: companies.choices.map(({ id, name }) => ({ id, label: name })),
            },
          ]}
        />
        <SegmentedControl<"all" | "unread">
          label={m.applications_filter_show()}
          value={search.unread ? "unread" : "all"}
          onChange={(value) => set("unread", value === "unread" ? true : undefined)}
          options={[
            { value: "all", label: m.applications_filter_show_all() },
            { value: "unread", label: m.applications_filter_show_unread() },
          ]}
        />
      </div>
      <Disclosure label={m.applications_filters_more()} defaultExpanded={hasMoreFilters(search)}>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <MultiSelect
            label={m.applications_filter_source()}
            placeholder={m.applications_filter_source_any()}
            value={search.source ?? []}
            onChange={(ids) =>
              set("source", SOURCE_KINDS.filter((kind) => ids.includes(kind)) as SourceKind[])
            }
            groups={[
              { id: "sources", options: SOURCE_KINDS.map((id) => ({ id, label: sourceLabels[id]() })) },
            ]}
          />
          <Select
            label={m.applications_filter_language()}
            placeholder={m.applications_filter_language_any()}
            value={search.language ?? ANY}
            onChange={(id) => set("language", id === ANY ? undefined : id)}
            groups={[
              { id: "any-language", options: [{ id: ANY, label: m.applications_filter_language_any() }] },
              { id: "languages", options: languages.map((tag) => ({ id: tag, label: languageLabel(tag) })) },
            ]}
          />
          <Select
            label={m.applications_filter_updated()}
            placeholder={m.applications_filter_updated_any()}
            value={search.updated ? String(search.updated) : ANY}
            onChange={(id) => set("updated", id === ANY ? undefined : (Number(id) as UpdatedWithin))}
            groups={[
              {
                id: "updated",
                options: [
                  { id: ANY, label: m.applications_filter_updated_any() },
                  ...UPDATED_WITHIN.map((days) => ({
                    id: String(days),
                    label: m.applications_filter_updated_within({ days }),
                  })),
                ],
              },
            ]}
          />
          <NumberField
            label={m.applications_filter_want_min()}
            description={m.applications_filter_score_hint()}
            minValue={0}
            maxValue={5}
            step={0.5}
            value={search.wantMin ?? Number.NaN}
            onChange={(value) => set("wantMin", score(value))}
          />
          <NumberField
            label={m.applications_filter_fit_min()}
            description={m.applications_filter_score_hint()}
            minValue={0}
            maxValue={5}
            step={0.5}
            value={search.fitMin ?? Number.NaN}
            onChange={(value) => set("fitMin", score(value))}
          />
        </div>
      </Disclosure>
      {isFiltered(search) ? (
        <Button variant="secondary" className="self-start" onPress={() => onSearch(withoutFilters(search))}>
          {m.applications_filters_reset()}
        </Button>
      ) : null}
    </section>
  );
}
