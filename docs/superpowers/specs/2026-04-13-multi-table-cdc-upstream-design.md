# Multi-Table CDC Upstream Design

## Goal

Upgrade the upstream CDC pipeline from a single-table connector model to a multi-table connector model.

This design targets one connector that listens to multiple tables from one MySQL instance, consumes one binlog stream, preserves MySQL transaction boundaries, and advances one connector-level checkpoint.

This document covers only the upstream producer side:

- configuration
- metadata loading
- binlog event filtering
- transaction buffering
- message model
- checkpoint semantics

This document does not redesign downstream sink behavior. It does call out downstream contract impacts where the upstream change makes them unavoidable.

## Problem Statement

The current implementation is built on a single-table assumption:

- one configured `database/table`
- one cached `TableMetadata`
- one transaction buffer whose rows do not carry table identity
- one `CdcTransactionEvent` whose header contains exactly one `database/table`
- one checkpoint row that is validated against exactly one configured table

This works only because the current connector listens to one table. Once one connector must listen to multiple tables, the model breaks down.

The root reason is that MySQL binlog is not table-scoped. For one MySQL instance, all relevant row changes from all databases and tables are emitted on one binlog event stream. Table filtering happens after the connector reads that stream.

## Scope

This version supports:

- one connector listening to multiple configured tables
- one connector-level startup checkpoint
- one binlog client and one binlog reader thread
- one MySQL transaction mapping to one CDC transaction message
- row-level table identity inside the transaction payload
- incremental CDC only

This version intentionally does not include:

- multi-MySQL-instance aggregation
- DDL synchronization
- a redesign of downstream consumers
- multi-table snapshot bootstrap

Snapshot support is deferred because the current snapshot model is explicitly single-table and would add a second large refactor surface.

## Current Model

### Runtime shape

Today the upstream pipeline runs as:

1. load one configured table from properties
2. load one `TableMetadata`
3. load one checkpoint using `connectorName`
4. start one `BinaryLogClient` from that checkpoint
5. read the full MySQL binlog stream
6. discard all row events that are not from the configured table
7. buffer matching rows until `XID`
8. publish one transaction message whose header carries one `database/table`
9. save checkpoint after publish succeeds

### Current constraints

The current message model cannot correctly represent one MySQL transaction that changes multiple listened tables.

Example:

- `insert user(1)`
- `insert order(10)`

At `XID`, the connector has one transaction, but `CdcTransactionEvent` can only carry one `database/table` in the header. That means the published message would be lossy or would require breaking one MySQL transaction into multiple CDC messages.

## Target Model

### Runtime shape

The target upstream pipeline runs as:

1. load one connector configuration with a set of listened tables
2. load metadata for every configured table
3. load one checkpoint using `connectorName`
4. start one `BinaryLogClient` from that checkpoint
5. read the full MySQL binlog stream
6. for each row event, determine whether its table belongs to the listened table set
7. append matching rows to the current transaction buffer, including row-level table identity
8. wait for `XID`
9. build one CDC transaction event that preserves the whole MySQL transaction boundary
10. publish that transaction event
11. after publish succeeds, advance one connector-level checkpoint

### Core invariants

The following invariants define the new model:

- one connector has one checkpoint
- one connector reads one binlog stream from one MySQL instance
- one MySQL transaction is the unit of buffering and checkpoint advancement
- one CDC transaction message may contain rows from multiple tables
- each buffered row must know its own `database/table`

## Checkpoint Semantics

### Definition

The checkpoint should represent:

- the latest MySQL transaction position that this connector has completely processed and safely accepted

It should not mean:

- the latest table-specific position
- the latest position for one particular row type
- the latest transaction that produced a business message for only one table

### Why connector-level checkpoint is required

For one connector listening to multiple tables, storing separate per-table checkpoints is incorrect because:

- MySQL binlog ordering is instance-level, not table-level
- the connector reads one ordered stream
- one transaction may contain rows from multiple tables
- transaction atomicity would be broken if each table advanced independently

### Advancement timing

Checkpoint advancement must happen at transaction boundaries, not at individual row events.

The correct advancement point is after:

