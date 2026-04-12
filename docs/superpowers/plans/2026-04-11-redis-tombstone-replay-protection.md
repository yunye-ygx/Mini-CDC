# Redis Tombstone Replay Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional Redis `meta` apply mode so stale transaction replays cannot resurrect deleted rows or delete newer data, while preserving the existing Redis business-key payload shape.

**Architecture:** Keep `RedisSyncConsumer` stable by turning `RedisTransactionApplier` into a thin mode-selecting facade. `simple` mode keeps the current done-key plus business-key Lua path, while `meta` mode adds per-row metadata tombstones and a dedicated Lua script that compares `(binlogFilename, nextPosition, eventIndex)` before applying each row.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data Redis, Jackson, Lua, JUnit 5, Mockito, Testcontainers (Redis), Maven

---

## File Map

- Modify: `pom.xml`
  - Add Testcontainers test dependencies so the metadata-aware Lua behavior is verified against a real Redis server.
- Modify: `src/main/java/com/yunye/mncdc/config/MiniCdcProperties.java`
  - Add Redis apply-mode and metadata-prefix configuration with `simple` as the default.
- Modify: `src/main/resources/application.yml`
  - Surface the new Redis settings in the sample application config.
- Modify: `src/main/java/com/yunye/mncdc/model/CdcTransactionRow.java`
  - Add `eventIndex` to the row payload published to Kafka and consumed by Redis.
- Create: `src/main/java/com/yunye/mncdc/model/RedisRowVersion.java`
  - Strongly typed metadata version payload written to Redis in `meta` mode.
- Create: `src/main/java/com/yunye/mncdc/model/RedisRowMetadata.java`
  - Strongly typed Redis metadata/tombstone JSON envelope.
- Modify: `src/main/java/com/yunye/mncdc/service/BinlogCdcLifecycle.java`
  - Assign deterministic `eventIndex` values in committed row order.
- Modify: `src/main/java/com/yunye/mncdc/service/RedisTransactionApplier.java`
  - Convert the current service into a facade that delegates to `simple` or `meta`.
- Create: `src/main/java/com/yunye/mncdc/service/RedisApplyStrategy.java`
  - Shared contract for Redis apply implementations.
- Create: `src/main/java/com/yunye/mncdc/service/SimpleRedisApplyStrategy.java`
  - Preserve the current done-key and business-key apply semantics.
- Create: `src/main/java/com/yunye/mncdc/service/MetaRedisApplyStrategy.java`
  - Build business keys plus metadata keys and invoke the metadata-aware Lua script.
- Create: `src/main/resources/lua/apply-transaction-meta.lua`
  - Compare stored and incoming row versions, maintain tombstones, and keep transaction apply atomic.
- Modify: `src/test/java/com/yunye/mncdc/service/BinlogCdcLifecycleTransactionTest.java`
  - Cover `eventIndex` assignment and repeated-key ordering inside one transaction.
- Create: `src/test/java/com/yunye/mncdc/service/CdcEventPublisherTest.java`
  - Verify `eventIndex` is serialized in published transaction JSON.
- Modify: `src/test/java/com/yunye/mncdc/service/RedisTransactionApplierTest.java`
  - Refocus on facade delegation by apply mode instead of low-level Redis argument construction.
- Create: `src/test/java/com/yunye/mncdc/service/SimpleRedisApplyStrategyTest.java`
  - Preserve the current `simple` Lua contract and validation behavior.
- Create: `src/test/java/com/yunye/mncdc/service/MetaRedisApplyStrategyTest.java`
  - Verify metadata key layout, metadata JSON payload shape, duplicate handling, and Lua resource loading.
- Create: `src/test/java/com/yunye/mncdc/service/MetaRedisApplyStrategyIntegrationTest.java`
  - Run real Redis behavioral checks for tombstones, stale replay rejection, and `eventIndex` ordering.
- Modify: `src/test/java/com/yunye/mncdc/service/RedisSyncConsumerTest.java`
  - Update fixtures to the final `CdcTransactionRow` shape so Kafka consumer coverage keeps compiling and round-tripping.

### Task 1: Add `eventIndex` To The Transaction Message Model

**Files:**
- Create: `src/test/java/com/yunye/mncdc/service/CdcEventPublisherTest.java`
- Modify: `src/test/java/com/yunye/mncdc/service/BinlogCdcLifecycleTransactionTest.java`
- Modify: `src/main/java/com/yunye/mncdc/model/CdcTransactionRow.java`
- Modify: `src/main/java/com/yunye/mncdc/service/BinlogCdcLifecycle.java`

- [ ] **Step 1: Write the failing producer and serialization tests**

