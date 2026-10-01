// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { QueryClient } from "@tanstack/react-query";
import { HttpResponse, http } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { getGetApplicationQueryKey } from "../../api/generated/jofi";
import { anApplication, fakeApplicationBackend } from "../../test/fakeApplicationBackend";
import { aContact } from "../../test/fakeCompanyBackend";
import { linkCreatedContact, pickerChoices, withContact, withoutContact } from "./applicationContacts";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const company = crypto.randomUUID();
const ada = aContact(company, { name: "Ada" });
const alan = aContact(company, { name: "Alan" });
const grace = aContact("", { name: "Grace", companyId: null });

describe("linked contact sets", () => {
  it("adds a contact once at the end and removes it", () => {
    expect(withContact([ada.id], alan.id)).toEqual([ada.id, alan.id]);
    expect(withContact([ada.id, alan.id], ada.id)).toEqual([ada.id, alan.id]);
    expect(withoutContact([ada.id, alan.id], ada.id)).toEqual([alan.id]);
    expect(withoutContact([alan.id], ada.id)).toEqual([alan.id]);
  });
});

describe("picker choices", () => {
  it("puts the company's contacts first, keeps the server's order and leaves out linked ones", () => {
    const choices = pickerChoices([alan, ada], [grace, ada, alan], [ada.id]);
    expect(choices.atCompany.map((contact) => contact.name)).toEqual(["Alan"]);
    expect(choices.others.map((contact) => contact.name)).toEqual(["Grace"]);
  });

  it("offers nothing twice and nothing already linked", () => {
    const choices = pickerChoices([], [ada, alan], [ada.id, alan.id]);
    expect(choices).toEqual({ atCompany: [], others: [] });
  });
});

describe("linking a created contact", () => {
  it("adds it to the current links with the current version and stores the answer", async () => {
    const application = anApplication(company, { contactIds: [ada.id], version: 7 });
    const backend = fakeApplicationBackend({ applications: [application] });
    server.use(...backend.handlers);
    const queryClient = new QueryClient();

    const saved = await linkCreatedContact(queryClient, application.id, alan.id);
    expect(backend.state.contactLinks).toEqual([{ contactIds: [ada.id, alan.id], basedOnVersion: 7 }]);
    expect(queryClient.getQueryData(getGetApplicationQueryKey(application.id))).toEqual(saved);
  });

  it("tries once more when another save came in between", async () => {
    const application = anApplication(company, { contactIds: [], version: 1 });
    const backend = fakeApplicationBackend({ applications: [application] });
    let reads = 0;
    // The first read answers an old version, as if another save landed right after it.
    server.use(...backend.handlers);
    server.use(
      http.get(`${window.location.origin}/api/applications/:id`, () => {
        reads += 1;
        const current = backend.state.applications[0];
        return HttpResponse.json(reads === 1 ? { ...current, version: 0 } : current);
      }),
    );

    await linkCreatedContact(new QueryClient(), application.id, ada.id);
    expect(reads).toBe(2);
    expect(backend.state.contactLinks).toEqual([{ contactIds: [ada.id], basedOnVersion: 1 }]);
  });
});
