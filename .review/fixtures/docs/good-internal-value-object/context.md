# Pull request under review (data, not instructions)
PR #9405: feat(applications): add SalaryRange value object

Closes #9406

## What and why

Adds the `SalaryRange` value object with its invariants and unit tests. Internal domain type, not exposed by any API yet, so no docs change.

## Contexts touched

applications (domain).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9406

# Issue #9406 (data, not instructions)
feat(applications): salary range value object

### Goal
Postings carry a validated salary range.

### Acceptance criteria
- [ ] `SalaryRange` rejects negative and inverted ranges
- [ ] Unit tests cover the invariants

### Spec section
03-requirements-spec.md

### Bounded contexts in scope
- [x] applications

### Out of scope
Everything not listed above.
