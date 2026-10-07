# Fixture projects

A verification tool is only worth trusting if it can be shown to distinguish code that is wrong from
code that is right. Every fixture in `fixtures/` is deliberately broken in a specific way — or
deliberately correct — and the end-to-end tests assert that CodeWitness reaches the expected verdict.

The results below were produced by `tools/verify-all-fixtures.sh`, which runs
`./codewitness verify <fixture> --requirements <fixture>/requirements.md --max-duration 10m` for each
fixture and prints the outcome. `./codewitness` is the launcher in the repository root; the build does
not install a `codewitness` command on `PATH`.

| Fixture | Type of defect | Overall | Requirements | What it proves |
| --- | --- | --- | --- | --- |
| `withdrawal-ledger` | four defects: rolling window, discarded `BigDecimal`, negative amounts, race | `FAILED` | 4 `FAILED`, 1 `UNVERIFIED` | Several independent defects in one project, each mapped to its own requirement, each with a reproducible counterexample; and an honest `UNVERIFIED` for a requirement nobody can execute. |
| `python-withdrawal-ledger` | Python: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a language the core knows nothing about: detection, syntax build, tests, scenarios, counterexamples and AST mutation, using only the standard library. |
| `rust-withdrawal-ledger` | Rust: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a compiled language with a build step: Cargo detection and build, libtest parsing, scenario-generated integration tests, counterexamples and bounded source mutation, with no third-party crate. |
| `go-withdrawal-ledger` | Go: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for Go: module detection, `go build`, `go test -json` parsing, scenario-generated `_test.go` files in the package under test, counterexamples, built-in coverprofile coverage and bounded source mutation, with no third-party module. |
| `typescript-withdrawal-ledger` | TypeScript: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a language executed by Node's type stripper: detection, syntax build, `node:test` with JUnit parsing, scenario-generated `node:test` modules, counterexamples, V8 coverage and bounded source mutation, with no third-party package. |
| `javascript-withdrawal-ledger` | JavaScript: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for plain JavaScript, with no compiler and no type check anywhere: detection, `node --check` syntax build, `node:test` with JUnit parsing, scenario-generated `node:test` modules, counterexamples, V8 coverage and bounded source mutation, with no third-party package. |
| `polyglot-service` | six languages in one directory, plus a `Gemfile` nothing can verify | `PARTIALLY_VERIFIED` | 1 `VERIFIED`, 5 `UNVERIFIED` | One run verifies every language a tree contains: Maven, Python, Cargo, Go, TypeScript and JavaScript each build, test and analyse their own files in a single tree, a Python scenario discharges a requirement while five other languages read the same directory, dependencies come from more than one ecosystem, and the language no adapter understands is reported as present and not verified rather than left out. |
| `off-by-one-boundary` | boundary comparison (`>=` where `>` is required) | `FAILED` | 1 `FAILED`, 1 `VERIFIED` | Boundary enumeration finds the exact value (`amount=1000`) that the project's own tests never try. |
| `null-handling-defect` | missing null check (`username.trim()` before validation) | `FAILED` | 1 `FAILED`, 1 `VERIFIED` | A generated case can pass `null`; the NPE is captured as the counterexample's observed result. |
| `security-vulnerability` | hardcoded credential, SQL built by concatenation | `FAILED` | 2 `FAILED` | Static security rules produce counterexamples with file, line and rule id, without any test running. |
| `mutation-surviving` | correct code, weak tests (a boundary nobody asserts) | `VERIFIED` | 2 `VERIFIED (+2 survived mutants)` | Verdicts follow executed experiments; weak tests are surfaced as a test-strength limitation rather than silently lowering a verdict. |
| `misleading-tests` | correct code, a test with no assertion | `PARTIALLY_VERIFIED` | 1 `VERIFIED`, 1 `UNVERIFIED` | A test that cannot fail is reported as a finding, and a requirement described only in prose stays `UNVERIFIED`. |
| `correct-implementation` | none (control) | `VERIFIED` | 1 `VERIFIED` | `VERIFIED` is reachable, and means "every planned obligation was discharged by an executed experiment". |

