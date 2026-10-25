# diagnosis-validation Specification

## Purpose

Guards against typical LLM errors — invalid JSON, missing fields, bad enum values, hallucinated adapters or tools, hypotheses contradicting the logs — and recovers from them by normalization and targeted re-prompting.

## Requirements

### Requirement: Accept JSON wrapped in prose or fences
The system SHALL extract the JSON object from an LLM response that wraps it in Markdown code fences or surrounding text before parsing.

#### Scenario: Fenced response
- **WHEN** the LLM returns the diagnosis inside a ```` ```json ```` fence with a leading sentence
- **THEN** the object is parsed without a re-prompt

### Requirement: Re-prompt on invalid JSON
When the response contains no parseable JSON object, the system SHALL re-prompt the LLM with the parse error.

#### Scenario: Truncated JSON
- **WHEN** the first response is `{"category": "External provider degradation", "summary": {`
- **THEN** the system sends a follow-up request containing the parse error and uses the next valid response

### Requirement: Validate schema fields and enums
The system SHALL report as validation errors any missing required field, wrong field type, and any value outside the allowed sets: `fault_layer` ∈ SDK/Core/API/Infrastructure/External, `severity` ∈ low/medium/high/critical, `blast_radius` ∈ single_merchant/single_adapter/multi_adapter/platform_wide, `probability` ∈ likely/possible/unlikely, `risk` ∈ safe/caution/risky, and `category` ∈ the known categories.

#### Scenario: Unknown severity
- **WHEN** the response has `"severity": "urgent"`
- **THEN** a validation error names the field and lists the allowed values

#### Scenario: Missing field
- **WHEN** the response lacks `summary.blast_radius`
- **THEN** a validation error names the missing field

### Requirement: Normalize trivial deviations without re-prompting
The system SHALL fix in code, without re-prompting: enum values that differ only in case or separators (`High` → `high`, `single-adapter` → `single_adapter`), lists exceeding their limit (more than 3 hypotheses, more than 3 next steps, more than 2 immediate actions) by truncation, and an `incident_id` differing from the one derived from the file name.

#### Scenario: Case-only enum
- **WHEN** the response has `"fault_layer": "external"`
- **THEN** the output has `"fault_layer": "External"` and no re-prompt is made for it

#### Scenario: Too many hypotheses
- **WHEN** the response contains 5 hypotheses
- **THEN** the output contains the first 3

### Requirement: Enforce minimum list sizes
Each hypothesis SHALL have at least 2 next steps, and the diagnosis SHALL have at least one hypothesis and one immediate action; violations are validation errors.

#### Scenario: Single next step
- **WHEN** a hypothesis has one next step
- **THEN** a validation error identifies that hypothesis

### Requirement: Reject hallucinated adapters
Every entry of `affected_adapters` SHALL refer to an adapter that is in the known adapter registry or was extracted from the log; any other adapter is a validation error.

#### Scenario: Invented adapter
- **WHEN** the response lists `cc999 (StripeX)` for the INC-201 snippet
- **THEN** a validation error names `cc999` as not present in the system or the logs

### Requirement: Restrict next-step tools
Every `next_steps[].tool` SHALL name a tool from the allowed set: ELK, Grafana, Prometheus, Consul, Vault, Redis, RabbitMQ, PostgreSQL, or a component mentioned in the log (for example MongoDB, Terminals Service); any other tool, including provider-side checks such as a status page, is a validation error.

#### Scenario: Unknown tool
- **WHEN** a next step uses tool `Datadog`
- **THEN** a validation error names `Datadog` and lists the allowed tools

#### Scenario: Component from the log
- **WHEN** a next step for the INC-203 snippet uses tool `MongoDB`
- **THEN** no validation error is reported for that tool

### Requirement: Detect contradictions with log evidence
The system SHALL report a validation error when the diagnosis contradicts the extracted signals: `fault_layer` `External` without any provider-side error signal (5xx, provider error code, outbound timeout), a `likely` hypothesis blaming a component never mentioned in the log, or `blast_radius` `single_adapter` when more than one adapter was extracted.

#### Scenario: Blast radius contradiction
- **WHEN** the INC-204 snippet (adapters cc109 and cc131) yields `"blast_radius": "single_adapter"`
- **THEN** a validation error states that multiple adapters are affected

### Requirement: Re-prompt with validation errors and bounded retries
When validation errors remain after normalization, the system SHALL re-prompt the LLM with its previous answer and the full list of errors, at most 3 times (4 LLM calls in total). All checks SHALL be deterministic code; no LLM is used to judge an answer.

#### Scenario: Recovery on retry
- **WHEN** the first response has a hallucinated adapter and the second is valid
- **THEN** the second response is output and the exit code is 0

### Requirement: Report unrecovered failures
When errors remain after the last retry, the system SHALL fail: print nothing to stdout, print the last attempt and its validation errors to stderr, and exit non-zero.

#### Scenario: Retries exhausted
- **WHEN** all four responses contain an unknown tool
- **THEN** stdout is empty, stderr contains the last attempt and the unknown-tool errors, and the exit code is non-zero
