package com.apimarketplace.interfaces;

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
        "com.apimarketplace.interfaces",
        "com.apimarketplace.common.storage",
        "com.apimarketplace.auth.client"
    },
    // A @ComponentScan declared here replaces the one @SpringBootApplication carries, and with it
    // Boot's TypeExcludeFilter: a @SpringBootTest context would then scan the test classes too (and a
    // slice test such as @WebMvcTest would load every component). Same filter as
    // ConversationServiceApplication; inert at runtime.
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
    }
)
@EnableJpaRepositories(basePackages = {
    "com.apimarketplace.interfaces.repository",
    "com.apimarketplace.common.storage.repository"
})
@EntityScan(basePackages = {
    "com.apimarketplace.interfaces.domain"
},
    basePackageClasses = {
        com.apimarketplace.common.storage.domain.StorageEntity.class,
        com.apimarketplace.common.storage.domain.TenantStorageQuota.class
    })
@EnableAsync
public class InterfaceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InterfaceServiceApplication.class, args);
    }
}