## Details

### `withdrawal-ledger`

The reference fixture: a ledger whose daily limit is applied per calendar day instead of over a
rolling 24-hour window, whose `applyFee` discards the result of `BigDecimal.add`, which accepts
negative withdrawals, and whose concurrent entry point is a check-then-act race.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit            FAILED
  ✗ R-002  Fees are applied to the total       FAILED
  ✗ R-003  Negative amounts are rejected       FAILED
  ✗ R-004  Concurrent withdrawals              FAILED
  ? R-005  Rounding of computed totals         UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=700, t2=90000000, a2=700}  actual: 1400
  CE-002  R-002  inputs: {}                                          actual: 100
  CE-003  R-003  inputs: {amount=-100}                               actual: 400
  CE-005  R-004  inputs: {amount=273}  seed: 11, case: 0            actual: 1092
```

All three of the project's own tests pass. The counterexamples come from generated experiments —
boundary enumeration for the midnight crossing, a fixed two-step sequence for the fee, a seeded
random search for the negative amount, and eight threads released simultaneously for the race.

R-005 is the interesting one: it is `UNVERIFIED`, with the rationale "every planned obligation is
still unverified. The requirement must not be treated as passing."

### `python-withdrawal-ledger`

The Python counterpart of the reference fixture: a ledger whose limit is applied per calendar day
instead of over a rolling 24-hour window (R-001) and which accepts a negative withdrawal and reduces
the recorded total (R-003). The project's own tests pass, which is the point.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit             FAILED
  ✓ R-002  Fees are applied to the total        VERIFIED
  ✗ R-003  Negative amounts are rejected        FAILED
  ? R-004  Rounding of computed totals          UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=700, t2=90000000, a2=700}  actual: 1400
  CE-002  R-001  inputs: {t1=133185906, a1=775.03, t2=7535, a2=886.94}  actual: 1661.97
  CE-003  R-003  inputs: {amount=-100}                               actual: 400

Mutation testing: 12 mutants, 5 killed, 7 survived (standard library AST mutator)
```

R-001 and R-003 fail through generated `unittest` scenarios; R-002 is discharged by an executed
generated test, which is the only path to `VERIFIED`; R-004 declares no scenario and stays
`UNVERIFIED`. The counterexamples carry the exact inputs and a replay command that runs the saved
generated module with the recorded seed and case.

The fixture needs an interpreter and nothing else: the adapter uses pytest when it can import it and
a standard-library runner otherwise, so no third-party package has to be installed for this result.

### `rust-withdrawal-ledger`

The Rust counterpart of the reference fixture: a Cargo library whose limit is applied per calendar day
instead of over a rolling 24-hour window (R-001) and which accepts a negative withdrawal and reduces
the recorded total (R-003). Amounts are integer minor units and instants are epoch milliseconds, so
the fixture needs no decimal crate. The project's own tests pass, which is the point.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit              FAILED
  ✓ R-002  Fees are applied to the recorded total VERIFIED
  ✗ R-003  Negative amounts are rejected         FAILED
  ? R-004  Rounding of computed totals           UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}  actual: 140000
  CE-002  R-003  inputs: {amount=-10000}                                 actual: 40000

