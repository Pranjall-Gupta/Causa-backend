package com.causa.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CausaBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(CausaBackendApplication.class, args);
    }
}
