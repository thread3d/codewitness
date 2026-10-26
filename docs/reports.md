# Reports and reproducibility

Every run produces both:

- `report.json` — the canonical machine-readable representation, with run id, environment, status,
  per-requirement verdicts, obligations, evidence, counterexamples, test/mutation/coverage/
  static-analysis summaries, phases, budget usage and limitations. `status` is the verdict aggregate and
  `runStatus` the pipeline's own outcome, and they are separate keys because a run can be `BLOCKED` while
  every requirement it reached is `VERIFIED`. `build` records what the build phase attempted, ran and
  observed: the command, the exit status and the compiler's error and warning counts;
- every report is written through a text guard that replaces an unpaired surrogate with U+FFFD, because a
  hostile test report can carry one and `Files.writeString` throws on it before writing anything — which used to
  lose both report files and the durable record with them. The HTML report additionally carries a
  `Content-Security-Policy` that allows nothing but its own inline stylesheet, and its tables are bounded at 500
  rows with the truncation stated in the report, so a project's own warning count cannot make the report
  unopenable;
- `report.html` — a self-contained human report that starts with an executive summary and then shows
  the evidence, including an appendix listing every evidence record with the command that produced it.
  Both the overall status and the run status appear in the header, a run that did not complete says so in
  a banner, and the build phase has its own section — a report that showed only the verdicts rendered a
  blocked run as a green page. When two verdicts are recorded for one requirement id (a repeated heading
  in the requirements document), the most severe is shown and the collision is stated.

Run data lives in `<project>/.codewitness/runs/<run-id>/`: the report files, the raw artifacts
(generated test sources, test and coverage reports) and an isolated copy of the project. Metadata is
also indexed in `<project>/.codewitness/codewitness.db` (SQLite).

Each run keeps a copy of the project, so a history grows with the tree: `report.keep_runs: N` prunes all
but the N most recent runs after a successful `verify` (deleting their index rows with their directories),
and `clean` does the same on demand. Retention is off by default, because deleting a history nobody asked
to delete is not a safe default.

Two runs against the same project contend for one SQLite writer. The loser waits 15 s for the lock and, if it
still cannot get it, retries the write with a doubling backoff before reporting the failure — and the failure
names the contention, so it cannot arrive as a generic storage error. A read-only directory or a corrupt file
is never retried, because waiting cannot fix either.

Reading a history is a separate operation from writing one. `report`, `reproduce` and `listRuns` open the
database read-only and never create it, and any storage restriction that makes the *index* unreadable — a
read-only mount, a directory the process may not write, a database another run holds — is reported as a
statement about the history with exit `3`, not as an internal failure of the tool. A corrupt database is
the opposite case: that is a defect in CodeWitness, and it is journalled. Where the index cannot be read,
`report <run-id>` still works, because it reads the stored report files directly.

A report is only useful if it can be reproduced, so every run records: the project identifier, the
source revision, the CodeWitness version, the runtime version (the Java version for a Java project,
the .NET SDK version for a C# one, the Kotlin release for a Kotlin one, the CMake version for a C and
C++ one, the PHP version for a PHP one, the Ruby version for a Ruby one, the Swift version for a Swift
one, the Icarus Verilog version for a Verilog one, the GHDL release for a VHDL one), the build tool and its version,
the verification configuration fingerprint, every tool version, the language model identifier if any,
the requirements hash, the isolation level, timestamps and every command that was executed. Evidence
records carry their command and inputs, and counterexamples carry a replayable command.
