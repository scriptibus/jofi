// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { ConfirmationMismatchError } from "../../api/confirmation";
import { ApiProblemError } from "../../api/fetcher";
import { aCompany } from "../../test/fakeCompanyBackend";
import { parseCompaniesSearch } from "./CompaniesPage";
import { describeDelete } from "./CompanyDetailPage";
import { fieldErrorsOf, formValues, isWebAddress, parseLocations, toDetailsRequest } from "./company";
import { describeCompanyError, isVersionConflict } from "./companyProblems";

describe("company form values", () => {
  it("round-trips a company through the form", () => {
    const company = aCompany({
      name: "ACME",
      website: "https://acme.example",
      size: "SMALL",
      locations: ["Berlin", "Remote"],
      researchNotes: "# Notes",
    });
    expect(toDetailsRequest(formValues(company))).toEqual({
      name: "ACME",
      website: "https://acme.example",
      careersPage: null,
      industry: null,
      size: "SMALL",
      locations: ["Berlin", "Remote"],
      researchNotes: "# Notes",
    });
  });

  it("trims text and sends empty fields as null, because the server stores no blank or padded text", () => {
    const request = toDetailsRequest({
      ...formValues(),
      name: "  ACME  ",
      industry: "   ",
      researchNotes: "\n notes \n",
    });
    expect(request).toMatchObject({ name: "ACME", industry: null, researchNotes: "notes", size: null });
  });

  it("reads one location per line, trimmed, without blanks or case-insensitive repeats", () => {
    expect(parseLocations(" Berlin \n\nberlin\nHamburg, Germany\n  ")).toEqual([
      "Berlin",
      "Hamburg, Germany",
    ]);
  });

  it("accepts absolute http(s) addresses only", () => {
    expect(isWebAddress("https://bücher.example/jobs?x=1")).toBe(true);
    expect(isWebAddress("HTTP://acme.example")).toBe(true);
    expect(isWebAddress("acme.example")).toBe(false);
    expect(isWebAddress("javascript:alert(1)")).toBe(false);
    expect(isWebAddress("https://user@acme.example")).toBe(false);
  });
});

describe("company problems", () => {
  const problem = (status: number, type: string, extra = {}) =>
    new ApiProblemError(status, { type: `urn:jofi:problem:companies:${type}`, status, ...extra });

  it("maps 400 violations to field errors in the user's language", () => {
    const error = problem(400, "invalid-company", {
      violations: [
        { field: "name", problem: "TOO_LONG" },
        { field: "website", problem: "INVALID_URL" },
        { field: "locations", problem: "SOMETHING_NEW" },
      ],
    });
    expect(fieldErrorsOf(error)).toEqual({
      name: "This is too long.",
      website: "Enter a full web address starting with https:// or http://.",
      locations: "This value is not valid.",
    });
    expect(fieldErrorsOf(problem(409, "version-conflict"))).toBeUndefined();
  });

  it("tells a version conflict, remaining applications and a mismatched confirmation apart", () => {
    expect(isVersionConflict(problem(409, "version-conflict"))).toBe(true);
    expect(isVersionConflict(problem(409, "has-applications"))).toBe(false);
    expect(describeCompanyError(problem(409, "has-applications")).message).toContain(
      "still has applications",
    );
    expect(describeCompanyError(problem(404, "company-not-found")).message).toContain("does not exist");
    const mismatch = new ConfirmationMismatchError(
      { operation: "companies.delete", targets: ["a"] },
      { operation: "contacts.delete", targets: ["b"] },
    );
    expect(describeCompanyError(mismatch).message).toContain("Nothing was deleted");
  });

  it("describes the delete from the server's effect, with the number of contacts", () => {
    expect(describeDelete({ kind: "company", name: "ACME", counts: { contacts: 0 } })).toBe(
      "ACME will be deleted. This cannot be undone.",
    );
    expect(describeDelete({ kind: "company", name: "ACME", counts: { contacts: 1 } })).toContain(
      "its 1 contact.",
    );
    expect(describeDelete({ kind: "company", name: "ACME", counts: { contacts: 3 } })).toContain(
      "its 3 contacts.",
    );
  });
});

describe("companies list search params", () => {
  it("keeps only valid values from the user-editable query string", () => {
    expect(parseCompaniesSearch({ q: " acme ", preference: "FAVOURITE", page: 2 })).toEqual({
      q: "acme",
      preference: "FAVOURITE",
      page: 2,
    });
    expect(parseCompaniesSearch({ q: "  ", preference: "EVIL", page: -1 })).toEqual({});
    expect(parseCompaniesSearch({ page: 1.5 })).toEqual({});
  });
});
