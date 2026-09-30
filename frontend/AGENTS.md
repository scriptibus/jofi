<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Jofi frontend: agent guide

React 19 + Vite 8 + TypeScript 7 (native `tsc`), Tailwind v4 driven by our tokens,
React Aria Components behind our own `src/ui` library, Paraglide JS 2 for DE/EN.
Spec: `docs/spec/04-tech-stack-proposal.md` (3.4, 4.5, 4.6, 4.6a, 4.8a, 4.10) and
`docs/spec/06-design-brief.md` (direction B · Stall).

## Commands (run in `frontend/`)

| Command | What it does |
|---|---|
| `pnpm install` | Install (lockfile committed; `minimumReleaseAge` 7 days, also re-checked for every lockfile entry; build scripts blocked unless allowlisted) |
| `pnpm dev` | Generate the API client (`predev`), then the dev server on http://localhost:5173 |
| `pnpm build` / `pnpm preview` | Production build / serve it on :4173 |
| `pnpm lint` | Biome lint + format check (a11y rules, import rules, token plugin) |
| `pnpm format` | Biome autofix + format |
| `pnpm api` | Generate the typed API client from `../api/openapi.json` into `src/api/generated/` (orval) |
| `pnpm typecheck` | Compile Paraglide messages, generate the API client, then `tsc` for app and node configs |
| `pnpm test` | Generate the API client, then Vitest (jsdom + Testing Library, MSW for HTTP), then `pnpm test:stack` |
| `pnpm test:stack` | `node --test` for the e2e stack's own code (fake AI provider, fixtures) |
| `pnpm e2e` | Full-stack Playwright (see below): builds the image, starts and seeds the `e2e` compose stack, checks it, tests light/dark/phone incl. axe WCAG 2.2 AA, tears it down |
| `pnpm e2e:up` / `pnpm e2e:down` | Start (build, seed, check) / remove the e2e stack; while it runs, `E2E_REUSE_STACK=1 pnpm e2e` skips the start |
| `pnpm e2e:preview` | The same Playwright tests against `vite preview` only (no backend; stack-only tests skip) |
| `pnpm run license-check` | Every installed package against the AGPL-compatible allowlist |
| `pnpm check` | lint + typecheck + test + build + license-check (run before every commit) |

pnpm is pinned in `package.json` `packageManager` as `pnpm@<version>+sha512.<hex>` (the hex SHA-512 of
the npm tarball, which Corepack verifies); `pnpm-lock.yaml` records the same version. CI uses
`pnpm/setup`, the Dockerfile uses Corepack. Without either locally, `npx pnpm@<version>` works.

First e2e run on a machine: `pnpm exec playwright install chromium`.
Screenshots: `SCREENSHOT_DIR=/some/dir pnpm e2e` writes one PNG per project and step.
On Podman: `COMPOSE=podman-compose CONTAINER=podman pnpm e2e`; `SKIP_BUILD=1` reuses the `localhost/jofi:e2e` image.

## Rules

1. **Current versions and docs only.** Before adding a package or API, check the latest
   stable version on npm and read its current docs; pin exact versions; no deprecated
   APIs (`noDeprecatedImports` is on). List version + doc link in the PR.
2. **Tokens only.** Raw colours, shadows and fonts live in `src/styles/tokens.css`, nowhere
   else. Use the generated utilities (`bg-surface`, `text-muted`, `border-line`,
   `bg-accent text-accent-fg`, `text-good/warn/bad`, `rounded`, `shadow-card`,
   `font-display/body/data`, `text-display/h2/h3/lede/body/eyebrow`, `ease-spring`,
   `transition-spring`). No Tailwind arbitrary values (`p-[13px]`, `bg-[#fff]`,
   `p-(--x)`); a Biome plugin (`lint/no-raw-style-values.grit`) fails the lint. Need a new
   value? Add a token.
3. **Only `src/ui` in features.** Feature code imports components from `src/ui` (the
   barrel `src/ui/index.ts`). `react-aria-components`, `react-aria`, `react-stately` may
   only be imported inside `src/ui` (Biome `noRestrictedImports`). Missing a component?
   Build it in `src/ui` with React Aria + tokens, with a test.
4. **i18n for every user-facing string.** Add the key to both `messages/en.json` and
   `messages/de.json`, use `m.key()` from `src/paraglide/messages.js`. An unknown key is a
   type error; a key missing in one locale fails `pnpm typecheck` (`src/i18n-parity.ts`).
5. **Accessible by construction.** Every interactive element has a role and an accessible
   name (visible label preferred). Headings in order, landmarks (`header`, `main`), `lang`
   on foreign-language text. Colour is never the only signal. Respect reduced motion
   (`motion-safe:` for decorative animation; the global reduce rule is a backstop).
   Don't transition colours with the spring curve (low-contrast in-between frames).
6. **Tests find things like users do.** `getByRole`/`getByLabelText`/`getByText`;
   `data-testid` only when no role or label fits. No timing waits (`waitForTimeout`,
   sleeps); rely on web-first assertions.
