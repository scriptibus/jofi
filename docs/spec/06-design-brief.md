# Jofi: design brief

Version 0.1, 2026-09-29, from Lucas's answers in the tech-stack thread. Foundation: React Aria Components + Tailwind v4 + own design tokens (04-tech-stack-proposal.md, section 3.4).

## Taste
- **Feel:** crisp, but not dense; room to breathe.
- **Theme:** light and dark equally, following the browser/OS setting (with a manual override).
- **Density:** airy, generous whitespace, never overwhelming.
- **Colour:** mostly neutral with **one strong accent colour**.
- **Typography:** headings may have character; body stays clean.
- **Shape:** precise and sharp (small or zero radius), not rounded.
- **Motion:** noticeable animation is welcome; Lucas will say if it's too much. Always respects reduced-motion.
- **Personality:** a professional companion.
- **Mascot/logo:** **Jofi is a donkey** doing the donkey work for you. The logo doubles as the loading indicator and can appear in a few flashy places.
- **References:** none, start fresh.

## Prototype
Three clickable directions (Today dashboard, Applications board, application detail, loader), switchable in one page, light/dark aware:
https://claude.ai/artifact/49fgnfv8NCPp394aP6ieFR

- **A · Präzision:** cool neutrals, cobalt accent, expressive serif headings + crisp grotesk, hairlines, 2px corners, smooth motion; line-drawn donkey that draws itself.
- **B · Stall:** warm stone neutrals, saffron accent, bold variable grotesk headings, soft shadows, 4px corners, springy motion; solid donkey that bobs its head.
- **C · Signal:** graphite + raspberry, wide uppercase display type, mono for data, 0 radius, hard offset shadows, snappy stepped motion; donkey trots across the loading line.

## Decision (2026-09-29)
- **Direction B · Stall** is chosen: calming, crisp, good overview. Warm stone neutrals, bold variable grotesk headings (Bricolage Grotesque) over a clean body face (Onest), mono for data, soft shadows, 4px corners, springy motion, solid donkey that bobs its head while loading.
- Lucas liked A's colours but found A "a bit too default AI". C rejected (readability of the wide uppercase type, raspberry contrast too harsh).
- **Accent colour is a user setting.** Curated presets, each tuned for light and dark and checked for contrast: **Saffron (default)**, Cobalt, Teal, Plum, Ink (monochrome). No free colour picker, because arbitrary colours break contrast and a11y. Implemented as a `data-accent` token override; only `--accent`, `--accent-fg`, `--accent-soft` change, neutrals stay.

## Next
Tokens for B (colour incl. semantic states, type scale, spacing, radius, elevation, motion) → donkey logo refinement → core components in Storybook. Part of M0 frontend work.
