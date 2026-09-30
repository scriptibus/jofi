<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0037: Frontend shell: code-based routes, a session guard, and a shell-only service worker

- Status: accepted
- Date: 2026-09-30
- Source: issue #22 (M0-8); ADR-0018, ADR-0033, ADR-0035, ADR-0036; docs/spec/04-tech-stack-proposal.md §3

## Context

Issue #22 turns the one-page demo into the app every feature plugs into: routes for the M1 areas, the
login and first-run screens of ADR-0035, global error display, and an installable PWA that is a Web
Share Target. Open questions were how routes are declared, where the auth decision is made, how the
browser keeps (or must not keep) data, how deep links reach the SPA, and how e2e tests enter wrong
passwords when every browser shares one client address (ADR-0036).

## Decision

- **TanStack Router 1.170 with code-based routes** (`src/app/router.tsx`), no route generator plugin:
  a dozen routes do not justify a build plugin and a generated file. Links are typed through
  `createLink` over React Aria's `Link` (`NavItem`, `TextLink` in `src/ui`), which gives
  `aria-current="page"` on the active item. Docs: tanstack.com/router/latest (code-based routing,
  authenticated routes, custom link).
- **The guard is a pathless layout route** (`_app`) whose `beforeLoad` reads `GET /api/auth/session`
  through TanStack Query: not set up → `/first-run`, not logged in → `/login?redirect=<path>`. The cached
  status is re-checked after 60 s on navigation; login and first run always fetch it fresh (it also
  renews the CSRF cookie). `redirect` is accepted only as a same-origin path outside the auth pages.
- **One error path for every request.** The QueryClient's query and mutation caches send a 401
  `not-logged-in` to "session ended" (drop cached data, back to login with the target), and every other
  failure to a global notice list, unless the request's `meta.errorHandledLocally` says a form shows it.
  Problem types are mapped to DE/EN messages; unknown problems show the status and the server's
  `detail` marked `lang="en"`. A 429 disables the form's submit button for `Retry-After` seconds.
- **The service worker caches the shell only** (vite-plugin-pwa 1.3, `generateSW`, Workbox 7.4):
  precached HTML/JS/CSS/fonts/icons, navigation fallback to `index.html` except `/api/` and
  `/actuator/`, **no runtime caching**. API responses, which may hold personal data, never enter the
  Cache Storage; offline, the shell loads and says the server is unreachable. `registerType: autoUpdate`.
  Manifest colours and `theme-color` come from `tokens.css`; icons are rendered from the donkey by
  `scripts/generate-pwa-icons.ts`. `share_target` is a GET to `/share` (no file sharing yet).
- **The backend serves `index.html` for client routes** (`SpaFallbackResourceResolver`): paths without
  a file extension outside `/api/` and `/actuator/`. Deep links and the share target work on the first
  visit, before any service worker exists; missing assets and API paths stay 404 problem details.
- **e2e: password flows run last and alone.** Project order: `first-run` (the UI first run, wrong token
  included) → `seed` (API login, storage state) → `desktop-light`/`desktop-dark`/`phone` (no password
  entered) → `auth` (wrong passwords, the 429 backoff, logout, password change; serial). Every `auth`
  test ends with a successful check, which resets the backoff; a password change is changed back so a
  reused stack still logs in.

## Consequences

- New pages are one `createRoute` under `appRoute` plus a `NAVIGATION` entry when they belong in the
  menu; they get the guard, the error handling and focus management for free.
- A feature that must work offline needs a deliberate, reviewed exception to "no runtime caching".
- Push notifications stay out (spec §10.3). File sharing to Jofi (POST share target) is a later change
  to the manifest and the backend.
- Licence exceptions (reviewed): `isbot` (Unlicense, via TanStack Router, SSR only) and `caniuse-lite`
  (CC-BY-4.0, build time only, via Workbox's Babel).
