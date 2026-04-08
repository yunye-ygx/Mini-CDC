package com.yunye.mncdc;

import com.yunye.mncdc.config.MiniCdcProperties;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
@EnableKafka
@EnableConfigurationProperties(MiniCdcProperties.class)
public class MnCdcApplication {

    public static void main(String[] args) {
        SpringApplication.run(MnCdcApplication.class, args);
    }

}
