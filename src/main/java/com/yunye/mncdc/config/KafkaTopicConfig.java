package com.yunye.mncdc.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic userChangeTopic(MiniCdcProperties properties) {
        return TopicBuilder.name(properties.getKafka().getTopic())
                .partitions(1)
                .replicas(1)
                .build();
    }
}
