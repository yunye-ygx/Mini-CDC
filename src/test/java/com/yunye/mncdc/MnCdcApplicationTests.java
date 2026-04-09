package com.yunye.mncdc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "mini-cdc.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class MnCdcApplicationTests {

    @Autowired(required = false)
    private DataSource dataSource;

    @MockitoBean
    private KafkaAdmin kafkaAdmin;

    @Test
    void contextLoads() {
        assertThat(dataSource).isNotNull();
    }

}
