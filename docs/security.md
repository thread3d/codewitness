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
The Maven, Gradle, Python, Cargo, Go and Node caches inside the container start empty, which is the
honest trade-off of isolation: the first run has to download what it needs, and with `--network=none`
it cannot, so container mode is most useful with a pre-populated image.

The image must contain the toolchain for the language under verification: a JDK image for Java (the
default), an image with Python 3 plus pytest and coverage.py if you want those phases for a Python
project, an image with the Rust toolchain for a Rust project, an image with the `go` tool for a Go
module, or an image with a Node runtime for a TypeScript project (22.6+) or a JavaScript project
(18.17+). The local-process sandbox needs no image and uses the interpreter or toolchain the project
already has. A run whose `sandbox.allow_network` is false also sets `CARGO_NET_OFFLINE` for Cargo,
`GOPROXY=off` for Go and the package-manager offline flags for Node, so a build that would need to
download something fails loudly instead of reaching the network.

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
