# Backlog

What is known to be wrong or worth doing, with enough context that a fresh reader can pick an item up. It is
maintained by hand and updated as items land; a completed item is moved to the bottom with the release that
closed it, so the list is also a short history of what was fixed and why.

## Open

Nothing. Every item this backlog has carried is closed, and the closed table below records what each release settled.
A new item should be written here — with its reasoning — before it is worked, so the list stays the authority rather
than a summary of what was done.

## Closed

Every release, newest first, with the item it closed.

| Release | Item |
| --- | --- |
| 1.0.11 | Process scenarios can be generated as native JUnit and unittest tests (`native_test: true`), and the generator no longer emits a syntactically invalid test for a process scenario it cannot render. |
| 1.0.10 | Engine decomposition finished: the strategy loop and the verdict loop are methods, `verify()` 670 → 360 lines; the report assembly is deliberately left as an eleven-line builder chain. |
| 1.0.9 | Mutation testing of the verdict logic: 31 mutants, 31 killed, 100% — after the first run's survivor exposed an untested branch in the 1.0.5 verdict text. `mutation.target_classes` was documented and inert; it is now honoured. |
| 1.0.8 | The README became a landing page and the reference material moved into `docs/` by moving text rather than rewriting it. |
| 1.0.7 | Boundary sampling rewritten: a baseline case, then round-robin variation so every input moves, then pairs; and `clean --keep` got a total order after CI found that equal modification times made it non-deterministic. |
| 1.0.6 | Adapter conversion complete: SQL and C++ join the base class (fourteen of sixteen), and a test pins the two that cannot — Java and Kotlin, whose phases select a build system rather than delegate. |
| 1.0.5 | The HDL-family adapters converted, and the resulting capability contract recorded as a test. |
| 1.0.4 | Eight uniform adapters converted to the derived-capability pattern. |
| 1.0.3 | `AdapterProviders` + `AbstractLanguageAdapter`: one declaration feeds the adapter and its bundle, and `capabilities()` is derived from the providers instead of restated. |
| 1.0.2 | `mode: process` scenarios: a command is run and its exit code and streams asserted, so the tool's own exit-code contract (CWR-007) is `VERIFIED` by execution — every ground rule in the self-check verified for the first time. |
| 1.0.1 | The release workflow landed and published its own first release. |
| 1.0.0 | The interface contract written down: the CLI, the report documents, the configuration keys and the adapter SPI are stable. |
| 0.1.21 | All seven phase bodies extracted as methods; `verify()` 670 → 433 lines. |
| 0.1.20 | The build phase extracted, and `docs/backlog.md` created so the backlog no longer lived only in a conversation. |
| 0.1.19 | The dependency phase extracted. |
| 0.1.18 | The security phase extracted. |
| 0.1.17 | The static-analysis phase extracted. |
| 0.1.16 | `RunScope`: one run-scoped object instead of sixteen arguments, eight times. |
| 0.1.15 | An adapter that registers a provider for a phase it does not declare is reported. |
| 0.1.14 | One implementation of the security-phase forwarding; fourteen copies removed. |
| 0.1.13 | Release notes moved into the repository. |
| 0.1.12 | Report hardening: surrogate safety, a content security policy, bounded tables. |
| 0.1.11 | A contended write is retried instead of lost to a five-second timeout. |
| 0.1.10 | One hardened XML loader instead of nine copies across seven adapters. |
| 0.1.9 | One boundary-case enumeration instead of fourteen, with its truncation rule documented rather than assumed. |
| 0.1.8 | One implementation of the tool-output tail; the Kotlin copies' `strip()` drift removed. |
| 0.1.7 | Run retention; a history that can be read where it cannot be written. |
| 0.1.6 | Findings reached CI: SARIF export, baselines, suppression. |
| 0.1.5 | The report and the exit code stopped asserting what the run did not establish; the workspace copy stopped following symbolic links out of the project. |
