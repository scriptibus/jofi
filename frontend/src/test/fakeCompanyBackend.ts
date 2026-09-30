// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the companies API (ADR-0041) and the read side of contacts, as MSW
// handlers next to `fakeAuthBackend`. It mirrors the backend's status codes and problem types: 409
// `version-conflict` for a stale `basedOnVersion`, 409 `has-applications` before the 428 of a delete.
// Its "fuzzy" search is a case-insensitive substring match; the real one runs in the e2e suite.

import { HttpResponse, http } from "msw";
import type {
  CompanyDetailsRequest,
  CompanyPreferenceRequest,
  CompanyResponse,
  ContactResponse,
  UpdateCompanyRequest,
} from "../api/generated/jofi";

const origin = () => window.location.origin;
const json = (body: Record<string, unknown>, status: number) =>
  HttpResponse.json(body, { status, headers: { "Content-Type": "application/problem+json" } });
const problem = (status: number, code: string, extra: Record<string, unknown> = {}) =>
  json(
    { type: `urn:jofi:problem:companies:${code}`, title: "Problem", status, detail: code, ...extra },
    status,
  );

export const DELETE_TOKEN = "delete-t0k3n";

export function aCompany(overrides: Partial<CompanyResponse> = {}): CompanyResponse {
  return {
    id: crypto.randomUUID(),
    name: "ACME GmbH",
    website: null,
    industry: null,
    size: null,
    locations: [],
    careersPage: null,
    researchNotes: null,
    profile: null,
    preference: "NONE",
    preferenceReason: null,
    applicationCount: 0,
    version: 0,
    createdAt: "2026-09-30T10:00:00Z",
    updatedAt: "2026-09-30T10:00:00Z",
    ...overrides,
  };
}

export function aContact(companyId: string, overrides: Partial<ContactResponse> = {}): ContactResponse {
  return {
    id: crypto.randomUUID(),
    companyId,
    name: "Ada Lovelace",
    role: "Recruiter",
    channels: [],
    relationshipNotes: null,
    version: 0,
    createdAt: "2026-09-30T10:00:00Z",
    updatedAt: "2026-09-30T10:00:00Z",
    ...overrides,
  };
}

export interface FakeCompanyState {
  companies: CompanyResponse[];
  contacts: ContactResponse[];
  /** Delete calls seen: `first` without token, `confirmed` with it. */
  deleteCalls: ("first" | "confirmed")[];
  /** Search parameters of every list request, in order. */
  searches: URLSearchParams[];
  /** A rule only the server knows: a request whose `field` equals `value` is refused with `problem`. */
  refuse?: { field: keyof CompanyDetailsRequest; value: string; problem: string };
}

