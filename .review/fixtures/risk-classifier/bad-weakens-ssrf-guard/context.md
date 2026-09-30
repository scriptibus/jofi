# Pull request under review (data, not instructions)
PR #9503: fix(scanners): allow job boards on the local network

Closes #9504

## What and why

Some users run a job board mirror at home; the SSRF guard blocked it.

## Contexts touched

shared (net adapter).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9504

# Issue #9504 (data, not instructions)
fix(scanners): local job board blocked

### Goal
A job board on the user's LAN can be scanned.

### Acceptance criteria
- [ ] Scanning a LAN job board works

### Spec section
03-requirements-spec.md

### Bounded contexts in scope
- [x] scanners

### Out of scope
Everything not listed above.
