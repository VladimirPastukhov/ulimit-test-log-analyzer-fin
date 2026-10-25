# Spec Delta

## Purpose

Defines the command-line contract of the analyzer: which input it takes, how the LLM provider is configured, what context the diagnosis is grounded in, and the diagnosis JSON it outputs.

## ADDED Requirements

### Requirement: Analyze a log file from the command line
The system SHALL accept one log file path as its argument and print the diagnosis JSON to stdout. Progress and diagnostics SHALL go to stderr only, so stdout contains nothing but the JSON document.

#### Scenario: Successful run
- **WHEN** the user runs `java -jar build/libs/analyzer.jar examples/INC-201.txt` with a valid API key configured
- **THEN** stdout contains a single JSON document and the exit code is 0

#### Scenario: Missing file
- **WHEN** the given path does not exist
- **THEN** an error naming the path is printed to stderr, nothing is printed to stdout, and the exit code is non-zero

#### Scenario: No argument
- **WHEN** the tool is run without arguments
- **THEN** usage text is printed to stderr and the exit code is non-zero

### Requirement: Derive incident id from file name
The system SHALL set `incident_id` to the first `INC-<digits>` token in the input file name, and to `null` when the name contains none.

#### Scenario: Incident file
- **WHEN** the input is `examples/INC-205.txt`
- **THEN** `incident_id` is `"INC-205"`

#### Scenario: Arbitrary file
- **WHEN** the input is `/tmp/snippet.log`
- **THEN** `incident_id` is `null`

### Requirement: Configure LLM provider via environment
The system SHALL select the provider from `LLM_PROVIDER` (`anthropic` or `openai`); when unset, it SHALL use whichever of `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` is set, preferring Anthropic. `LLM_MODEL` SHALL override the provider's default model. Keys SHALL NOT be read from files or arguments.

#### Scenario: Auto-detect provider
- **WHEN** only `OPENAI_API_KEY` is set and `LLM_PROVIDER` is unset
- **THEN** the OpenAI provider is used

#### Scenario: No key configured
- **WHEN** neither API key is set
- **THEN** an error explaining the required variables is printed to stderr and the exit code is non-zero, without calling any LLM

### Requirement: Ground the diagnosis in system context
The LLM request SHALL include the system architecture description (adapter layers, infrastructure components, common failure modes), the known adapter registry, the allowed diagnostic tools, the extracted signals and the raw log snippet.

#### Scenario: Context present in request
- **WHEN** a diagnosis is requested for any input
- **THEN** the prompt sent to the LLM contains the extracted signals, the raw snippet and the list of allowed tools

### Requirement: Output diagnosis in the task schema
The output SHALL be a JSON object with exactly the fields of the task schema: `incident_id`, `category`, `summary` (`description`, `affected_adapters`, `affected_order_types`, `fault_layer`, `severity`, `severity_reasoning`, `blast_radius`), `hypotheses` (`title`, `reasoning`, `probability`, `next_steps` of `action`/`tool`/`detail`) and `immediate_actions` (`action`, `risk`, `reasoning`).

#### Scenario: Schema conformance
- **WHEN** the analyzer finishes successfully
- **THEN** the output parses as JSON, contains every schema field, and contains no fields outside the schema

### Requirement: Classify into a known category
`category` SHALL be one of: `External provider degradation`, `Internal configuration / credential issue`, `Infrastructure failure (DB/cache/queue)`, `Connection pool / resource exhaustion`, `Routing / terminal configuration issue`.

#### Scenario: Provider maintenance
- **WHEN** the input is the INC-201 snippet (OPay returning 503 MAINTENANCE)
- **THEN** `category` is `External provider degradation` and `fault_layer` is `External`
