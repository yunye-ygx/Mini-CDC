# Redis Tombstone And Replay Protection Design

## Goal

Add an optional Redis replay-protection mode that preserves business-key payloads in their current shape while preventing stale transaction replays from resurrecting deleted rows or deleting newer data.

## Scope

This design covers:

- Redis sink behavior only
- Existing single-table CDC pipeline
- Transaction messages already published to Kafka
- Optional row-level replay protection using Redis metadata keys
- Redis tombstone behavior for `DELETE`
- Version comparison using transaction position plus row order

This design does not cover:

- New sink types beyond Redis
- Kafka compacted-topic tombstones
- Automatic tombstone expiration or cleanup
- Partial transaction replay tooling
- Primary key mutation support

## Why This Change Exists

The current pipeline has transaction-level deduplication only. That protects against replay of the same `transactionId`, but it does not protect against an older transaction being replayed later.

That gap already exists for `INSERT` and `UPDATE`, but `DELETE` makes it more visible:

- an old `INSERT` or `UPDATE` can recreate data that was deleted later
- an old `DELETE` can remove data that was inserted or updated later

The current design in [2026-04-11-delete-event-message-model-design.md](C:/javapractice/mn-CDC/docs/superpowers/specs/2026-04-11-delete-event-message-model-design.md) intentionally left this problem unsolved. This spec adds that missing protection.

## Product Positioning

The Redis sink remains an in-project reference adapter. It is not the whole CDC architecture, but it is responsible for making the Redis landing path reliable enough for practical replay and recovery scenarios.

To preserve the current learning-friendly path, replay protection is configurable:

- `simple` mode keeps the current behavior
- `meta` mode adds row metadata and tombstone protection

Default mode stays `simple`.

## Target Semantics

### `simple` mode

- Keep existing transaction done-key dedupe
- `INSERT` and `UPDATE` write Redis business keys directly
- `DELETE` removes Redis business keys directly
- No protection against stale replay across different transactions

### `meta` mode

- Keep existing transaction done-key dedupe
- Add one metadata key per business key
- Treat the metadata key as the latest known row state ledger
- Compare incoming row version with stored row version before applying changes
- Ignore stale row changes
- Preserve Redis business-key payload shape for consumers
- Use delete tombstones by keeping metadata even after the business key is removed

## Configuration

Add a Redis apply mode setting:

```yaml
mini-cdc:
  redis:
    apply-mode: simple # simple | meta
```

Expected behavior:

- `simple` is the default
- `meta` enables replay protection and tombstones

## Message Model Changes

`CdcTransactionRow` gains one new field:

```json
{
  "eventIndex": 0,
  "eventType": "INSERT|UPDATE|DELETE",
  "primaryKey": { "id": 1 },
  "before": { "id": 1, "username": "tom" },
  "after": { "id": 1, "username": "tommy" }
}
```

`eventIndex` is the zero-based order of the row inside the committed transaction event list.

This field is needed so row versions can distinguish multiple changes to the same primary key inside one transaction.

## Version Model

Each incoming row version is derived from:

- `binlogFilename`
- `nextPosition`
- `eventIndex`

Comparison order:

1. binlog filename numeric suffix
2. next position
3. event index

Example:

- `mysql-bin.000010`, `120`, `0`
- `mysql-bin.000010`, `120`, `1`
- `mysql-bin.000010`, `125`, `0`

The second version is newer than the first because `eventIndex` is greater inside the same transaction boundary. The third version is newer than both because `nextPosition` is greater.

## Redis Key Design

### Existing transaction done key

Keep the current key:

`mini-cdc:txn:done:{transactionId}`

Responsibility:

- dedupe exact replay of the same transaction

### Business key

Keep the current business key shape:

`{keyPrefix}{primaryKeySuffix}`

Example:

`user:1`

Responsibility:

- store only business payload JSON

### Metadata key

Add a new metadata key:

`{rowMetaPrefix}{table}:{primaryKeySuffix}`

Recommended default prefix:

`mini-cdc:row:meta:`

Example:

`mini-cdc:row:meta:user:1`

Responsibility:

- store latest row version
- store deleted/live status
- act as tombstone after delete

## Metadata Value Shape

The metadata value should be JSON:

```json
{
  "deleted": false,
  "version": {
    "binlogFilename": "mysql-bin.000010",
    "nextPosition": 125,
    "eventIndex": 1,
    "transactionId": "mini-user-sync:mysql-bin.000010:88:125"
  }
}
```

Notes:

- `transactionId` is included for debugging and traceability
- version comparison should use `binlogFilename`, `nextPosition`, and `eventIndex`
- `deleted=true` means the metadata key is acting as a tombstone

## Apply Rules

### When metadata does not exist

| Event | Business key | Metadata key |
|---|---|---|
| `INSERT` | `SET after` | Create metadata with `deleted=false` and incoming version |
| `UPDATE` | `SET after` | Create metadata with `deleted=false` and incoming version |
| `DELETE` | `DEL` or no-op if absent | Create metadata with `deleted=true` and incoming version |

Rationale:

- the first observed row change must establish the row ledger
- delete must leave a tombstone even if the business key does not exist

### When metadata exists and incoming version is newer

| Event | Business key | Metadata key |
|---|---|---|
| `INSERT` | `SET after` | Update metadata to `deleted=false` and incoming version |
| `UPDATE` | `SET after` | Update metadata to `deleted=false` and incoming version |
| `DELETE` | `DEL` | Update metadata to `deleted=true` and incoming version |

### When metadata exists and incoming version is older or equal

| Event | Business key | Metadata key |
|---|---|---|
| `INSERT` | No-op | No-op |
| `UPDATE` | No-op | No-op |
| `DELETE` | No-op | No-op |

Rationale:

- stale replay must not change current Redis state

## Strategy Structure

Add a narrow strategy seam in the Redis sink:

- `RedisApplyStrategy`
- `SimpleRedisApplyStrategy`
- `MetaRedisApplyStrategy`

Responsibilities:

- `RedisSyncConsumer` stays unchanged except for receiving the configured applier behavior
- `SimpleRedisApplyStrategy` preserves current script and behavior
- `MetaRedisApplyStrategy` builds business keys plus metadata keys and uses a metadata-aware Lua script

This avoids duplicating the upstream CDC, Kafka, and consumer pipeline.

## Lua Behavior

### Simple strategy Lua

Keep the current script:

- check transaction done key
- apply `SET` or `DEL`
- write transaction done key

### Meta strategy Lua

The metadata-aware script should:

1. Check transaction done key
2. For each row:
   - read metadata key if it exists
   - compare stored version with incoming version
   - if incoming version is newer:
     - `INSERT` or `UPDATE`: `SET` business key payload and update metadata to `deleted=false`
     - `DELETE`: `DEL` business key and update metadata to `deleted=true`
   - otherwise skip the row
3. Write transaction done key
4. Return `APPLIED` or `DUPLICATE`

Transaction done-key semantics remain unchanged. A transaction can still be skipped immediately if it was already applied before.

## Reliability Guarantees

### Guaranteed in `simple`

- exact duplicate transaction replay is ignored
- Redis apply remains atomic per transaction message

### Guaranteed in `meta`

- exact duplicate transaction replay is ignored
- stale row replay across different transactions is ignored
- deleted rows are protected from stale resurrection
- newer rows are protected from stale deletes
- Redis apply remains atomic per transaction message

### Not guaranteed in this version

- metadata key cleanup
- cross-table routing
- primary key mutation support

## Backward Compatibility

`simple` remains the default so the existing behavior is preserved unless `meta` mode is enabled.

Enabling `meta` mode changes Redis internals by adding metadata keys, but it does not change the business payload stored at the Redis business key.

## Testing Requirements

Add coverage for:

- `eventIndex` serialization in transaction messages
- `simple` mode still using the existing script semantics
- `meta` mode creating metadata on first insert
- `meta` mode creating tombstone metadata on delete
- stale insert replay after a newer delete is skipped
- stale delete replay after a newer insert/update is skipped
- same-transaction repeated key changes use `eventIndex` correctly
- metadata-aware strategy still returns `DUPLICATE` when the transaction done key exists

## Open Follow-Up

Future work can address:

- metadata key TTL or compaction
- shared abstractions for other sink adapters
- replay tooling that can intentionally rebuild Redis from historical checkpoints
