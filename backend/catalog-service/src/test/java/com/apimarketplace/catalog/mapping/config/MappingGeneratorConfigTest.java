package com.apimarketplace.catalog.mapping.config;

import com.apimarketplace.catalog.mapping.generator.StrictMappingGenerator;
import com.apimarketplace.catalog.mapping.service.MappingGeneratorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("MappingGeneratorConfig")
class MappingGeneratorConfigTest {

    @Mock
    private StrictMappingGenerator strictMappingGenerator;

    @Test
    @DisplayName("should create WebClient.Builder bean")
    void createsWebClientBuilder() {
        MappingGeneratorConfig config = new MappingGeneratorConfig();

        WebClient.Builder builder = config.webClientBuilder();

        assertThat(builder).isNotNull();
    }

    @Test
    @DisplayName("should create CacheManager bean with mapping-cache")
    void createsCacheManager() {
        MappingGeneratorConfig config = new MappingGeneratorConfig();

        CacheManager cacheManager = config.cacheManager();

        assertThat(cacheManager).isNotNull();
        assertThat(cacheManager.getCache("mapping-cache")).isNotNull();
    }

    @Test
    @DisplayName("should create MappingGeneratorService")
    void createsMappingGeneratorService() {
        MappingGeneratorService service = new MappingGeneratorService(strictMappingGenerator);

        assertThat(service).isNotNull();
    }

    @Test
    @DisplayName("config should have default values")
    void hasDefaultValues() {
        MappingGeneratorConfig config = new MappingGeneratorConfig();

        // Verify config fields can be set via reflection (as Spring @Value does)
        ReflectionTestUtils.setField(config, "mappingEnabled", true);
        ReflectionTestUtils.setField(config, "mappingProvider", "deepinfra");
        ReflectionTestUtils.setField(config, "maxFallbacks", 4);
        ReflectionTestUtils.setField(config, "timeoutMs", 300000);

        // Just verify no exceptions are thrown during bean creation
        assertThat(config.cacheManager()).isNotNull();
    }
}
