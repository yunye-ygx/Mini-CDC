package com.yunye.mncdc.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {
    private static final long SIX_HOURS_MS = 6L * 60L * 60L * 1000L;

    @Bean
    public NewTopic userChangeTopic(MiniCdcProperties properties) {
        return TopicBuilder.name(properties.getKafka().getTopic())
                .partitions(1)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(SIX_HOURS_MS))
                .build();
    }
}
