# Security model

CodeWitness compiles and runs arbitrary project code. It therefore treats every project under
verification as hostile, and it is explicit about how far that protection goes.

## What is protected

- **One path for execution.** Every command that touches project code — builds, tests, generated
  tests, analyzers, mutation runs — goes through a single `ExecutionSandbox`. Nothing else in the
  codebase starts a process. That makes the boundary one thing to review rather than a habit spread
  across the code.
- **A sanitised environment.** The child process does not inherit the host environment. Only a
  small allow-list is forwarded (`PATH`, `JAVA_HOME`, `LANG`, `LC_ALL`, `TZ`, `TMPDIR`, `HOME`,
  `GRADLE_USER_HOME`, `JAVA_TOOL_OPTIONS`, `SDKMAN_DIR`), so a build script that prints
  `$AWS_SECRET_ACCESS_KEY` prints nothing. Two details matter: `MAVEN_OPTS` is explicitly removed,
  but `JAVA_TOOL_OPTIONS` is forwarded and routinely carries proxy settings (`-Dhttps.proxyHost`,
  `-Dhttp.proxyHost`), so proxy configuration can still reach the build; and the allow-list is what
  the sandbox applies, so anything not named above — credentials, tokens, `HTTP_PROXY` and friends —
  is dropped rather than filtered.
- **An isolated working directory.** A build may only run inside the run workspace or the run
  directory. A command that names any other working directory is refused, not executed.
- **A pristine copy by default.** The project is copied into `<run-dir>/workspace` before anything
  runs, excluding build output, `.git` and previous run data. Generated tests therefore never appear
  in your working tree, and a run cannot corrupt the checkout it is verifying.
- **Bounded resources.** Every command has a hard timeout (default 15 minutes per command, and an
  overall run budget), capped output (256 KiB baseline, larger for build tools), and force-killing of
  the whole process on timeout. A build that hangs or floods stdout cannot wedge the tool.
- **Untrusted input at the model boundary.** Source code, requirement text and tool output are
  wrapped in explicitly marked untrusted sections when they are sent to a language model, and the
  system instructions say that instruction-like text inside them is data. A source comment reading
  "ignore all previous instructions" stays a comment.

## What is *not* protected

The default sandbox is `local-process` and it is **a resource-safety boundary, not a security
boundary**. Specifically:

- Project code still runs with the privileges of the user running CodeWitness. It can read any file
  that user can read, including files outside the workspace. It can write anywhere that user can
  write, except that the build tools are pointed at the workspace.
- Network access is *not* blocked in `local-process` mode. `sandbox.allow_network` documents the
  intent; only the container sandbox enforces it.
- A malicious build script can consume CPU, memory and disk until the timeout and budget stop it, but
  it can also do damage before being stopped.

Both facts are printed in every report, in the `environment.isolationLevel` and
`environment.sandboxDescription` fields, and in the run's `limitations` list. CodeWitness never claims
isolation it did not get.

## Container isolation

```yaml
sandbox:
  mode: docker
  image: eclipse-temurin:25-jdk
  container_workdir: /work
```

With `mode: docker` (and a reachable Docker daemon — CodeWitness probes for one and falls back,
loudly, if it is not there) every command runs as:

```
docker run --rm --network=none --cap-drop=ALL --security-opt=no-new-privileges \
           -v <workspace>:/work -w <work-dir> <image> <command>
```

The container sees only the project mount, has no network, no capabilities and no host credentials.
The Maven, Gradle, Python, Cargo, Go, Node, NuGet, Composer and Bundler caches inside the container start
empty, which is the honest trade-off of isolation: the first run has to download what it needs, and with
`--network=none`
it cannot, so container mode is most useful with a pre-populated image.

