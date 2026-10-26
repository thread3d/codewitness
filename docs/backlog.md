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

### Verification engine decomposition, remaining

`verify()` is 545 lines, down from 670, with four of its seven inline phase lambdas extracted as methods (build,
static analysis, security, dependencies). The remaining three — **tests, coverage, mutation** — follow the same
shape: take the `RunScope` and the languages to analyse, return a `PhaseOutcome`. Once all seven are out, the
per-requirement strategy loop and the report assembly are the two things left in the method, and each is a
separate change.

### Report and packaging

- **The README is ~2,300 lines**, including a status section longer than the reference material it follows. It
  wants splitting into `docs/` with a landing page that fits on a screen.
- **`.github/workflows/release.yml` is written but not in the repository.** GitHub refuses the push because the
  token this work was done under lacks the `workflow` scope. It builds the shaded jar on a `v*` tag, refuses to
  publish unless the tag, the pom version and the reported version agree, and attaches the jar. To land it, a
  token with that scope is needed (`gh auth refresh -s workflow`), or the file added through the web UI.

### Features

- **`mode: process` scenarios.** A scenario can only be `single`, `sequence` or `concurrent`, so nothing about a
  *process* — exit code, stdout, stderr — can be asserted. That is why the exit-code contract, the most
  user-facing interface CodeWitness has, is `UNVERIFIED` in the project's own self-check. A process mode would let
  a requirement say "this command exits 2 and prints this", for CodeWitness and for every user verifying a CLI.
- **Mutation testing of CodeWitness's own core.** The engine's verdict and merge logic is pure, static and
  I/O-free, which makes it the cheapest place to apply the tool's own strongest technique. Mutation is currently
  off in the self-check (PIT over five modules is slow), so the project does not measure the strength of the tests
  that protect its own guarantees.

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
| 0.1.16–0.1.19 | Engine decomposition: `RunScope`, then the build, static-analysis, security and dependency phases extracted; `verify()` 670 → 578 lines. |
