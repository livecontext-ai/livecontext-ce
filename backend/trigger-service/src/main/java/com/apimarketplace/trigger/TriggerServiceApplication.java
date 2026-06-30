package com.apimarketplace.trigger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
    "com.apimarketplace.trigger",
    "com.apimarketplace.common.security",
    "com.apimarketplace.auth.client"
})
@EnableScheduling
public class TriggerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TriggerServiceApplication.class, args);
    }
}
