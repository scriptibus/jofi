// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { ConfirmationMismatchError } from "../../api/confirmation";
import { ApiProblemError } from "../../api/fetcher";
import { aContact } from "../../test/fakeCompanyBackend";
import { describeContactDelete } from "./ContactDetailPage";
import { parseContactsSearch, parseNewContactSearch } from "./ContactsPage";
import {
  channelValueProblem,
  contactFieldErrorsOf,
  formValues,
  newChannel,
  toDetailsRequest,
} from "./contact";
import { describeContactError, isContactVersionConflict } from "./contactProblems";

const COMPANY = "0b7c8f2e-3a41-4d6e-9f10-2c3d4e5f6a7b";

describe("contact form values", () => {
  it("round-trips a contact with its channels in order", () => {
    const contact = aContact(COMPANY, {
      name: "Ada",
      role: null,
      channels: [
        { kind: "PHONE", value: "+49 30 1", label: "mobile" },
        { kind: "EMAIL", value: "ada@example.org", label: null },
      ],
      relationshipNotes: "Met at **FOSDEM**",
    });
    expect(toDetailsRequest(formValues(contact))).toEqual({
      name: "Ada",
      role: null,
      companyId: COMPANY,
      channels: [
        { kind: "PHONE", value: "+49 30 1", label: "mobile" },
        { kind: "EMAIL", value: "ada@example.org", label: null },
      ],
      relationshipNotes: "Met at **FOSDEM**",
    });
  });

  it("trims text, sends empty fields as null and keeps blank channel rows so positions match", () => {
    const request = toDetailsRequest({
      ...formValues(),
      name: "  Ada  ",
      role: "  ",
      channels: [
        { ...newChannel("EMAIL"), value: "   " },
        { ...newChannel("WEB"), value: " https://ada.example ", label: "  " },
      ],
    });
    expect(request).toEqual({
      name: "Ada",
      role: null,
      companyId: null,
      channels: [
        { kind: "EMAIL", value: "", label: null },
        { kind: "WEB", value: "https://ada.example", label: null },
      ],
      relationshipNotes: null,
    });
  });

  it("preselects a company for a new contact", () => {
    expect(formValues(undefined, COMPANY).companyId).toBe(COMPANY);
  });

  it("gives every channel row its own key", () => {
    expect(newChannel().key).not.toBe(newChannel().key);
  });
});

describe("channel value rules (the server's, checked before sending)", () => {
  it("wants an @ with text on both sides and no spaces in an email address", () => {
    expect(channelValueProblem("EMAIL", "ada@example.org")).toBeNull();
    expect(channelValueProblem("EMAIL", "ada@")).toMatch(/email address/);
    expect(channelValueProblem("EMAIL", "a da@example.org")).toMatch(/email address/);
  });

  it("wants a digit of any script in a phone number", () => {
    expect(channelValueProblem("PHONE", "+٤٩")).toBeNull();
    expect(channelValueProblem("PHONE", "call me")).toMatch(/digit/);
  });

  it("wants an absolute http(s) web address and accepts any other text", () => {
    expect(channelValueProblem("WEB", "linkedin.com/in/ada")).toMatch(/https:\/\//);
    expect(channelValueProblem("OTHER", "@ada on Signal")).toBeNull();
    expect(channelValueProblem("EMAIL", "   ")).toBeNull();
  });
});

describe("contact problems", () => {
  const problem = (status: number, type: string, extra = {}) =>
    new ApiProblemError(status, { status, type: `urn:jofi:problem:companies:${type}`, ...extra });

  it("maps violations to their fields, channel positions included", () => {
    const error = problem(400, "invalid-contact", {
      violations: [
        { field: "channels[2].value", problem: "INVALID_EMAIL" },
        { field: "companyId", problem: "NOT_FOUND" },
        { field: "name", problem: "SOMETHING_NEW" },
      ],
    });
    expect(contactFieldErrorsOf(error)).toEqual({
      "channels[2].value": "Enter an email address with an @, without spaces.",
      companyId: "This company does not exist (any more). Choose another one.",
      name: "This value is not valid.",
    });
    expect(contactFieldErrorsOf(problem(404, "contact-not-found"))).toBeUndefined();
  });

  it("describes version conflicts, missing contacts and mismatched confirmations", () => {
    const conflict = problem(409, "contact-version-conflict");
    expect(isContactVersionConflict(conflict)).toBe(true);
    expect(describeContactError(conflict).message).toMatch(/changed elsewhere/);
    expect(describeContactError(problem(404, "contact-not-found")).message).toMatch(/does not exist/);
    const mismatch = new ConfirmationMismatchError(
      { operation: "contacts.delete", targets: ["a"] },
      { operation: "companies.delete", targets: ["b"] },
    );
    expect(describeContactError(mismatch).message).toMatch(/Nothing was deleted/);
  });

  it("words the delete question from the server's effect", () => {
    const effect = (applications?: number) => ({
      kind: "contact",
      name: "Ada",
      counts: applications === undefined ? {} : { applications },
    });
    expect(describeContactDelete(effect())).toMatch(/^Ada will be deleted with all personal data/);
    expect(describeContactDelete(effect(1))).toMatch(/The link to 1 application is removed/);
    expect(describeContactDelete(effect(3))).toMatch(/The links to 3 applications are removed/);
  });
});

describe("contacts search in the URL", () => {
  it("keeps only a company id and a page: no names", () => {
    expect(parseContactsSearch({ company: COMPANY, page: 2, q: "Ada" })).toEqual({
      company: COMPANY,
      page: 2,
    });
    expect(parseContactsSearch({ company: "Ada Lovelace", page: -1 })).toEqual({});
    expect(parseNewContactSearch({ company: COMPANY, page: 3 })).toEqual({ company: COMPANY });
  });
});
