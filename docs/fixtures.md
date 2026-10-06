# Fixture projects

A verification tool is only worth trusting if it can be shown to distinguish code that is wrong from
code that is right. Every fixture in `fixtures/` is deliberately broken in a specific way — or
deliberately correct — and the end-to-end tests assert that CodeWitness reaches the expected verdict.

The results below were produced by `tools/verify-all-fixtures.sh`, which runs
`codewitness verify <fixture> --requirements <fixture>/requirements.md --max-duration 10m` for each
fixture and prints the outcome.

| Fixture | Type of defect | Overall | Requirements | What it proves |
| --- | --- | --- | --- | --- |
| `withdrawal-ledger` | four defects: rolling window, discarded `BigDecimal`, negative amounts, race | `FAILED` | 4 `FAILED`, 1 `UNVERIFIED` | Several independent defects in one project, each mapped to its own requirement, each with a reproducible counterexample; and an honest `UNVERIFIED` for a requirement nobody can execute. |
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

Each fixture is a standalone Maven project and can also be verified one at a time:

```bash
codewitness verify fixtures/withdrawal-ledger --requirements fixtures/withdrawal-ledger/requirements.md
codewitness verify fixtures/security-vulnerability --requirements fixtures/security-vulnerability/requirements.yaml
```

Run data is written under `<fixture>/.codewitness/` and is ignored by git; remove it with
`codewitness clean <fixture> --all`.

## Adding a fixture

See [CONTRIBUTING.md](../CONTRIBUTING.md): one defect, stated in a comment; requirements that
describe the intended behaviour; scenarios that can expose the defect; and an entry in the table
above plus an assertion in the end-to-end suite.
