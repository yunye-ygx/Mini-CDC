# Observability Ops Dashboard Design

## Goal

Add a service-oriented observability surface to the CDC application so it is no longer just a runnable data pipeline, but an operable service with standard health endpoints, CDC-specific status queries, and a lightweight read-only dashboard suitable for interview demos.

This design targets a one-week implementation window and optimizes for:

- Standard Spring Boot operations visibility
- CDC-domain operational visibility
- Clear demo flow for DML and DDL rebuild scenarios
- Minimal implementation scope and deployment overhead

## Scope

In scope:

- Spring Boot Actuator integration
- Standard health and metrics endpoints
- CDC-specific custom health details
- CDC-specific Micrometer metrics
- Read-only Ops API endpoints
- Lightweight same-repo dashboard page
- In-memory recent event summaries for demo visibility

Out of scope:

- Separate frontend application
- Login, auth, or RBAC
- Write operations from the dashboard
- Full business-table CRUD or business-data browsing
- Persistent audit/event history storage
- External alert integrations such as email, webhook, or chat notifications

## Background

The current project already has the internal state required for operations visibility:

- `checkpoint` state for source progress
- `full_sync_task` state for snapshot/bootstrap progress
- `rebuild_task` state for downstream DDL rebuild execution
- `schema_state` for downstream schema convergence state

What is missing is an external service-facing observation surface. At the moment, those states exist only inside persistence and internal services. An operator or interviewer can tell that the CDC chain exists, but cannot quickly answer service questions such as:

- Is the service healthy?
- Is CDC actively progressing?
- What is the current checkpoint?
- Is a table rebuilding?
- Did a recent DDL leave tasks failed or pending?
- What recent events prove the pipeline is alive?

The design therefore focuses on exposing existing control state and runtime signals rather than building a management backend.

## Design Summary

The observability surface is split into four layers:

`CDC runtime state -> health/metrics/ops aggregation -> Actuator + Ops API -> read-only dashboard`

Responsibilities are intentionally separated:

- Actuator provides standard Spring Boot operations endpoints
- Custom health and metrics provide CDC-specific semantics
- Ops API provides read-only CDC-domain operational views
- Dashboard provides a simple visual observation layer for demo and debugging

`Knife4j` remains useful for triggering or testing APIs, but it is not the observability surface itself.

## Core Design

### Standard Operations Surface

The application will expose standard Actuator endpoints:

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/metrics`
- `/actuator/info`
- optional `/actuator/prometheus`

These endpoints establish that the service follows normal Spring Boot operational conventions and can integrate with standard monitoring tooling later.

### CDC-Specific Health Semantics

Health must distinguish process survival from CDC readiness.

#### Liveness

`liveness` answers whether the process is alive and able to serve HTTP traffic.

It should not fail just because a rebuild task exists or a table is currently rebuilding.

#### Readiness

`readiness` answers whether the service is ready to perform its CDC responsibilities.

Readiness should incorporate:

- MySQL connectivity
- Redis connectivity
- Kafka basic availability
- CDC runtime component state
- checkpoint state availability

#### Health Details

Custom health details should include at least:

- current connector name
- current startup strategy
- whether checkpoint load succeeded
- whether the CDC runtime is enabled
- whether any rebuild tasks are currently failed or running

Important rule:

- rebuild failure or rebuild backlog should appear as detailed operational information
- they should not automatically force the whole service to `DOWN` unless the service is genuinely unable to function

This keeps health meaningful instead of turning every domain warning into hard unavailability.

### CDC Metrics

Micrometer metrics will provide a small but high-value set of counters, gauges, and timers.

Recommended counters:

- `cdc_events_published_total`
- `cdc_events_consumed_total`
- `cdc_events_failed_total`
- `cdc_checkpoint_save_total`
- `cdc_snapshot_rows_sent_total`
- `cdc_rebuild_started_total`
- `cdc_rebuild_completed_total`
- `cdc_rebuild_failed_total`

Recommended timer:

- `cdc_rebuild_duration`

Recommended gauges:

- `cdc_pending_rebuild_tasks`
- `cdc_failed_rebuild_tasks`

The metric set is intentionally narrow. It is designed to answer:

- Is the pipeline active?
- Are failures occurring?
- Is rebuild work accumulating?
- Are rebuilds finishing?

### Ops API

Ops API exists to expose CDC operational state that standard Actuator endpoints do not model directly.

All Ops API endpoints are read-only.

#### `GET /ops/overview`

Returns the dashboard homepage aggregate view, including:

- application summary
- health summary
- checkpoint summary
- full sync summary
- rebuild summary
- schema-state summary
- recent event summary preview

This endpoint is optimized for first-page load and fast demo refresh.

#### `GET /ops/checkpoint/current`

Returns the current source checkpoint view:

- connector name
- startup strategy
- checkpoint present or absent
- current binlog filename
- current binlog position

#### `GET /ops/full-sync/tasks`

Returns current or recent full sync task state:

- connector name
- database name
- table name
- status
- cutover binlog coordinate
- last sent primary key
- started at
- finished at
- last error

#### `GET /ops/rebuild/tasks`

Returns recent rebuild task rows, optionally filtered by status.

Fields include:

- task id
- database name
- table name
- schema binlog coordinate
- status
- retry count
- last error

#### `GET /ops/rebuild/summary`

Returns aggregated rebuild counts:

- pending count
- running count
- done count
- failed count
- obsolete count
- most recent failure summary if present

#### `GET /ops/schema-state`

Returns the current schema convergence view for listened tables:

- database name
- table name
- status
- schema binlog file
- schema next position
- ddl type
- ddl sql

#### `GET /ops/events/recent`

Returns recent in-memory event summaries.

This endpoint is for operational observation, not payload replay or business-data browsing.

### Recent Event Buffer

The application will maintain an in-memory ring buffer for recent event summaries.

Characteristics:

- fixed-size bounded buffer
- newest-first or oldest-first ordering must be consistent and documented
- entries are not persisted
- service restart clears the buffer

This is acceptable because the feature exists for short-term runtime visibility during demo and debugging, not for long-term audit retention.

Each event summary should store only operationally useful data:

- timestamp
- event type such as `SNAPSHOT_ROW`, `DML_TX`, `DDL`, `REBUILD`
- database name
- table name
- primary key summary or transaction id summary
- result such as `SUCCESS`, `FAILED`, `BUFFERED`, `TRIGGERED_REBUILD`
- short message

The buffer must not store full business payloads. That would drift toward a business-data console instead of a service observability surface.

### Dashboard

The dashboard will live inside the existing Spring Boot application as a static page under `src/main/resources/static`.

Implementation direction:

- plain HTML
- plain CSS
- plain JavaScript
- `fetch` calls to Actuator and Ops APIs

No separate frontend build pipeline is introduced.

#### Dashboard Sections

The dashboard is a single read-only page with five sections.

##### 1. Health

Displays:

- app status
- MySQL status
- Redis status
- Kafka status
- CDC runtime status

##### 2. Checkpoint

Displays:

- connector name
- startup strategy
- current binlog file
- current binlog position

##### 3. Task Status

Displays:

- full sync task summary
- rebuild summary counts
- latest failed rebuild if present

##### 4. Schema State

Displays one row per listened table:

- database
- table
- current schema status
- latest DDL type
- latest schema coordinate

##### 5. Recent Events

Displays the recent in-memory event summaries so that an operator can see that activity is happening without inspecting Kafka or Redis directly.

#### Dashboard Refresh Model

The page should:

- load overview data on initial render
- refresh summary sections periodically
- refresh recent events periodically
- show last refresh time
- show a simple error banner if refresh fails

The page does not support user-triggered write actions.

## Implementation Direction

### New Dependencies

Add:

- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`