The image must contain the toolchain for the language under verification: a JDK image for Java (the
default), an image with Python 3 plus pytest and coverage.py if you want those phases for a Python
project, an image with the Rust toolchain for a Rust project, an image with the `go` tool for a Go
module, an image with a Node runtime for a TypeScript project (22.6+) or a JavaScript project
(18.17+), an image with the .NET SDK (8+) for a C# project, an image with CMake, a C or C++ compiler and
`gcov` for a C or C++ project, an image with PHP 8, Composer and a coverage driver (Xdebug or PCOV) for
a PHP project, an image with Ruby and Bundler for a Ruby project, an image with a Swift toolchain for a
Swift package, an image with Icarus Verilog for a Verilog or SystemVerilog design, or an image with a
JVM and the
project's own build tool for a Kotlin project — Kotlin
needs no image of its own, because it is compiled by the same `kotlinc` the project's Maven or Gradle
build already invokes. The local-process sandbox needs no image
and uses the interpreter or toolchain the project already has. A run whose `sandbox.allow_network` is
false also sets `CARGO_NET_OFFLINE` for Cargo, `GOPROXY=off` for Go and the package-manager offline
flags for Node, so a build that would need to download something fails loudly instead of reaching the
network.

NuGet is the exception: it has no switch that forbids the network the way those do, so
`allow_network: false` is not enforced for a `dotnet` restore, and the report says which sandbox and
which network position a run actually had rather than implying otherwise. What the .NET adapter does
instead is remove the requests that are not part of restoring a package — the certificate revocation
check of package signatures is set to its documented offline value, and NuGet's own vulnerability audit
is disabled on every command — and leave it to the build to fail loudly when a package is not in the
machine's cache, rather than pretending the network was never touched.

A `dotnet` run also keeps its own state in the run directory: the CLI home and NuGet's HTTP cache are
redirected under the run's artefact store, so a run cannot read or write a developer's global .NET
state. The NuGet package folder is deliberately left alone — it is where the machine already keeps the
packages it has restored, and pointing it somewhere else would turn every project into a download —
which is why the caveat that project code can read anything the user can read applies to it too.

C and C++ are in the same position as NuGet, in a different way. A `cmake` configure and build runs the
project's own build, and the build directory the adapter creates is inside the run rather than inside
the project, so the configure cache, the objects and the generated test executables are not left in the
tree being verified; but `allow_network: false` is not enforced for a CMake project that fetches its
dependencies with `FetchContent` or through a package manager such as Conan or vcpkg. CMake has no
switch that forbids the network the way `CARGO_NET_OFFLINE` or `GOPROXY=off` do, so a configure that
downloads a dependency will download it, and the report says which sandbox and which network position
the run actually had rather than implying otherwise. The C and C++ dependency phase reads the inventory
it can read — `conan.lock`, `vcpkg.json` and the CMake files — and says how many packages carry no
resolved version, instead of treating an unknown as a clean result.

Composer is in the same position as NuGet and CMake, for the same reason: CodeWitness sets no switch
that forbids the network for it, so `allow_network: false` does not stop a `composer install` from
reaching Packagist, and the report says which sandbox and which network position a run actually had
rather than implying otherwise. What the PHP adapter does instead is give each run its own Composer
state: `COMPOSER_HOME` and `COMPOSER_CACHE_DIR` are pointed under the run's artefact store, so a run
cannot read or write a developer's global Composer files or make one run's downloads visible to the
next. It also passes `COMPOSER_NO_AUDIT=1`, because CodeWitness runs its own advisory scan and a second
one would only cost time; `COMPOSER_NO_INTERACTION=1` and `COMPOSER_ALLOW_SUPERUSER=1`, because a run is
non-interactive and a container may run as root; and `COMPOSER_DISABLE_XDEBUG_WARN=1`, so Composer's Xdebug
performance warning cannot appear in output a parser reads. The PHP dependency phase reads the inventory
it can read — `composer.json` and, when it exists, `composer.lock` — and reports a version that is a
range, a wildcard or a branch alias as unresolved rather than treating an unknown as a clean result.

