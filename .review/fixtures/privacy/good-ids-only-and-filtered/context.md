# Pull request under review (data, not instructions)
PR #9205: feat(documents): draft cover letters with AI

Closes #9206

## What and why

Adds `DraftCoverLetterUseCase`: builds a prompt from the posting and the knowledge returned by `KnowledgeForAi.allowedForAi()` (which applies the never-send-to-AI filter) and stores the draft for review. Logs only ids and counts.

## Contexts touched

documents (application layer).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9206

# Issue #9206 (data, not instructions)
feat(documents): AI cover letter draft

### Goal
The user gets a cover letter draft for an application and edits it before use.

### Acceptance criteria
- [ ] A use case drafts a cover letter via `LlmPort` and stores it as a draft
- [ ] Entries flagged never-send-to-AI are not sent

### Spec section
03-requirements-spec.md §7.3

### Bounded contexts in scope
- [x] documents

### Out of scope
Everything not listed above.
