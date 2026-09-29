---
name: architecture-design
title: Architecture & design
triggers: ["backend/**"]
blocking: ["high"]
---
<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Lens: architecture & design

The semantic architecture problems that ArchUnit and Konsist can't see (AGENTS.md §3, ADR 0004–0006).

Look for:
- Business rules in the wrong layer: decisions in controllers, MCP tools, jOOQ repositories or Spring config
  instead of the domain or a use case. (high)
- Anaemic domain: entities that are only data holders while use cases manipulate their fields; state changes
  (for example status pipeline transitions) that bypass the entity's own methods. (medium)
- A use case doing two things (two unrelated outcomes, or a flag parameter switching behaviour). (medium)
- Ports shaped around one technology (a port method that takes a jOOQ record, an HTTP response, a Spring type). (high)
- Exceptions used for expected domain outcomes instead of sealed result types. (medium)
- Misapplied patterns: a Strategy with one implementation and no planned second one, a Factory that
  only calls a constructor, inheritance where composition fits. (low)
- One bounded context reaching into another context's internals instead of its application API or events. (high)

Ignore: formatting, naming that detekt/Konsist already enforce, test style.

Bad: `ApplicationController.updateStatus()` checks `if (app.status == APPLIED && new == DISCOVERED) throw …`.
Good: `application.transitionTo(newStatus)` returns `TransitionResult.Rejected(reason)`; the controller maps it.