### New Components

Recommended components:

- `OpsQueryService`
  - aggregates checkpoint, full sync, rebuild, schema, and recent-event data
- `RecentEventBuffer`
  - manages bounded in-memory event summaries
- custom `HealthIndicator` implementations or health contributors
  - expose MySQL, Redis, Kafka, checkpoint, and CDC runtime operational details
- metrics helper/service
  - centralizes counter, timer, and gauge updates
- ops controller
  - exposes `/ops/*` endpoints

### Data Reuse Strategy

The design should reuse existing store and mapper abstractions wherever possible instead of bypassing them.

Observability should be built on top of current persisted state:

- `CheckpointStore`
- `FullSyncTaskStore`
- `RebuildTaskStore`
- `SchemaStateStore`

Where current stores do not support the required query shape, read-only query methods may be added.

### Event Summary Capture Points

Recent event summaries should be appended from the runtime locations where meaningful outcomes already occur, such as:

- snapshot row publish/send success
- downstream DML consume/apply success or failure
- DDL intake
- rebuild start
- rebuild completion
- rebuild failure
- transaction buffering during rebuild

The design does not require every internal event to be recorded. It only requires enough summaries to make the system visibly alive and diagnosable.

## Non-Goals and Guardrails

To keep implementation focused, this design explicitly rejects the following directions:

- turning the dashboard into a management console
- adding write APIs just because the dashboard exists
- exposing full business-table data in observability endpoints
- persisting recent-event history in a new table
- building a separate SPA

The service should look more operable, not more like a generic admin system.

## Testing Strategy

### Unit Tests

Add unit tests for:

- `OpsQueryService` aggregation logic
- `RecentEventBuffer` capacity, overwrite, and ordering behavior
- metrics recording behavior
- custom health result mapping

### Integration Tests

Add integration coverage for:

- `/actuator/health`
- `/ops/overview`
- `/ops/checkpoint/current`
- `/ops/rebuild/summary`
- `/ops/events/recent`

Tests should verify status code, essential fields, and empty-state handling.

### Manual Demo Verification

The intended demo flow is:

1. Start the service
2. Open the dashboard and verify health is normal
3. Trigger a DML change through MySQL or a test endpoint
4. Verify checkpoint movement, metric growth, and a new recent-event summary
5. Trigger a DDL rebuild scenario
6. Verify schema-state and rebuild-task changes become visible on the dashboard

### Failure-Path Verification

At minimum, validate:

- rebuild failure appears in rebuild summary and recent events
- dependency or query failure appears as health/readiness degradation or dashboard refresh failure messaging

## Trade-offs

Pros:

- Makes the service visibly operable without large architectural expansion
- Reuses existing CDC state instead of inventing a management domain
- Keeps deployment simple by embedding the dashboard in the same application
- Demonstrates standard Spring Boot operational maturity and CDC-specific visibility

Cons:

- Recent event summaries are lost on restart
- The dashboard is intentionally read-only and lightweight
- Metrics are useful but not exhaustive
- This does not yet include alerting or long-term monitoring storage

## Acceptance Criteria

The design is successful if:

- the service exposes standard Actuator health and metrics endpoints
- the service exposes read-only Ops API endpoints for checkpoint, tasks, schema state, and recent events
- the dashboard is available from the same Spring Boot application without a separate frontend build
- a normal DML flow visibly changes checkpoint, metrics, and recent-event output
- a DDL rebuild flow visibly changes schema state and rebuild-task output
- recent events stay bounded in memory and are cleared on restart
- the implementation remains within service observability scope and does not become a CRUD admin backend
