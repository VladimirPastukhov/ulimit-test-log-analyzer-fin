# Payment platform

Our payment platform processes transactions through **payment adapters** — microservices that translate our internal protocol into provider-specific API calls. Each adapter follows a 3-layer architecture:

```
┌──────────────────────────────────────────────────────┐
│                      API Layer                       │
│  REST controllers, RabbitMQ consumers                │
│  Receives orders from the orchestrator (MI)          │
├──────────────────────────────────────────────────────┤
│                      Core Layer                      │
│  Business logic: status mapping, decline mapping,    │
│  timeout handling, retry decisions                   │
├──────────────────────────────────────────────────────┤
│                      SDK Layer                       │
│  HTTP client to the external provider                │
│  DTOs matching the provider's API format             │
│  Uses RestClientProvider for HTTP with metrics       │
└──────────────────────────────────────────────────────┘
```

## Supporting infrastructure

| Component | Role |
|---|---|
| **MI (Merchant Interface)** | Central orchestrator — routes orders to adapters based on terminal configuration |
| **Terminals Service** | Maps merchant terminals to adapter URLs and credentials |
| **Consul** | Service discovery — adapters and MI register here |
| **Vault** | Stores API keys, signing keys, and adapter credentials |
| **Redis** | Caches terminal link lookups (10-min TTL) |
| **RabbitMQ** | Async messaging for callbacks and status updates |
| **PostgreSQL** | Order persistence (separate instances per adapter) |
| **ELK** | Centralized log aggregation |
| **Prometheus/Grafana** | Metrics and alerting |

## Common failure modes

- External provider degradation (timeouts, 5xx, maintenance windows).
- Credential rotation issues (Vault secret updated but adapter not restarted).
- Connection pool exhaustion under traffic spikes.
- Redis cache containing stale terminal links after configuration change.
- Signing key mismatch (`X-Sign` validation failures) after library upgrade.
- RabbitMQ queue backlog causing delayed callback processing.
- Database connection pool saturation from long-running queries.