```java
@Test
void assignsEventIndexInCommittedRowOrder() throws Exception {
    when(publisher.publishTransaction(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

    invokeHandleEvent(tableMapEvent(10L, "mini", "user"));
    invokeHandleEvent(writeRowsEvent(10L, 120L, new Serializable[]{1L, "alice"}));
    invokeHandleEvent(updateRowsEvent(10L, 120L, new Serializable[]{1L, "alice"}, new Serializable[]{1L, "alicia"}));
    invokeHandleEvent(deleteRowsEvent(10L, 120L, new Serializable[]{1L, "alicia"}));
    invokeHandleEvent(xidEvent(345L, 240L));

    ArgumentCaptor<CdcTransactionEvent> eventCaptor = ArgumentCaptor.forClass(CdcTransactionEvent.class);
    verify(publisher).publishTransaction(eventCaptor.capture());
    assertThat(eventCaptor.getValue().events())
            .extracting(CdcTransactionRow::eventIndex, CdcTransactionRow::eventType)
            .containsExactly(
                    tuple(0, "INSERT"),
                    tuple(1, "UPDATE"),
                    tuple(2, "DELETE")
            );
}
```

```java
@Test
void serializesEventIndexInPublishedPayload() throws Exception {
    when(properties.getKafka()).thenReturn(kafkaProperties);
    when(kafkaProperties.getTopic()).thenReturn("user-change-topic");
    when(properties.isLogEventJson()).thenReturn(false);
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

    publisher.publishTransaction(new CdcTransactionEvent(
            "txn-1",
            "mini-user-sync",
            "mini",
            "user",
            "mysql-bin.000010",
            88L,
            125L,
            1L,
            List.of(new CdcTransactionRow(
                    1,
                    "UPDATE",
                    Map.of("id", 1L),
                    Map.of("id", 1L, "username", "tom"),
                    Map.of("id", 1L, "username", "tommy")
            ))
    ));

    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate).send(eq("user-change-topic"), eq("txn-1"), payloadCaptor.capture());
    assertThat(payloadCaptor.getValue()).contains("\"eventIndex\":1");

    CdcTransactionEvent roundTrip = new ObjectMapper().readValue(payloadCaptor.getValue(), CdcTransactionEvent.class);
    assertThat(roundTrip.events().get(0).eventIndex()).isEqualTo(1);
}
```

- [ ] **Step 2: Run the focused producer tests and verify they fail**

Run: `mvn -Dtest=BinlogCdcLifecycleTransactionTest,CdcEventPublisherTest test`
Expected: FAIL because `CdcTransactionRow` has no `eventIndex`, constructors no longer match, and committed rows do not expose a deterministic per-transaction order.

- [ ] **Step 3: Extend the row model and assign event order at buffer time**

```java
public record CdcTransactionRow(
        int eventIndex,
        String eventType,
        Map<String, Object> primaryKey,
        Map<String, Object> before,
        Map<String, Object> after
) {
}
```

```java
private void handleInsert(WriteRowsEventData eventData) {
    if (!isConfiguredTable(eventData.getTableId())) {
        return;
    }
    for (Serializable[] row : eventData.getRows()) {
        Map<String, Object> after = toRowMap(row);
        appendBufferedRow("INSERT", extractPrimaryKey(after), null, after);
    }
}

private void handleUpdate(UpdateRowsEventData eventData) {
    if (!isConfiguredTable(eventData.getTableId())) {
        return;
    }
    for (Map.Entry<Serializable[], Serializable[]> row : eventData.getRows()) {
        Map<String, Object> before = toRowMap(row.getKey());
        Map<String, Object> after = toRowMap(row.getValue());
        assertPrimaryKeyUnchanged(before, after);
        appendBufferedRow("UPDATE", extractPrimaryKey(after), before, after);
    }
}

private void handleDelete(DeleteRowsEventData eventData) {
    if (!isConfiguredTable(eventData.getTableId())) {
        return;
    }
    for (Serializable[] row : eventData.getRows()) {
        Map<String, Object> before = toRowMap(row);
        appendBufferedRow("DELETE", extractPrimaryKey(before), before, null);
    }
}

private void appendBufferedRow(
        String eventType,
        Map<String, Object> primaryKey,
        Map<String, Object> before,
        Map<String, Object> after
) {
    bufferedTransactionRows.add(new CdcTransactionRow(
            bufferedTransactionRows.size(),
            eventType,
            primaryKey,
            before,
            after
    ));
}
```

- [ ] **Step 4: Re-run the producer tests and verify they pass**

Run: `mvn -Dtest=BinlogCdcLifecycleTransactionTest,CdcEventPublisherTest test`
Expected: PASS

