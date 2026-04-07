package com.search.coordinator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Virtual-thread executor for scatter-gather.
     * Each shard HTTP call blocks on I/O — virtual threads park cheaply
     * without consuming platform threads, giving free parallelism at scale.
     */
    @Bean("scatterGatherExecutor")
    public ExecutorService scatterGatherExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
