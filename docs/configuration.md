# Configuration

`codewitness.yaml` at the project root; every value has a conservative default.

```yaml
project:
  name: payment-service

language:
  adapter: auto          # auto | java | python | rust | go | typescript | javascript | csharp | kotlin | cpp | php | ruby | swift | verilog | vhdl | sql | pascal

verification:
  timeout: 15m           # per build or test command
  max_duration: 15m      # whole run
  max_test_generation_iterations: 5
  max_generated_tests: 500
  max_mutation_time: 10m

mutation:      { enabled: true, tool: pitest }
property_testing: { enabled: true, max_scenarios: 50, concurrent: true }
static_analysis:  { enabled: true, analyzers: [javac, heuristics] }
security:
  enabled: true
  vulnerabilities:       # dependency advisories; off by default, and needs network access
    enabled: false
    source: osv          # osv
    endpoint: https://api.osv.dev
    timeout: 30s
    max_dependencies: 500
    fail_on: high        # critical | high | medium | low | none
tests:         { enabled: true }

llm:
  enabled: false         # advisory only, off by default
  provider: none         # none | openai-compatible
  api_key_env: CODEWITNESS_LLM_API_KEY
  max_calls: 0

report:  { html: true, json: true, sarif: false, keep_runs: 0 }   # keep_runs prunes old runs after a verify
findings:
  baseline: null         # a previous report.json; findings it already contains are not reported again
  suppress: []           # [{rule: CW-CONC-001, path: null, reason: "..."}]
sandbox:
  mode: local-process    # local-process | docker
  allow_network: false

database:                # an ephemeral database for tests and scenarios; off by default
  enabled: false
  engine: sqlite         # sqlite
  schema: db/schema.sql  # one .sql file, or a directory of them applied in file-name order
  seed: db/seed.sql      # optional, applied after the schema
  url: null              # or a connection URL the project manages itself; then nothing is created
  timeout: 30s
```

A database that is prepared is exported to every process the run starts as `CODEWITNESS_DATABASE_URL`,
so a test or a generated scenario reaches it in whatever language it is written, and the database
itself is kept in the run directory so a counterexample that names a table can be replayed against the
schema that was actually loaded. A requested database that cannot be prepared is a reported limitation
and leaves the requirements that needed one `UNVERIFIED`; it never turns into a pass.

The language model is optional, disabled by default, and never decides anything. When it is enabled,
its output is stored as `LLM_ANALYSIS` evidence with `ADVISORY` confidence,
`generatedByLlm = true` and `executedIndependently = false`, and it is reported as an unexecuted
suggestion. Source code and project files are treated as untrusted data and fenced accordingly, so
an "ignore all previous instructions" comment inside a source file stays data.
