package com.apimarketplace.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {
    "com.apimarketplace.auth",
    "com.apimarketplace.common.mapping",
    "com.apimarketplace.common.security",
    "com.apimarketplace.common.storage"
})
@EnableJpaRepositories(basePackages = {
    "com.apimarketplace.auth.repository",
    "com.apimarketplace.auth.credential.repository",
    "com.apimarketplace.auth.ce",
    "com.apimarketplace.common.storage.repository"
})
@EntityScan(basePackages = {
    "com.apimarketplace.auth.domain",
    "com.apimarketplace.auth.credential.domain",
    "com.apimarketplace.auth.ce",
    "com.apimarketplace.common.storage.domain"
})
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }

}