### Task 2: Add Redis Apply Mode Configuration And Delegation Seam

**Files:**
- Modify: `src/main/java/com/yunye/mncdc/config/MiniCdcProperties.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/yunye/mncdc/service/RedisApplyStrategy.java`
- Modify: `src/main/java/com/yunye/mncdc/service/RedisTransactionApplier.java`
- Modify: `src/test/java/com/yunye/mncdc/service/RedisTransactionApplierTest.java`

- [ ] **Step 1: Write the failing facade tests for default `simple` mode and explicit `meta` mode**

```java
@Test
void delegatesToSimpleStrategyByDefault() {
    MiniCdcProperties properties = new MiniCdcProperties();
    RedisTransactionApplier applier = new RedisTransactionApplier(properties, simpleStrategy, metaStrategy);
    CdcTransactionEvent transaction = sampleTransaction();
    when(simpleStrategy.apply(transaction)).thenReturn(RedisTransactionApplier.ApplyResult.APPLIED);

    assertThat(applier.apply(transaction)).isEqualTo(RedisTransactionApplier.ApplyResult.APPLIED);
    verify(simpleStrategy).apply(transaction);
    verifyNoInteractions(metaStrategy);
}

@Test
void delegatesToMetaStrategyWhenConfigured() {
    MiniCdcProperties properties = new MiniCdcProperties();
    properties.getRedis().setApplyMode(MiniCdcProperties.Redis.ApplyMode.META);
    RedisTransactionApplier applier = new RedisTransactionApplier(properties, simpleStrategy, metaStrategy);
    CdcTransactionEvent transaction = sampleTransaction();
    when(metaStrategy.apply(transaction)).thenReturn(RedisTransactionApplier.ApplyResult.DUPLICATE);

    assertThat(applier.apply(transaction)).isEqualTo(RedisTransactionApplier.ApplyResult.DUPLICATE);
    verify(metaStrategy).apply(transaction);
    verifyNoInteractions(simpleStrategy);
}
```

- [ ] **Step 2: Run the facade test class and verify it fails**

Run: `mvn -Dtest=RedisTransactionApplierTest test`
Expected: FAIL because `RedisTransactionApplier` still owns all Redis logic directly and there is no configurable apply mode.

- [ ] **Step 3: Add configuration defaults and the delegation seam**

```java
@Data
public static class Redis {

    private String keyPrefix = "user:";

    private String transactionDonePrefix = "mini-cdc:txn:done:";

    private String rowMetaPrefix = "mini-cdc:row:meta:";

    private ApplyMode applyMode = ApplyMode.SIMPLE;

    public enum ApplyMode {
        SIMPLE,
        META
    }
}
```

```yaml
mini-cdc:
  redis:
    key-prefix: "user:"
    transaction-done-prefix: "mini-cdc:txn:done:"
    row-meta-prefix: "mini-cdc:row:meta:"
    apply-mode: simple
```

```java
public interface RedisApplyStrategy {

    RedisTransactionApplier.ApplyResult apply(CdcTransactionEvent transactionEvent);
}
```

```java
@Service
@RequiredArgsConstructor
public class RedisTransactionApplier {

    private final MiniCdcProperties properties;

    private final SimpleRedisApplyStrategy simpleStrategy;

    private final MetaRedisApplyStrategy metaStrategy;

    public ApplyResult apply(CdcTransactionEvent transactionEvent) {
        return switch (properties.getRedis().getApplyMode()) {
            case META -> metaStrategy.apply(transactionEvent);
            case SIMPLE -> simpleStrategy.apply(transactionEvent);
        };
    }

    public enum ApplyResult {
        APPLIED,
        DUPLICATE;

        public static ApplyResult from(String value) {
            if ("APPLIED".equals(value)) {
                return APPLIED;
            }
            if ("DUPLICATE".equals(value)) {
                return DUPLICATE;
            }
            throw new IllegalStateException("Unexpected Redis apply result: " + value);
        }
    }
}
```

- [ ] **Step 4: Re-run the facade tests and verify they pass**

Run: `mvn -Dtest=RedisTransactionApplierTest test`
Expected: PASS

### Task 3: Move Current Behavior Into `SimpleRedisApplyStrategy`

**Files:**
- Create: `src/main/java/com/yunye/mncdc/service/SimpleRedisApplyStrategy.java`
- Create: `src/test/java/com/yunye/mncdc/service/SimpleRedisApplyStrategyTest.java`

- [ ] **Step 1: Write the failing `simple` strategy tests by porting the current applier contract**

