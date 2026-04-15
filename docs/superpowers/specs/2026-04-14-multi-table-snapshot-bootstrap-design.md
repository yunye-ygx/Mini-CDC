# Multi-Table Snapshot Bootstrap Design

## Goal

Extend the existing snapshot-then-incremental startup mode from a single-table bootstrap into a multi-table bootstrap that works with the new multi-table upstream connector model.

The target behavior is:

- one connector listens to multiple configured tables
- one connector captures one cutover binlog position before snapshot starts
- snapshot reads only the configured listened tables
- snapshot scans tables serially in a deterministic order
- one `full_sync_task` row tracks one table's bootstrap state
- one connector-level `cdc_offset` is written only after all listened tables finish snapshot successfully

This design preserves the current connector-level incremental checkpoint semantics and avoids introducing per-table formal binlog recovery points.

## Scope

This version supports:

- one MySQL instance per connector
- one connector with multiple configured listened tables
- one cutover point shared by all tables in the same bootstrap batch
- serial per-table snapshot scanning
- one `full_sync_task` row per `(connector, database, table)`
- rerun-from-start failure recovery

This version does not support:

- parallel table snapshot scanning
- per-table formal incremental checkpoints
- resume-from-last-sent-pk after crash
- DDL bootstrap

## Problem Statement

The incremental upstream path already supports one connector listening to multiple tables, but the snapshot bootstrap path is still single-table.

Current limitations:

- `BinlogCdcLifecycle` rejects snapshot startup when more than one configured table exists.
- `SnapshotBootstrapService` accepts only one `TableMetadata`.
- `full_sync_task` contains `database_name` and `table_name`, but the primary key and update methods are still connector-only.
- snapshot progress is therefore not modeled correctly for a connector bootstrapping multiple tables.

Without a dedicated multi-table snapshot design, the connector can either:

- fail immediately in snapshot mode, or
- lose table-level bootstrap observability if multiple tables are forced into one connector-level task row.

## Design Principles

### 1. Connector-level cutover remains single-valued

The cutover binlog position is captured once for the connector before any snapshot scan begins.

All listened tables in the same bootstrap batch share:

- `cutover_binlog_filename`
- `cutover_binlog_position`

This preserves the current connector-level incremental handoff semantics and keeps one formal `cdc_offset`.

### 2. Snapshot scanning remains table-local

Snapshot reads do not come from binlog and do not naturally produce mixed-table pages.

Each page query is still a single-table SQL query driven by one table's primary key cursor. The system must not model snapshot pages as globally interleaved rows across tables.

### 3. Full-sync progress is table-level, not connector-level

`last_sent_pk` is meaningful only in the context of one table. A connector-level task row cannot explain which table the cursor belongs to.

Therefore the bootstrap state must be tracked per table even though the formal incremental checkpoint remains connector-scoped.

### 4. Failure recovery stays simple

If any table fails during snapshot bootstrap:

- the current bootstrap batch is treated as failed
- no formal connector checkpoint is written
- restart reruns the whole bootstrap with a new cutover point

This keeps the same failure model as the current single-table implementation.

## Target Data Model

### `cdc_offset`

No semantic change:

- still one row per connector
- still represents the formal incremental recovery point
- still must not advance before the full bootstrap batch completes

### `full_sync_task`

`full_sync_task` becomes a per-table bootstrap status table.

Recommended key:

- `PRIMARY KEY (connector_name, database_name, table_name)`

Fields:

- `connector_name`
- `database_name`
- `table_name`
- `status`
- `cutover_binlog_filename`
- `cutover_binlog_position`
- `last_sent_pk`
- `started_at`
- `finished_at`
- `last_error`
- `updated_at`

Semantics:

- one row describes one listened table in one connector bootstrap batch
- rows from the same bootstrap batch share the same `cutover_*`
- `last_sent_pk` belongs only to that row's table
- `COMPLETED` means that table's snapshot pages were fully published
- the connector may write `cdc_offset` only after all configured table rows reach `COMPLETED`

### Status Model

Minimum supported statuses:

- `RUNNING`
- `COMPLETED`
- `FAILED`

Optional future status:

- `PENDING`

This design does not require `PENDING`. The implementation may create each table row as `RUNNING` at bootstrap start and leave `last_sent_pk = null` until the first page is sent.

## Execution Order

Snapshot tables are processed serially, not in parallel.

Recommended ordering rule:

- use the configured order from `mini-cdc.mysql.tables`

Reasoning:

- deterministic and operator-controlled
- aligns with the current ordered configuration loading
- avoids introducing extra ordering logic

Alternative stable orderings such as lexical `database.table` sorting are valid but not recommended for the first implementation.

## Runtime Flow

### Startup Branch

1. Load configured table metadata map from `TableMetadataService`
2. Check connector-level `cdc_offset`
3. If checkpoint exists, skip snapshot and start incremental listener
4. If checkpoint does not exist and startup strategy is `LATEST`, start incremental from latest server position
5. If checkpoint does not exist and startup strategy is `SNAPSHOT_THEN_INCREMENTAL`, execute multi-table snapshot bootstrap

