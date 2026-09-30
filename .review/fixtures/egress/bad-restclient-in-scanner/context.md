# Pull request under review (data, not instructions)
PR #9301: feat(scanners): fetch career pages for page watchers

Closes #9302

## What and why

Implements `PageFetchPort` so page watchers can load the career page URL the user entered.

## Contexts touched

scanners (adapter).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9302

# Issue #9302 (data, not instructions)
feat(scanners): page watcher fetch

### Goal
A page watcher loads the career page URL the user configured.

### Acceptance criteria
- [ ] `PageFetchPort` is implemented and returns the page HTML

### Spec section
03-requirements-spec.md §8.1

### Bounded contexts in scope
- [x] scanners

### Out of scope
Everything not listed above.
