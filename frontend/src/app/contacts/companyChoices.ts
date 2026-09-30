// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useGetCompany, useSearchCompanies } from "../../api/generated/jofi";

/** The most companies one request returns (backend `CompanySearch.MAX_SIZE`); the pickers offer these. */
const MAX_CHOICES = 200;

export interface CompanyChoice {
  id: string;
  name: string;
}

export interface CompanyChoices {
  /** By name. */
  choices: CompanyChoice[];
  /** False until the server answered; until then a missing company is not yet known to be missing. */
  isLoaded: boolean;
}

/**
 * The companies a contact can belong to, by name, for a company picker: the first 200 by name, plus
 * `selected` when that one is further down, so a picker always shows the current choice.
 */
export function useCompanyChoices(selected?: string): CompanyChoices {
  const page = useSearchCompanies({ page: 0, size: MAX_CHOICES });
  const listed = page.data?.companies.map(({ id, name }) => ({ id, name })) ?? [];
  const missing = Boolean(selected) && page.data !== undefined && !listed.some(({ id }) => id === selected);
  const extra = useGetCompany(selected ?? "", { query: { enabled: missing } });
  const choices = missing && extra.data ? [...listed, { id: extra.data.id, name: extra.data.name }] : listed;
  return { choices, isLoaded: page.data !== undefined };
}

/** The name of company `id`: from the loaded choices when it is there, else asked from the server. */
export function useCompanyName(id: string | null | undefined, { choices, isLoaded }: CompanyChoices) {
  const known = id ? choices.find((choice) => choice.id === id)?.name : undefined;
  const company = useGetCompany(id ?? "", { query: { enabled: Boolean(id) && isLoaded && !known } });
  return known ?? company.data?.name;
}
