package com.yunye.mncdc.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringApplicationShutdownHandler implements ApplicationShutdownHandler {

    private final ApplicationContext applicationContext;

    @Override
    public void exit(int statusCode) {
        Thread shutdownThread = new Thread(() -> {
            try {
                SpringApplication.exit(applicationContext, () -> statusCode);
            } finally {
                System.exit(statusCode);
            }
        }, "application-shutdown");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
    }
}
