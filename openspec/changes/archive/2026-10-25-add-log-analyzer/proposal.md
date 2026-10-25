# Proposal

## Why

On-call engineers need to decide quickly whether a failing payment adapter is broken in our code, at the provider, or in the infrastructure between them. The take-home task (docs/task_APM_log_analyzer.md) asks for a small agent that turns incident log snippets into a structured, machine-readable diagnosis, using the LLM as a controlled stage of a pipeline rather than a single black-box call.

## What Changes

- New Kotlin CLI: `java -jar build/libs/analyzer.jar <log-file>` prints a diagnosis JSON matching the task's schema to stdout.
- The incident id is derived from the input file name (`examples/INC-205.txt` → `INC-205`), `null` when the name carries no `INC-<n>` id.
- Log parsing and signal extraction (adapters, layers, error types, order types, time window, metrics) are deterministic — no LLM involved.
- Signals are enriched with a static system-architecture context (layers, infrastructure components, failure modes, known adapters, allowed diagnostic tools) before the LLM call.
- The LLM (Anthropic or OpenAI, selected and keyed via environment variables) produces the diagnosis.
- Every LLM response is validated deterministically (JSON syntax, schema, enums, list limits, adapter and tool grounding, consistency with log evidence); trivial issues are normalized in code, the rest triggers a re-prompt with the concrete errors, up to 3 retries.
- If validation still fails, the run fails: nothing on stdout, the last attempt and its errors on stderr, non-zero exit. No offline / rule-only diagnosis mode.
- `examples/INC-201.txt` … `INC-205.txt` hold the task's incident snippets, with `INC-*.expected.json` listing the predictable output fields used by end-to-end tests; README documents running, architecture, test results and trade-offs.

## Capabilities

### New Capabilities

- `log-signal-extraction`: Deterministic parsing of adapter log snippets into entries and extraction of incident signals.
- `incident-diagnosis`: The CLI contract — input file, incident id, LLM provider configuration, context enrichment, and the diagnosis JSON output.
- `diagnosis-validation`: Validation of LLM output against the schema, the system description and the log evidence, with normalization and re-prompt recovery.

### Modified Capabilities

None.

## Impact

- New Gradle (Kotlin DSL) project with a fat-jar build; dependencies: LangChain4j (Anthropic, OpenAI modules), Jackson.
- New environment variables: `LLM_PROVIDER`, `LLM_MODEL`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`.
- No HTTP service, no persistence, no connectivity to real log sources.
