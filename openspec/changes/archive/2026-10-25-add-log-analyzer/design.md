# Design

## Context

Greenfield repository: only the task statement (docs/task_APM_log_analyzer.md), the example snippets in `examples/` and OpenSpec setup exist. Motivation is in proposal.md; behavior is in the three specs. The budget is ~3 hours, so the design favors few files and plain code over framework features.

## Goals / Non-Goals

**Goals:**
- A pipeline whose stages are visible in code and in the stderr trace: parse → extract signals → enrich → diagnose → validate/repair.
- Everything except the diagnosis stage is deterministic and unit-testable without an API key.
- The recovery loop is testable with a scripted fake model.

**Non-Goals:**
- Streaming, tool calling / function calling, multi-agent setups, RAG or vector stores.
- Caching of diagnoses or historical incident correlation.
- Rule-only (offline) diagnosis or degraded fallback output.

## Decisions

### Pipeline and module layout

Single Gradle module, package `analyzer`:

```
Main.kt            CLI: args, env config, exit codes, stderr trace
LogParser.kt       text → List<LogEntry> + notes + unparsed lines
SignalExtractor.kt entries → Signals (adapters, layers, errors, order types, window, metrics, components)
SystemContext.kt   loads resources/system-context.md + known adapters / tools / categories
Diagnoser.kt       builds prompt, calls ChatModel, runs validate/repair loop
Validator.kt       parse + normalize + validate → (Diagnosis?, List<ValidationError>)
Model.kt           data classes for LogEntry, Signals, Diagnosis (mirrors the task schema)
```

Each stage is a pure function of the previous stage's output except `Diagnoser`, which takes the `ChatModel` as a constructor parameter. Alternative considered: LangChain4j `AiServices` with an agent abstraction — rejected because it hides the raw text we need to validate, and the "agent" here is a fixed pipeline, not open-ended tool use.

### Deterministic parsing and signal extraction

Regexes over the known log layout: header `^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([^\]]+)\] (\w+)\s+(\S+)`, continuation = indented line, note = line starting with `...`. Signals use small keyword/regex tables (HTTP status, `"code":"X"`, `\w+Exception`, `timed out after (\d+)ms`, `X-Sign`, `No terminal link`, `decline_code=`, `key=value` numbers with thousands separators). Alternative considered: LLM extraction — rejected for cost, latency and non-determinism; the extracted signals also serve as ground truth for validation, which an LLM-produced extraction could not.

### Static system context as a resource

`src/main/resources/system-context.md` holds the architecture, infrastructure table and failure modes (copied from the task). Known adapters, allowed tools and categories are Kotlin constants in `SystemContext.kt`:

| Adapter | Provider | Port |
|---|---|---|
| cc087 | PayMe | 10087 |
| cc109 | OPay | 10109 |
| cc131 | Cobre | 10131 |
| cc139 | Halopesa | 10139 |

The whole context goes into the system prompt (it is ~1 KB). Allowed entities for grounding = registry ∪ components extracted from the log, so MongoDB in INC-203 is accepted. Adapters are rendered as `ccNNN (Provider)` in the output, matching the task example.

### LLM integration

LangChain4j `ChatModel` with `langchain4j-anthropic` and `langchain4j-open-ai`. `Main` builds the model from env (`LLM_PROVIDER`, `LLM_MODEL`, keys); defaults: Anthropic `claude-sonnet-5-5` with the API default temperature (current Claude models reject `temperature` as deprecated), OpenAI `gpt-4.1` at temperature 0 (a non-reasoning model, so it is accepted; GPT-5 models reject it). The prompt is a system message (role, context, schema, rules: allowed enums/tools, list limits, "respond with JSON only") and a user message (signals as JSON, notes, raw snippet). We ask for JSON in text and parse it ourselves with Jackson rather than using provider JSON mode / structured output, so the invalid-JSON recovery path is real and provider-agnostic.

### Validate / repair loop

```
attempt = 1; messages = [system, user]
loop:
  text = model.chat(messages)
  (diag, errors) = Validator.check(text, signals)   // extract JSON → normalize → validate
  if errors empty → return Success(diag)
  if attempt == 4 → return Failure(text, errors)     // Main: stderr only, non-zero exit
  messages += [assistant(text), user("Fix these errors: …")]
  attempt++
```

