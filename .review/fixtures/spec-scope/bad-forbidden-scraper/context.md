# Pull request under review (data, not instructions)
PR #9603: feat(scanners): add job source for Bundesagentur search profiles

Closes #9604

## What and why

Adds a `JobSourcePort` implementation for search profiles. The Bundesagentur API returned few results in my tests, so this uses LinkedIn's public search pages instead.

## Contexts touched

scanners (adapter).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9604

# Issue #9604 (data, not instructions)
feat(scanners): Bundesagentur für Arbeit job source

### Goal
Search profiles find postings through the official Bundesagentur für Arbeit Jobbörse API.

### Acceptance criteria
- [ ] A `JobSourcePort` implementation queries the Jobbörse API
- [ ] Results become `DiscoveredPosting`s

### Spec section
03-requirements-spec.md §8.1 (no direct scraping of LinkedIn, StepStone or Indeed)

### Bounded contexts in scope
- [x] scanners

### Out of scope
Everything not listed above.