Bundler is in the same position as Composer, for the same reason: CodeWitness sets no switch that
forbids the network for it, so `allow_network: false` does not stop a `bundle install` from reaching
RubyGems, and the report says which sandbox and which network position a run actually had rather than
implying otherwise. What the Ruby adapter does instead is give each run its own Bundler state:
`BUNDLE_PATH`, `BUNDLE_APP_CONFIG`, `BUNDLE_USER_HOME`, `BUNDLE_USER_CACHE`, `BUNDLE_USER_CONFIG` and
`BUNDLE_USER_PLUGIN` are all pointed under the run's artefact store, so a run cannot read or write a
developer's global `~/.bundle` or gem home, or make one run's downloads visible to the next. It also
passes `BUNDLE_SILENCE_ROOT_WARNING=1`, because a run's workspace is often owned by root and the warning
is about the account rather than the project, and `BUNDLE_DISABLE_SHARED_GEMS=1`, so the dependency set a
run is tested against is exactly the one this project's lock file names. The Ruby dependency phase reads
the inventory it can read — `Gemfile.lock` — and reports a gem with no resolved version as unresolved
rather than treating an unknown as a clean result.

Swift is the one case where a nested sandbox is deliberately disabled. SwiftPM wraps manifest evaluation
in `sandbox-exec` on macOS, so a run already inside a boundary that forbids a nested one fails at
manifest compilation before a line of the package is read; the adapter therefore passes
`--disable-sandbox` to every SwiftPM command, and CodeWitness's own sandbox remains the declared
boundary, with `mode: docker` the one that is a security boundary. What the adapter does instead is give
each run its own SwiftPM state: `--cache-path`, `--config-path` and `--security-path`, plus
`CLANG_MODULE_CACHE_PATH` and `SWIFT_MODULECACHE_PATH`, are all pointed under the run's artefact store,
so a run cannot read or write a developer's `~/Library/org.swift.swiftpm`, their `~/.cache/org.swift.swiftpm`
or a shared Clang module cache, and one run's manifest compilation is not visible to the next. SwiftPM
has no switch that forbids the network the way Cargo's `CARGO_NET_OFFLINE` does, so
`allow_network: false` is not enforced for dependency resolution; the Swift fixture declares no
dependencies, so it resolves nothing and builds and tests offline. The Swift dependency phase reads the
inventory it can read — `Package.resolved` — and reports a pin with neither a version nor a revision as
unresolved rather than treating an unknown as a clean result.

Verilog is the case where the toolchain needs no state of its own. Icarus Verilog keeps no user-level
cache and reads no configuration from the environment, so a run redirects nothing and there is no
`~/.iverilog` to protect or to share between runs. `iverilog` compiles the design and `vvp` simulates
it, and both the compiled simulation and any waveform a testbench dumps are written inside the run's
artefact directory rather than into the tree being verified. The fixture's `sandbox.allow_network:
false` is honest because nothing in the run fetches: a combinational design with no manifest has no
dependency to resolve and the simulator reaches no network. There is also nothing to inventory — Verilog
has no package manager and there is no advisory ecosystem for HDL — so the dependency phase reads no lock
file and consults no advisory source, rather than reporting a clean result it cannot support.

## Recommendations

| Situation | Use |
| --- | --- |
| Your own project, your own machine | `local-process` (the default) |
| A project you did not write, or one produced by a model you do not fully trust | `mode: docker` |
| CI | `mode: docker`, with a prepared image and a read-only checkout |
| Anywhere credentials live in the environment | `mode: docker` |

Independent of the sandbox, the practical precautions are the same ones you would take before
running a stranger's build: run as a user with no valuable credentials, keep the environment clean,
and do not point it at repositories you cannot afford to have read.

## Reporting a security issue

CodeWitness is a tool for evaluating untrusted code, so a sandbox escape or a prompt-injection path
that reaches a verdict is a serious bug. Please report it privately rather than in a public issue, and
include the project or configuration that demonstrates it. Until a fix ships, the workaround is
`mode: docker`.