Mutation testing: 12 mutants, 5 killed, 7 survived (built-in source mutator)
```

R-001 and R-003 fail through generated Cargo integration tests; R-002 is discharged by an executed
generated test, which is the only path to `VERIFIED`; R-004 declares no scenario and stays
`UNVERIFIED`. Each counterexample carries the exact inputs and a replay command that runs the saved
generated test target with the recorded seed and case.

The fixture needs a Rust toolchain and nothing else: it has no dependencies, so it builds and tests
offline. Coverage is reported as "not measured" unless `cargo-llvm-cov` is installed, which is itself
the honest result the adapter is designed to produce.

### `go-withdrawal-ledger`

The Go counterpart of the reference fixture: a Go module whose limit is applied per calendar day
instead of over a rolling 24-hour window (R-001) and which accepts a negative withdrawal and reduces
the recorded total (R-003). Amounts are integer minor units and instants are epoch milliseconds, so
the fixture needs no decimal package. The project's own tests pass, which is the point.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit               FAILED
  ✓ R-002  Fees are applied to the recorded total VERIFIED
  ✗ R-003  Negative amounts are rejected          FAILED
  ? R-004  Rounding of computed totals            UNVERIFIED
```

R-001 and R-003 fail through generated `_test.go` files placed in the package under test; R-002 is
discharged by an executed generated test, which is the only path to `VERIFIED`; R-004 declares no
scenario and stays `UNVERIFIED`. Each counterexample carries the exact inputs, the seed and case
index, and a replay command that runs the saved generated test with that seed and case.

The fixture needs the `go` tool and nothing else: it has no dependencies, so it builds and tests
offline. Coverage comes from the built-in `go test -coverprofile`, so it is available without
installing anything; the profile carries statements only, and branch coverage is reported as
unavailable rather than guessed.

### `typescript-withdrawal-ledger`

The TypeScript counterpart of the reference fixture: a Node module whose limit is applied per calendar
day instead of over a rolling 24-hour window (R-001) and which accepts a negative withdrawal and
reduces the recorded total (R-003). Amounts are `bigint` minor units and instants are epoch
milliseconds, so the arithmetic is exact and the fixture needs no package. The project's own tests
pass, which is the point.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit              FAILED
  ✓ R-002  Fees are applied to the recorded total VERIFIED
  ✗ R-003  Negative amounts are rejected         FAILED
  ? R-004  Rounding of computed totals           UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}  actual: 140000
  CE-002  R-003  inputs: {amount=-10000}                                 actual: 40000

Tests:            2 executed, 2 passed
Coverage:         line 97.3% (73/75), branch 88.9% (8/9)   (V8, via LCOV)
Mutation testing: 12 mutants, 5 killed, 7 survived         (built-in source mutator)
```

R-001 and R-003 fail through generated `node:test` modules; R-002 is discharged by an executed
generated test, which is the only path to `VERIFIED`; R-004 declares no scenario and stays
`UNVERIFIED`. Each counterexample carries the exact inputs and a replay command that runs the saved
generated test with the recorded seed and case.

The fixture needs Node 22.6 or later and nothing else: it has no packages, so it is syntax-checked,
tested, covered and mutated by the runtime it already has. Its scenario fragments reference inputs as
`#{name}` rather than `${name}`, because `${...}` is template-literal interpolation in TypeScript.

### `javascript-withdrawal-ledger`

The JavaScript counterpart of the reference fixture: a plain JavaScript module whose limit is applied
per calendar day instead of over a rolling 24-hour window (R-001) and which accepts a negative
withdrawal and reduces the recorded total (R-003). Amounts are `bigint` minor units and instants are
epoch milliseconds, so the arithmetic is exact and the fixture needs no package. The project's own
tests pass, which is the point.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit              FAILED
  ✓ R-002  Fees are applied to the recorded total VERIFIED
  ✗ R-003  Negative amounts are rejected         FAILED
  ? R-004  Rounding of computed totals           UNVERIFIED