```java
@Test
void appliesMixedSetAndDeleteOperationsInOrder() {
    when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("APPLIED");

    RedisTransactionApplier.ApplyResult result = strategy.apply(new CdcTransactionEvent(
            "mini-user-sync:mysql-bin.000006:123:456",
            "mini-user-sync",
            "mini",
            "user",
            "mysql-bin.000006",
            123L,
            456L,
            6L,
            List.of(
                    new CdcTransactionRow(0, "INSERT", Map.of("id", 10L), null, Map.of("id", 10L, "username", "bob")),
                    new CdcTransactionRow(1, "DELETE", Map.of("id", 20L), Map.of("id", 20L, "username", "charlie"), null)
            )
    ));

    assertThat(result).isEqualTo(RedisTransactionApplier.ApplyResult.APPLIED);

    ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());
    assertThat(keysCaptor.getValue()).containsExactly(
            "mini-cdc:txn:done:mini-user-sync:mysql-bin.000006:123:456",
            "user:10",
            "user:20"
    );
    Object[] args = argsCaptor.getValue();
    assertThat(args[0]).isEqualTo("SET");
    assertThat((String) args[1]).contains("\"username\":\"bob\"");
    assertThat(args[2]).isEqualTo("DEL");
    assertThat(args[3]).isEqualTo("1");
}
```

```java
@Test
void loadsLuaScriptFromClasspathResource() throws Exception {
    when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("APPLIED");

    strategy.apply(sampleTransaction());

    ArgumentCaptor<RedisScript> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
    verify(stringRedisTemplate).execute(scriptCaptor.capture(), anyList(), any(Object[].class));
    assertThat(scriptCaptor.getValue().getScriptAsString())
            .isEqualTo(new String(
                    new ClassPathResource("lua/apply-transaction.lua").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
}
```

- [ ] **Step 2: Run the `simple` strategy tests and verify they fail**

Run: `mvn -Dtest=SimpleRedisApplyStrategyTest test`
Expected: FAIL because the existing Redis logic still lives inside the facade service and there is no dedicated `SimpleRedisApplyStrategy`.

- [ ] **Step 3: Move the current Redis script contract into the new strategy**

```java
@Service
@RequiredArgsConstructor
public class SimpleRedisApplyStrategy implements RedisApplyStrategy {

    private static final DefaultRedisScript<String> APPLY_TRANSACTION_SCRIPT = createApplyTransactionScript();

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    private final MiniCdcProperties properties;

    @Override
    public RedisTransactionApplier.ApplyResult apply(CdcTransactionEvent transactionEvent) {
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        keys.add(properties.getRedis().getTransactionDonePrefix() + transactionEvent.transactionId());
        for (CdcTransactionRow event : transactionEvent.events()) {
            if (event.primaryKey() == null || event.primaryKey().isEmpty()) {
                throw new IllegalStateException("CDC transaction row must contain primaryKey.");
            }
            keys.add(properties.getRedis().getKeyPrefix() + buildPrimaryKeySuffix(event.primaryKey()));
            if ("DELETE".equals(event.eventType())) {
                values.add("DEL");
            } else if ("INSERT".equals(event.eventType()) || "UPDATE".equals(event.eventType())) {
                if (event.after() == null) {
                    throw new IllegalStateException("CDC transaction row must contain after for INSERT/UPDATE.");
                }
                values.add("SET");
                values.add(toJson(event.after()));
            } else {
                throw new IllegalStateException("Unexpected CDC transaction eventType: " + event.eventType());
            }
        }
        values.add("1");
        String result = stringRedisTemplate.execute(APPLY_TRANSACTION_SCRIPT, keys, values.toArray());
        return RedisTransactionApplier.ApplyResult.from(result);
    }
}
```

- [ ] **Step 4: Re-run the `simple` strategy and facade tests and verify they pass**

Run: `mvn -Dtest=SimpleRedisApplyStrategyTest,RedisTransactionApplierTest test`
Expected: PASS

### Task 4: Build Metadata Payloads And Key Layout For `meta` Mode

**Files:**
- Create: `src/main/java/com/yunye/mncdc/model/RedisRowVersion.java`
- Create: `src/main/java/com/yunye/mncdc/model/RedisRowMetadata.java`
- Create: `src/main/java/com/yunye/mncdc/service/MetaRedisApplyStrategy.java`
- Create: `src/main/resources/lua/apply-transaction-meta.lua`
- Create: `src/test/java/com/yunye/mncdc/service/MetaRedisApplyStrategyTest.java`

- [ ] **Step 1: Write the failing `meta` strategy unit tests for key layout, metadata JSON, and duplicate handling**

