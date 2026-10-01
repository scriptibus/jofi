// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createMemoryHistory } from "@tanstack/react-router";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { fakeAuthBackend } from "../../test/fakeAuthBackend";
import { type FakeSetupState, fakeSetupBackend } from "../../test/fakeSetupBackend";
import { App, createApp } from "../App";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const local = {
  id: "00000000-0000-4000-8000-0000000000c1",
  kind: "OPENAI_COMPATIBLE" as const,
  displayName: "Home server",
  baseUrl: "https://ai.example.com/v1",
  apiKeySet: false,
};
const cloud = {
  id: "00000000-0000-4000-8000-0000000000c2",
  kind: "OPENAI" as const,
  displayName: "OpenAI",
  baseUrl: null,
  apiKeySet: true,
};
const price = (model: string, input: number, output: number) => ({
  model,
  inputMicrosPerMillion: input,
  outputMicrosPerMillion: output,
  updatedAt: "2026-10-02T12:00:00Z",
});

function start(setup: Partial<FakeSetupState> = {}) {
  const auth = fakeAuthBackend({ authenticated: true });
  const fake = fakeSetupBackend({ providers: [{ ...local }, { ...cloud }], ...setup });
  server.use(...fake.handlers, ...auth.handlers);
  render(<App app={createApp(createMemoryHistory({ initialEntries: ["/settings"] }))} />);
  return { setup: fake.state, user: userEvent.setup() };
}

const INPUT = "Input price per million tokens (US dollars)";
const OUTPUT = "Output price per million tokens (US dollars)";

async function fillPrice(
  user: ReturnType<typeof userEvent.setup>,
  form: HTMLElement,
  model: string | null,
  input: string,
  output: string,
) {
  if (model !== null) await user.type(within(form).getByLabelText("Model name"), model);
  await user.clear(within(form).getByLabelText(INPUT));
  await user.type(within(form).getByLabelText(INPUT), input);
  await user.clear(within(form).getByLabelText(OUTPUT));
  await user.type(within(form).getByLabelText(OUTPUT), output);
}

async function priceCard() {
  const heading = await screen.findByRole("heading", { name: "Prices for your own models" });
  const section = heading.closest("section");
  if (!section) throw new Error("The prices card is missing");
  return within(section).findByRole("region", { name: "Prices of Home server" });
}

