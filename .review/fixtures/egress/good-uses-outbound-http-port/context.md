# Pull request under review (data, not instructions)
PR #9305: feat(scanners): fetch career pages for page watchers

Closes #9306

## What and why

Implements `PageFetchPort` on top of `OutboundHttpPort` (adapters/net), which applies the SSRF guard, timeouts, size limits and robots.txt.

## Contexts touched

scanners (adapter).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9306

# Issue #9306 (data, not instructions)
feat(scanners): page watcher fetch

### Goal
A page watcher loads the career page URL the user configured.

### Acceptance criteria
- [ ] `PageFetchPort` is implemented and returns the page HTML
- [ ] All fetches go through adapters/net

### Spec section
03-requirements-spec.md §8.1

### Bounded contexts in scope
- [x] scanners

### Out of scope
Everything not listed above.
