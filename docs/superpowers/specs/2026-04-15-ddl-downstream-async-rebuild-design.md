# DDL Downstream Async Rebuild Design

## Goal

Add downstream handling for destructive MySQL DDL so Redis does not apply stale schema blindly, while keeping the current upstream transaction-oriented Kafka publishing model.

This design keeps these constraints:

- Upstream still publishes Kafka messages by transaction, keyed by `transactionId`
- Cross-table transaction atomicity is preserved at the message boundary
- Destructive DDL must not block unrelated-table transactions forever
- Rebuild retry must not depend on Kafka offset replay

## Scope

In scope:

- Upstream detection of DDL binlog events
- Publishing DDL messages to Kafka
- Refreshing upstream table metadata after accepted DDL
- Downstream schema state tracking
- Downstream rebuild task persistence
- Downstream pending transaction buffering during rebuild
- Async table rebuild and replay flow

Out of scope:

- Per-table Kafka partition isolation
- Breaking one database transaction into multiple Kafka messages
- Exact-once semantics across Kafka and Redis
- Automatic SQL execution in Redis
- Cross-process distributed locking beyond a single logical task owner

## Background

Current flow:

`MySQL binlog -> BinlogCdcLifecycle -> Kafka -> RedisSyncConsumer -> Redis`

Current downstream consumer assumes every Kafka message is a DML transaction and can be applied immediately to Redis. That works for row events, but breaks when a destructive DDL changes schema before historical Redis data is rebuilt.

The main architectural constraint is that Kafka routing is currently transaction-based rather than table-based. Because of that, the design must preserve transaction payloads and handle DDL side effects with downstream control state instead of partition-level isolation.

## DDL Model

MySQL DDL for listened tables is detected from binlog `QUERY` events. `QUERY` is not DDL-only, so the upstream listener must:

1. Check `eventType == QUERY`
2. Extract SQL text from `QueryEventData`
3. Decide whether the SQL is a DDL for a configured table

The design distinguishes:

- Lightweight DDL: `ADD COLUMN`
- Destructive DDL: `DROP COLUMN`, `RENAME COLUMN`, `MODIFY COLUMN`, `TRUNCATE TABLE`, `DROP TABLE`

`ADD COLUMN` can be handled as schema evolution without immediate rebuild if downstream tolerates missing historical fields as `null`.

Destructive DDL requires downstream control-state changes and, except for direct table-clear operations like `TRUNCATE TABLE`, a rebuild workflow.

## Core Design

The design separates three concerns that were previously conflated:

1. Source progress: upstream binlog checkpoint after Kafka producer ack
2. Sink intake progress: Kafka consumer offset after downstream state persistence
3. Sink convergence progress: rebuild task completion plus pending transaction replay

These are intentionally independent.

### Upstream Responsibilities

When upstream receives a destructive DDL for a configured table:

1. Build a DDL Kafka message with:
   - `database`
   - `table`
   - `ddlType`
   - `rawSql`
   - `binlogFilename`
   - `nextPosition`
   - `timestamp`
2. Publish to Kafka
3. After Kafka producer ack, advance upstream checkpoint
4. Refresh local metadata cache so later row events are parsed with the newest table definition

Upstream does not write downstream schema state. That state belongs to the sink side because it represents sink readiness, not source progress.

### Downstream Responsibilities

When downstream receives a DDL message:

- Persist the new target schema state for the affected table
- For destructive DDL, create a rebuild task
- Ack Kafka only after the sink-side state required for recovery has been persisted

When downstream receives a DML transaction:

- Inspect the tables referenced by the transaction rows
- If all involved tables are `ACTIVE`, apply directly to Redis
- If any involved table is not `ACTIVE`, persist the entire transaction into `pending_tx` and ack Kafka

The whole transaction is buffered. It is never split by table, because the existing Kafka contract is transaction-oriented.

## Versioning Model

This design does not introduce an integer `schema_version`.

Instead, the effective schema version of a table is the DDL binlog coordinate:

- `schema_binlog_file`
- `schema_next_position`

This coordinate is used in:

- `schema_state`
- `rebuild_task`
- `pending_tx`

