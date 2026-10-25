# Tasks

Implement one group per `/opsx:apply` run: finish the group with its tests green, commit, and stop for review before the next group.

## 1. Setup

- [x] 1.1 Create Gradle Kotlin DSL project (JDK 17 toolchain, Kotlin JVM, Shadow, LangChain4j Anthropic + OpenAI, Jackson Kotlin, JUnit 5), `.gitignore` for build/.gradle/.idea; verify `./gradlew shadowJar` produces `build/libs/analyzer.jar`
- [x] 1.2 Add `Model.kt` data classes for `LogEntry`, `Signals` and `Diagnosis` (snake_case Jackson mapping) and a shared JSON serializer; verify a test round-trips the task's example JSON without field loss
- [x] 1.3 Add a golden-file test helper (compare with `src/test/resources/golden/<name>`, rewrite it when `UPDATE_GOLDEN=1`); verify a self-test fails on mismatch and passes after update

## 2. Stage 1 — Parse

- [x] 2.1 Implement `LogParser` (headers, continuation lines, `...` notes, unparsed lines); verify unit tests for the multi-line record, note and garbage-line scenarios of the log-signal-extraction spec
- [x] 2.2 Generate, review and commit `golden/INC-20x.parsed.json` for all five examples; verify `./gradlew test` passes without `UPDATE_GOLDEN`

## 3. Stage 2–3 — Extract signals and enrich

- [x] 3.1 Implement `SystemContext` (adapter registry, layers, allowed tools, categories, `system-context.md` resource); verify a test resolves port `10109` to `cc109 (OPay)` and the resource loads
- [x] 3.2 Implement `SignalExtractor` (adapters, layers, error signals with counts, order types, time window, level counts, metrics, components); verify targeted tests for MongoDB in INC-203 and exactly cc109+cc131 in INC-204
- [x] 3.3 Generate, review and commit `golden/INC-20x.signals.json` for all five examples; verify `./gradlew test` passes without `UPDATE_GOLDEN`

## 4. Stage 5a — Validate and normalize

- [x] 4.1 Implement JSON extraction from fenced/prose responses and parse-error reporting in `Validator`; verify tests for fenced, prose-wrapped and truncated responses
- [x] 4.2 Implement normalization (enum case/separators, list truncation, incident_id override); verify tests for `external` → `External`, `single-adapter` → `single_adapter`, 5 → 3 hypotheses
- [x] 4.3 Implement schema, enum and minimum-size validation; verify tests for unknown severity, missing `blast_radius`, a hypothesis with one next step
- [x] 4.4 Implement grounding and contradiction checks (adapters, strict tool allowlist, `External` without provider errors, `likely` hypothesis on unmentioned component, `single_adapter` with multiple adapters); verify tests for `cc999`, `Datadog`, `Provider status page` rejected, `MongoDB` accepted for INC-203, INC-204 blast-radius contradiction

## 5. Stage 4 + 5b — Diagnose and repair loop

- [x] 5.1 Implement `Diagnoser` prompt building (system context, schema, rules, signals, notes, raw snippet); verify a capturing fake `ChatModel` test asserts the prompt contains signals, snippet and allowed tools
- [x] 5.2 Implement the repair loop (conversation history, max 3 retries, `Success`/`Failure` result); verify scripted fake-model tests: invalid JSON then valid, hallucinated adapter then valid, four failing answers → `Failure` after exactly 4 calls with the errors of the last attempt

## 6. CLI

- [x] 6.1 Implement `Main` (arguments, incident id from file name, env provider selection, stdout JSON / stderr trace, exit codes, failure output to stderr only); verify tests for incident id derivation and provider auto-detection, and that running the jar without arguments, with a missing file, or without keys exits non-zero with a stderr message and empty stdout

## 7. End-to-end and documentation

- [x] 7.1 Add `examples/INC-20x.expected.json` (predictable fields per the design's table) and an end-to-end test that runs the pipeline with the real LLM, skipped when no API key is set, comparing the expected fields (exact / any-of / set) and re-validating the output; verify it is skipped without a key and passes for all five incidents with a key
- [x] 7.2 Run the jar on all five examples, save final outputs to `examples/output/INC-20x.json`; verify each file matches its `expected.json`
- [x] 7.3 Write README: run steps and env vars, pipeline stages, testing layers, expected vs actual per incident (including retries observed and INC-205's chosen layer), trade-offs and future work; verify the documented commands run as written from a clean checkout

## Workflow follow-up

- Archive the change after review so `openspec/specs/` holds the final specs.
