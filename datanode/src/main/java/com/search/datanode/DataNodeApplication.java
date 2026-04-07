package com.search.datanode;

import com.search.shared.dto.NodeRegistrationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class DataNodeApplication {
    private static final Logger log = LoggerFactory.getLogger(DataNodeApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(DataNodeApplication.class, args);
    }

    /**
     * Self-registers with the coordinator at startup.
     * If the coordinator is unreachable, log a warning — operator can register
     * manually via POST /nodes on the coordinator.
     */
    @Bean
    CommandLineRunner registerWithCoordinator(
            @Value("${node.id}")         String nodeId,
            @Value("${node.url}")        String nodeUrl,
            @Value("${coordinator.url}") String coordinatorUrl,
            RestTemplate restTemplate) {
        return args -> {
            try {
                restTemplate.postForEntity(
                    coordinatorUrl + "/nodes",
                    new NodeRegistrationRequest(nodeId, nodeUrl),
                    Void.class);
                log.info("Registered with coordinator at {} as node '{}'", coordinatorUrl, nodeId);
            } catch (Exception e) {
                log.warn("Could not reach coordinator at {} ({}). Register manually if needed.",
                    coordinatorUrl, e.getMessage());
            }
        };
    }
}
