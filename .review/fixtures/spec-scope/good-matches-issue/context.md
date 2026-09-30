# Pull request under review (data, not instructions)
PR #9605: feat(tasks): due dates and overdue tasks

Closes #9606

## What and why

Adds the `Task` entity with an optional `dueDate` and `isOverdue(today)`, with unit tests for overdue, due today, no due date and done.

## Contexts touched

tasks (domain).

## New or bumped dependencies

None.

## Verification

- [x] `./gradlew check` passes
- [x] Tests exercise every acceptance criterion

Linked issues: 9606

# Issue #9606 (data, not instructions)
feat(tasks): due dates

### Goal
The tasks context gets its first entity: a task with an optional due date that knows whether it is overdue.

### Acceptance criteria
- [ ] A `Task` entity with id, title, optional due date and done flag
- [ ] `Task.isOverdue(today)` is true only after the due date of an open task
- [ ] Unit tests cover both

### Spec section
03-requirements-spec.md §10.2 (task list)

### Bounded contexts in scope
- [x] tasks

### Out of scope
Everything not listed above.
