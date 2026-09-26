package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.mapping.SimpleMappingEngine;
import com.apimarketplace.common.mapping.SimpleMappingService;
import com.apimarketplace.common.mapping.StrictMappingEngine;
import com.apimarketplace.common.storage.config.StorageMappingConfig;
import com.apimarketplace.common.storage.dto.MappingResolutionResult;
import com.apimarketplace.common.storage.dto.MappingSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.FieldSpec;
import com.apimarketplace.common.storage.dto.MappingSpec.SourceSpec;
import com.apimarketplace.common.storage.service.mapping.MappingSpecConverter;
import com.apimarketplace.common.storage.service.mapping.MappingSpecNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("StorageMappingResolverService Tests")
@ExtendWith(MockitoExtension.class)
class StorageMappingResolverServiceTest {

    @Mock
    private SimpleMappingService simpleMappingService;

    @Mock
    private MappingSpecNormalizer normalizer;

    @Mock
    private MappingSpecConverter converter;

    @Mock
    private StorageMappingConfig config;

    private StorageMappingResolverService service;

    @BeforeEach
    void setUp() {
        when(config.getCatalogBaseUrl()).thenReturn("http://localhost:8081");

        WebClient.Builder webClientBuilder = WebClient.builder();

        service = new StorageMappingResolverService(
            new ObjectMapper(),
            config,
            simpleMappingService,
            normalizer,
            converter,
            webClientBuilder
        );
    }

    @Nested
    @DisplayName("WebClient base URL wiring")
    class WebClientBaseUrlTests {

        // Guards the actual consumption of the resolved URL: a regression that drops
        // `.baseUrl(config.getCatalogBaseUrl())` (the k3s fix) would otherwise pass unnoticed.
        @Test
        @DisplayName("builds the WebClient with the catalog base URL resolved by the config")
        void usesResolvedCatalogBaseUrlForWebClient() {
            StorageMappingConfig cfg = mock(StorageMappingConfig.class);
            when(cfg.getCatalogBaseUrl()).thenReturn("http://livecontext-livecontext-catalog:8081");
            WebClient.Builder builder = mock(WebClient.Builder.class, RETURNS_SELF);
            when(builder.build()).thenReturn(mock(WebClient.class));

            new StorageMappingResolverService(
                new ObjectMapper(), cfg, simpleMappingService, normalizer, converter, builder);

            verify(builder).baseUrl("http://livecontext-livecontext-catalog:8081");
        }
    }

    @Nested
    @DisplayName("isEnabled")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when config is enabled")
        void shouldReturnTrueWhenEnabled() {
            when(config.isEnabled()).thenReturn(true);

            assertThat(service.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("should return false when config is disabled")
        void shouldReturnFalseWhenDisabled() {
            when(config.isEnabled()).thenReturn(false);

            assertThat(service.isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("getMappingSpec")
    class GetMappingSpecTests {

        @Test
        @DisplayName("should return null when disabled")
        void shouldReturnNullWhenDisabled() {
            when(config.isEnabled()).thenReturn(false);

            MappingSpec result = service.getMappingSpec(UUID.randomUUID());

            assertThat(result).isNull();
        }
    }

    /**
     * Regression (prod 2026-09-25): an unmapped tool is the normal case and must not be reported as
     * a failure, while any OTHER catalog refusal is an anomaly that must stay visible. The catalog's
     * exact "no mapping" answer is the only thing allowed to drop to DEBUG.
     */
    @Nested
    @DisplayName("parseMappingResponse - log level of a catalog refusal")
    class CatalogRefusalLogLevelTests {

        @Test
        @DisplayName("the catalog's 'no mapping' answer returns null and logs nothing at WARN or above")
        void noMappingAnswerIsNotAWarning() throws Exception {
            UUID toolId = UUID.randomUUID();
            List<ch.qos.logback.classic.spi.ILoggingEvent> events = captureLogs(() ->
                    assertThat(service.parseMappingResponse(
                            "{\"success\":false,\"error\":\"No mapping found for this tool\"}", toolId)).isNull());

            assertThat(events).noneMatch(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
        }

        @Test
        @DisplayName("any other catalog refusal returns null and stays at WARN with the catalog's reason")
        void otherRefusalStaysAWarning() throws Exception {
            UUID toolId = UUID.randomUUID();
            List<ch.qos.logback.classic.spi.ILoggingEvent> events = captureLogs(() ->
                    assertThat(service.parseMappingResponse(
                            "{\"success\":false,\"error\":\"No mapping definition found\"}", toolId)).isNull());

            assertThat(events).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                    && e.getFormattedMessage().contains(toolId.toString())
                    && e.getFormattedMessage().contains("No mapping definition found"));
        }

        private List<ch.qos.logback.classic.spi.ILoggingEvent> captureLogs(ThrowingRunnable action) throws Exception {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(StorageMappingResolverService.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                action.run();
            } finally {
                logger.detachAppender(appender);
            }
            return appender.list;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Nested
    @DisplayName("resolve")
    class ResolveTests {

        @Test
        @DisplayName("should return null when disabled")
        void shouldReturnNullWhenDisabled() {
            when(config.isEnabled()).thenReturn(false);

            MappingResolutionResult result = service.resolve(UUID.randomUUID(), "{\"data\":\"value\"}");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null when toolId is null")
        void shouldReturnNullWhenToolIdNull() {
            when(config.isEnabled()).thenReturn(true);

            MappingResolutionResult result = service.resolve(null, "{\"data\":\"value\"}");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null when jsonData is null")
        void shouldReturnNullWhenJsonDataNull() {
            when(config.isEnabled()).thenReturn(true);

            MappingResolutionResult result = service.resolve(UUID.randomUUID(), null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null when jsonData is blank")
        void shouldReturnNullWhenJsonDataBlank() {
            when(config.isEnabled()).thenReturn(true);

            MappingResolutionResult result = service.resolve(UUID.randomUUID(), "   ");

            assertThat(result).isNull();
        }
    }
}
