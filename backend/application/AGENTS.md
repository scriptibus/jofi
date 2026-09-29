# application

Owns the use cases and ports of every bounded context. Depends on `domain` only.

- `io.github.scriptibus.jofi.<context>.application`: one class per use case, named `*UseCase`,
  with exactly one public method (`execute(...)`). Ports come in through the constructor.
- `io.github.scriptibus.jofi.<context>.application.port`: interfaces named `*Port`
  (outbound: persistence, AI, HTTP, clock, build info, ...). Adapters implement them.

Rules:
- No Spring or other framework annotations; `bootstrap` wires use cases as beans.
- Business logic lives here and in `domain`, never in adapters or controllers.
- Unit-test every use case with Kotest assertions + MockK fakes for ports. Kover gate: >= 70 %.
