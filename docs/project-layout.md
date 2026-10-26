# Project layout

```
codewitness/
├── codewitness-core/          language-independent verification core
│   ├── model/                 project, build, test, coverage, mutation, finding models
│   ├── evidence/              the evidence model and ledger
│   ├── requirements/          requirement parsing, properties, obligations
│   ├── result/                verdicts, counterexamples, report, budget, run record
│   ├── spi/                   LanguageAdapter, VerificationTools, registries, context
│   ├── engine/                the pipeline, verdict calculator, strategies, the finding baseline
│   ├── exec/                  sandbox and process execution
│   ├── io/                    isolated workspace and artifact store
│   ├── config/                codewitness.yaml
│   └── llm/                   advisory provider abstraction and prompt fencing
├── codewitness-adapter-support/ helpers shared by more than one adapter: the adapter base class, the
│                              hardened XML loader, the boundary-case enumeration every scenario generator
│                              uses, the security-phase forwarding, source mutation, the mutation loop, and
│                              the Node toolchain
├── codewitness-adapter-java/  Java 17+ projects (module targets 25), Maven and Gradle, JUnit 5, PIT, JaCoCo
├── codewitness-adapter-python/ Python 3 projects: syntax build, pytest/unittest, heuristics, security,
│                              coverage.py, AST mutation, dependency inventory; no third-party package required
├── codewitness-adapter-rust/   Rust projects: Cargo build and libtest, rustc diagnostics, heuristics,
│                               security, cargo-llvm-cov, built-in source mutation, dependency inventory;
│                               no third-party crate required
├── codewitness-adapter-go/     Go modules: go build and go test -json, go vet diagnostics, heuristics,
│                               security, go test -coverprofile coverage, built-in source mutation,
│                               module inventory; no third-party module required
├── codewitness-adapter-typescript/ TypeScript projects: Node syntax check and tsc, node:test with JUnit
│                               parsing, heuristics, security, V8 coverage from LCOV, built-in source
│                               mutation, dependency inventory; no third-party package required
├── codewitness-adapter-javascript/ JavaScript projects: Node syntax check only (no compiler, no type
│                               check), node:test with JUnit parsing, heuristics, security, V8
│                               coverage from LCOV, built-in source mutation, dependency inventory;
│                               no third-party package required
├── codewitness-adapter-csharp/  C#/.NET projects: dotnet build over the tree's solution, dotnet test
│                               with TRX parsing, Roslyn diagnostics, heuristics, security, coverlet
│                               cobertura coverage, bounded source mutation, NuGet inventory
├── codewitness-adapter-kotlin/  Kotlin projects: Gradle or Maven with the Kotlin plugin, kotlinc
│                               diagnostics, JUnit XML reports, heuristics, security, JaCoCo
│                               coverage, bounded source mutation, Maven and Gradle inventories
├── codewitness-adapter-cpp/     C and C++ projects: CMake configure and build, clang/gcc/MSVC
│                               diagnostics, CTest with JUnit parsing, heuristics, security, gcov
│                               coverage, bounded source mutation, Conan/vcpkg/CMake inventory
├── codewitness-adapter-php/     PHP projects: the interpreter's own parse and composer install,
│                               PHPUnit with JUnit parsing, heuristics, security, Xdebug/PCOV
│                               coverage, bounded source mutation, Composer/Packagist inventory
├── codewitness-adapter-ruby/    Ruby projects: the interpreter's own parse and bundle install,
│                               RSpec with JSON parsing, heuristics, security, SimpleCov coverage,
│                               bounded source mutation, Bundler/RubyGems inventory
├── codewitness-adapter-swift/   Swift packages: swift build and swift test with XCTest results read
│                               from the runner's console, heuristics, security, LLVM coverage,
│                               bounded source mutation, Package.resolved inventory
├── codewitness-adapter-verilog/ Verilog and SystemVerilog projects: iverilog -g2012 build and vvp -N
│                               testbench simulation, heuristics, security, bounded source mutation;
│                               no coverage export and no dependency inventory
├── codewitness-adapter-vhdl/    VHDL designs: ghdl -a analysis into a work library, ghdl -e elaboration
│                               and ghdl -r testbench simulation, heuristics, security, bounded source
│                               mutation; no coverage export and no dependency inventory
├── codewitness-adapter-sql/    SQL projects: schema and migration loading into an ephemeral SQLite
│                               database as the language's first failure, declared SQL test scripts
│                               whose assertion queries must return no rows, generated constraint probes,
│                               schema mutation, heuristics and security; no coverage and no dependency
│                               inventory
├── codewitness-adapter-pascal/ Pascal projects: fpc compilation with diagnostics and the project's own
│                               fpcunit suites compiled and run with per-test XML results, heuristics,
│                               security and bounded source mutation, generated scenario programs;
│                               no coverage export and no dependency inventory
├── codewitness-storage/       SQLite persistence
├── codewitness-report/        canonical JSON and human HTML reports
├── codewitness-cli/           command line interface
└── fixtures/                  deliberately defective projects used to test all of this
```

Language support lives entirely in the adapters: adding a language means adding a module that
implements `dev.codewitness.core.spi.LanguageAdapter` and registering its providers — not editing the
engine. Recognising a language CodeWitness has no adapter for is the same shape: a module implementing
`dev.codewitness.core.spi.LanguageDetector`, discovered the same way, and the detectors for the languages
nothing here implements live in `codewitness-language-detectors`. A few platform defaults do remain in core
— the `LanguageId` and `BuildSystemId` constants (`JAVA`, `PYTHON`, `RUST`, `GO`, `JAVASCRIPT`,
`TYPESCRIPT`, `CSHARP`, `KOTLIN`, `CPP`, `PHP`, `RUBY`, `SWIFT`, `VERILOG`, `VHDL`, `SQL`, `PASCAL`, `CMAKE`,
`COMPOSER`, `BUNDLER`, `SWIFTPM`, `ICARUS`, `GHDL`, `FPC`, `POLYGLOT` and the rest), the typed
`PackageCoordinate` an advisory
lookup needs (`PACKAGIST` for PHP, `RUBYGEMS` for Ruby and `SWIFT_URL` for Swift among them — Verilog and
VHDL add none, because HDL has no advisory ecosystem, and so does Pascal, because Free Pascal's base
distribution has no package manager), and the excluded-directory and build-wrapper names
in `WorkspaceManager` — but they are
vocabulary and platform defaults, not language logic, and the engine never switches on a language.
Nothing in core names a language it cannot already verify.
