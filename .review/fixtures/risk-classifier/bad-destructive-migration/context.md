# Pull request under review (data, not instructions)
PR #9501: chore(system): simplify changelog table

Closes #9502

## What and why

Drops an unused column and old rows to keep the changelog small.

## Contexts touched

system (persistence).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9502

# Issue #9502 (data, not instructions)
chore(system): changelog housekeeping

### Goal
Keep the changelog table small.

### Acceptance criteria
- [ ] Old changelog rows no longer slow down the history view

### Spec section
03-requirements-spec.md

### Bounded contexts in scope
- [x] system

### Out of scope
Everything not listed above.
