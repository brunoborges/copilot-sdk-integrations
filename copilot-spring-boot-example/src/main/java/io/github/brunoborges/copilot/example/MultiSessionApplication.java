package io.github.brunoborges.copilot.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MultiSessionApplication {

    private static final Logger log = LoggerFactory.getLogger(MultiSessionApplication.class);

    public static void main(String[] args) {
        try (var context = SpringApplication.run(MultiSessionApplication.class, args)) {
            log.info("Example finished; closing the Spring-managed client.");
        }
    }
}
