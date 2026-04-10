package com.yunye.mncdc;

import com.yunye.mncdc.config.MiniCdcProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@MapperScan("com.yunye.mncdc.mapper")
@EnableKafka
@EnableConfigurationProperties(MiniCdcProperties.class)
public class MnCdcApplication {

    public static void main(String[] args) {
        SpringApplication.run(MnCdcApplication.class, args);
    }

}