1. the connector reaches `XID`
2. the whole transaction has been evaluated
3. the outbound Kafka publish for that transaction has succeeded

The checkpoint must not advance:

- when only part of the transaction has been seen
- when only the first row event has been read
- before Kafka publish success is known

### Transactions with no matching rows

The target model should treat the checkpoint as connector read progress, not only business-hit progress.

That means:

- if a transaction is fully read
- and no rows belong to the configured table set
- the connector should still be allowed to advance its checkpoint at `XID`

Reasoning:

- otherwise the checkpoint can lag far behind the actual reader position
- restarts would repeatedly rescan long runs of irrelevant transactions
- the connector would have poor recovery behavior on sparse tables

This is a deliberate semantic change from the current implementation.

## Message Model Redesign

### Problem in the current event shape

The current event header includes:

- `database`
- `table`

This assumes one transaction belongs to one table, which is false for multi-table listening.

### Target event shape

`CdcTransactionEvent` should carry only transaction-level data:

- `transactionId`
- `connectorName`
- `binlogFilename`
- `xid`
- `nextPosition`
- `timestamp`
- `events`

`CdcTransactionRow` should carry row-level data:

- `database`
- `table`
- `eventIndex`
- `eventType`
- `primaryKey`
- `before`
- `after`

This preserves:

- full transaction boundaries
- row order within the transaction
- exact table ownership of each row

### Why row-level table identity is mandatory

A table list in the transaction header is not enough.

Example:

- row 0 is from `user`
- row 1 is from `order`
- row 2 is from `user`

A header-level table list cannot tell which row belongs to which table. The table identity must be stored on each row from the moment it enters the transaction buffer.

## Configuration Changes

### Current shape

Current source configuration assumes one table:

- one `database`
- one `table`

### Target shape

Source configuration should define a set of listened tables under one connector.

Acceptable shapes include:

- `List<String>` where each value is `database.table`
- `List<TableRef>` where each element carries `database` and `table`

The design recommendation is to use an explicit structured table reference type because it is easier to validate and less error-prone than parsing concatenated strings everywhere.

### Connector identity

`connectorName` remains single-valued.

For a multi-table connector:

- one connector name
- one table scope
- one checkpoint row

## Metadata Loading

### Current behavior

The current metadata service caches one `TableMetadata`.

### Target behavior

The metadata service must:

- load metadata for every configured table
- cache metadata by qualified table identity
- provide lookup by `(database, table)`

Recommended cache shape:

- `Map<QualifiedTable, TableMetadata>`

Required behaviors:

- validate all configured tables at startup
- fail fast if a configured table does not exist
- fail fast if a configured table lacks primary keys

## Binlog Lifecycle Changes

`BinlogCdcLifecycle` is the main refactor point.

### Current behavior

The current lifecycle class:

- keeps one `tableMetadata`
- checks whether a row belongs to exactly one configured table
- converts row values using that one metadata object
- buffers rows without table identity
- emits one single-table event at `XID`

### Target behavior

The lifecycle class must instead:

- maintain a configured listened-table set
- keep table metadata accessible by table identity
- map row events to their qualified table through `TABLE_MAP`
- skip rows from tables outside the listened-table set
- convert row values using metadata for the specific source table
- buffer rows together with table identity
- emit one transaction event at `XID` containing all matching rows from that transaction

### Transaction buffer requirements

The transaction buffer should remain transaction-ordered and append-only during one open transaction.

Every buffered row must include:

- source table identity
- row order inside the transaction
- event type
- `before/after`
- primary key

The buffer must not depend on one global `TableMetadata`.

## Checkpoint Storage Changes

### Current shape

The existing checkpoint row stores:

- `connector_name`
- `database_name`
- `table_name`
- binlog location

This shape reflects the old single-table model.

### Target shape

The primary identity remains:

- `connector_name`

The stored checkpoint should represent the connector's recovery point, not one single table's recovery point.

There are two viable storage options:

1. Keep `database_name/table_name` only for backward compatibility and diagnostics, while no longer using them as strict validation keys.
2. Replace single-table identity fields with a connector scope representation such as:
   - `table_scope_json`
   - `table_scope_hash`