### Multi-Table Snapshot Bootstrap

1. Query current MySQL master status once and store it as connector cutover `Pstart`
2. Resolve the ordered configured listened tables
3. For each configured table, upsert one `full_sync_task` row containing:
   - connector name
   - database/table
   - `status = RUNNING`
   - shared `cutover_*`
   - `last_sent_pk = null`
4. Iterate tables serially in configured order
5. For the current table:
   - open a consistent snapshot read scope
   - read one page using that table's primary-key cursor
   - publish one `SNAPSHOT_UPSERT` event page
   - after publish success, update that table row's `last_sent_pk`
   - repeat until the table is exhausted
6. Mark that table row `COMPLETED`
7. Continue to the next configured table
8. After all configured tables are `COMPLETED`, write `cdc_offset = Pstart`
9. Start incremental binlog listener from `Pstart`

## Snapshot Read Semantics

Each page query is still metadata-driven and table-local.

Examples:

```sql
SELECT *
FROM user
WHERE id > ?
ORDER BY id
LIMIT 10;
```

```sql
SELECT *
FROM `order`
WHERE id > ?
ORDER BY id
LIMIT 10;
```

Composite primary keys continue to use ordered cursor traversal based on table metadata.

In a connector with:

- `user` rows = 30
- `order` rows = 70
- `pageSize = 10`

The serial execution is:

- `user`: 3 pages
- `order`: 7 pages

This is still valid even though total snapshot rows across both tables equal 100. The page size applies to one table query at a time, not to a global mixed-table row stream.

## Filtering Rules

The configured metadata map is the authoritative whitelist for both snapshot and incremental processing.

Required behavior:

- snapshot bootstrap must create task rows only for configured listened tables
- snapshot bootstrap must query only configured listened tables
- non-configured tables must never participate in full-sync progress tracking
- non-configured tables remain filtered out in incremental binlog processing as they are today

This keeps the upstream behavior consistent: the connector owns one explicit listened-table scope and ignores everything outside that scope.

## Failure Handling

### Failure during one table page read or page publish

If a table fails while bootstrapping:

- mark that table row `FAILED`
- set `finished_at`
- persist `last_error`
- stop the current bootstrap batch
- do not write connector `cdc_offset`

Previously completed table rows may remain `COMPLETED`. That is acceptable because they are operational records, not formal recovery checkpoints.

### Restart after failure

On restart:

- existing failed or partial table task rows are diagnostic only
- the connector captures a new cutover point
- the connector recreates or overwrites the current bootstrap batch state
- the connector reruns the snapshot from the beginning for all configured tables

This design intentionally does not resume from `last_sent_pk`.

## Component Changes

### `TableMetadataService`

No semantic redesign is needed.

It already provides the essential input for multi-table snapshot bootstrap:

- configured listened tables
- metadata by qualified table
- deterministic iteration order

### `BinlogCdcLifecycle`

Required changes:

- remove the single-table restriction for snapshot startup
- replace the single-table snapshot bootstrap call with a multi-table bootstrap call
- continue using connector-level `cdc_offset` resolution logic

### `SnapshotBootstrapService`

This is the largest code change.

Required changes:

- change bootstrap input from one `TableMetadata` to ordered multi-table input
- add an outer serial loop over configured tables
- reuse the existing inner page-read and page-publish behavior per table
- update table-specific task rows after each successful page publish
- mark individual tables completed or failed
- write connector `cdc_offset` only after all tables complete

### `FullSyncTaskStore` and mapper/entity layer

Required changes:

- treat `(connector, database, table)` as the task identity
- update mapper SQL `WHERE` conditions to include all three columns
- keep `last_sent_pk` table-scoped
- keep `cutover_*` shared across rows in the same batch

### `CheckpointStore`

No semantic change is required.

It must continue to expose:

- `load()`
- `loadLatestServerCheckpoint()`
- `save()`

with connector-level meaning only.

## Compatibility

Single-table bootstrap remains a valid special case of this design:

- one configured table still creates one task row
- one table still shares one cutover point with itself
- all completion and checkpoint rules remain the same

The multi-table design therefore generalizes the old behavior instead of replacing it with a different checkpoint model.

## Testing Requirements

Tests must cover:

- snapshot startup accepts multiple configured tables
- serial execution follows configured table order
- all table rows share the same cutover position
- each table updates only its own `last_sent_pk`
- `cdc_offset` is not written until all tables complete
- if the second table fails after the first completes, first stays `COMPLETED`, second becomes `FAILED`, and `cdc_offset` is still absent
- restart after failure still reruns the full bootstrap rather than resuming partial progress

## Acceptance Criteria

The design is complete when:

- one connector in snapshot mode can bootstrap multiple configured tables
- snapshot queries run only against configured listened tables
- tables are processed serially in deterministic order
- one `full_sync_task` row exists per configured table
- all rows in the same bootstrap batch share the same cutover binlog position
- connector `cdc_offset` is written only after all tables finish successfully
- incremental startup after bootstrap still resumes from one connector-level checkpoint
