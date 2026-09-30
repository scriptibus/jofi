# Pull request under review (data, not instructions)
PR #9403: feat(documents): render PDFs

Closes #9404

## What and why

Implements `PdfRenderPort`.

## Contexts touched

documents (pdf adapter).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9404

# Issue #9404 (data, not instructions)
feat(documents): PDF export of cover letters

### Goal
The user downloads a cover letter as PDF.

### Acceptance criteria
- [ ] `PdfRenderPort` is implemented

### Spec section
03-requirements-spec.md §7.3

### Bounded contexts in scope
- [x] documents

### Out of scope
Everything not listed above.