Recommendation:

- preserve backward compatibility for the first incremental refactor if that reduces migration cost
- but remove strict single-table validation from startup logic

### Startup validation

The current startup validation compares one stored `database/table` to one configured table.

That must change because one checkpoint now belongs to one connector with a table set.

New validation should confirm one of the following:

- the stored checkpoint belongs to the same connector and the same configured table scope
- or the connector is starting fresh and no checkpoint exists yet

## Kafka Publish Semantics

The producer-side send flow remains broadly unchanged:

- build transaction payload
- serialize payload
- publish to one configured topic
- wait for send success
- only then advance checkpoint

What changes is the payload contract, not the fundamental send lifecycle.

## Deferred Snapshot Design

The current snapshot bootstrap design is explicitly single-table.

This upstream refactor should not attempt to include multi-table snapshot support in the same implementation step.

Reasons:

- snapshot bootstrap today depends on one `TableMetadata`
- `full_sync_task` rows are keyed and described as single-table tasks
- multi-table bootstrap raises new questions about cutover semantics, task status tracking, retry boundaries, and task fan-out

Recommended sequencing:

1. implement incremental multi-table CDC first
2. freeze startup strategy for this connector to incremental-only in the first step if necessary
3. design multi-table bootstrap as a follow-up spec

## Compatibility And Impact

### Direct upstream impacts

The following upstream components must change:

- `MiniCdcProperties`
- `application.yml`
- `TableMetadataService`
- `BinlogCdcLifecycle`
- `CdcTransactionEvent`
- `CdcTransactionRow`
- `CheckpointStore`
- checkpoint validation logic

### Downstream contract impact

Even though this design does not implement downstream changes, the upstream message contract change will inevitably affect downstream consumers.

Specifically:

- any consumer that expects `database/table` only at the transaction header level will break
- downstream routing or key derivation that assumes one transaction belongs to one table must be updated

This is a known and intentional compatibility impact.

### Operational impact

Compared with a multi-connector-per-table approach, this design:

- reduces connector count
- reduces duplicate binlog reads
- keeps one checkpoint per multi-table connector
- makes cross-table transaction handling more correct

But it also:

- increases the complexity of one connector instance
- requires a message schema migration
- makes one connector failure affect a larger listened-table scope

## Alternative Considered

### Alternative: keep single-table model and run one connector per table

This alternative was considered because it minimizes code changes.

Why it was rejected as the primary target:

- configuration and instance count scale linearly with table count
- multiple connectors repeatedly read the same MySQL binlog stream
- cross-table transactions are not modeled as one unit inside one connector
- operational complexity grows quickly as more tables are added

This remains a valid short-term fallback strategy, but not the recommended target architecture.

## Implementation Direction

The implementation should proceed in two stages.

### Stage 1: incremental multi-table upstream

Deliver:

- multi-table connector configuration
- multi-table metadata cache
- row-level table identity
- connector-level checkpoint semantics
- transaction-boundary multi-table event publishing

Do not include:

- snapshot refactor
- downstream contract migration

### Stage 2: follow-up work

Design separately:

- multi-table snapshot bootstrap
- downstream message contract adoption
- topic routing or partition strategy changes if throughput later requires them

## Testing Requirements

Tests must cover:

- startup with a multi-table configuration loads metadata for every configured table
- row events from configured tables are buffered with correct table identity
- row events from non-configured tables are ignored
- one MySQL transaction containing rows from multiple configured tables produces one CDC transaction event with mixed-table rows
- checkpoint advances only after Kafka send success
- checkpoint can advance for transactions with no matching rows once the connector has fully processed the transaction
- restart resumes from the connector checkpoint rather than any per-table position
- existing single-table behavior remains compatible where explicitly preserved

## Acceptance Criteria

This design is complete when:

- one connector can listen to multiple configured tables from one MySQL instance
- one MySQL transaction that touches multiple configured tables is emitted as one CDC transaction message
- every emitted row includes its source `database/table`
- the connector advances one connector-level checkpoint
- restarts resume from that connector checkpoint
- the implementation no longer depends on a single configured `database/table` assumption in the incremental upstream path