7. **Verify in a headless browser.** For UI changes, run `pnpm e2e` (axe must report zero
   violations in light, dark and phone) and attach screenshots (`SCREENSHOT_DIR`) to the PR.
   Tests run against the full stack; never mock the backend in e2e (use seed data and fake AI scenarios).
8. **SPDX headers** on every source file: the copyright + licence lines from the root
   `AGENTS.md` section 4, as `//` comments in TS/JS, `/* */` in CSS and `<!-- -->` in HTML/MD.
   JSON can't carry them (REUSE.toml covers it).
9. **Licenses.** New dependencies must pass `pnpm run license-check`. Anything outside the
   allowlist needs human review, then an entry with a reason in
   `scripts/license-exceptions.json`.
10. **Talk to the backend only through the generated client.** Feature code imports hooks,
    request functions, types and Zod schemas from `src/api/generated/` (`jofi.ts`, `jofi.zod.ts`)
    and `ApiProblemError` from `src/api/fetcher.ts`; no hand-written `fetch` calls or DTO types.
    Errors arrive as `ApiProblemError` carrying the RFC 9457 `problem`. `apiFetch` echoes the
    `XSRF-TOKEN` cookie in `X-XSRF-TOKEN` on unsafe requests (CSRF, ADR-0035); call
    `GET /api/auth/session` on start and after login/logout to get a fresh cookie. The client is regenerated
    by every `pnpm typecheck`/`test`/`build` and never committed, so a backend contract change
    shows up as a type error. To change the API, change the backend, regenerate `api/openapi.json`
    there (`./gradlew :adapters:web:updateOpenApiSpec`), then `pnpm api`. Test hooks against MSW
    handlers (`msw/node`), as in `src/api/client.test.tsx`.
11. **Install scripts.** Dependencies may not run install scripts unless listed under
    `allowBuilds` in `pnpm-workspace.yaml` (after review).

## Full-stack e2e (ADR-0036)

`pnpm e2e` runs `../scripts/e2e-stack.sh test`: `compose.yaml` + `compose.e2e.yaml` with the `e2e`
profile. `app`, `worker` and `db` are the production services on an internal network without internet,
next to `fake-ai` and `wiremock`; `edge` publishes the app on `http://127.0.0.1:8180` (`JOFI_E2E_PORT`).
The script ignores `.env`, seeds, checks the stack (seed idempotent, fake AI reachable, no internet), runs Playwright
with `JOFI_E2E_BASE_URL`, and on failure writes the container logs to `test-results/e2e-stack.log`.
Nothing here ships in the image; `../scripts/e2e-isolation-test.sh` proves it in CI (job `e2e`).

**Seed data.** Two idempotent formats; later milestones extend them:

- **API steps** (preferred): functions in `tests/stack/seed/api.ts`, called from the `seed` setup
  project (`tests/stack/seed.setup.ts`), which every browser project depends on. They call Jofi's
  public API like a user. Today: login (#16) with `E2E_PASSWORD`, saved as storage state
  (`playwright/.auth/e2e.json`), so every test starts logged in. First run happens before, through
  the UI, in the `first-run` project (`tests/stack/first-run.setup.ts`, German, a wrong setup token
  first) with the one-time setup token (the script reads `/data/secrets/setup-token` from the app
  container and passes it as `JOFI_E2E_SETUP_TOKEN`); the seed step still does it by API when that
  project is skipped. Make each step safe to repeat (check before create).
- **Wrong passwords.** All requests reach the app from the one `edge` address, so the login backoff
  sees one client. Browser projects never enter a password. Tests that do (wrong passwords, the 429
  backoff, logout, password change) live in `tests/auth/` and run in the `auth` project, after every
  other project, one at a time; each ends with a successful check (which resets the backoff) and a
  changed password is changed back.
- **SQL steps** for data without an API yet: `tests/stack/seed/db/NNNN-<name>.sql`, applied in name order,
  each in one transaction, by the `seed` service after Flyway ran. Use fixed ids and
  `ON CONFLICT ... DO UPDATE` so reruns converge, and append a `changelog_entry` (actor `SYSTEM`, name
  `e2e-seed`) only when a row is first created. Move a step to the API once its endpoint exists.
  `0001-ai-provider.sql` seeds the fake AI as `OPENAI_COMPATIBLE` provider (`http://fake-ai:8080/v1`),
  assigns model `fake-<task>` to every text task and `EMBEDDING`, with capabilities.

**Fake AI** (`tests/stack/fake-ai`, OpenAI-compatible: chat completions with tools and SSE streaming,
embeddings, models). The real gateway, privacy filter, Spring AI adapter and SSRF guard run in front
of it. Pick responses with fixture files `fixtures/<task>/<scenario>.json` (`<task>` = AiTask in kebab
case). A request's model `fake-<task>` selects the task; a `[[scenario:<name>]]` marker anywhere in the
messages (e.g. in text a test types or in seeded data) selects the scenario, else `default`. Formats
(`description` is required, plus exactly one of `turns`, `dimensions`, `error`):

```text
{ "description": "…", "turns": [
    { "toolCalls": [{ "name": "find_company", "arguments": { "name": "ACME GmbH" } }] },
    { "text": "…", "chunks": ["optional ", "stream ", "fragments"], "finishReason": "stop",
      "usage": { "inputTokens": 10, "outputTokens": 5 } } ] }
{ "description": "…", "dimensions": 16 }
{ "description": "…", "error": { "status": 429, "type": "requests", "code": "rate_limit_exceeded",
    "message": "…", "retryAfterSeconds": 2 } }
```

The n-th answer in a conversation is `turns[n]` (n = assistant messages so far; the last turn repeats),
so a tool round trip is two turns. Without `chunks` a stream sends one delta per word; without `usage`
tokens are about 4 characters each. Embeddings are unit vectors from each text's SHA-256. Unknown
models, scenarios, or tool calls the request does not offer are loud 4xx errors. Add a scenario per
behaviour a test needs (errors included: `rate-limited`, `unavailable`, `auth-failed`,
`context-too-long`, `truncated` exist for `chat`) and cover new fixture logic in `fake-ai.test.ts`.

**WireMock** (`tests/stack/wiremock/mappings/*.json`): stubs for external sources, reachable in the
stack as `http://wiremock:8080`. Only a placeholder today; each scanner source (M4) adds its mappings.

## Layout

```
src/
  app/            feature code: App.tsx (wiring), router.tsx (all routes + auth guard),
                  queryClient.ts (global error handling), problems.ts (problem types -> messages),
                  notices.ts, preferences.tsx, auth/ (login, first run, password change),
                  shell/ (layout, navigation, logout, notices), pages/ (placeholders, settings, share)
  ui/             our component library (Alert, Button, ConfirmDialog, DonkeyLogo, EmptyState,
                  NavItem/TextLink, SegmentedControl, TextField, Form, icons, appearance)
  pwa/            manifest.ts: web app manifest + theme-color from tokens.css (used by vite.config.ts)
  styles/         tokens.css (the only raw values) + app.css (Tailwind, fonts, base)
  api/            fetcher.ts (orval mutator, ApiProblemError), confirmation.ts (two-step flow)
                  + generated/ (orval, git-ignored)
  test/           Vitest setup, fakeAuthBackend.ts (MSW handlers mirroring the auth API)
  paraglide/      generated by Paraglide (git-ignored)
  i18n-parity.ts  compile-time DE/EN key parity
messages/         de.json, en.json
project.inlang/   Paraglide/inlang settings (plugin loaded from node_modules, no CDN)
public/           appearance-boot.js (applies theme/accent before first paint), favicon, icons/ (PWA)
tests/e2e/        Playwright specs for the browser projects (run against the full stack by `pnpm e2e`)
tests/auth/       password flows (wrong passwords, backoff, logout, password change): project `auth`
tests/stack/      the e2e stack: fake-ai/, seed/, first-run.setup.ts, seed.setup.ts, wiremock/, edge/
scripts/          license check, generate-pwa-icons.ts
orval.config.ts   API client generation (input: ../api/openapi.json)
lint/             Biome GritQL plugins
```

## App shell (ADR-0037)

- **Routes** are code-based in `src/app/router.tsx`. A new page is a `createRoute` under `appRoute`
  (behind the session guard) and, if it belongs in the menu, an entry in `shell/navigation.ts`. Use
  `getRouteApi("<id>")` in page components to read search params without importing the router.
- **Errors:** requests that fail go through the QueryClient: 401 `not-logged-in` returns to login,
  everything else becomes a global notice. A form that shows its own errors sets
  `meta: { errorHandledLocally: true }` on its mutation. Map new problem types in `problems.ts`.
- **Deletes and outward-facing actions** (ADR-0039): the server answers the first call with 428, a
  token and a structured `effect` (`kind`, `name`, `counts`). Run the generated request function
  through `useConfirmation()` (`src/app/useConfirmation.tsx`) and render its `dialog`:
  `confirmed((options) => deleteThing(id, options), { expect: { operation: "things.delete", targets: [id] },
  describe: (effect) => m.thing_delete_confirm({ name: effect.name, parts: effect.counts.parts ?? 0 }) })`.
  The dialog text comes from the server's effect (what it would really run); a 428 for another
  operation or target throws `ConfirmationMismatchError` without asking. The call is repeated with the
  `Jofi-Confirmation` header only on a yes; `cancelled` means nothing ran (also when a newer question
  replaced it or the page unmounted). A 412 (stale token) maps to a notice in `problems.ts`.
- **Page titles:** call `usePageTitle()` (or use `PageHeader`); focus moves to `<main>` on navigation.
- **PWA:** the service worker precaches the shell only and has no runtime caching. Never add runtime
  caching for `/api/` (personal data). After changing the donkey or the light palette, run
  `node scripts/generate-pwa-icons.ts` and commit the PNGs.

Theme/accent: `html[data-theme=light|dark]` (absent = OS), `html[data-accent=cobalt|teal|plum|ink]`
(absent = saffron). Use `useTheme()` / `useAccent()` from `src/ui`; they persist to
localStorage (wrapped in try/catch). If you change keys or values, update
`public/appearance-boot.js` too (a unit test checks they match).