/** Server-side rules the UI relies on: a name, and http(s) web addresses. */
function violations(details: CompanyDetailsRequest, refuse: FakeCompanyState["refuse"]) {
  const found: { field: string; problem: string }[] = [];
  if (refuse && details[refuse.field] === refuse.value)
    found.push({ field: refuse.field, problem: refuse.problem });
  if (details.name.trim() === "") found.push({ field: "name", problem: "REQUIRED" });
  if (details.name.length > 200) found.push({ field: "name", problem: "TOO_LONG" });
  for (const field of ["website", "careersPage"] as const) {
    const value = details[field];
    if (value && !/^https?:\/\//i.test(value)) found.push({ field, problem: "INVALID_URL" });
  }
  return found;
}

function withDetails(company: CompanyResponse, details: CompanyDetailsRequest): CompanyResponse {
  return {
    ...company,
    name: details.name,
    website: details.website ?? null,
    careersPage: details.careersPage ?? null,
    industry: details.industry ?? null,
    size: details.size ?? null,
    locations: details.locations ?? [],
    researchNotes: details.researchNotes ?? null,
  };
}

export function fakeCompanyBackend(initial: Partial<FakeCompanyState> = {}) {
  const state: FakeCompanyState = { companies: [], contacts: [], deleteCalls: [], searches: [], ...initial };
  const find = (id: unknown) => state.companies.find((company) => company.id === id);
  const replace = (next: CompanyResponse) => {
    const saved = { ...next, version: next.version + 1 };
    state.companies = state.companies.map((company) => (company.id === saved.id ? saved : company));
    return HttpResponse.json(saved);
  };
  const invalid = (found: { field: string; problem: string }[]) =>
    json({ type: "urn:jofi:problem:companies:invalid-company", status: 400, violations: found }, 400);

  const handlers = [
    http.get(`${origin()}/api/companies`, ({ request }) => {
      const params = new URL(request.url).searchParams;
      state.searches.push(params);
      const search = params.get("search")?.toLowerCase() ?? "";
      const preference = params.get("preference");
      const page = Number(params.get("page") ?? 0);
      const size = Number(params.get("size") ?? 50);
      const matching = state.companies.filter(
        (company) =>
          company.name.toLowerCase().includes(search) &&
          (preference === null || company.preference === preference),
      );
      const companies = matching.slice(page * size, (page + 1) * size);
      return HttpResponse.json({ companies, page, size, total: matching.length });
    }),
    http.post(`${origin()}/api/companies`, async ({ request }) => {
      const details = (await request.json()) as CompanyDetailsRequest;
      const found = violations(details, state.refuse);
      if (found.length > 0) return invalid(found);
      const company = withDetails(aCompany(), details);
      state.companies.push(company);
      return HttpResponse.json(company, { status: 201 });
    }),
    http.get(`${origin()}/api/companies/:id`, ({ params }) => {
      const company = find(params.id);
      return company ? HttpResponse.json(company) : problem(404, "company-not-found");
    }),
    http.put(`${origin()}/api/companies/:id`, async ({ request, params }) => {
      const company = find(params.id);
      if (!company) return problem(404, "company-not-found");
      const body = (await request.json()) as UpdateCompanyRequest;
      const found = violations(body.details, state.refuse);
      if (found.length > 0) return invalid(found);
      if (body.basedOnVersion !== company.version) return problem(409, "version-conflict");
      return replace(withDetails(company, body.details));
    }),
    http.put(`${origin()}/api/companies/:id/preference`, async ({ request, params }) => {
      const company = find(params.id);
      if (!company) return problem(404, "company-not-found");
      const body = (await request.json()) as CompanyPreferenceRequest;
      if (body.basedOnVersion !== company.version) return problem(409, "version-conflict");
      const reason = body.preference === "NONE" ? null : (body.reason ?? null);
      return replace({ ...company, preference: body.preference, preferenceReason: reason });
    }),
    http.delete(`${origin()}/api/companies/:id`, ({ request, params }) => {
      const company = find(params.id);
      if (!company) return problem(404, "company-not-found");
      if (company.applicationCount > 0) return problem(409, "has-applications");
      const contacts = state.contacts.filter((contact) => contact.companyId === company.id);
      if (request.headers.get("Jofi-Confirmation") !== DELETE_TOKEN) {
        state.deleteCalls.push("first");
        return json(
          {
            type: "urn:jofi:problem:shared:confirmation-required",
            status: 428,
            confirmationToken: DELETE_TOKEN,
            expiresAt: "2026-09-30T12:05:00Z",
            operation: "companies.delete",
            targets: [company.id],
            effect: { kind: "company", name: company.name, counts: { contacts: contacts.length } },
          },
          428,
        );
      }
      state.deleteCalls.push("confirmed");
      state.companies = state.companies.filter((other) => other.id !== company.id);
      state.contacts = state.contacts.filter((contact) => contact.companyId !== company.id);
      return new HttpResponse(null, { status: 204 });
    }),
    http.get(`${origin()}/api/contacts`, ({ request }) => {
      const companyId = new URL(request.url).searchParams.get("companyId");
      const contacts = state.contacts.filter((contact) => contact.companyId === companyId);
      return HttpResponse.json({ contacts, page: 0, size: 200, total: contacts.length });
    }),
    // The applications use cases are not there yet (#82): the contract answers 501.
    http.get(`${origin()}/api/applications`, () =>
      json({ title: "Not Implemented", status: 501, detail: "Applications are not available yet" }, 501),
    ),
  ];

  return { state, handlers };
}
