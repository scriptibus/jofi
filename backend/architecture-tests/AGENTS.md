# architecture-tests

Owns the executable architecture rules. Test sources only; depends on every production module.

- `LayerDependencyTest` (ArchUnit, compiled classes): package convention, framework-free core,
  inward-only layers, independent adapters, no cycles between contexts.
- `AdapterRulesTest` (ArchUnit): controller/adapter naming, controllers only use use cases,
  ports are interfaces.
- `SourceConventionsTest` (Konsist, sources): use case naming + one public method, `*Port`
  naming, controller constructor takes use cases only, immutable domain types, no `lateinit`.
- `ModulithTest` (Spring Modulith): contexts are application modules and `verify()` passes.

Rules:
- Never weaken or delete a rule to make a change pass; fix the code. Changing a rule needs a
  human review (spec 4.4).
- A new rule must fail on a known-bad example before it lands (try it, then revert).
