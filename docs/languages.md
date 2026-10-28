# Languages

Every language below is detected automatically, and a project may be written in several. These are the quick
starts; what each adapter needs installed, and what it can and cannot verify, follows them.

Verify a Java project:

```bash
./codewitness verify ./my-java-project --requirements requirements.md
```

or a Python project:

```bash
./codewitness verify ./my-python-project --requirements requirements.md
```

or a Rust project:

```bash
./codewitness verify ./my-rust-crate --requirements requirements.md
```

or a Go module:

```bash
./codewitness verify ./my-go-module --requirements requirements.md
```

or a TypeScript project:

```bash
./codewitness verify ./my-typescript-project --requirements requirements.md
```

or a JavaScript project:

```bash
./codewitness verify ./my-javascript-project --requirements requirements.md
```

or a C#/.NET project:

```bash
./codewitness verify ./my-dotnet-project --requirements requirements.md
```

or a Kotlin project:

```bash
./codewitness verify ./my-kotlin-project --requirements requirements.md
```

or a CMake C++ project:

```bash
./codewitness verify ./my-cmake-project --requirements requirements.md
```

or a PHP project:

```bash
./codewitness verify ./my-php-project --requirements requirements.md
```

or a Ruby project:

```bash
./codewitness verify ./my-ruby-project --requirements requirements.md
```

or a Swift package:

```bash
./codewitness verify ./my-swift-package --requirements requirements.md
```

or a Verilog project:

```bash
./codewitness verify ./my-verilog-project --requirements requirements.md
```

or a VHDL design:

```bash
./codewitness verify ./my-vhdl-design --requirements requirements.md
```

or a SQL project:

```bash
./codewitness verify ./my-sql-project --requirements requirements.md
```

or a Pascal project:

```bash
./codewitness verify ./my-pascal-project --requirements requirements.md
```

The language is detected, and a project may be written in several. `LanguageAdapterRegistry.selectAll`
runs **every** adapter that claims part of the tree — a Java service with a TypeScript frontend is two
verifiable projects, and one of them being read is not a reason for the other to disappear. Phases that
belong to a language run once per language against that language's own description; a language whose build
fails blocks only the requirements written in it, and the run is `BLOCKED` only when that failure actually
stopped one from being verified.

JavaScript and TypeScript coexist in one tree. They own disjoint extensions — `js`/`mjs`/`cjs` against
`ts`/`tsx`/`mts`/`cts` — so the split is at the file level, and a TypeScript frontend next to plain
JavaScript tooling is verified in both rather than losing one.

The report says `polyglot` and lists every language it accounted for under `languages`, each entry marked
`verified` or not, so the verdicts cannot be read as if the whole tree were one language. A language the
tree contains that **no** adapter understands is an entry in that same list, marked not verified and naming
the file that gave it away — a language that appears nowhere in the report is indistinguishable from one
that is not there. Recognition comes from `LanguageDetector` implementations discovered like adapters are,
so naming a language CodeWitness cannot verify is a module and a provider file, not an edit to the engine.

An adapter declares the phases it supports (`Capability`) and registers the providers that implement them. The
two are recorded in different places, so the engine checks them against each other at the start of every run: an
adapter that registers a coverage provider while not declaring `COVERAGE` would have that phase skipped with the
provider sitting unused, and that is now a stated limitation and a self-issue rather than a quietly narrower
verification.

`language.adapter: java`, `python`, `rust`, `go`, `typescript`, `javascript`, `csharp`, `kotlin`,
`cpp`, `php`, `ruby`, `swift`, `verilog`, `vhdl`, `sql` or `pascal`
*narrows* the run
to that one adapter instead of being merged with the automatic choice. It is not forced: the named adapter
is used when it exists and actually claims the project, and otherwise the run falls back to
auto-detection — an unknown id is silently ignored rather than reported.