Tests:            2 executed, 2 passed
Coverage:         line 97.3% (73/75), branch 88.9% (8/9)   (V8, via LCOV)
Mutation testing: 12 mutants, 5 killed, 7 survived         (built-in source mutator)
```

R-001 and R-003 fail through generated `node:test` modules; R-002 is discharged by an executed
generated test, which is the only path to `VERIFIED`; R-004 declares no scenario and stays
`UNVERIFIED`. Each counterexample carries the exact inputs and a replay command that runs the saved
generated test with the recorded seed and case. Coverage and mutation are measured exactly as they are
for the TypeScript fixture, with no compiler anywhere in the pipeline.

The fixture needs Node 18.17 or later and nothing else: it has no packages, so it is syntax-checked,
tested, covered and mutated by the runtime it already has. Its `codewitness.yaml` names the built-in
mutator as `mutation.tool: javascript-text`. Its scenario fragments reference inputs as
`#{name}` rather than `${name}`, because `${...}` is template-literal interpolation in JavaScript, and
the generated test is written with the `.test.mjs` extension so it is unambiguously an ES module.

Unlike the TypeScript fixture, nothing here is type checked: the JavaScript adapter has no compiler,
and its build is a syntax check with the runtime's own parser. The two adapters are exclusive — a
project with a `tsconfig.json` or any TypeScript source belongs to the TypeScript adapter — so a
JavaScript run never implies a type check happened.

### `polyglot-service`

One directory holding six languages' projects and a `Gemfile`: a Maven project, a Python package with a
`poetry.lock`, a Cargo crate, a Go module, a TypeScript ledger and a plain-JavaScript tool. Nothing
arranges the sharing - each adapter finds its own files by extension and ignores the rest - which is the
property the fixture exists to demonstrate. JavaScript and TypeScript own disjoint extensions
(`js`/`mjs`/`cjs` against `ts`/`tsx`/`mts`/`cts`), so both are verified rather than one stepping aside
for the other.

One scenario is declared, for the Python half. The other five requirements have no scenario and stay
`UNVERIFIED`, which is the honest answer for a requirement nobody has said how to execute. That one
scenario is what proves the thing the per-language fixtures cannot: a generated test executed against the
right language while six of them share a tree.

The `Gemfile` is not decoration either. It is the case that running more adapters cannot fix - a language
present in the tree that nothing understands - and the report names it, with the file that gave it away,
instead of computing verdicts as though the code were not there.

```text
project      polyglot-service (polyglot, polyglot)
  language   java via java (maven, 1 module(s), build succeeded)
  language   python via python (python, 1 module(s), build succeeded)
  language   rust via rust (cargo, 1 module(s), build succeeded)
  language   go via go (go, 1 module(s), build succeeded)
  language   javascript via javascript (node, 1 module(s), build succeeded)
  language   typescript via typescript (node, 1 module(s), build succeeded)
  NOT VERIFIED ruby - present (Gemfile), but no adapter in this run can verify it

Requirements
  ? R-001  The Java ledger totals a list of amounts      UNVERIFIED
  ✓ R-002  The Python ledger totals a list of amounts    VERIFIED
  ? R-003  The Rust ledger totals a list of amounts      UNVERIFIED
  ? R-004  The Go ledger totals a list of amounts        UNVERIFIED
  ? R-005  The TypeScript ledger totals a list of amounts UNVERIFIED
  ? R-006  The JavaScript tool formats minor units       UNVERIFIED

Tests
  8 executed, 8 passed, 0 failed, 0 skipped (Python 2, Rust 2, Go 2, JavaScript 1, TypeScript 1)
```

### `off-by-one-boundary`

```text
R-001  A withdrawal of exactly the daily limit is accepted    FAILED
       CE-001 inputs: {amount=1000}  actual: 0
R-002  A withdrawal strictly below the daily limit            VERIFIED
```

The implementation guards with `>= 0` where `> 0` is required, so a withdrawal of exactly 1,000 is
rejected. The project's tests probe 999.99 and 1,000.01, which is exactly the gap boundary
enumeration is designed to close.

### `null-handling-defect`

```text
R-001  A null username is rejected without throwing   FAILED
       CE-001 inputs: {username=null}
       actual: java.lang.NullPointerException: Cannot invoke "String.trim()" because "username" is null
R-002  Non-blank usernames are registered             VERIFIED
```

### `security-vulnerability`

