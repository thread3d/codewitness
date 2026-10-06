# Writing scenarios

A **scenario** is how you tell CodeWitness how to execute one of your requirements against your code.
CodeWitness cannot guess your API, so you supply the setup, the calls and the invariant — the oracle —
and CodeWitness supplies the mechanical part: boundary enumeration, seeded random search, concurrency
scheduling, execution, and reproducible counterexamples.

Scenarios live in `codewitness.yaml` under `scenarios:`. The fragments (`setup`, `call`,
`observation`, `invariant`) are written in the **project's own language**: Java for a Java project,
Python for a Python project, Rust for a Rust project, Go for a Go module, TypeScript for a TypeScript
project, plain JavaScript for a JavaScript project. Everything else — modes, inputs, ranges, seeds, the
counterexample protocol — is identical. The examples below are Java; the Python, Rust, Go, TypeScript
and JavaScript forms are shown in [Python projects](#python-projects), [Rust projects](#rust-projects),
[Go projects](#go-projects), [TypeScript projects](#typescript-projects) and
[JavaScript projects](#javascript-projects).

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
| `name` | Identifier, referenced as `${name}` inside `call`, `observation` and `invariant`. TypeScript and JavaScript use `#{name}` instead; see the TypeScript and JavaScript sections. |
| `type` | The type of the generated variable: a Java type for Java (`java.math.BigDecimal`, `long`, `int`, `java.time.Instant`, `java.lang.String`), a Rust type such as `i64` or `String`, a Go type such as `int64`, `float64`, `bool` or `string`, a TypeScript type such as `bigint` or `number`, or `bigint` for JavaScript (where `type` only selects `BigInt` wrapping and every other input is a plain value). |
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

## Rust projects

For a Rust project the same scenario is written in Rust. The generated test is an ordinary Cargo
integration test under `tests/`, so it calls the crate by its **crate name** (the package name with
hyphens replaced by underscores), and `${name}` becomes a Rust variable:

```yaml
language:
  adapter: rust

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      let mut ledger = withdrawal_ledger::WithdrawalLedger::new();
    steps:
      - call: "ledger.withdraw(${t1}, ${a1});"
        inputs:
          - {name: t1, type: i64, domain: integer, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: i64, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
    observation: "ledger.total_within_last_24_hours(${t1})"
    invariant: "observation <= 100000"
    cases: 200
    seed: 42
```

Notes specific to Rust:

- the fragments are Rust **statements**, so each `call` ends in `;` (CodeWitness adds one if you leave
  it out); `setup` declares the subject with `let` and the step list mutates it as your API requires;
- `type` is a Rust type such as `i64`, `u32`, `f64`, `bool`, `String` or `Option<i64>`. When it is a
  standard primitive CodeWitness annotates the generated variable with it and casts drawn values to
  it; for any other type, use `domain: raw` and write the value as Rust code;
- Rust has no standard decimal type, so `domain: decimal` generates an `f64`. Model money with integer
  minor units (as the Rust fixture does) rather than a binary float;
- anything printed in a counterexample must implement `Debug`: the generated test renders the
  observation and each input with `format!("{:?}", …)`;
- generated tests live in the workspace's `tests/` directory while they run and are removed afterwards
  unless `report.keep_generated_tests` is set; a copy is always kept in the run's artefact store;
- `mode: concurrent` uses `std::thread::scope` and a barrier. Scoped threads borrow the subject, so a
  concurrent scenario must give the threads shared access — put the subject behind
  `std::sync::Mutex` (or another interior-mutability type) in `setup`;
- an unexpected panic in a step is converted into a counterexample whose observed result is the panic
  message, so "must reject by panicking" can be expressed with `#[should_panic]`-style logic inside a
  step only if you catch it yourself; otherwise treat a panic as a failure.

Rust rejection is expressed the same way, in Rust. A step that returns a `Result` is easiest to write
explicitly:

```yaml
steps:
  - call: "let rejected = ledger.withdraw(${amount}, 0).is_err(); assert!(rejected, \"expected rejection\");"
    description: an amount above the limit must be rejected with Err
```

## Go projects

For a Go module the same scenario is written in Go. The generated test is an ordinary `_test.go` file
placed **in the package under test**, so it needs no import, may call exported and unexported
identifiers alike, and `${name}` becomes a Go variable:

```yaml
language:
  adapter: go

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      l := New()
    steps:
      - call: "l.Withdraw(${t1}, ${a1})"
        inputs:
          - {name: t1, type: int64, domain: integer, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: int64, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
    observation: "l.TotalWithinLast24Hours(${t1})"
    invariant: "observation <= 100000"
    cases: 200
    seed: 42
```

Notes specific to Go:

- the fragments are Go **statements**; a `call` does not need a trailing semicolon, and a returned
  value may be discarded by calling the function as a statement (as the fixture does with `Withdraw`);
- `type` is a Go type such as `int64`, `int`, `float64`, `bool` or `string`. When it is a standard
  primitive CodeWitness annotates the generated variable with it and casts drawn values to it; for any
  other type, use `domain: raw` and write the value as Go code;
- Go rejects unused local variables. Every generated **input** is blanked with `_ = name`, but a
  variable your `setup` declares must be used by a step or an observation, or the generated file will
  not compile;
- a step that panics is converted into a counterexample whose observed result is the panic message, so
  a rejection that Go expresses by panicking can be asserted; an invariant violation reports through
  `t.Errorf` and does not abort the other cases;
- the generated file imports a fixed set of standard packages (`fmt`, `math`, `os`, `regexp`, `sort`,
  `strconv`, `strings`, `sync`, `time`, `errors`) and blank-references each one, so an unused import
  never breaks the build; a fragment that needs another package cannot use it;
- `domain: decimal` generates a `float64`. Model money with integer minor units (as the Go fixture
  does) rather than a binary float;
- generated tests are written into the package directory while they run and are removed afterwards
  unless `report.keep_generated_tests` is set; a copy is always kept in the run's artefact store;
- `mode: concurrent` starts eight goroutines behind a channel barrier and then checks the invariant.
  Go has no scoped threads, so a concurrent scenario must share the subject safely (a `sync.Mutex` in
  `setup`, for example) if the API is not already safe.

Go rejection is expressed the same way, in Go. A step that returns a boolean is easiest to assert
inline:

```yaml
steps:
  - call: "if l.Withdraw(0, ${amount}) { panic(\"expected rejection\") }"
    description: an amount above the limit must be rejected
```

## TypeScript projects

For a TypeScript project the same scenario is written in TypeScript. The generated test is a
`node:test` module written beside the project's own tests, so a relative import in `setup` resolves
from that directory, and `#{name}` becomes a TypeScript variable:

```yaml
language:
  adapter: typescript

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      import { WithdrawalLedger } from "../src/ledger.ts";
      const ledger = new WithdrawalLedger();
    steps:
      - call: "ledger.withdraw(#{t1}, #{a1});"
        inputs:
          - {name: t1, type: bigint, domain: integer, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: bigint, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
    observation: "ledger.totalWithinLast24Hours(#{t1})"
    invariant: "observation <= 100000n"
    cases: 200
    seed: 42
```

Notes specific to TypeScript:

- inputs are referenced as `#{name}`, **not** `${name}`: `${...}` is template-literal interpolation in
  TypeScript, so `#{...}` is used instead and cannot collide with it. A `${...}` inside a fragment is
  left exactly as written;
- an `import` line in `setup` is hoisted to the top of the generated module, because an import cannot
  appear inside a function; everything else in `setup` runs inside each case;
- `type` is a TypeScript type the generator can produce: `number`, `bigint`, `string` or `boolean`.
  Money is usually `bigint` minor units, as the TypeScript fixture does. For anything else use
  `domain: raw` and write the value as TypeScript;
- the fragments are TypeScript **statements**, so each `call` ends in `;` (CodeWitness adds one if you
  leave it out);
- generated modules use only Node's standard library, so they run with `node --test` on Node 22.6 or
  later by stripping type annotations. Only erasable TypeScript can run this way: `enum`, `namespace`
  with a runtime value and parameter properties need a compiler, and `.tsx` files cannot be executed;
- generated tests are written into the project's first test directory while they run and are removed
  afterwards unless `report.keep_generated_tests` is set; a copy is always kept in the run's artefact
  store;
- `mode: concurrent` runs eight async workers released together by a barrier and then checks the
  invariant once, which exercises interleavings across `await` points; it is not true parallelism;
- an unexpected exception in a step becomes a counterexample whose observed result is its message.

TypeScript rejection is expressed the same way, in TypeScript:

```yaml
steps:
  - call: "const accepted = ledger.withdraw(#{amount}, 0n); if (accepted) { throw new Error(\"expected rejection\"); }"
    description: an amount above the limit must be rejected
```

## JavaScript projects

For a JavaScript project the same scenario is written in plain JavaScript, with no type annotations.
The generated test is an ordinary `node:test` module written beside the project's own tests, so a
relative import in `setup` resolves from that directory, and `#{name}` becomes a JavaScript variable:

```yaml
language:
  adapter: javascript

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      import { WithdrawalLedger } from "../src/ledger.js";
      const ledger = new WithdrawalLedger();
    steps:
      - call: "ledger.withdraw(#{t1}, #{a1});"
        inputs:
          - {name: t1, type: bigint, domain: integer, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: bigint, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
    observation: "ledger.totalWithinLast24Hours(#{t1})"
    invariant: "observation <= 100000n"
    cases: 200
    seed: 42
```

Notes specific to JavaScript:

- inputs are referenced as `#{name}`, **not** `${name}`: `${...}` is template-literal interpolation in
  JavaScript, so `#{...}` is used instead and cannot collide with it. A `${...}` inside a fragment is
  left exactly as written;
- an `import` line in `setup` is hoisted to the top of the generated module, because an import cannot
  appear inside a function; everything else in `setup` runs inside each case;
- the generated file is always written with the `.test.mjs` extension, so it is an ES module whatever
  the project's `package.json` `"type"` says, and it needs no dependency beyond the Node standard
  library. **In a CommonJS project this matters for `setup`:** the fragment's `import` must use the
  form Node's ESM/CJS interop supports. A named import works when the CommonJS module exposes its
  exports statically (`exports.Foo = …` or `module.exports = { Foo }`); otherwise import the module's
  default and read the member from it. The build check and the project's own tests still follow the
  project's module type, because `node --check` and `node --test` both honour `package.json`
  `"type"` — only the generated scenario test is unconditionally ESM;
- the fragments are plain JavaScript **statements**, so each `call` ends in `;` (CodeWitness adds one
  if you leave it out); a step, `observation` or `invariant` is spliced in as written and is never
  type-annotated;
- `type` only selects `bigint` wrapping: an input declared `type: bigint` is generated as a
  `BigInt(...)`, and every other input is a plain JavaScript value. `domain` chooses how values are
  drawn. Money is usually `bigint` minor units, as the JavaScript fixture does; for anything else use
  `domain: raw` and write the value as JavaScript;
- generated modules run with `node --test` on Node 18.17 or later. Node 18.17 is the first release
  whose JUnit reporter CodeWitness reads;
- generated tests are written into the project's first test directory while they run and are removed
  afterwards unless `report.keep_generated_tests` is set; a copy is always kept in the run's artefact
  store;
- `mode: concurrent` runs eight async workers released together by a barrier and then checks the
  invariant once, which exercises interleavings across `await` points; it is not true parallelism;
- an unexpected exception in a step becomes a counterexample whose observed result is its message.

JavaScript rejection is expressed the same way, in JavaScript:

```yaml
steps:
  - call: "const accepted = ledger.withdraw(#{amount}, 0n); if (accepted) { throw new Error(\"expected rejection\"); }"
    description: an amount above the limit must be rejected
```

There is no type check anywhere in a JavaScript verification: the build is a syntax check with Node's
own parser and nothing more. A project that has a `tsconfig.json` or any `.ts`/`.tsx`/`.mts`/`.cts`
source is deliberately left to the TypeScript adapter, which can actually type check it.

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
