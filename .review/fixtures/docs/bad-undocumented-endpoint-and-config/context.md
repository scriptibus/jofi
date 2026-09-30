# Pull request under review (data, not instructions)
PR #9401: feat(scanners): run a scanner on demand

Closes #9402

## What and why

Adds a button-backing endpoint to run one scanner now.

## Contexts touched

scanners.

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9402

# Issue #9402 (data, not instructions)
feat(scanners): run now

### Goal
The user can run a scanner immediately instead of waiting for its schedule.

### Acceptance criteria
- [ ] An endpoint starts one scanner run

### Spec section
03-requirements-spec.md §8.1

### Bounded contexts in scope
- [x] scanners

### Out of scope
Everything not listed above.
