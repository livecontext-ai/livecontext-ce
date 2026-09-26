package com.apimarketplace.storage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;

// UserDetailsServiceAutoConfiguration excluded, as MonolithApplication does: SecurityConfig
// permits every request and uses no form or basic login, so the in-memory user it creates is
// never used, and its only effect was a generated password printed at INFO on every boot.
@SpringBootApplication(exclude = {
    org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class
})
@EnableDiscoveryClient
@org.springframework.scheduling.annotation.EnableScheduling
@ComponentScan(basePackages = {
    "com.apimarketplace.storage",
    "com.apimarketplace.auth.client",
    "com.apimarketplace.common.mapping",
    "com.apimarketplace.common.storage"  // StorageService + repos for Files-tab indexing
})
@org.springframework.boot.autoconfigure.domain.EntityScan(basePackages = {
    "com.apimarketplace.storage.domain",          // StoredFile (legacy user files)
    "com.apimarketplace.common.storage.domain"    // StorageEntity (Files tab + Storage Explorer)
})
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages = {
    "com.apimarketplace.storage.repository",
    "com.apimarketplace.common.storage.repository"
})
public class StorageApplication {

    public static void main(String[] args) {
        SpringApplication.run(StorageApplication.class, args);
    }
}
