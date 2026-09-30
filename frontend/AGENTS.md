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
| `pnpm test` | Generate the API client, then Vitest (jsdom + Testing Library, MSW for HTTP) |
| `pnpm e2e` | Playwright: builds, runs `vite preview`, tests light/dark/phone incl. axe WCAG 2.2 AA |
| `pnpm run license-check` | Every installed package against the AGPL-compatible allowlist |
| `pnpm check` | lint + typecheck + test + build + license-check (run before every commit) |

pnpm is pinned in `package.json` `packageManager` as `pnpm@<version>+sha512.<hex>` (the hex SHA-512 of
the npm tarball, which Corepack verifies); `pnpm-lock.yaml` records the same version. CI uses
`pnpm/setup`, the Dockerfile uses Corepack. Without either locally, `npx pnpm@<version>` works.

First e2e run on a machine: `pnpm exec playwright install chromium`.
Screenshots: `SCREENSHOT_DIR=/some/dir pnpm e2e` writes one PNG per project and step.

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
8. **SPDX headers** on every source file: the copyright + licence lines from the root
   `AGENTS.md` section 4, as `//` comments in TS/JS, `/* */` in CSS and `<!-- -->` in HTML/MD.
   JSON can't carry them (REUSE.toml covers it).
9. **Licenses.** New dependencies must pass `pnpm run license-check`. Anything outside the
   allowlist needs human review, then an entry with a reason in
   `scripts/license-exceptions.json`.
10. **Talk to the backend only through the generated client.** Feature code imports hooks,
    request functions, types and Zod schemas from `src/api/generated/` (`jofi.ts`, `jofi.zod.ts`)
    and `ApiProblemError` from `src/api/fetcher.ts`; no hand-written `fetch` calls or DTO types.
    Errors arrive as `ApiProblemError` carrying the RFC 9457 `problem`. The client is regenerated
    by every `pnpm typecheck`/`test`/`build` and never committed, so a backend contract change
    shows up as a type error. To change the API, change the backend, regenerate `api/openapi.json`
    there (`./gradlew :adapters:web:updateOpenApiSpec`), then `pnpm api`. Test hooks against MSW
    handlers (`msw/node`), as in `src/api/client.test.tsx`.
11. **Install scripts.** Dependencies may not run install scripts unless listed under
    `allowBuilds` in `pnpm-workspace.yaml` (after review).

## Layout

```
src/
  app/            feature code (currently the shell page)
  ui/             our component library (Button, DonkeyLogo, SegmentedControl, appearance)
  styles/         tokens.css (the only raw values) + app.css (Tailwind, fonts, base)
  api/            fetcher.ts (orval mutator, ApiProblemError) + generated/ (orval, git-ignored)
  paraglide/      generated by Paraglide (git-ignored)
  i18n-parity.ts  compile-time DE/EN key parity
messages/         de.json, en.json
project.inlang/   Paraglide/inlang settings (plugin loaded from node_modules, no CDN)
public/           appearance-boot.js (applies theme/accent before first paint), favicon
tests/e2e/        Playwright
scripts/          license check
orval.config.ts   API client generation (input: ../api/openapi.json)
lint/             Biome GritQL plugins
```

Theme/accent: `html[data-theme=light|dark]` (absent = OS), `html[data-accent=cobalt|teal|plum|ink]`
(absent = saffron). Use `useTheme()` / `useAccent()` from `src/ui`; they persist to
localStorage (wrapped in try/catch). If you change keys or values, update
`public/appearance-boot.js` too (a unit test checks they match).
