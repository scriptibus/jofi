# Pull request under review (data, not instructions)
PR #9101: feat(applications): let the user change an application's status

Closes #9102

## What and why

Adds `PUT /api/applications/{id}/status` so the board can move cards between columns.

## Contexts touched

applications (web adapter).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9102

# Issue #9102 (data, not instructions)
feat(applications): change application status from the board

### Goal
The user moves an application along the status pipeline from the board.

### Acceptance criteria
- [ ] `PUT /api/applications/{id}/status` changes the status
- [ ] Invalid transitions (for example back from APPLIED to DISCOVERED) are rejected with 422

### Spec section
03-requirements-spec.md §6.2 (status pipeline)

### Bounded contexts in scope
- [x] applications

### Out of scope
Everything not listed above.
