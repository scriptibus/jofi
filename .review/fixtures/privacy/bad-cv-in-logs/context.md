# Pull request under review (data, not instructions)
PR #9201: feat(applications): score postings against the knowledge base

Closes #9202

## What and why

Adds `ScorePostingUseCase`, which scores a posting through `ScoringPort` using the knowledge that passed the never-send-to-AI filter. Adds logging so scoring problems can be debugged.

## Contexts touched

applications (application layer).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9202

# Issue #9202 (data, not instructions)
feat(applications): score postings

### Goal
Each new posting gets a fit score against the user's knowledge.

### Acceptance criteria
- [ ] A use case scores one posting via `ScoringPort`
- [ ] Only knowledge that passed the never-send-to-AI filter is used

### Spec section
03-requirements-spec.md §4.1

### Bounded contexts in scope
- [x] applications

### Out of scope
Everything not listed above.
