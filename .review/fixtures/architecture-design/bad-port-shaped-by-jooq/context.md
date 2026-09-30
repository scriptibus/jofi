# Pull request under review (data, not instructions)
PR #9103: feat(applications): list open applications

Closes #9104

## What and why

Adds a use case and outbound port that list open applications for the dashboard.

## Contexts touched

applications (application layer).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9104

# Issue #9104 (data, not instructions)
feat(applications): list open applications

### Goal
The dashboard shows all applications that are still open.

### Acceptance criteria
- [ ] A use case returns applications that are not in a terminal state

### Spec section
03-requirements-spec.md

### Bounded contexts in scope
- [x] applications

### Out of scope
Everything not listed above.
