package com.apimarketplace.publication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {
        "com.apimarketplace.publication",
        "com.apimarketplace.common.storage",
        "com.apimarketplace.auth.client"
})
@EnableJpaRepositories(basePackages = {
        "com.apimarketplace.publication.repository",
        "com.apimarketplace.publication.screening",
        "com.apimarketplace.common.storage.repository"
})
@EntityScan(
        basePackages = {
                "com.apimarketplace.publication.domain",
                "com.apimarketplace.publication.screening"
        },
        basePackageClasses = {
                com.apimarketplace.common.storage.domain.StorageEntity.class,
                com.apimarketplace.common.storage.domain.TenantStorageQuota.class
        }
)
public class PublicationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PublicationServiceApplication.class, args);
    }
}