describe("Settings > AI: prices for your own models", () => {
  it("offers prices for OpenAI-compatible providers only", async () => {
    start({ prices: new Map([[local.id, [price("llama3.1:8b", 0, 0)]]]) });
    const card = await priceCard();
    expect(within(card).getByText("llama3.1:8b")).toBeVisible();
    const section = card.closest("section") as HTMLElement;
    expect(within(section).queryByRole("region", { name: "Prices of OpenAI" })).not.toBeInTheDocument();
  });

  it("says what to do without an OpenAI-compatible provider", async () => {
    start({ providers: [{ ...cloud }] });
    expect(await screen.findByText(/Add an OpenAI-compatible provider above/)).toBeVisible();
  });

  it("shows the stored prices to the micro and says they apply from now on", async () => {
    start({
      prices: new Map([[local.id, [price("meta/llama-3.1-8b", 150_000, 600_000), price("tiny", 125, 0)]]]),
    });
    const card = await priceCard();
    expect(within(card).getByText("Input $0.15, output $0.60 per million tokens")).toBeVisible();
    expect(within(card).getByText("Input $0.000125, output $0.00 per million tokens")).toBeVisible();
    expect(screen.getByText(/A price applies to calls from now on/)).toBeVisible();
  });

  it("adds a price in micros, and 0 is a price", async () => {
    const { user, setup } = start();
    const card = await priceCard();
    const form = within(card).getByRole("region", { name: "Add a price" });

    await fillPrice(user, form, "llama3.1:8b", "0", "0");
    await user.click(within(form).getByRole("button", { name: "Save price" }));

    expect(await within(card).findByText("Input $0.00, output $0.00 per million tokens")).toBeVisible();
    expect(within(card).getByText("Price of llama3.1:8b saved.")).toBeVisible();
    expect(setup.priceBodies).toEqual([
      { model: "llama3.1:8b", inputMicrosPerMillion: 0, outputMicrosPerMillion: 0 },
    ]);
    expect(within(form).getByLabelText("Model name")).toHaveValue("");
  });

  it("converts dollars per million tokens to micros", async () => {
    const { user, setup } = start();
    const card = await priceCard();
    const form = within(card).getByRole("region", { name: "Add a price" });

    await fillPrice(user, form, "meta/llama-3.1-8b", "0.15", "0.6");
    await user.click(within(form).getByRole("button", { name: "Save price" }));

    expect(await within(card).findByText("Input $0.15, output $0.60 per million tokens")).toBeVisible();
    expect(setup.priceBodies).toEqual([
      { model: "meta/llama-3.1-8b", inputMicrosPerMillion: 150_000, outputMicrosPerMillion: 600_000 },
    ]);
  });

  it("changes a price with the model name fixed", async () => {
    const { user, setup } = start({
      prices: new Map([[local.id, [price("llama3.1:8b", 1_000_000, 2_000_000)]]]),
    });
    const card = await priceCard();
    await user.click(within(card).getByRole("button", { name: "Edit the price of llama3.1:8b" }));
    const form = within(card).getByRole("region", { name: "Change the price of llama3.1:8b" });
    expect(within(form).getByLabelText("Model name")).toHaveAttribute("readonly");

    await fillPrice(user, form, null, "1", "3");
    await user.click(within(form).getByRole("button", { name: "Save price" }));

    expect(await within(card).findByText("Input $1.00, output $3.00 per million tokens")).toBeVisible();
    expect(setup.prices.get(local.id)).toHaveLength(1);
    expect(setup.priceBodies).toEqual([
      { model: "llama3.1:8b", inputMicrosPerMillion: 1_000_000, outputMicrosPerMillion: 3_000_000 },
    ]);
  });

  it("removes a price and says what happens next", async () => {
    const { user, setup } = start({ prices: new Map([[local.id, [price("llama3.1:8b", 1, 1)]]]) });
    const card = await priceCard();

    await user.click(within(card).getByRole("button", { name: "Remove the price of llama3.1:8b" }));

    expect(
      await within(card).findByText(/Price of llama3.1:8b removed. Its next calls have no price./),
    ).toBeVisible();
    expect(within(card).queryByText("llama3.1:8b")).not.toBeInTheDocument();
    expect(setup.prices.get(local.id)).toEqual([]);
    expect(within(card).getByText("No prices set for this provider yet.")).toBeVisible();
  });

  it("shows the server's violations at their fields", async () => {
    start();
    server.use(
      http.put(`${window.location.origin}/api/setup/providers/:id/model-prices`, () =>
        HttpResponse.json(
          {
            type: "urn:jofi:problem:setup:invalid-input",
            status: 400,
            violations: [
              { field: "model", problem: "TOO_LONG" },
              { field: "outputMicrosPerMillion", problem: "OUT_OF_RANGE" },
            ],
          },
          { status: 400, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    const user = userEvent.setup();
    const card = await priceCard();
    const form = within(card).getByRole("region", { name: "Add a price" });

    await fillPrice(user, form, "x", "1", "2");
    await user.click(within(form).getByRole("button", { name: "Save price" }));

    const model = await within(form).findByLabelText("Model name");
    expect(model).toHaveAttribute("aria-invalid", "true");
    expect(model).toHaveAccessibleDescription(/This is too long./);
    expect(within(form).getByLabelText(OUTPUT)).toHaveAttribute("aria-invalid", "true");
    expect(within(form).getByLabelText(OUTPUT)).toHaveAccessibleDescription(/This value is out of range./);
  });

  it("explains a refused price of a provider that is not OpenAI-compatible", async () => {
    start();
    server.use(
      http.put(`${window.location.origin}/api/setup/providers/:id/model-prices`, () =>
        HttpResponse.json(
          { type: "urn:jofi:problem:setup:price-not-allowed", status: 409 },
          { status: 409, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    const user = userEvent.setup();
    const card = await priceCard();
    const form = within(card).getByRole("region", { name: "Add a price" });

    await fillPrice(user, form, "gpt", "1", "1");
    await user.click(within(form).getByRole("button", { name: "Save price" }));

    expect(
      await within(card).findByText(/Only models of an OpenAI-compatible provider take a price/),
    ).toBeVisible();
  });
});
