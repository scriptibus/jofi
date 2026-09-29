# adapters/web

Owns the REST API: Spring MVC controllers and their request/response DTOs.
Packages: `io.github.scriptibus.jofi.<context>.adapter.web`. Base path: `/api/<context>/...`.

Rules:
- Controllers are named `*Controller`, inject use cases only (never ports, adapters or
  repositories) and contain no business logic: parse input, call one use case, map the result.
- DTOs (`*Request`/`*Response`) stay in this module; domain types are not serialized directly.
- No dependency on other adapter modules.
- Test each controller with a `@WebMvcTest` slice + `MockMvcTester`; provide use cases from a
  `@TestConfiguration` backed by MockK port fakes. `WebAdapterTestApplication` (test sources)
  is the slice's configuration root.
