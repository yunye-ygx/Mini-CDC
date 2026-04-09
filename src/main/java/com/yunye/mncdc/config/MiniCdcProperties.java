package com.yunye.mncdc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mini-cdc")
public class MiniCdcProperties {

    private boolean enabled = false;

    private boolean logEventJson = true;

    private Mysql mysql = new Mysql();

    private Kafka kafka = new Kafka();

    private Redis redis = new Redis();

    private Checkpoint checkpoint = new Checkpoint();

    @Data
    public static class Mysql {

        private String host;

        private int port = 3306;

        private String username;

        private String password;

        private String database;

        private String table;

        private long serverId = 5401L;

        private String jdbcUrl;
    }

    @Data
    public static class Kafka {

        private String topic = "user-change-topic";
    }

    @Data
    public static class Redis {

        private String keyPrefix = "user:";
    }

    @Data
    public static class Checkpoint {

        private boolean enabled = true;

        private String connectorName = "mini-cdc-default";

        private StartupStrategy startupStrategy = StartupStrategy.LATEST;
    }

    public enum StartupStrategy {
        LATEST
    }
}