```java
@Test
void buildsBusinessAndMetadataKeysForInsertRows() {
    when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("APPLIED");

    RedisTransactionApplier.ApplyResult result = strategy.apply(insertTransaction(
            "mini-user-sync:mysql-bin.000010:88:125",
            "mysql-bin.000010",
            125L,
            0,
            Map.of("id", 1L, "username", "alice")
    ));

    assertThat(result).isEqualTo(RedisTransactionApplier.ApplyResult.APPLIED);

    ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());

    assertThat(keysCaptor.getValue()).containsExactly(
            "mini-cdc:txn:done:mini-user-sync:mysql-bin.000010:88:125",
            "user:1",
            "mini-cdc:row:meta:user:1"
    );
    assertThat(argsCaptor.getValue()[0]).isEqualTo("INSERT");
    assertThat((String) argsCaptor.getValue()[1]).contains("\"username\":\"alice\"");
    assertThat((String) argsCaptor.getValue()[2]).contains("\"deleted\":false");
    assertThat((String) argsCaptor.getValue()[2]).contains("\"binlogFilename\":\"mysql-bin.000010\"");
    assertThat((String) argsCaptor.getValue()[2]).contains("\"nextPosition\":125");
    assertThat((String) argsCaptor.getValue()[2]).contains("\"eventIndex\":0");
    assertThat((String) argsCaptor.getValue()[2]).contains("\"transactionId\":\"mini-user-sync:mysql-bin.000010:88:125\"");
}

@Test
void buildsDeleteTombstoneMetadata() {
    when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("APPLIED");

    strategy.apply(deleteTransaction(
            "mini-user-sync:mysql-bin.000011:89:130",
            "mysql-bin.000011",
            130L,
            1,
            Map.of("id", 1L, "username", "alice")
    ));

    ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
    assertThat(argsCaptor.getValue()[0]).isEqualTo("DELETE");
    assertThat(argsCaptor.getValue()[1]).isEqualTo("");
    assertThat((String) argsCaptor.getValue()[2]).contains("\"deleted\":true");
    assertThat((String) argsCaptor.getValue()[2]).contains("\"eventIndex\":1");
}

@Test
void returnsDuplicateWhenLuaReportsDoneKeyHit() {
    when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("DUPLICATE");

    assertThat(strategy.apply(insertTransaction(
            "mini-user-sync:mysql-bin.000012:90:140",
            "mysql-bin.000012",
            140L,
            0,
            Map.of("id", 1L, "username", "alice")
    ))).isEqualTo(RedisTransactionApplier.ApplyResult.DUPLICATE);
}
```

- [ ] **Step 2: Run the `meta` strategy unit tests and verify they fail**

Run: `mvn -Dtest=MetaRedisApplyStrategyTest test`
Expected: FAIL because there is no metadata payload model, no metadata-aware strategy, and no metadata Lua script.

- [ ] **Step 3: Add the metadata model and `meta` strategy argument contract**

```java
public record RedisRowVersion(
        String binlogFilename,
        long nextPosition,
        int eventIndex,
        String transactionId
) {
}
```

```java
public record RedisRowMetadata(
        boolean deleted,
        RedisRowVersion version
) {
}
```

```java
@Service
@RequiredArgsConstructor
public class MetaRedisApplyStrategy implements RedisApplyStrategy {

    private static final DefaultRedisScript<String> APPLY_TRANSACTION_SCRIPT = createApplyTransactionScript();

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    private final MiniCdcProperties properties;

    @Override
    public RedisTransactionApplier.ApplyResult apply(CdcTransactionEvent transactionEvent) {
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        keys.add(properties.getRedis().getTransactionDonePrefix() + transactionEvent.transactionId());
        for (CdcTransactionRow row : transactionEvent.events()) {
            if (row.primaryKey() == null || row.primaryKey().isEmpty()) {
                throw new IllegalStateException("CDC transaction row must contain primaryKey.");
            }
            if (!"DELETE".equals(row.eventType()) && row.after() == null) {
                throw new IllegalStateException("CDC transaction row must contain after for INSERT/UPDATE.");
            }

            String primaryKeySuffix = buildPrimaryKeySuffix(row.primaryKey());
            keys.add(properties.getRedis().getKeyPrefix() + primaryKeySuffix);
            keys.add(properties.getRedis().getRowMetaPrefix() + transactionEvent.table() + ":" + primaryKeySuffix);

            RedisRowMetadata metadata = new RedisRowMetadata(
                    "DELETE".equals(row.eventType()),
                    new RedisRowVersion(
                            transactionEvent.binlogFilename(),
                            transactionEvent.nextPosition(),
                            row.eventIndex(),
                            transactionEvent.transactionId()
                    )
            );

            values.add(row.eventType());
            values.add("DELETE".equals(row.eventType()) ? "" : toJson(row.after()));
            values.add(toJson(metadata));
        }
        values.add("1");
        String result = stringRedisTemplate.execute(APPLY_TRANSACTION_SCRIPT, keys, values.toArray());
        return RedisTransactionApplier.ApplyResult.from(result);
    }
}
```

