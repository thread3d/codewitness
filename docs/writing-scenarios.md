# Writing scenarios

A **scenario** is how you tell CodeWitness how to execute one of your requirements against your code.
CodeWitness cannot guess your API, so you supply the setup, the calls and the invariant — the oracle —
and CodeWitness supplies the mechanical part: boundary enumeration, seeded random search, concurrency
scheduling, execution, and reproducible counterexamples.

Scenarios live in `codewitness.yaml` under `scenarios:`. The fragments (`setup`, `call`,
`observation`, `invariant`) are written in the **project's own language**: Java for a Java project,
Python for a Python project, Rust for a Rust project, Go for a Go module, TypeScript for a TypeScript
project, plain JavaScript for a JavaScript project, C# for a C#/.NET project, Kotlin for a Kotlin
project, C or C++ for a project whose sources are either — one adapter covers both, so the
fragment is written in whichever language the code under test is written in — PHP for a PHP
project, Ruby for a Ruby project, Swift for a Swift package, and Verilog for a Verilog or SystemVerilog
design (one adapter covers both, the way one adapter covers C and C++). Everything else — modes,
inputs, ranges, seeds, the counterexample protocol — is identical. The examples below are Java; the
Python, Rust, Go, TypeScript, JavaScript, C#, Kotlin, C/C++, PHP, Ruby, Swift and Verilog forms are
shown in [Python projects](#python-projects), [Rust projects](#rust-projects), [Go projects](#go-projects),
[TypeScript projects](#typescript-projects), [JavaScript projects](#javascript-projects),
[C# projects](#c-projects), [Kotlin projects](#kotlin-projects),
[C and C++ projects](#c-and-c-projects), [PHP projects](#php-projects),
[Ruby projects](#ruby-projects), [Swift projects](#swift-projects) and
[Verilog projects](#verilog-projects).

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
| `name` | Identifier, referenced as `${name}` inside `call`, `observation` and `invariant`; in PHP it becomes the PHP variable `$name`, in Ruby and Swift the bare local variable `name`, and in Verilog the bare signal `name`. TypeScript and JavaScript use `#{name}` instead; see the TypeScript and JavaScript sections. |
| `type` | The type of the generated variable: a Java type for Java (`java.math.BigDecimal`, `long`, `int`, `java.time.Instant`, `java.lang.String`), a Rust type such as `i64` or `String`, a Go type such as `int64`, `float64`, `bool` or `string`, a TypeScript type such as `bigint` or `number`, `bigint` for JavaScript (where `type` only selects `BigInt` wrapping and every other input is a plain value), a C# type such as `long`, `decimal`, `double`, `bool` or `string`, a Kotlin type such as `Long`, `Int`, `Double`, `Boolean`, `String` or a JVM class such as `java.math.BigDecimal`, or a C++ type such as `long long`, `int`, `double`, `bool`, `std::string` or a type the project itself declares, a PHP type name such as `int`, `float`, `string` or `bool`, a Ruby type name such as `int`, `float`, `bool` or `string`, a Swift type name such as `int`, `double`, `bool` or `string` (the generated locals are `Int`, `Double`, `Bool` and `String`), or a Verilog type name such as `integer`, `reg`, `logic` or `bit` (optionally with a width, as in `reg [31:0]`) or `time` — except that a Verilog input naming one of the design's own ports takes that port's declaration instead, so its width is the design's rather than the scenario's. |
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

That is the Java shape, and it is not the only one. Each language's section below says what its own
shape is: a standard-library `unittest` case for Python, a Cargo integration test for Rust, a
`_test.go` file in the package under test for Go, a `node:test` module for TypeScript and JavaScript,
an xUnit class in the tree's own test project for C#, a Kotlin JUnit 5 class in the tree's own test
sources for Kotlin, a PHPUnit `TestCase` class in the tree's own test root for PHP, an RSpec spec in the
tree's own test root for Ruby, an XCTest class in the tree's own test target for Swift, a generated
testbench module for Verilog that instantiates the design under test as `dut`, and, for C and C++,
one self-contained program with a `main` rather than a class
in a framework — written into the run's artefact store, never into the tree.

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
./codewitness reproduce <run-id> --counterexample CE-001
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

## C# projects

For a C# project the same scenario is written in C#. A generated test must live in a test project —
that is the only place the .NET SDK will compile and run it — so it is written into the tree's own test
project, in the namespace that project's tests already use, and `${name}` becomes a C# variable:

```yaml
language:
  adapter: csharp

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      var l = new WithdrawalLedger();
    steps:
      - call: "l.Withdraw(${t1}, ${a1})"
        inputs:
          - {name: t1, type: long, domain: integer, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: long, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
    observation: "l.TotalWithinLast24Hours(${t1})"
    invariant: "observation <= 100000"
    cases: 200
    seed: 42
```

Notes specific to C#:

- the fragments are C# **statements**, so each `call` ends in `;` (CodeWitness adds one if you leave it
  out); a step may be several statements, and a call whose value you do not need is simply a statement;
- the generated file imports `System`, `System.Collections.Generic`, `System.Globalization`,
  `System.Linq`, `System.Threading.Tasks`, `Xunit` and every namespace the project declares, so a
  scenario writes `new WithdrawalLedger()` without spelling out where the type lives. If two production
  namespaces declare the same type name the unqualified mention becomes ambiguous and the generated file
  does not compile, which the run reports as a build failure rather than as a defect in the code;
- `type` is a C# type the generator can name: `long`, `int`, `short`, `byte`, `sbyte`, `uint`, `ulong`,
  `ushort`, `double`, `float`, `decimal`, `bool`, `string`, `object` or `char`. `domain: decimal`
  generates a `decimal`, `domain: double` a `double`, `domain: boolean` a `bool`, `domain: string` a
  `string`, and the integer domains (`integer`, `long`, `instantMillis`) a `long`. Money is usually
  `long` minor units, as the C# fixture does. For anything else use `domain: raw` and write the value
  as C#;
- every generated input is discarded with `_ = name;`, so a fragment that does not mention an input does
  not produce a warning in a project that treats warnings as errors;
- a failing case is reported by throwing `Xunit.Sdk.XunitException` carrying the single
  `CODEWITNESS_COUNTEREXAMPLE|case=…|seed=…|inputs={…}|…` line. That is what puts the structured line
  into the TRX failure message, which is where the engine reads it from — which is why a counterexample
  contains the exact inputs rather than only a stack trace;
- `mode: concurrent` runs the step list once per worker across eight `Parallel.For` workers against one
  shared subject, then checks the invariant once. It is real parallelism on a multi-core machine, so an
  interleaving is more likely to be observed than under a single-threaded runner, but the workers are
  not released by a barrier: a concurrent scenario must still share the subject safely if the API is
  not already safe;
- generated classes are written into the test project's own directory while they run and are removed
  afterwards unless `report.keep_generated_tests` is set; a copy is always kept in the run's artefact
  store. Each class carries its own private helpers, so two scenarios in one project cannot collide;
- a tree with no test project gets no generated test at all, and the requirement stays `UNVERIFIED`
  rather than `BLOCKED`: nothing was attempted, and the report says so.

A generated class is named after the scenario and the obligation, in PascalCase — `SC-ROLLING` and
`OB-001` become `CwScRollingOb001`, declared in the test project's own namespace, so the full name is
`Ledger.Tests.CwScRollingOb001`. The seed and the case index are carried in the environment rather than
on the command line, because a `--filter` cannot express them:

```bash
env CODEWITNESS_SEED=42 CODEWITNESS_CASE=37 dotnet test tests/Ledger.Tests/Ledger.Tests.csproj \
    --nologo -p:NuGetAudit=false --filter FullyQualifiedName~CwScRollingOb001
```

`CODEWITNESS_SEED` and `CODEWITNESS_CASE` pin the search (and `CODEWITNESS_CASES` the case count);
`./codewitness reproduce <run-id> --counterexample CE-001` prints exactly this command.

C# rejection is expressed the same way, in C#. A step that throws is a failure by default, so assert the
throw rather than letting it escape:

```yaml
steps:
  - call: "Assert.Throws<System.ArgumentOutOfRangeException>(() => l.Withdraw(0, ${amount}));"
    description: an amount above the limit must be rejected by throwing
```

## Kotlin projects

For a Kotlin project the fragments are Kotlin statements, and the generated test is an ordinary Kotlin
JUnit 5 class written into the tree's own test sources — `src/test/kotlin` for a Maven build, the Kotlin
test source set for a Gradle one — so it is compiled and run by the build the project already uses:

```yaml
language:
  adapter: kotlin

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: "val ledger = com.example.account.WithdrawalLedger()"
    steps:
      - call: "ledger.withdraw(${t1}, ${a1})"
        inputs:
          - {name: t1, type: long, domain: long, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: java.math.BigDecimal, domain: decimal, values: ["700"],
             range: {min: 100, max: 900, scale: 2}}
    observation: "ledger.totalWithinLast24Hours(${t1})"
    invariant: "observation.compareTo(java.math.BigDecimal(\"1000\")) <= 0"
    cases: 200
    seed: 42
```

Notes specific to Kotlin:

- the fragments are Kotlin **statements**. `${name}` becomes a Kotlin local, so a `call` is written the
  way the API is used — `ledger.withdraw(t1, a1)`, with no semicolon and no `new`;
- `type` is the type the generator names in the case-runner signature. Java-primitive spellings are
  translated (`long` → `Long`, `int` → `Int`, `double` → `Double`, `boolean` → `Boolean`,
  `java.lang.String` → `String`), because those are also the spellings the `domain` table understands;
  anything else — a JVM class such as `java.math.BigDecimal`, `java.time.Instant` or a type from the
  project itself — is spelled the same way in Kotlin and is passed through unchanged. Every other local
  is declared with `val` and no annotation, so the initialiser decides its type and a scenario never has
  to name a Kotlin type for an input it draws;
- `domain: double` writes the literal with a decimal point (`1000.0`), because Kotlin has no `d` suffix
  and a bare integer literal does not convert to a `Double`;
- a `$` in an invariant or a step description is escaped in the generated string literal; Kotlin would
  otherwise read it as the start of a string template. The fragments themselves are code, so `${name}`
  is the only substitution CodeWitness performs on them;
- a failing case is reported by throwing `AssertionError` carrying the single
  `CODEWITNESS_COUNTEREXAMPLE|case=…|seed=…|inputs={…}|…` line. That is what puts the structured line
  into the JUnit failure text, which is where the engine reads it from — which is why a counterexample
  contains the exact inputs rather than only a stack trace. It throws rather than calling
  `Assertions.fail`, whose type variable Kotlin cannot always infer from a statement position;
- `mode: concurrent` runs the step list from eight threads against one shared subject, released
  together by a `CountDownLatch` so that a race is not hidden by thread start-up skew, and then checks
  the invariant once;
- generated classes are written into the test source root while they run and are removed afterwards
  unless `report.keep_generated_tests` is set; a copy is always kept in the run's artefact store. Each
  class carries its own private helpers, so two scenarios in one project cannot collide;
- a project whose generated test does not compile reports the requirement as `UNVERIFIED` with the
  compiler's own diagnostics in the evidence, rather than as a defect in the code.

A generated class is named after the scenario and the obligation and lives in
`dev.codewitness.generated`, so `SC-ROLLING` and `OB-001` become
`dev.codewitness.generated.Cw_SC_ROLLING_OB_001_Test`. The seed and the case index are passed as system
properties, which Surefire forwards to the test JVM; the generated class also reads the environment, so
the same class replays under Gradle, which does not forward system properties:

```bash
mvn -Dtest=Cw_SC_ROLLING_OB_001_Test -Dcodewitness.seed=42 -Dcodewitness.case=37 test
```

`./codewitness reproduce <run-id> --counterexample CE-001` prints exactly the command the run used.

Kotlin rejection is expressed the same way, in Kotlin. A step that throws is a failure by default, so
assert the throw rather than letting it escape:

```yaml
steps:
  - call: "runCatching { ledger.withdraw(0L, ${amount}) }.also { check(it.isFailure) }"
    description: an amount above the limit must be rejected by throwing
```

## C and C++ projects

For a C or C++ project the same scenario is written in C++ — or in C, if that is what the code under
test is written in, because one adapter covers both languages and one `LanguageId` (`cpp`) carries
them. A generated test is one self-contained C++ program with a `main`, written per scenario into the
run's artefact store and never into the tree: it is not a class in a test framework, and it is never
added to the project's `CMakeLists.txt`. The fragments are C++ statements and `${name}` becomes a C++
variable:

```yaml
language:
  adapter: cpp

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      auto l = ledger::WithdrawalLedger();
    steps:
      - call: "l.withdraw(${t1}, ${a1})"
        inputs:
          - {name: t1, type: long long, domain: integer, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: long long, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
      - call: "l.withdraw(${t2}, ${a2})"
        inputs:
          - {name: t2, type: long long, domain: integer, values: ["90000000"]}
          - {name: a2, type: long long, domain: integer, values: ["70000"]}
    observation: "l.totalWithinLast24Hours(${t2})"
    invariant: "observation <= 100000"
    cases: 200
    seed: 42
```

Notes specific to C and C++:

- `${name}` is replaced by the generated variable itself, so an input declared `long long` is written
  `${t1}` and not a cast. Money in the fixture is `long long` minor units and instants are epoch
  milliseconds, which is why no decimal type is needed;
- `type` is a C++ type such as `long long`, `int`, `double`, `bool`, `std::string` or a type the project
  declares. The generated declaration is `const <type> <name> = <value>;`, so a boundary value is emitted
  as the scenario wrote it in C++ syntax, and a value that does not fit the declared type is a compile
  error, which the run reports as a generated test that did not compile rather than as a defect;
- the fragments are C++ **statements**, so each `call` ends in `;` (CodeWitness adds one if you leave it
  out), and `setup` declares the subject with `auto` or with the project's own type;
- the generated file includes every header the project has, because a scenario fragment may name any of
  the project's types and the generator cannot read a C++ declaration to know which. A project whose
  headers cannot all be included in one translation unit therefore gets a generated test that does not
  compile, which the report says in those words. That is a stated limit rather than a hidden one;
- the program is compiled by the compiler the project's own build used, with the exact arguments that
  build recorded in `compile_commands.json`, and linked against the archives that build produced. The
  adapter's own configure always exports that file; a project whose build could not be configured has
  nothing to compile a generated test with, and the requirement stays `UNVERIFIED`;
- a failing case prints the single `CODEWITNESS_COUNTEREXAMPLE|case=…|seed=…|inputs={…}|…` line to
  standard output and exits non-zero. CTest records `Failed` as the failure message with the program's
  output beside it in `system-out`, and the report parser joins the two, which is why a counterexample
  contains the exact inputs rather than only a nonzero exit status;
- a generated test that does not compile leaves its requirement `UNVERIFIED`, never `BLOCKED`: nothing
  was demonstrated either way.

The compiled program is kept in the run's artefact store, so the replay command runs it directly with
the recorded seed and case:

```bash
env CODEWITNESS_SEED=42 CODEWITNESS_CASE=0 <run>/artifacts/generated-tests/CwScRollingOb001
```

C and C++ rejection is expressed the same way, through the invariant, because a step that throws is a
failure by default. The fixture's `withdraw` returns `void` and reports a rejection only through the
state it leaves, so `SC-NEGATIVE` records a withdrawal and then asserts on `totalWithinLast24Hours`; a
step whose API returns a value can instead check it inline, because `call` is spliced in as a C++
statement.

## PHP projects

For a PHP project the fragments are PHP statements and expressions, and the generated test is a PHPUnit
`TestCase` class written into the project's own test root — a PHP project's autoloader and PHPUnit
configuration are what make a class runnable, so there is nowhere else PHPUnit would find it.
`${name}` is replaced textually by the PHP variable `$name`, exactly as the Python generator replaces it
by the bare name, so a fragment writes `$ledger->withdraw(${t1}, ${a1})` and the variable the call sees is
`$t1`:

```yaml
language:
  adapter: php

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    description: Two withdrawals across midnight, one hour apart
    mode: sequence
    setup: |
      $ledger = new \CodeWitness\Fixture\WithdrawalLedger();
    steps:
      - call: "$ledger->withdraw(${t1}, ${a1});"
        inputs:
          - {name: t1, type: int, domain: integer, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: int, domain: integer, values: ["70000"], range: {min: 100, max: 90000}}
      - call: "$ledger->withdraw(${t2}, ${a2});"
        inputs:
          - {name: t2, type: int, domain: integer, values: ["90000000"]}
          - {name: a2, type: int, domain: integer, values: ["70000"]}
    observation: "$ledger->totalWithinLast24Hours(${t2})"
    invariant: "$observation <= 100000"
    cases: 200
    seed: 42
```

That scenario is the fixture's own, from `fixtures/php-withdrawal-ledger/codewitness.yaml`, and it is
what finds the calendar-day defect in the ledger: the boundary case is a withdrawal at 23:50 and
another at 01:00, 70,000 cents each, checked against the rolling limit.

Notes specific to PHP:

- `setup` is PHP statements run once per case, before the steps. A fragment that needs a file the
  project's autoloader does not cover can `require_once` it here; the generated class itself is written
  into the project's own test root, which is where the project's PHPUnit configuration looks for it;
- `observation` is a PHP expression assigned to `$observation`, and `invariant` is a PHP boolean
  expression over `$observation` and the inputs — `$observation <= 100000` in the example above. The
  invariant is checked after every step, exactly as it is in every other language;
- `type` is a PHP type name the generator can produce: `int`, `float`, `string` or `bool`. PHP has no
  typed local variables, so `type` describes the value the fragment receives rather than a declaration
  the generated file carries, and the variable is the plain `$name` the fragment writes. Money is
  usually integer minor units, as the PHP fixture does — cents — because PHP's `float` is binary
  floating point and PHP has no built-in decimal type. `domain` chooses how values are drawn, as it does
  everywhere else;
- the generated file is a PHPUnit `TestCase` and each case is one test method, so a failure is attributed
  by the ordinary JUnit report rather than by a line number the reader has to interpret. A generated
  class is named after the scenario, `CwSc<ScenarioId>Test`, where every character of the id that is not
  a letter or a digit becomes an underscore: `SC-ROLLING` generates the class `CwScSC_ROLLINGTest` in
  `tests/CwScSC_ROLLINGTest.php`, which is the file the project's PHPUnit configuration discovers;
- `mode: concurrent` is executed **sequentially**, and the generated file says so in a `LIMIT:` comment.
  PHP's standard build has no threads, so there is no barrier to release and no race to run; the
  alternative would be a generated test that names a concurrency check it never performed. Such a
  scenario is evidence about the sequence of the steps and nothing about interleaving;
- PHPUnit is a Composer dependency, not part of the toolchain: it exists only after the build phase has
  run `composer install`. The adapter resolves it at test time for exactly that reason, and a project
  that has none is reported as having no test runner rather than as passing — PHP has no
  standard-library test framework to fall back on;
- a failing case carries the single `CODEWITNESS_COUNTEREXAMPLE|case=…|seed=…|inputs={…}|…` line in its
  failure message. That is what puts the structured line into the JUnit `<failure>` text, which is where
  the engine reads it from — which is why a counterexample contains the exact inputs rather than only a
  stack trace;
- `CODEWITNESS_SEED` and `CODEWITNESS_CASE` in the environment pin one case for replay, and the
  reproduction command is a real PHPUnit invocation rather than a description of one:

```bash
env CODEWITNESS_SEED=42 CODEWITNESS_CASE=0 php vendor/bin/phpunit --filter 'CwScSC_ROLLINGTest'
```

PHP rejection is expressed the same way, in PHP. A step that throws is a failure by default, so catch
the exception yourself when throwing is the required behaviour — and catch the specific type, because a
bare `catch (\Throwable $e)` throws away the distinction between "rejected as required" and "crashed
for another reason":

```yaml
steps:
  - call: |
      try {
          $ledger->withdraw(0, ${amount});
      } catch (\InvalidArgumentException $expected) {
          // required behaviour: the withdrawal is rejected by throwing
      }
    description: an amount above the limit must be rejected by throwing
```

If the implementation rejects by returning a value, the invariant handles it and no wrapper is needed;
the PHP fixture's `withdraw` returns a boolean and its acceptance is observed through the state it
leaves.

## Ruby projects

For a Ruby project the fragments are Ruby statements and expressions, and the generated test is an
ordinary RSpec spec written under `<test root>/generated/` in the project's own test tree — RSpec
discovers a spec by its file name, and the `require 'spec_helper'` the generated file opens with is what
loads the project's own configuration, so there is nowhere else RSpec would find it. `${name}` is
replaced textually by the bare Ruby local variable `name`, exactly as the Python generator replaces it by
the bare name, so a fragment writes `@ledger.withdraw(${t1}, ${a1})` and the variable the call sees is
`t1`:

```yaml
language:
  adapter: ruby

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    description: Two withdrawals across midnight, one hour apart
    mode: sequence
    setup: |
      @ledger = WithdrawalLedger.new
    steps:
      - call: "@ledger.withdraw(${t1}, ${a1})"
        inputs:
          - {name: t1, type: int, domain: integer, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: int, domain: integer, values: ["70000"], range: {min: 100, max: 90000}}
      - call: "@ledger.withdraw(${t2}, ${a2})"
        inputs:
          - {name: t2, type: int, domain: integer, values: ["90000000"]}
          - {name: a2, type: int, domain: integer, values: ["70000"]}
    observation: "@ledger.total_within_last_24_hours(${t2})"
    invariant: "observation <= 100000"
    cases: 200
    seed: 42
```

That scenario is the fixture's own, from `fixtures/ruby-withdrawal-ledger/codewitness.yaml`, and it is
what finds the calendar-day defect in the ledger: the boundary case is a withdrawal at 23:50 and
another at 01:00, 70,000 cents each, checked against the rolling limit.

Notes specific to Ruby:

- `setup` is Ruby statements run once per case, before the steps. It is the scenario's own fixture, so it
  is executed inside each example rather than through a framework hook. A fragment that needs a file the
  project's `spec_helper` does not cover can `require` it here; the generated spec itself is written into
  the project's own first test root, under `generated/`, which is the tree RSpec scans;
- `observation` is a Ruby expression assigned to the local `observation`, and `invariant` is a Ruby
  boolean expression over `observation` and the inputs — `observation <= 100000` in the example above. It
  is checked with `expect(<invariant>).to be(true)`, so it must evaluate to exactly `true` or `false`: an
  expression that merely returns a truthy value fails, which is the strict reading of the contract. The
  invariant is checked after every step, exactly as it is in every other language;
- `type` is a Ruby type name the generator can produce: `int`, `float`, `bool` or `string`. Ruby has no
  typed local variables, so `type` describes the value the fragment receives rather than a declaration
  the generated file carries, and the variable is the bare `name` the fragment writes. The generator also
  reads the Ruby spellings (`integer`, `fixnum`, `bignum`, `double`, `rational`, `bigdecimal`, `boolean`,
  `trueclass`, `falseclass`, `symbol`) when no explicit `domain` is given. Money is usually integer minor
  units, as the Ruby fixture does — cents — because Ruby has no built-in decimal type. `domain` chooses how
  values are drawn, as it does everywhere else;
- the generated file is an ordinary RSpec spec named after the scenario: `cw_sc_<ScenarioId>_spec.rb`,
  where every character of the id that is not a letter or a digit becomes an underscore, so `SC-ROLLING`
  generates `spec/generated/cw_sc_SC_ROLLING_spec.rb`. It wraps one `RSpec.describe` block around one `it`
  per case, and each case is one example, so a failure is attributed by RSpec's own report rather than by
  a line number the reader has to interpret. The examples are named `cw_case0000`, `cw_case0001` and so on
  for the enumerated boundary cases, `cw_single_case` for a scenario that declares no inputs, and
  `cw_random_search` or `cw_concurrent_search` for the seeded search;
- `mode: concurrent` is executed **sequentially**, and the generated file says so in a `LIMIT:` comment.
  A scenario's fragments are arbitrary user code the generator cannot assume is thread-safe, and a step
  that raised inside a worker thread would have to be caught and re-reported by machinery the generated
  file does not own, so the case executes the declared steps in order and demonstrates nothing about
  interleaving;
- RSpec is a bundle dependency, not part of the toolchain: it exists only after the build phase has run
  `bundle install`. The adapter resolves it at test time for exactly that reason, and a project that has
  none is reported as having no test runner rather than as passing;
- a failing case raises the single `CODEWITNESS_COUNTEREXAMPLE|case=…|seed=…|inputs={…}|…` line from an
  `RSpec::Expectations::ExpectationNotMetError`. That is what puts the structured line into RSpec's JSON
  report, in the failing example's `exception.message`, which is where the engine reads it from — which is
  why a counterexample contains the exact inputs rather than only a stack trace;
- `CODEWITNESS_SEED` and `CODEWITNESS_CASE` in the environment pin one case for replay, because RSpec has
  no per-case seed flag of its own. The reproduction command is a real RSpec invocation rather than a
  description of one:

```bash
env CODEWITNESS_SEED=42 CODEWITNESS_CASE=0 bundle exec rspec spec/generated/cw_sc_SC_ROLLING_spec.rb
```

Ruby rejection is expressed the same way, in Ruby. A step that throws is a failure by default, so catch
the exception yourself when throwing is the required behaviour — and rescue the specific class, because a
bare `rescue` throws away the distinction between "rejected as required" and "crashed for another
reason":

```yaml
steps:
  - call: |
      begin
        @ledger.withdraw(0, ${amount})
      rescue ArgumentError
        # required behaviour: the withdrawal is rejected by throwing
      end
    description: an amount above the limit must be rejected by throwing
```

If the implementation rejects by returning a value, the invariant handles it and no wrapper is needed;
the Ruby fixture's `withdraw` returns a boolean and its acceptance is observed through the state it
leaves, exactly as the PHP fixture's does.

## Swift projects

For a Swift package the fragments are Swift statements and expressions, and the generated test is an
ordinary XCTest class written under `<test root>/Generated/` inside the project's own test target —
SwiftPM compiles only the Swift files that live inside a declared target's directory, so there is
nowhere else a generated class would be built. `${name}` is replaced textually by the local `name`, so a
fragment writes `ledger.withdraw(${t1}, ${a1})` and the variable the call sees is `t1`:

```yaml
language:
  adapter: swift

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    description: Two withdrawals across midnight, one hour apart
    mode: sequence
    setup: |
      let ledger = WithdrawalLedger()
    steps:
      - call: "ledger.withdraw(${t1}, ${a1})"
        inputs:
          - {name: t1, type: int, domain: integer, values: ["85800000"], range: {min: 0, max: 172800000}}
          - {name: a1, type: int, domain: integer, values: ["70000"], range: {min: 100, max: 90000}}
      - call: "ledger.withdraw(${t2}, ${a2})"
        inputs:
          - {name: t2, type: int, domain: integer, values: ["90000000"]}
          - {name: a2, type: int, domain: integer, values: ["70000"]}
    observation: "ledger.totalWithinLast24Hours(${t2})"
    invariant: "observation <= 100000"
    cases: 200
    seed: 42
```

That scenario is the fixture's own, from `fixtures/swift-withdrawal-ledger/codewitness.yaml`, and it is
what finds the calendar-day defect in the ledger: the boundary case is a withdrawal at 23:50 and another
at 01:00, 70,000 cents each, checked against the rolling limit.

Notes specific to Swift:

- `setup` is Swift statements run once per case, before the steps, and it stays in scope for them: the
  whole case is emitted inside one method body. A `let` declared in `setup` is therefore visible to the
  steps and to the observation, which is why the fixture writes `let ledger = WithdrawalLedger()`;
- `observation` is a Swift expression assigned to `let observation`, and `invariant` is a Swift boolean
  expression over `observation` and the inputs — `observation <= 100000` in the example above. It is
  checked after every step, exactly as it is in every other language;
- `type` is a Swift type name the generator can produce: `int`, `double`, `bool` or `string`, generating
  the Swift locals `Int`, `Double`, `Bool` and `String`. The generator also reads the Swift spellings
  (`Int`, `Int64`, `Double`, `Bool`, `String`, `boolean`) when no explicit `domain` is given. Money is
  usually integer minor units, as the Swift fixture does — cents — because Swift's `Double` is binary
  floating point; `Decimal` is a type a project may declare and is not one this generator draws;
- the generated file is an ordinary XCTest class named after the scenario: `CwSc<ScenarioId>Tests`,
  where every character of the id that is not a letter or a digit becomes an underscore, so `SC-ROLLING`
  generates `Tests/<target>/Generated/CwScSC_ROLLINGTests.swift`. It opens with `import XCTest` and
  `@testable import <library target>`, and its methods are `testCwCase0000`, `testCwCase0001` and so on
  for the enumerated boundary cases, `testCwSingleCase` for a scenario that declares no inputs, and
  `testCwSearch` for the seeded search;
- **every generated method name begins with `test`** because XCTest discovers a test method by that
  prefix and by nothing else. A method named `cw_case0000` compiles and then runs zero cases, and
  `swift test --filter` selects only from the discovered set, so a replay filter for such a method
  matches nothing. This is the one place the Swift generator's names differ from the other adapters';
- the class is written into the project's own test target and is reported under that target's module
  (`WithdrawalLedgerTests.CwScSC_ROLLINGTests`), while it imports the package's library target
  (`WithdrawalLedger`) — a test target cannot import itself, so the two names are different facts;
- `mode: concurrent` is executed **sequentially**, and the generated file says so in a `LIMIT:` comment,
  for the same reason the Ruby and PHP adapters state it: a scenario's fragments are arbitrary user code
  the generator cannot assume is safe under Swift concurrency;
- a failing case calls `XCTFail` with the single
  `CODEWITNESS_COUNTEREXAMPLE|case=…|seed=…|inputs={…}|…` line. XCTest prints that message on the
  compiler-style error line that precedes the case's closing line, which is where the console parser
  reads it from — which is why a counterexample contains the exact inputs rather than only a stack
  trace;
- `CODEWITNESS_SEED` and `CODEWITNESS_CASE` in the environment pin one case for replay, because
  `swift test` has no seed option that selects a search case. The reproduction command is a real
  `swift test --filter <Module.Class>/<method>` invocation:

```bash
env CODEWITNESS_SEED=42 CODEWITNESS_CASE=0 swift test --filter WithdrawalLedgerTests.CwScSC_ROLLINGTests/testCwSearch
```

Swift rejection is expressed the same way, in Swift. A step that throws is a failure by default, so
catch the error yourself when throwing is the required behaviour — and catch the specific error type,
because a bare `catch` throws away the distinction between "rejected as required" and "crashed for
another reason":

```yaml
steps:
  - call: |
      do {
          try ledger.withdraw(0, ${amount})
      } catch let error as LedgerError {
          // required behaviour: the withdrawal is rejected by throwing
      }
    description: an amount above the limit must be rejected by throwing
```

If the implementation rejects by returning a value, the invariant handles it and no wrapper is needed;
the Swift fixture's `withdraw` returns a boolean and its acceptance is observed through the state it
leaves, exactly as the Ruby and PHP fixtures do.

## Verilog projects

For a Verilog or SystemVerilog design the fragments are Verilog statements and expressions, and the
generated test is an ordinary testbench module written under `generated-tests/` in the project. The
generator instantiates the design under test as `dut` and connects the declared inputs to its ports, so
a fragment refers to an input by its bare signal name: `${name}` is replaced textually by `name`, and a
`call` is a Verilog statement rather than a method invocation. A combinational design has no procedure
to call, so the fixture's steps are the empty statement `;` and the inputs alone are the whole
invocation:

```yaml
language:
  adapter: verilog

scenarios:
  - id: SC-DEBIT
    requirement: R-001
    description: An accepted withdrawal reduces the balance by exactly the amount
    mode: single
    setup: ""
    steps:
      - call: ";"
        description: the ledger is combinational, so the inputs are the whole invocation
        inputs:
          - name: balance
            type: integer
            domain: integer
            values: ["100000"]
            range: {min: 100000, max: 100000}
          - name: amount
            type: integer
            domain: integer
            values: ["1", "99999"]
            range: {min: 1, max: 99999}
    observation: "dut.new_balance"
    invariant: "dut.accepted == 1 && observation == balance - amount"
    cases: 1
    seed: 1
```

That scenario is the fixture's own, from `fixtures/verilog-withdrawal-ledger/codewitness.yaml`, and it
is what finds the one-cent defect in the ledger: the boundary cases drive `balance = 100000` with an
`amount` of `1` and `99999`, and check that the accepted branch reduces the balance by exactly the
amount requested.

Notes specific to Verilog:

- `setup` is Verilog statements run once per case, before the steps, and each step's `call` is a Verilog
  statement executed in order. A combinational design declares no procedure to call, so a step's `call`
  is the empty statement `;` and the declared inputs are the whole invocation; a sequential design can
  drive its clock and reset in `setup` and pulse them in a `call`;
- `observation` is a Verilog expression assigned to a 64-bit register named `observation` after a `#1`
  delay, so a combinational design has settled before the value is sampled. `invariant` is a boolean
  Verilog expression over `observation` and the inputs — `dut.accepted == 1 && observation == balance -
  amount` in the example above — and it is checked after every step, exactly as it is in every other
  language;
- inputs are declared at the design's own port widths: the generator reads the design under test's ports
  and declares each input as a `reg` of the same width. An input whose name is not one of the design's
  ports takes the scenario's own `type` when that is a type the generator can write — `integer`, `time`,
  or `reg`/`logic`/`bit` with an optional width such as `reg [31:0]` — and a 64-bit register otherwise.
  `real` falls into that fallback deliberately, because the counterexample line formats every input with
  `%0d`. A name that is a port keeps the port's own width, so a value wider than the design can hold is
  truncated by Verilog's own rules rather than by a guess at the generator's;
- boundary values come from each input's `values` list plus `range.min` and `range.max`, enumerated as up
  to twelve combinations, exactly as in the other languages. `mode: sequence` and `mode: concurrent` add
  a seeded `$urandom_range(min, max)` search when the scenario declares a non-negative `range`, so a
  failing random case is reproducible from the recorded seed and case;
- the generated file is a testbench module named `cw_sc_<scenario>_<obligation>`, where every character
  of the scenario id that is not a letter or a digit becomes an underscore. It instantiates the design
  under test as `dut`, drives the inputs, and checks the invariant; a failing case prints the single
  `CODEWITNESS_COUNTEREXAMPLE|case=…|seed=…|inputs={…}|…` line and the testbench calls `$fatal` once at
  the end, so the simulation exits non-zero and the engine records a failing test rather than a pass.
  The counterexample's inputs are signal values driven onto the design's ports, which is why a Verilog
  counterexample names signals such as `balance` and `amount` rather than call arguments;
- Verilog has no case-level test protocol, so one test case per testbench is reported, under the
  testbench file's own name. A testbench that does not compile is an error rather than a failing test,
  and there is no single test command: a Verilog test is two processes, so the reproduction command
  compiles the kept generated testbench together with the project and then simulates it:

```bash
iverilog -g2012 -s cw_sc_SC_DEBIT_OB_001 \
    -o build.vvp rtl/ledger.v tb/ledger_tb.v generated-tests/cw_sc_SC_DEBIT_OB_001.v
vvp -N build.vvp
```

Verilog rejection has no exception mechanism to catch. A design signals rejection through its own
outputs — the fixture's ledger simply lowers `accepted` and leaves `new_balance` unchanged — so the
invariant is where the required behaviour is expressed: an over-limit withdrawal is a `call` that drives
the inputs and an invariant requiring `accepted == 0` and an unchanged balance. When a design's own
protocol signals rejection with `$error` or `$fatal`, the step trips it and the simulation fails by
itself, and the counterexample carries the line the simulator printed. If the implementation rejects by
a value on an output, the invariant handles it and no wrapper is needed, exactly as the Ruby, PHP and
Swift fixtures do for a boolean result.

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