Failing hard instead of printing a best-effort answer keeps the stdout contract simple: anything on stdout is a fully valid diagnosis.

The conversation keeps the previous answer so the model fixes rather than regenerates. Normalization (enum case/separators, truncating oversize lists, overwriting `incident_id` with the file-derived one) runs before validation so it never costs a retry. Contradiction checks are a short list of rules over `Signals` (see diagnosis-validation spec), not a second LLM judge — a judge doubles cost and is itself unvalidated.

### Testing strategy

Three layers, matching the determinism of each stage:

1. **Deterministic stages — golden files.** `examples/INC-*.txt` are inputs; `src/test/resources/golden/INC-*.parsed.json` and `INC-*.signals.json` are expected outputs compared exactly. Golden files are written by the tests themselves when run with `UPDATE_GOLDEN=1`, reviewed by hand, then committed.
2. **Repair loop — scripted fake `ChatModel`.** Replays fixed answers (broken JSON, `cc999`, `"severity": "urgent"`, `Datadog`, four failures in a row) and asserts retry count, re-prompt content and the final success/failure. No API key needed.
3. **End-to-end — real LLM, predictable fields only.** Runs only when an API key is set (JUnit assumption; skipped otherwise). `examples/INC-*.expected.json` is a partial diagnosis: scalar fields are an exact value or an array of accepted values; `affected_adapters` / `affected_order_types` are compared as sets (adapters by `ccNNN` id). Free text is not compared. The output must also pass the validator.

| Incident | category | fault_layer | adapters | order types | blast_radius | severity |
|---|---|---|---|---|---|---|
| INC-201 | External provider degradation | External | cc109 | PAYMENT | single_adapter | high, critical |
| INC-202 | Internal configuration / credential issue | API, Core | cc087 | PAYMENT | single_adapter | high, critical |
| INC-203 | Infrastructure failure (DB/cache/queue) | Infrastructure | cc139 | PAYOUT | single_adapter | high, critical |
| INC-204 | Routing / terminal configuration issue | Infrastructure, Core | exactly cc109, cc131 | PAYMENT | multi_adapter | medium, high |
| INC-205 | External provider degradation, Connection pool / resource exhaustion | External, SDK | cc087 | PAYMENT | single_adapter | high, critical |

For INC-205 the root cause is the slow provider holding all connections, so `External provider degradation` / `External` are listed first as the expected primary answer, and the pool-exhaustion reading (`Connection pool / resource exhaustion` / `SDK`, where the pool lives) is also accepted; real runs returned both. The README reports which one each run produced.

### Implementation workflow

The change is implemented with `/opsx:apply` one task group at a time; groups follow pipeline stages, each ends with its tests green and one commit, and the agent stops for review between groups.

### Build and runtime

Kotlin 1.9.23 with Gradle 8.5 (the newest versions IntelliJ IDEA CE 2023.3 supports; `jackson-module-kotlin` is pinned to 2.19.2 because later releases pull in Kotlin 2.x metadata), targeting JDK 17 (the oldest JDK LangChain4j supports, and the default `java` on the dev machine, so `java -jar` works without switching JDKs), Gradle Kotlin DSL, Shadow plugin producing `build/libs/analyzer.jar` (archive classifier removed). Jackson with `jackson-module-kotlin`, snake_case naming so data classes map 1:1 to the task schema. Tests: JUnit 5 + kotlin-test.

## Risks / Trade-offs

- [Regex extraction misses formats beyond the 5 examples] → unparsed lines are kept and the raw snippet always reaches the LLM, so missing signals degrade grounding checks, not the diagnosis itself.
- [Contradiction rules are coarse and may reject a valid answer] → only three narrow rules; failures surface as re-prompts with explicit reasons, and the failed attempts are visible on stderr.
- [Adapter registry is hard-coded from the examples] → adapters seen in the log are also accepted; a real deployment would load the registry from Terminals Service / Consul.
- [Model output varies between runs] → temperature 0 where the provider allows it; end-to-end tests assert only predictable fields with accepted-value sets; README records observed outputs.
- [Strict tool allowlist rejects reasonable provider-side steps such as checking a status page] → accepted to keep validation simple; the model is told the allowed tools up front.
- [Fixed category list is stricter than the task's "for example"] → keeps output machine-comparable; extending it is a one-line constant change.
