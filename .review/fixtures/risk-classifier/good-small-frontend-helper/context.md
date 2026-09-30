# Pull request under review (data, not instructions)
PR #9505: feat(applications): format salary ranges in the UI

Closes #9506

## What and why

Adds a pure formatting helper with unit tests; used by the posting card in a follow-up.

## Contexts touched

applications (frontend).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9506

# Issue #9506 (data, not instructions)
feat(applications): salary range formatting

### Goal
Salary ranges read naturally in DE and EN.

### Acceptance criteria
- [ ] A helper formats a range per locale
- [ ] Unit tests

### Spec section
03-requirements-spec.md

### Bounded contexts in scope
- [x] applications

### Out of scope
Everything not listed above.
