# Backlog

What is known to be wrong or worth doing, with enough context that a fresh reader can pick an item up. It is
maintained by hand and updated as items land; a completed item is moved to the bottom with the release that
closed it, so the list is also a short history of what was fixed and why.

## Open

### Adapter duplication, remaining

- **`AbstractLanguageAdapter` and the `*AdapterBundle` classes.** Fifteen bundles are 48 lines each and
  structurally identical; fourteen adapters declare `build`/`runTests`/`analyze` as one-line delegations to the
  providers they also register, and `capabilities()` restates the registration list by hand. A base class that
  resolves providers and derives `capabilities()` from what was registered would remove ~1,000 lines. The
  capability guard (0.1.15) makes the derivation safe to rely on: a mismatch is now reported rather than ignored.
- **The boundary-case enumeration truncates from the front.** With four inputs of six values, the twelve kept
  cases never vary the first two inputs. Every case is complete and valid, so nothing is mis-generated, but a
  scenario that expects all of its inputs exercised does not get that once the product exceeds the cap. Sampling
  one-at-a-time variation plus pairwise combinations is the usual reading of "boundary values" and would fix it;
  it changes which experiments run, so the fixtures need re-baselining in the same change.

### Verification engine decomposition

**Complete for the phases.** All seven phase bodies are methods taking the `RunScope`; `verify()` is 433 lines,
down from 670, and holds no inline phase body. What remains inside it is the per-requirement strategy loop and the
report assembly — each a separate change, and each now readable without the phases in the way.

### Report and packaging

- **The README is ~2,300 lines**, including a status section longer than the reference material it follows. It
  wants splitting into `docs/` with a landing page that fits on a screen.
- **The README split** is the last packaging item; the release workflow landed in 1.0.1.

### Features

- **Mutation testing of CodeWitness's own core.** The engine's verdict and merge logic is pure, static and
  I/O-free, which makes it the cheapest place to apply the tool's own strongest technique. Mutation is currently
  off in the self-check (PIT over five modules is slow), so the project does not measure the strength of the tests
  that protect its own guarantees.

- **Process scenarios in the per-language adapters.** `mode: process` is implemented in the engine — it runs a
  command and asserts on the outcome — so it works for any project, in any language, today. What it does *not* yet
  do is generate a language-native test from a process scenario the way the other modes do; a project that wants
  its process assertions to live in its own test suite still writes them there. Adding generators for the Java and
  Python adapters is the natural next step.

## Closed

| Release | Item |
| --- | --- |
| 0.1.5 | The report and the exit code stopped asserting what the run did not establish; the workspace copy stopped following symbolic links out of the project. |
| 0.1.6 | Findings reached CI: SARIF export, baselines, suppression. |
| 0.1.7 | Run retention; a history that can be read where it cannot be written. |
| 0.1.8 | One implementation of the tool-output tail; the Kotlin copies' `strip()` drift removed. |
| 0.1.9 | One boundary-case enumeration instead of fourteen, with its truncation rule documented rather than assumed. |
| 0.1.10 | One hardened XML loader instead of nine copies across seven adapters. |
| 0.1.11 | A contended write is retried instead of lost to a five-second timeout. |
| 0.1.12 | Report hardening: surrogate safety, a content security policy, bounded tables. |
| 0.1.13 | Release notes moved into the repository. |
| 0.1.14 | One implementation of the security-phase forwarding; fourteen copies removed. |
| 0.1.15 | An adapter that registers a provider for a phase it does not declare is reported. |
| 0.1.16–0.1.21 | Engine decomposition: `RunScope`, then all seven phase bodies extracted as methods; `verify()` 670 → 433 lines. |
| 1.0.2 | `mode: process` scenarios: a command is run and its exit code and streams asserted, so CodeWitness's own exit-code contract (CWR-007) is now `VERIFIED` by execution — every ground rule in the self-check is verified for the first time. |
## Closed

| Release | Item |
| --- | --- |
| 0.1.5 | The report and the exit code stopped asserting what the run did not establish; the workspace copy stopped following symbolic links out of the project. |
| 0.1.6 | Findings reached CI: SARIF export, baselines, suppression. |
| 0.1.7 | Run retention; a history that can be read where it cannot be written. |
| 0.1.8 | One implementation of the tool-output tail; the Kotlin copies' `strip()` drift removed. |
| 0.1.9 | One boundary-case enumeration instead of fourteen, with its truncation rule documented rather than assumed. |
| 0.1.10 | One hardened XML loader instead of nine copies across seven adapters. |
| 0.1.11 | A contended write is retried instead of lost to a five-second timeout. |
| 0.1.12 | Report hardening: surrogate safety, a content security policy, bounded tables. |
| 0.1.13 | Release notes moved into the repository. |
| 0.1.14 | One implementation of the security-phase forwarding; fourteen copies removed. |
| 0.1.15 | An adapter that registers a provider for a phase it does not declare is reported. |
| 0.1.16–0.1.21 | Engine decomposition: `RunScope`, then all seven phase bodies extracted as methods; `verify()` 670 → 433 lines. |
