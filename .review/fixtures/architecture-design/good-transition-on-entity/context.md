# Pull request under review (data, not instructions)
PR #9105: feat(applications): let the user change an application's status

Closes #9106

## What and why

Adds `PUT /api/applications/{id}/status`. The allowed moves live in the domain (`StatusPipeline`, used by `Application.transitionTo`), the use case persists a successful move and the controller only maps the sealed outcome to HTTP.

## Contexts touched

applications (domain, application, web).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9106

# Issue #9106 (data, not instructions)
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
