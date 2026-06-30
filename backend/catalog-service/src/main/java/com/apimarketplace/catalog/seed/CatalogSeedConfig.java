package com.apimarketplace.catalog.seed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "catalog.seed.enabled", havingValue = "true")
@ConfigurationProperties(prefix = "catalog.seed")
public class CatalogSeedConfig {

    private String path = "./catalog-seeds";
    private String ownerId = "SYSTEM";

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }
}
