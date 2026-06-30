package com.apimarketplace.datasource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ComponentScan(
    basePackages = {
        "com.apimarketplace.datasource",
        "com.apimarketplace.common.security",
        "com.apimarketplace.common.storage",
        "com.apimarketplace.auth.client"
    },
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
    }
)
@EnableJpaRepositories(basePackages = {
    "com.apimarketplace.datasource.persistence",
    "com.apimarketplace.datasource.crud.repository",
    "com.apimarketplace.datasource.repository",
    "com.apimarketplace.common.storage.repository"
})
@EntityScan(basePackages = {
    "com.apimarketplace.datasource.domain"
},
    basePackageClasses = {
        com.apimarketplace.common.storage.domain.StorageEntity.class,
        com.apimarketplace.common.storage.domain.TenantStorageQuota.class
    })
@EnableAsync
public class DatasourceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatasourceServiceApplication.class, args);
    }
}
