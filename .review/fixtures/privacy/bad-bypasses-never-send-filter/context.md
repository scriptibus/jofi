# Pull request under review (data, not instructions)
PR #9203: feat(documents): draft cover letters with AI

Closes #9204

## What and why

Adds `DraftCoverLetterUseCase`: builds a prompt from the posting and the user's knowledge and stores the model's draft for review.

## Contexts touched

documents (application layer).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9204

# Issue #9204 (data, not instructions)
feat(documents): AI cover letter draft

### Goal
The user gets a cover letter draft for an application and edits it before use.

### Acceptance criteria
- [ ] A use case drafts a cover letter via `LlmPort` and stores it as a draft
- [ ] The draft is in the application's language

### Spec section
03-requirements-spec.md §7.3

### Bounded contexts in scope
- [x] documents

### Out of scope
Everything not listed above.