Python support needs only an interpreter: it uses `pytest` when the project has it and the standard library `unittest` runner
otherwise, so a project with no third-party packages can still be verified. Coverage needs `coverage.py`
importable by that same interpreter, because the suite is re-run under it; when it is not importable the
report says coverage was not measured and names the reason, so a project without it is verified exactly as
thoroughly in every other phase. CI installs `coverage.py` and asserts it, so the phase is exercised rather
than merely reported — the arrangement PHP's Xdebug driver has, and the one Python lacked until 1.0.18.
Rust support needs only
`cargo`: a dependency-free crate is built, tested, analysed and mutated by the toolchain it already
has, and generated scenarios become ordinary Cargo integration tests. Go support needs only the `go`
tool: a dependency-free module is built,
tested, analysed and mutated by the toolchain it already has, coverage comes from
`go test -coverprofile`, and generated scenarios become ordinary `_test.go` files in the package
under test. TypeScript support needs only Node 22.6 or later: a project with no `node_modules` is
syntax-checked, tested, analysed and mutated by the runtime it already has, and generated scenarios
become ordinary `node:test` modules. JavaScript support needs only Node 18.17 or later: there is no
compiler and no type check anywhere in the pipeline — the build is a syntax check with the runtime's
own parser — and a project with no `node_modules` is checked, tested, analysed and mutated by the
runtime it already has, with generated scenarios becoming ordinary `node:test` modules. C# support
needs only the .NET SDK: a solution is built with `dotnet build` and tested with `dotnet test`,
restore is left enabled but `-p:NuGetAudit=false` is passed on every command, because CodeWitness runs
its own advisory scan and NuGet's own audit would make each build depend on api.nuget.org, and
generated scenarios become xUnit cases in the tree's own test project. Kotlin support needs only the
project's own build tool: a Maven build with `kotlin-maven-plugin` or a Gradle build with the Kotlin
plugin is compiled by `kotlinc` through that build, its Kotlin compiler diagnostics are read from the
format the compiler itself prints, coverage comes from JaCoCo over the same JVM bytecode a Java
project produces, and generated scenarios become ordinary Kotlin JUnit 5 classes in the tree's own
test sources. C and C++ support needs only CMake and CTest: a tree whose `CMakeLists.txt` declares a
`project()` and at least one C or C++ source is configured and built with `cmake`, its own CTest
suite is run and read from the JUnit report CTest writes, static analysis reads a C and C++ rule
table, coverage comes from `gcov` over a second configure that adds `--coverage` to the flags, the
package inventory is read from `conan.lock`, `vcpkg.json` and the CMake files, and generated
scenarios become one self-contained C++ translation unit each, compiled with the arguments the
project's own build recorded and linked against the archives it produced. A tree built by a
hand-written `Makefile` or by Meson is recognised and reported as present and not verified, because
this adapter drives CMake and does not guess at a build it does not understand. PHP support needs only
an interpreter and Composer: a tree with a `composer.json` is linted with the interpreter's own parser,
its dependencies are resolved with `composer install`, its PHPUnit suite is run and read from the JUnit
report PHPUnit writes, and generated scenarios become ordinary PHPUnit `TestCase` classes in the tree's
own test root. PHPUnit is the one test tool here that is not part of the toolchain — it is a Composer
dependency, so it exists only after the build has resolved it, and a project that has none is reported
as having no test runner rather than quietly passing. Ruby support needs only an interpreter and
Bundler: a tree with a `Gemfile` is linted with the interpreter's own parser, its gems are resolved with
`bundle install`, its RSpec suite is run and read from the JSON report RSpec writes, and generated
scenarios become ordinary RSpec spec files in the tree's own `spec/` root. RSpec is the second test tool
here that is not part of the toolchain — it is a bundle dependency, so it exists only after the build has
resolved it, and a project that has none is reported as having no test runner rather than quietly
passing. Swift support needs only the Swift toolchain: a package with a `Package.swift` is compiled with
`swift build --build-tests`, its XCTest suite is run with `swift test` and read from the runner's own
console, coverage comes from SwiftPM's exported LLVM coverage report, the package inventory is read from
`Package.resolved`, and generated scenarios become ordinary XCTest classes in the tree's own test
target. XCTest ships with the toolchain, so unlike PHP and Ruby there is no test tool to resolve, and a
package with no external dependencies needs no network to build, test or verify. A tree with `.swift`
files but no `Package.swift` — a CocoaPods project, say — is recognised and reported as present and not
verified, because this adapter drives Swift Package Manager and does not guess at a build it does not
understand. Verilog support needs only Icarus Verilog: a tree holding `.v`, `.sv`, `.vh` or `.svh`
files is claimed, the design under test is the first module with ports that is not a testbench, and
the tree is compiled with `iverilog -g2012` over every source and testbench while each testbench is
simulated with `vvp -N`. There is no case-level test protocol in the language, so one test case per
testbench is reported under the testbench file's own name, and a testbench that does not compile is
an error rather than a failing test. Icarus Verilog writes its diagnostics to the console rather than
to a machine-readable report, so a compile error is read from Icarus's own line shapes and is a
failed build. Static analysis reads a Verilog rule table, and generated scenarios become ordinary
testbench modules that instantiate the design under test as `dut`. Icarus Verilog exports no coverage
data and Verilog has no package manager or advisory ecosystem for HDL, so coverage and the dependency
inventory are reported as not measured rather than as zero. VHDL support needs only GHDL: a tree holding
`.vhd` or `.vhdl` files is claimed, each design unit is analysed with
`ghdl -a --std=08 --workdir=<lib>` into a work library inside the run's artefact directory, the first
entity with a port clause that is not a testbench is elaborated with `ghdl -e --std=08 --workdir=<lib>`,
and each testbench is simulated with `ghdl -r --std=08 --workdir=<lib> <testbench> --assert-level=error`.
GHDL is one executable with three roles, so the whole toolchain is the `ghdl` the machine already has.
Analysis is order-sensitive, so the sources are analysed one at a time and retried until no pass makes
progress, which discovers the dependency order rather than requiring it; `--assert-level=error` is
essential rather than tidy, because without it GHDL prints an assertion error and still exits zero, so a
failing design would be reported as passing. The revision is VHDL-2008, whose `std.env.finish` the adapter
uses. There is no case-level test protocol and no test framework, so one test case per testbench is
reported under the testbench file's own name, and a tree that does not analyse is a failed build rather
than a failing test. GHDL writes its diagnostics to the console rather than to a machine-readable report,
so a build error is read from GHDL's own line shapes. Static analysis reads a VHDL rule table, and
generated scenarios become ordinary testbench entities that declare a signal named after each of the
design's ports and connect it, because a component instance's ports cannot be selected by name. GHDL's
coverage export is not a form this adapter reads and VHDL has no package manager or advisory ecosystem,
so coverage and the dependency inventory are reported as not measured rather than as zero.
Pascal support needs only Free Pascal: a
tree holding `.pas`, `.pp`, `.lpr`, `.dpr` or `.p` files is claimed, the programs it declares are
compiled with `fpc`, and a tree of units with no program is compiled unit by unit as the language's
first-failure check. Unit and executable output go below the run's artefact directory, so a verification
leaves no `.ppu`, `.o` or binary in the tree. Tests are the project's own fpcunit suites: a program that
links them is compiled and run through the console runner, and a tree that keeps test units but no runner
is given a synthesized one, because fpcunit registers its suites when they are linked. A test program that
is not an fpcunit runner is judged by its exit status, and the console runner's JUnit-shaped XML report is
read per test; a test program that does not compile is an error rather than a failing test. Static
analysis reads a Pascal rule table, generated scenarios become ordinary programs named `cw_sc_…` that link
the unit under test and print a counterexample line, and mutation testing flips Pascal's own operators
(`=`, `<>`, `<=`, `>=`, `and`, `or`, `div`, `mod`, `shl`, `shr`) while leaving `{ }` and `(* *)` comments
and string literals whole. Free Pascal's base distribution exports no coverage data and has no package
manager or advisory ecosystem, so coverage and the dependency inventory are reported as not measured
rather than as zero.
