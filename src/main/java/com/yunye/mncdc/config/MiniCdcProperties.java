package com.yunye.mncdc.config;

import com.yunye.mncdc.model.QualifiedTable;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "mini-cdc")
public class MiniCdcProperties {

    private boolean enabled = false;

    private boolean logEventJson = true;

    private Mysql mysql = new Mysql();

    private Kafka kafka = new Kafka();

    private Redis redis = new Redis();

    private Checkpoint checkpoint = new Checkpoint();

    private Snapshot snapshot = new Snapshot();

    @Data
    public static class Mysql {

        private String host;

        private int port = 3306;

        private String username;

        private String password;

        private long serverId = 5401L;

        private String jdbcUrl;

        private List<QualifiedTable> tables = new ArrayList<>();

        public List<QualifiedTable> resolvedTables() {
            if (!tables.isEmpty()) {
                return List.copyOf(tables);
            }
            if (database != null && table != null) {
                return List.of(new QualifiedTable(database, table));
            }
            return List.of();
        }

        @Deprecated
        private String database;

        @Deprecated
        private String table;
    }

    @Data
    public static class Kafka {

        private String topic = "user-change-topic";
        private Consumer consumer = new Consumer();

        @Data
        public static class Consumer {

            private int maxAttempts = 4;

            private Duration retryBackoff = Duration.ofSeconds(1);
        }
    }

    @Data
    public static class Redis {

        private String keyPrefix = "cdc:";

        private String transactionDonePrefix = "mini-cdc:txn:done:";

        private String rowMetaPrefix = "mini-cdc:row:meta:";

        private BusinessKeyScope businessKeyScope = BusinessKeyScope.DATABASE_TABLE;

        private ApplyMode applyMode = ApplyMode.SIMPLE;

        public enum ApplyMode {
            SIMPLE,
            META
        }

        public enum BusinessKeyScope {
            TABLE,
            DATABASE_TABLE
        }
    }

    @Data
    public static class Checkpoint {

        private boolean enabled = true;

        private String connectorName = "mini-cdc-default";

        private StartupStrategy startupStrategy = StartupStrategy.LATEST;
    }

    @Data
    public static class Snapshot {

        private int pageSize = 500;
    }

    public enum StartupStrategy {
        LATEST,
        SNAPSHOT_THEN_INCREMENTAL
    }
}