Why this matters:

- A rebuild launched by an older DDL must not commit after a newer DDL has arrived
- Pending transactions need to know which schema transition blocked them
- Retry and obsolescence checks must compare against the latest accepted DDL coordinate

## Persistent State

### `schema_state`

One row per configured table.

Purpose:

- Store the latest accepted target schema coordinate for the sink
- Tell consumers whether transactions touching this table may be applied immediately

Suggested fields:

- `database_name`
- `table_name`
- `status`
- `schema_binlog_file`
- `schema_next_position`
- `ddl_type`
- `ddl_sql`
- `updated_at`

Suggested statuses:

- `ACTIVE`
- `REBUILD_REQUIRED`
- `REBUILDING`

### `rebuild_task`

One row per destructive DDL-triggered rebuild attempt.

Purpose:

- Persist async work outside Kafka offset semantics
- Allow retry after process restart or worker failure

Suggested fields:

- `task_id`
- `database_name`
- `table_name`
- `schema_binlog_file`
- `schema_next_position`
- `status`
- `retry_count`
- `last_error`
- `created_at`
- `updated_at`

Suggested statuses:

- `PENDING`
- `RUNNING`
- `DONE`
- `FAILED`
- `OBSOLETE`

### `pending_tx`

One row per Kafka transaction message that cannot yet be applied.

Purpose:

- Decouple Kafka offset advancement from downstream Redis readiness
- Preserve transaction atomicity while a table rebuild is in progress

Suggested fields:

- `transaction_id`
- `connector_name`
- `binlog_filename`
- `next_position`
- `payload_json`
- `blocked_tables`
- `blocked_schema_binlog_file`
- `blocked_schema_next_position`
- `status`
- `created_at`
- `updated_at`

Suggested statuses:

- `PENDING`
- `REPLAYED`

## DDL Timing and State Transitions

For a destructive DDL:

1. Upstream publishes DDL message and advances upstream checkpoint after producer ack
2. Downstream consumes DDL
3. Downstream updates the table row in `schema_state` to the new target coordinate and `REBUILD_REQUIRED`
4. Downstream inserts `rebuild_task`
5. Downstream acks Kafka offset
6. Async worker executes rebuild later

Important distinction:

- `schema_state` changes immediately when the DDL is accepted by the sink
- Live Redis business data does not change immediately
- Redis business data changes only after a successful rebuild commit

`schema_state` means "what the sink must converge to", not "what live Redis already is".

## Async Rebuild Model

### Serial Execution by Table

Destructive rebuilds for the same table must not run in parallel.

Rules:

- Only one `RUNNING` rebuild task is allowed per table
- Newer destructive DDL may insert a newer `PENDING` task for the same table
- Older running tasks may continue, but must perform a final version check before commit
- If the table's latest schema coordinate no longer matches the task coordinate, the task becomes `OBSOLETE`

### Staging Before Publish

Rebuild tasks must not write directly into live Redis while rebuilding.

They must write into a staging namespace first.

Reason:

- If DDL A starts rebuild and DDL B arrives before A commits, A's rebuilt data is stale
- It is not enough to skip status update; stale data itself must not be published into live Redis

Required pattern:

1. Rebuild into staging keys
2. Re-check current `schema_state`
3. If still current, swap/publish staging data into live namespace
4. If obsolete, discard staging data and mark task `OBSOLETE`

### Handling Consecutive DDL

Example:

- `t10`: `DROP COLUMN age`
- `t15`: `DROP COLUMN name`

Processing:

- `t10` updates `schema_state` to coordinate `t10` and creates task `t10`
- `t15` updates the same `schema_state` row to coordinate `t15` and creates task `t15`
- If task `t10` finishes after `t15` has been accepted, task `t10` must discard its staging output and mark itself `OBSOLETE`
- Only task `t15` may eventually publish rebuilt data and return the table to `ACTIVE`

## Transaction Routing During Rebuild

For normal Kafka transaction messages:

