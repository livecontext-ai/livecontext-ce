package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.file.FileDownloader;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.utils.file.MimeTypeRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServiceRegistry builder and getters.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceRegistry")
class ServiceRegistryTest {

    @Mock private ToolsGateway toolsGateway;
    @Mock private V2TemplateAdapter templateAdapter;
    @Mock private FileStorageService fileStorageService;
    @Mock private FileDownloader fileDownloader;
    @Mock private MimeTypeRegistry mimeTypeRegistry;
    @Mock private WorkflowEventPublisher eventPublisher;
    @Mock private RestTemplate restTemplate;
    @Mock private SplitContextManager splitContextManager;
    @Mock private UnifiedSignalService signalService;
    @Mock private Clock clock;

    @Nested
    @DisplayName("Builder pattern")
    class BuilderPattern {

        @Test
        @DisplayName("should build registry with all services")
        void shouldBuildWithAllServices() {
            ServiceRegistry registry = ServiceRegistry.builder()
                .toolsGateway(toolsGateway)
                .templateAdapter(templateAdapter)
                .fileStorageService(fileStorageService)
                .fileDownloader(fileDownloader)
                .mimeTypeRegistry(mimeTypeRegistry)
                .eventPublisher(eventPublisher)
                .restTemplate(restTemplate)
                .splitContextManager(splitContextManager)
                .signalService(signalService)
                .clock(clock)
                .build();

            assertNotNull(registry);
            assertSame(toolsGateway, registry.getToolsGateway());
            assertSame(templateAdapter, registry.getTemplateAdapter());
            assertSame(fileStorageService, registry.getFileStorageService());
            assertSame(fileDownloader, registry.getFileDownloader());
            assertSame(mimeTypeRegistry, registry.getMimeTypeRegistry());
            assertSame(eventPublisher, registry.getEventPublisher());
            assertSame(restTemplate, registry.getRestTemplate());
            assertSame(splitContextManager, registry.getSplitContextManager());
            assertSame(signalService, registry.getSignalService());
            assertSame(clock, registry.getClock());
        }

        @Test
        @DisplayName("should build registry with null services")
        void shouldBuildWithNullServices() {
            ServiceRegistry registry = ServiceRegistry.builder().build();

            assertNotNull(registry);
            assertNull(registry.getToolsGateway());
            assertNull(registry.getTemplateAdapter());
            assertNull(registry.getClock());
        }

        @Test
        @DisplayName("builder returns chainable builder")
        void shouldReturnChainableBuilder() {
            ServiceRegistry.Builder builder = ServiceRegistry.builder();
            assertNotNull(builder);

            ServiceRegistry.Builder chained = builder.toolsGateway(toolsGateway);
            assertSame(builder, chained);
        }
    }
}