```lua
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 'DUPLICATE'
end

local argIndex = 1
for keyIndex = 2, #KEYS, 2 do
  local eventType = ARGV[argIndex]
  local payload = ARGV[argIndex + 1]
  local metadataJson = ARGV[argIndex + 2]

  if eventType ~= 'INSERT' and eventType ~= 'UPDATE' and eventType ~= 'DELETE' then
    return redis.error_reply('Unexpected CDC transaction eventType: ' .. tostring(eventType))
  end

  redis.call('SET', KEYS[keyIndex + 1], metadataJson)
  argIndex = argIndex + 3
end

redis.call('SET', KEYS[1], ARGV[#ARGV])
return 'APPLIED'
```

- [ ] **Step 4: Re-run the `meta` strategy unit tests and verify they pass before behavior checks**

Run: `mvn -Dtest=MetaRedisApplyStrategyTest test`
Expected: PASS

### Task 5: Verify Tombstones And Replay Protection Against A Real Redis Server

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/lua/apply-transaction-meta.lua`
- Modify: `src/main/java/com/yunye/mncdc/service/MetaRedisApplyStrategy.java`
- Create: `src/test/java/com/yunye/mncdc/service/MetaRedisApplyStrategyIntegrationTest.java`

- [ ] **Step 1: Add the failing Redis integration tests for stale replay rejection and `eventIndex` ordering**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
```

```java
@Testcontainers
class MetaRedisApplyStrategyIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    private StringRedisTemplate stringRedisTemplate;

    private MetaRedisApplyStrategy strategy;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        stringRedisTemplate = new StringRedisTemplate(connectionFactory);

        MiniCdcProperties properties = new MiniCdcProperties();
        properties.getRedis().setKeyPrefix("user:");
        properties.getRedis().setTransactionDonePrefix("mini-cdc:txn:done:");
        properties.getRedis().setRowMetaPrefix("mini-cdc:row:meta:");

        strategy = new MetaRedisApplyStrategy(stringRedisTemplate, new ObjectMapper(), properties);
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void createsMetadataOnFirstInsert() {
        strategy.apply(insertTransaction("txn-new", "mysql-bin.000010", 125L, 0, Map.of("id", 1L, "username", "alice")));

        assertThat(stringRedisTemplate.opsForValue().get("user:1"))
                .contains("\"id\":1")
                .contains("\"username\":\"alice\"");
        assertThat(stringRedisTemplate.opsForValue().get("mini-cdc:row:meta:user:1"))
                .contains("\"deleted\":false")
                .contains("\"eventIndex\":0");
    }

    @Test
    void createsTombstoneOnDeleteEvenWhenBusinessKeyIsMissing() {
        strategy.apply(deleteTransaction("txn-delete", "mysql-bin.000010", 126L, 0, Map.of("id", 1L, "username", "alice")));

        assertThat(stringRedisTemplate.hasKey("user:1")).isFalse();
        assertThat(stringRedisTemplate.opsForValue().get("mini-cdc:row:meta:user:1"))
                .contains("\"deleted\":true")
                .contains("\"nextPosition\":126");
    }

    @Test
    void skipsStaleInsertReplayAfterNewerDelete() {
        strategy.apply(deleteTransaction("txn-delete", "mysql-bin.000010", 126L, 0, Map.of("id", 1L, "username", "alice")));
        strategy.apply(insertTransaction("txn-stale", "mysql-bin.000010", 125L, 0, Map.of("id", 1L, "username", "alice")));

        assertThat(stringRedisTemplate.hasKey("user:1")).isFalse();
        assertThat(stringRedisTemplate.opsForValue().get("mini-cdc:row:meta:user:1"))
                .contains("\"deleted\":true")
                .contains("\"nextPosition\":126");
    }

    @Test
    void skipsStaleDeleteReplayAfterNewerUpdate() {
        strategy.apply(insertTransaction("txn-insert", "mysql-bin.000010", 125L, 0, Map.of("id", 1L, "username", "alice")));
        strategy.apply(updateTransaction("txn-update", "mysql-bin.000010", 126L, 0, Map.of("id", 1L, "username", "alice"), Map.of("id", 1L, "username", "alicia")));
        strategy.apply(deleteTransaction("txn-stale-delete", "mysql-bin.000010", 124L, 0, Map.of("id", 1L, "username", "alice")));

        assertThat(stringRedisTemplate.opsForValue().get("user:1"))
                .contains("\"id\":1")
                .contains("\"username\":\"alicia\"");
        assertThat(stringRedisTemplate.opsForValue().get("mini-cdc:row:meta:user:1"))
                .contains("\"deleted\":false")
                .contains("\"nextPosition\":126");
    }

    @Test
    void usesEventIndexWhenSameTransactionTouchesTheSameKeyTwice() {
        strategy.apply(new CdcTransactionEvent(
                "txn-repeat",
                "mini-user-sync",
                "mini",
                "user",
                "mysql-bin.000010",
                88L,
                125L,
                1L,
                List.of(
                        new CdcTransactionRow(0, "INSERT", Map.of("id", 1L), null, Map.of("id", 1L, "username", "alice")),
                        new CdcTransactionRow(1, "UPDATE", Map.of("id", 1L), Map.of("id", 1L, "username", "alice"), Map.of("id", 1L, "username", "alicia"))
                )
        ));

        assertThat(stringRedisTemplate.opsForValue().get("user:1"))
                .contains("\"id\":1")
                .contains("\"username\":\"alicia\"");
        assertThat(stringRedisTemplate.opsForValue().get("mini-cdc:row:meta:user:1"))
                .contains("\"eventIndex\":1");
    }

    @Test
    void returnsDuplicateWhenDoneKeyAlreadyExists() {
        CdcTransactionEvent transaction = insertTransaction("txn-dup", "mysql-bin.000010", 125L, 0, Map.of("id", 1L, "username", "alice"));

        assertThat(strategy.apply(transaction)).isEqualTo(RedisTransactionApplier.ApplyResult.APPLIED);
        assertThat(strategy.apply(transaction)).isEqualTo(RedisTransactionApplier.ApplyResult.DUPLICATE);
    }
}
```

