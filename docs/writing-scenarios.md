# Writing scenarios

A **scenario** is how you tell CodeWitness how to execute one of your requirements against your code.
CodeWitness cannot guess your API, so you supply the setup, the calls and the invariant — the oracle —
and CodeWitness supplies the mechanical part: boundary enumeration, seeded random search, concurrency
scheduling, execution, and reproducible counterexamples.

Scenarios live in `codewitness.yaml` under `scenarios:`. The fragments (`setup`, `call`,
`observation`, `invariant`) are written in the **project's own language**: Java for a Java project,
Python for a Python project. Everything else — modes, inputs, ranges, seeds, the counterexample
protocol — is identical. The examples below are Java; the Python forms are shown in
[Python projects](#python-projects).

```yaml
scenarios:
  - id: SC-ROLLING
    requirement: R-001
    description: Two withdrawals across midnight, one hour apart
    mode: sequence
    setup: "com.example.account.WithdrawalLedger ledger = new com.example.account.WithdrawalLedger();"
    steps:
      - call: "ledger.withdraw(java.time.Instant.ofEpochMilli(${t1}), ${a1})"
        description: first withdrawal at 23:50
        inputs:
          - name: t1
            type: long
            domain: long
            values: ["85800000"]
            range: {min: 0, max: 172800000}
          - name: a1
            type: java.math.BigDecimal
            domain: decimal
            values: ["700"]
            range: {min: 100, max: 900, scale: 2}
      - call: "ledger.withdraw(java.time.Instant.ofEpochMilli(${t2}), ${a2})"
        description: second withdrawal at 01:00 the next day
        inputs:
          - name: t2
            type: long
            domain: long
            values: ["90000000"]
          - name: a2
            type: java.math.BigDecimal
            domain: decimal
            values: ["700"]
    observation: "ledger.totalWithinLast24Hours(java.time.Instant.ofEpochMilli(${t2}))"
    invariant: "observation.compareTo(new java.math.BigDecimal(\"1000\")) <= 0"
    cases: 300
    seed: 42
```

## Fields

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Scenario identifier, used in obligation ids, generated class names and the report. |
| `requirement` | yes | The requirement id this scenario executes. Scenarios are only attached to that requirement. |
| `description` | no | Human readable description, shown in evidence. |
| `mode` | no | `single` (default), `sequence` or `concurrent`. |
| `setup` | no | Statements executed once per case, before the steps, in the project's language. Variables declared here are visible to every step, to `observation` and to `invariant`. |
| `steps` | yes | One or more calls executed in order for each case. |
| `observation` | no | An expression in the project's language, evaluated after each step and made available as a variable named `observation` to `invariant`. |
| `invariant` | no | A boolean expression that must be true. A false value fails the case and produces a counterexample. |
| `cases` | no | Number of randomised cases (default 100). Ignored in `mode: single` when there is nothing to search. |
| `seed` | no | Random seed (default 42). Recorded in evidence so a failing case can be replayed exactly. |
| `timeout_seconds` | no | Per-case budget hint recorded with the scenario. |

## Inputs

Each step declares the inputs it searches over:

| Field | Meaning |
| --- | --- |
| `name` | Identifier, referenced as `${name}` inside `call`, `observation` and `invariant`. |
| `type` | The Java type of the generated variable, for example `java.math.BigDecimal`, `long`, `int`, `java.time.Instant`, `java.lang.String`. |
| `domain` | How values are drawn: `decimal`, `integer`, `long`, `double`, `boolean`, `string`, `instantMillis`, `null`, `raw`. Defaults from `type`. |
| `values` | Explicit values used to build deterministic boundary cases. |
| `range` | `{min, max, scale}` used for the seeded random search. |
| `scale` | Decimal scale for `decimal` domains (in `range`). |

**`${name}` is replaced by the generated variable itself, not by a literal.** So an input declared as
`java.math.BigDecimal` is used directly: write `${amount}`, not `new java.math.BigDecimal(${amount})`.
If you would rather build the object yourself in the call, declare the input as `java.lang.String`
with `domain: string` and construct it in the fragment.

Boundary cases are the Cartesian product of every input's `values` list, capped at twelve cases. If
any input declares no `values`, no boundary cases are generated and only the random search runs.

## Modes

- **`single`** — runs the step list once per boundary case. With no inputs at all, one `singleCase()`
  test runs the steps exactly once. Use it for fixed sequences, for example "record a withdrawal,
  apply a fee, check the total".
- **`sequence`** — additionally runs `cases` random draws; the invariant is checked after every step,
  so order-dependent bugs (a rolling window, a state machine) are exercised.
- **`concurrent`** — additionally runs `cases` random draws in which the step list is executed by
  eight threads released simultaneously against one shared subject, then checks the invariant once.
  Use it for check-then-act races and lost updates.

## How it becomes a test

For each obligation CodeWitness writes a JUnit 5 class into
`src/test/java/dev/codewitness/generated/`, compiles it with the project's own build tool, runs
exactly that class, parses the reports and then deletes the source (keeping a copy in the run
directory). Nothing about the generated test is special at runtime — you can read it, and you can run
it by hand.

A failing case fails the test with one structured line:

```
CODEWITNESS_COUNTEREXAMPLE|case=37|seed=42|inputs={t1=85800000, a1=700, t2=90000000, a2=700}|expected=…|actual=1400|invariant=…
```

CodeWitness parses that line, which is why counterexamples contain exact inputs rather than only a
stack trace. To replay a specific case:

```bash
mvn -Dtest=Cw_SC_ROLLING_OB_001_Test -Dcodewitness.seed=42 -Dcodewitness.case=37 test
```

or simply:

```bash
codewitness reproduce <run-id> --counterexample CE-001
```

(For Gradle, which does not forward `-D` to the test JVM, the generated tests read the seed from
`CODEWITNESS_SEED` and `CODEWITNESS_CASE` in the environment; CodeWitness prints the right form.)

## What an invariant can and cannot express

The invariant is checked after each step, so it sees the variables, the shared subject and
`observation`. That covers properties of the form "after this call, the observable state must satisfy
X". It does not cover:

- properties that must hold *during* a call;
- properties that the API does not expose an observation for — if you cannot observe it, CodeWitness
  cannot check it, and the requirement will stay `UNVERIFIED`;
- anything requiring a second, independent implementation as an oracle (write that as your
  `observation`, and say so in the requirement).

Keep fragments small and obvious. A scenario is the part of the verification that you can get wrong,
so it should be easy to review — and remember that a scenario is a *claim about how to test*, not
evidence on its own: CodeWitness only counts what it executes.

## Rejection: how to express "this must fail"

A common requirement is "an amount above the limit must be rejected". If the implementation rejects by
*returning a value*, the invariant handles it. If it rejects by *throwing*, note that the generated
runner treats any exception escaping a step as a failure — because an unexpected exception is a
failure. To say "throwing here is correct", wrap the call yourself inside the step fragment:

```yaml
steps:
  - call: "try { ledger.withdraw(${amount}); } catch (java.lang.IllegalArgumentException expected) { /* required behaviour */ }"
    description: an amount above the limit must be rejected by throwing
```

This works because `call` is spliced into the generated method as a Java **statement**, not as an
expression, so it can be a `try`/`catch`, an `if`, or several statements. Two cautions: a bare
`catch (Exception e) { }` throws away the distinction between "rejected as required" and "crashed for
another reason", so catch the specific type; and a swallowed exception leaves nothing to observe, so
check the state afterwards with an `observation` if it matters.

## Python projects

For a Python project the same scenario is written in Python. `type` is a Python type (or the `domain`
field is given explicitly), and `${name}` becomes a Python variable:

```yaml
language:
  adapter: python

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      from ledger import WithdrawalLedger
      ledger = WithdrawalLedger()
    steps:
      - call: "ledger.withdraw(${t1}, ${a1})"
        inputs:
          - {name: t1, type: int, domain: integer, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: decimal.Decimal, domain: decimal, values: ["700"], range: {min: 100, max: 900, scale: 2}}
    observation: "ledger.total_within_last_24_hours(${t1})"
    invariant: "observation <= decimal.Decimal(\"1000\")"
    cases: 200
    seed: 42
```

Notes specific to Python:

- generated tests are standard-library `unittest` cases, so they run whether or not pytest is
  installed; pytest collects them unchanged when it is present;
- the generated module is saved in the run directory and put on `PYTHONPATH`, so the replay command in
  the report runs the saved module directly and no generated file is left in your project;
- put any imports a fragment needs in `setup` (for example `from ledger import WithdrawalLedger`).
  CodeWitness puts the project's detected source roots on `PYTHONPATH` but does not guess your modules;
- `mode: concurrent` uses `threading`. The CPython GIL means a data race is not guaranteed to
  interleave, so a passing concurrent case is evidence rather than proof;
- the invariant is checked after every step, so an invariant that only holds after all steps belongs
  in a single compound step — the fixture's fee scenario does exactly that.

Python rejection is expressed the same way, in Python:

```yaml
steps:
  - call: "try:\n    ledger.withdraw(amount)\nexcept ValueError:\n    pass\n"
    description: an amount above the limit must be rejected by raising ValueError
```

## Practical advice

- Prefer two or three focused scenarios over one large one: the report is easier to read, and a
  failure points at the case that matters.
- Give the boundary values you actually care about (`999.99`, `1000`, `1000.01`) — the random search is
  a search, not a proof, and it is bad at hitting exact boundaries.
- Choose `range` values that make a violation reachable. A random search that never crosses midnight
  will never find a calendar-day bug.
- If a scenario fails for an infrastructure reason rather than a violation, the report says so and
  the requirement stays unverified; you will see the tool's output in the evidence record.
- If you are unsure whether the requirement is even executable, leave the scenario out: an
  `UNVERIFIED` verdict with the reason "no scenario declared" is a much better outcome than a
  scenario that quietly cannot detect anything.
