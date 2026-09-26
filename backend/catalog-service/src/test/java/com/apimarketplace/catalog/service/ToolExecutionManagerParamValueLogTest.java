package com.apimarketplace.catalog.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.service.relay.CeCatalogCloudRelay;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression, 2026-09-25: "Received parameters map" and "Executing tool ... JSON" printed every
 * parameter value at INFO, including a secret passed as a parameter (a {@code body_field}
 * integration's {@code {{credential.client_secret}}}, resolved upstream). The value must still
 * reach the API call unchanged; the log keeps only its shape.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ToolExecutionManager - parameter VALUES never reach the log")
class ToolExecutionManagerParamValueLogTest {

    private static final String SECRET = "sk_live_FAKE_param_secret_for_tem_555";

    @Mock private ToolContextService toolContextService;
    @Mock private ApiService apiService;
    @Mock private ResponseShaper responseShaper;
    @Mock private NextActionBuilder nextActionBuilder;
    @Mock private ResponseCache responseCache;
    @Mock private com.apimarketplace.catalog.repository.ToolNextHintRepository toolNextHintRepository;
    @Mock private ToolResponseService toolResponseService;
    @Mock private CredentialClient credentialClient;
    @Mock private CeCatalogCloudRelay ceCatalogCloudRelay;

    private ToolExecutionManager manager;
    private ListAppender<ILoggingEvent> logs;
    private Logger root;
    private Logger managerLogger;
    private ch.qos.logback.classic.Level previousLevel;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        var orchestrator = new com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator(
                new com.apimarketplace.catalog.service.execution.OutputProjector(objectMapper));
        var binary = new com.apimarketplace.catalog.service.execution.BinaryResponseHandler(objectMapper);
        manager = new ToolExecutionManager(toolContextService, apiService, objectMapper, responseShaper,
                nextActionBuilder, responseCache, toolNextHintRepository, toolResponseService, orchestrator, binary,
                null, credentialClient, null, ceCatalogCloudRelay);

        lenient().when(credentialClient.getCredentialStateVersion(anyString()))
                .thenReturn(CredentialClient.STATE_VERSION_UNAVAILABLE);
        lenient().when(ceCatalogCloudRelay.tryRelay(anyString(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(responseShaper.shape(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(inv.getArgument(0), java.util.List.of(),
                        ResponseShaper.Action.UNTOUCHED, 0, 0));
        lenient().when(nextActionBuilder.build(any(), any(), any())).thenReturn(Optional.empty());

        ToolContextService.ToolContext context = new ToolContextService.ToolContext();
        context.setToolId(UUID.randomUUID().toString());
        context.setApiId(UUID.randomUUID().toString());
        context.setToolName("create_transaction");
        context.setApiSlug("authorize-net");
        context.setToolSlug("authorize-net-create-transaction");
        context.setEndpoint("/xml/v1/request.api");
        context.setHttpMethod("POST");
        context.setAllowedParameterNames(Set.of("name", "transactionKey"));
        when(toolContextService.loadToolContext("authorize-net/create_transaction")).thenReturn(Optional.of(context));

        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        // DEBUG on purpose: the per-parameter DEBUG line printed values too.
        managerLogger = (Logger) LoggerFactory.getLogger(ToolExecutionManager.class);
        previousLevel = managerLogger.getLevel();
        managerLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        managerLogger.setLevel(previousLevel);
        root.detachAppender(logs);
    }

    @Test
    @DisplayName("Regression: a secret parameter reaches the API call but no log line")
    void secretParamPassedButNotLogged() {
        when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                .thenReturn(Map.of("success", true, "data", Map.of("ok", true), "status", 200));

        manager.executeTool("authorize-net/create_transaction",
                ToolExecutionRequest.builder().parameters(Map.of("name", "merchant-1", "transactionKey", SECRET)).build(),
                "42", "org-1", "req");

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(apiService).executeApiTool(anyString(), anyString(), sent.capture(), anySet(), anyString());
        assertThat(sent.getValue().toString()).contains(SECRET);

        StringBuilder all = new StringBuilder();
        logs.list.forEach(e -> all.append(e.getFormattedMessage()).append('\n'));
        assertThat(all.toString())
                .doesNotContain(SECRET)
                .contains("transactionKey=<string " + SECRET.length() + ">")
                .contains("Parameter: transactionKey = <string " + SECRET.length() + ">");
    }
}