Requirements are declared in `requirements.yaml` with `properties: [{type: security}]` and explicit
`paths`, so CodeWitness plans a security obligation for each:

```text
R-001  Database credentials are not embedded in source   FAILED
       CE-001  CW-SEC-001  CRITICAL  src/main/java/com/example/security/DatabaseConfig.java:19
R-002  SQL injection                                      FAILED
       CE-002  CW-SEC-002  HIGH      src/main/java/com/example/security/AccountRepository.java:47
```

No test runs: a deterministic analyzer demonstrating the defect is as good as a failing test, and the
obligation is marked `FAILED` by the analyzer rather than being overwritten by the strategies that
have nothing to execute.

### `mutation-surviving`

The implementation is correct and both requirements are `VERIFIED` by their scenarios. The project's
own suite is weak: it calls `describe(BigDecimal.ZERO)` without asserting anything, so PIT reports
`generated=8, killed=6, survived=2` and the run's limitations say:

```text
Test strength: 2 mutant(s) in the code covered by R-001 survived the test suite
(FeeSchedule#describe:50 -> changed conditional boundary). Nobody checked those behaviours:
surviving mutants are not a violation of the requirement, but they are a reason not to trust
unexecuted assumptions about it.
```

This is the deliberate design choice described in `docs/verification-model.md`: test strength is
evidence about the *tests*, not about the requirement, so it is reported loudly without silently
overriding a verdict that execution supports.

### `misleading-tests`

`GradeCalculator` is correct. The project's tests are not: one `@Test` method calls the code under
test and asserts nothing, and another asserts that a string has a non-negative length.

```text
R-001  Letter grades follow the documented boundaries   VERIFIED
R-002  The boundaries match the institution's policy    UNVERIFIED  (no scenario can express it)
findings: CW-TEST-001 MEDIUM src/test/java/com/example/grade/GradeCalculatorTest.java:27
```

A test that cannot fail is worse than no test, because it looks like evidence — so CodeWitness
reports it as a finding.

### `correct-implementation`

The control. A rolling 24-hour limit implemented with an explicit list of accepted timestamps,
`BigDecimal.compareTo`, and a closed `[t-24h, t]` window; one requirement, one `sequence` scenario
with boundary values and a 200-case random search.

```text
R-001  Rolling 24-hour withdrawal limit   VERIFIED
  OB-001 PROPERTY  VERIFIED  13 generated test case(s) executed and all passed
```

## Running them yourself

```bash
./build.sh
export PATH=/path/to/apache-maven/bin:$PATH
./tools/verify-all-fixtures.sh
```

Each fixture is a standalone project and can also be verified one at a time, from the repository root
with the `./codewitness` launcher:

```bash
./codewitness verify fixtures/withdrawal-ledger --requirements fixtures/withdrawal-ledger/requirements.md
./codewitness verify fixtures/python-withdrawal-ledger --requirements fixtures/python-withdrawal-ledger/requirements.md
./codewitness verify fixtures/rust-withdrawal-ledger --requirements fixtures/rust-withdrawal-ledger/requirements.md
./codewitness verify fixtures/go-withdrawal-ledger --requirements fixtures/go-withdrawal-ledger/requirements.md
./codewitness verify fixtures/typescript-withdrawal-ledger --requirements fixtures/typescript-withdrawal-ledger/requirements.md
./codewitness verify fixtures/javascript-withdrawal-ledger --requirements fixtures/javascript-withdrawal-ledger/requirements.md
./codewitness verify fixtures/security-vulnerability --requirements fixtures/security-vulnerability/requirements.yaml
```

Run data is written under `<fixture>/.codewitness/` and is ignored by git; remove it with
`./codewitness clean <fixture> --all`.

## Adding a fixture

See [CONTRIBUTING.md](../CONTRIBUTING.md): one defect, stated in a comment; requirements that
describe the intended behaviour; scenarios that can expose the defect; and an entry in the table
above plus an assertion in the end-to-end suite.