1. Extract referenced tables from row events
2. Check `schema_state.status` for those tables
3. If every involved table is `ACTIVE`, apply directly to Redis
4. If any involved table is `REBUILD_REQUIRED` or `REBUILDING`, persist the entire transaction into `pending_tx` and ack Kafka

This rule intentionally buffers mixed transactions too. If one transaction touches both `user` and `order`, and `user` is rebuilding, the whole transaction is buffered. This preserves the current transaction message contract.

### Example Timeline

- `t10`: `user DROP COLUMN age`
- `t12`: `UPDATE order`
- `t13`: `UPDATE user`
- `t14`: transaction touching `user` and `order`
- `t15`: `user DROP COLUMN name`
- `t16`: `INSERT order`
- `t17`: `INSERT user`

Expected behavior:

- `t10` updates `schema_state(user)` and creates rebuild task
- `t12` applies directly to Redis
- `t13` is stored in `pending_tx`
- `t14` is stored in `pending_tx` because it touches rebuilding `user`
- `t15` updates the same `schema_state(user)` again and creates a newer rebuild task
- `t16` applies directly to Redis
- `t17` is stored in `pending_tx`
- Final rebuild is performed only for the latest accepted DDL coordinate

## Replay After Rebuild

After a rebuild task successfully publishes the staging data:

1. Load `pending_tx` entries blocked by this table and schema transition
2. Replay them in binlog order
3. Mark replayed rows in `pending_tx`
4. Update `schema_state` to `ACTIVE`
5. Mark `rebuild_task` as `DONE`

Replay ordering should use:

- `binlog_filename`
- `next_position`

The replay unit remains the whole buffered transaction payload.

## Failure and Retry Semantics

### Upstream failures

- Kafka publish failure means upstream checkpoint must not advance
- Metadata cache must not be considered switched until the DDL has been accepted and emitted

### Downstream DDL intake failures

- If `schema_state` or `rebuild_task` persistence fails, Kafka offset must not be acked
- The DDL message will be retried through normal Kafka redelivery

### Rebuild worker failures

- Failures after Kafka ack are retried from `rebuild_task`, not by Kafka offset replay
- `retry_count` and `last_error` record task history
- Worker restart safety comes from persistent task state plus persistent `pending_tx`

### Obsolete rebuild completion

- If a worker finishes and detects a newer schema coordinate for the same table, it must:
  - discard staging data
  - not publish to live Redis
  - not mark `schema_state` as `ACTIVE`
  - mark the task `OBSOLETE`

## Data Model Implications

This design assumes:

- Redis remains a sink projection rather than a schema-executing database
- Schema changes are represented as sink control state and rebuild actions
- Historical row shape may temporarily differ from target schema until rebuild succeeds

This is acceptable because `schema_state` represents convergence target and pending transaction buffering prevents newer incompatible row updates from being applied prematurely.

## Recommended Implementation Direction

Phase 1:

- Add upstream DDL detection and DDL Kafka publishing
- Refresh upstream metadata cache after accepted DDL
- Add downstream `schema_state`, `rebuild_task`, `pending_tx`
- Add destructive-DML transaction buffering and ack semantics
- Add single-table serial rebuild worker with staging namespace

Phase 2, only if later needed:

- Optimize table status lookup with cache
- Add task leasing for multi-instance workers
- Add more granular replay filters
- Consider topic or partition redesign for table-level isolation

## Trade-offs

Pros:

- Preserves current transaction-oriented Kafka contract
- Avoids blocking unrelated-table transactions
- Supports retry without abusing Kafka offset as rebuild state
- Handles consecutive DDL safely

Cons:

- Requires new persistent control state tables
- Requires staging namespace for rebuild safety
- Mixed transactions touching a rebuilding table are delayed as a whole
- Complexity is higher than fully blocking the consumer

## Acceptance Criteria

The design is successful if:

- Destructive DDL is detected upstream and published to Kafka
- Upstream later row parsing uses refreshed metadata
- Downstream does not apply incompatible row transactions directly during rebuild
- Unrelated-table transactions continue to flow
- Consecutive destructive DDL on the same table do not let stale rebuild output overwrite newer state
- Restart after worker failure can continue from persisted `rebuild_task` and `pending_tx`