- [ ] **Step 2: Run the integration test class and verify it fails**

Run: `mvn -Dtest=MetaRedisApplyStrategyIntegrationTest test`
Expected: FAIL because the metadata Lua script does not yet compare versions, preserve tombstones, or skip stale rows.

- [ ] **Step 3: Implement the metadata-aware Lua version comparison**

```lua
local function binlog_sequence(filename)
  local digits = string.match(filename or '', '(%d+)$')
  if digits == nil then
    return -1
  end
  return tonumber(digits)
end

local function is_newer(current_version, incoming_version)
  local current_file = binlog_sequence(current_version.binlogFilename)
  local incoming_file = binlog_sequence(incoming_version.binlogFilename)
  if incoming_file ~= current_file then
    return incoming_file > current_file
  end
  if incoming_version.nextPosition ~= current_version.nextPosition then
    return incoming_version.nextPosition > current_version.nextPosition
  end
  return incoming_version.eventIndex > current_version.eventIndex
end

if redis.call('EXISTS', KEYS[1]) == 1 then
  return 'DUPLICATE'
end

local argIndex = 1
for keyIndex = 2, #KEYS, 2 do
  local businessKey = KEYS[keyIndex]
  local metadataKey = KEYS[keyIndex + 1]
  local eventType = ARGV[argIndex]
  local payload = ARGV[argIndex + 1]
  local incomingMetadataJson = ARGV[argIndex + 2]
  local incomingMetadata = cjson.decode(incomingMetadataJson)
  local storedMetadataJson = redis.call('GET', metadataKey)

  local shouldApply = true
  if storedMetadataJson then
    local storedMetadata = cjson.decode(storedMetadataJson)
    shouldApply = is_newer(storedMetadata.version, incomingMetadata.version)
  end

  if shouldApply then
    if eventType == 'DELETE' then
      redis.call('DEL', businessKey)
    elseif eventType == 'INSERT' or eventType == 'UPDATE' then
      redis.call('SET', businessKey, payload)
    else
      return redis.error_reply('Unexpected CDC transaction eventType: ' .. tostring(eventType))
    end
    redis.call('SET', metadataKey, incomingMetadataJson)
  end

  argIndex = argIndex + 3
end

redis.call('SET', KEYS[1], ARGV[#ARGV])
return 'APPLIED'
```

- [ ] **Step 4: Re-run the integration tests and verify they pass**

Run: `mvn -Dtest=MetaRedisApplyStrategyIntegrationTest test`
Expected: PASS

### Task 6: Align Consumer Fixtures And Run Full Regression

