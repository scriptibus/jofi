# Pull request under review (data, not instructions)
PR #9303: feat(documents): inline template images before PDF rendering

Closes #9304

## What and why

Gotenberg renders without network access, so remote images in templates are inlined first.

## Contexts touched

documents (pdf adapter).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9304

# Issue #9304 (data, not instructions)
feat(documents): images in cover letter templates

### Goal
Cover letter templates can show a logo image.

### Acceptance criteria
- [ ] Images in a template appear in the rendered PDF
- [ ] The pdf container keeps having no network access

### Spec section
03-requirements-spec.md §7.3

### Bounded contexts in scope
- [x] documents

### Out of scope
Everything not listed above.
