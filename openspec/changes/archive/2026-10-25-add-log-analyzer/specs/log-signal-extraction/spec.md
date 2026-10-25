# Spec Delta

## Purpose

Turns raw payment-adapter log snippets into structured entries and incident signals without using an LLM, so that later stages work from deterministic, testable evidence.

## ADDED Requirements

### Requirement: Parse log entries
The system SHALL parse each log record of the form `<timestamp> [<thread>] <LEVEL> <logger>` followed by `- <message>` into an entry with timestamp, thread, level, logger and message. Indented lines following a header SHALL be appended to that entry's message.

#### Scenario: Multi-line record
- **WHEN** the input contains a header line `2024-11-28 20:10:05.100 [http-nio-10087-exec-48] ERROR c.c.m.client.payme.ClientService` followed by three indented lines
- **THEN** one entry is produced with level `ERROR`, thread `http-nio-10087-exec-48`, logger `c.c.m.client.payme.ClientService`, and a message containing all three lines

### Requirement: Keep annotation lines as notes
Lines starting with `...` SHALL be kept as free-text notes of the snippet rather than discarded or attached to an entry.

#### Scenario: Repetition note
- **WHEN** the input contains `... (pattern repeats for 200+ requests over 45 minutes)`
- **THEN** the note text is available to later stages and no entry is created for it

### Requirement: Tolerate unparseable lines
Lines that match no record format and are not continuation or note lines SHALL be kept as unparsed text and SHALL NOT abort processing.

#### Scenario: Garbage line
- **WHEN** the input contains a line `random text` before the first header
- **THEN** parsing succeeds and the line is retained as unparsed text

### Requirement: Extract affected adapters
The system SHALL identify affected adapters from the HTTP port in thread names (`10NNN` → `ccNNN`) and from provider names in logger packages, resolving them against the known adapter registry.

#### Scenario: Adapter from port and logger
- **WHEN** entries have thread `http-nio-10109-exec-12` and logger `c.c.m.client.opay.ClientService`
- **THEN** the extracted adapters contain `cc109` with provider `OPay`

#### Scenario: Multiple adapters
- **WHEN** entries come from ports `10109` and `10131`
- **THEN** both `cc109` and `cc131` are extracted

### Requirement: Extract architectural layers
The system SHALL map each entry's logger to a layer: `.client.` → SDK, `.core.` → Core, `.api.` → API, and cache/queue/database components → Infrastructure. It SHALL also add Infrastructure when a message contains a fully qualified exception from a database, cache or queue client library (for example `com.mongodb.`, `org.postgresql.`, `java.sql.`, `redis.clients.`, `io.lettuce.`, `com.rabbitmq.`, `org.springframework.amqp.`).

#### Scenario: Layer mapping
- **WHEN** entries have loggers `c.c.m.payme.api.PaymentController` and `c.c.m.common.cache.RedisCacheService`
- **THEN** the extracted layers are `API` and `Infrastructure`

#### Scenario: Infrastructure exception logged by a Core class
- **WHEN** a `c.c.m.halopesa.core.StatusCheckService` entry contains `com.mongodb.MongoTimeoutException`
- **THEN** the extracted layers contain both `Core` and `Infrastructure`

#### Scenario: Unqualified exception
- **WHEN** a message contains only `MongoTimeoutException` without its package
- **THEN** no Infrastructure layer is added because of it

### Requirement: Extract error signals
The system SHALL extract error signals from messages, including HTTP status codes, provider error codes, exception class names, timeouts with durations, signature mismatches, missing terminal links, and decline codes, each with its occurrence count.

#### Scenario: Provider 503
- **WHEN** two entries contain `status=503` and `"code":"MAINTENANCE"`
- **THEN** signals include HTTP status `503` with count 2 and provider code `MAINTENANCE`

#### Scenario: Exceptions and timeouts
- **WHEN** a message contains `java.net.SocketTimeoutException: Read timed out (30000ms)`
- **THEN** signals include exception `SocketTimeoutException` and a timeout of 30000 ms

### Requirement: Extract order types
The system SHALL infer affected order types (`PAYMENT`, `PAYOUT`) from service class names and order id prefixes.

#### Scenario: Payout incident
- **WHEN** entries come from `PayoutServiceImpl` and mention order `pyt-456`
- **THEN** the extracted order types contain `PAYOUT`

### Requirement: Extract time window, metrics and components
The system SHALL extract the earliest and latest timestamps, counts per log level, numeric metrics in `key=value` form, and mentioned infrastructure components (for example Redis, RabbitMQ, MongoDB, PostgreSQL, Terminals Service), including components absent from the system description.

#### Scenario: Metrics and unknown component
- **WHEN** the INC-203 snippet is processed
- **THEN** signals include the time window `2024-10-22 11:30:15.200`–`11:40:00.000`, queue depth `1247`, and component `MongoDB`