**Files:**
- Modify: `src/test/java/com/yunye/mncdc/service/RedisSyncConsumerTest.java`
- Modify: `src/test/java/com/yunye/mncdc/service/RedisTransactionApplierTest.java`
- Modify: `src/test/java/com/yunye/mncdc/service/SimpleRedisApplyStrategyTest.java`
- Modify: `src/test/java/com/yunye/mncdc/service/MetaRedisApplyStrategyTest.java`
- Modify: `src/test/java/com/yunye/mncdc/service/MetaRedisApplyStrategyIntegrationTest.java`

- [ ] **Step 1: Update the remaining fixtures to the final `CdcTransactionRow(eventIndex, eventType, primaryKey, before, after)` shape**

```java
private CdcTransactionEvent sampleTransaction() {
    return new CdcTransactionEvent(
            "mini-user-sync:mysql-bin.000001:345:240",
            "mini-user-sync",
            "mini",
            "user",
            "mysql-bin.000001",
            345L,
            240L,
            1L,
            List.of(new CdcTransactionRow(
                    0,
                    "INSERT",
                    Map.of("id", 1L),
                    null,
                    Map.of("id", 1L, "username", "alice")
            ))
    );
}
```

- [ ] **Step 2: Run the consumer-facing tests and verify they pass**

Run: `mvn -Dtest=RedisSyncConsumerTest,RedisTransactionApplierTest test`
Expected: PASS

- [ ] **Step 3: Run the full targeted regression suite**

Run: `mvn -Dtest=BinlogCdcLifecycleTransactionTest,CdcEventPublisherTest,RedisTransactionApplierTest,SimpleRedisApplyStrategyTest,MetaRedisApplyStrategyTest,MetaRedisApplyStrategyIntegrationTest,RedisSyncConsumerTest test`
Expected: PASS

- [ ] **Step 4: Run the full Maven test suite**

Run: `mvn test`
Expected: PASS

- [ ] **Step 5: Commit the replay-protection implementation**

```bash
git add pom.xml \
        src/main/java/com/yunye/mncdc/config/MiniCdcProperties.java \
        src/main/java/com/yunye/mncdc/model/CdcTransactionRow.java \
        src/main/java/com/yunye/mncdc/model/RedisRowVersion.java \
        src/main/java/com/yunye/mncdc/model/RedisRowMetadata.java \
        src/main/java/com/yunye/mncdc/service/BinlogCdcLifecycle.java \
        src/main/java/com/yunye/mncdc/service/RedisApplyStrategy.java \
        src/main/java/com/yunye/mncdc/service/RedisTransactionApplier.java \
        src/main/java/com/yunye/mncdc/service/SimpleRedisApplyStrategy.java \
        src/main/java/com/yunye/mncdc/service/MetaRedisApplyStrategy.java \
        src/main/resources/application.yml \
        src/main/resources/lua/apply-transaction-meta.lua \
        src/test/java/com/yunye/mncdc/service/BinlogCdcLifecycleTransactionTest.java \
        src/test/java/com/yunye/mncdc/service/CdcEventPublisherTest.java \
        src/test/java/com/yunye/mncdc/service/RedisTransactionApplierTest.java \
        src/test/java/com/yunye/mncdc/service/SimpleRedisApplyStrategyTest.java \
        src/test/java/com/yunye/mncdc/service/MetaRedisApplyStrategyTest.java \
        src/test/java/com/yunye/mncdc/service/MetaRedisApplyStrategyIntegrationTest.java \
        src/test/java/com/yunye/mncdc/service/RedisSyncConsumerTest.java
git commit -m "Add Redis tombstone replay protection"
```

## Self-Review

- Spec coverage:
  - `eventIndex` message model and serialization are covered in Task 1.
  - `simple` default, `meta` opt-in, metadata prefix, and strategy seam are covered in Task 2.
  - Existing simple-mode Lua semantics are preserved in Task 3.
  - Metadata key/value shape, tombstone writes, and duplicate handling are covered in Task 4.
  - Stale insert/delete replay rejection, tombstone preservation, version ordering by binlog suffix plus position plus event index, and duplicate done-key behavior are covered in Task 5.
  - Consumer regression and full-suite validation are covered in Task 6.
- Placeholder scan:
  - No `TODO`, `TBD`, or “handle appropriately” placeholders remain.
  - Every test command, expected outcome, and code-touching step is explicit.
- Type consistency:
  - All tasks use the same final row model: `CdcTransactionRow(eventIndex, eventType, primaryKey, before, after)`.
  - Both strategy implementations return `RedisTransactionApplier.ApplyResult`.
  - Metadata JSON consistently uses `deleted` plus `version.binlogFilename`, `version.nextPosition`, `version.eventIndex`, and `version.transactionId`.
