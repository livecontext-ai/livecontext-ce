package com.apimarketplace.orchestrator.services.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The orchestrator used to re-log the catalogue's refusals at ERROR - including the one it had
 * just identified two lines above as a plan refusal, and including a 402 for lack of credits,
 * which reached the generic handler and got a full stack trace. catalog-service logs the same
 * refusals at INFO; this side promoted them.
 */
@DisplayName("CatalogToolsGateway - a refused tool is not an error")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogToolsGatewayRefusalLogLevelTest {

    @Mock private RestTemplate restTemplate;
    @Mock private TypeCastingService typeCastingService;
    @Mock private CrudToolExecutor crudToolExecutor;

    private CatalogToolsGateway gateway;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger gatewayLogger;

    @BeforeEach
    void setUp() {
        gateway = new CatalogToolsGateway(
                restTemplate, "http://localhost:8081", typeCastingService, crudToolExecutor);

        gatewayLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CatalogToolsGateway.class);
        appender = new ListAppender<>();
        appender.start();
        gatewayLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        gatewayLogger.detachAppender(appender);
    }

    private ExecutionResult callWith(HttpStatus status, String body) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenThrow(HttpClientErrorException.create(
                        status, status.getReasonPhrase(), new HttpHeaders(),
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        return gateway.executeTool(new ToolRef("instagram/publish", 1), Map.of(), "tenant-1", Map.of());
    }

    @Test
    @DisplayName("a plan refusal is a WARN: the code says so two lines earlier")
    void planRefusalIsWarn() {
        ExecutionResult result = callWith(HttpStatus.FORBIDDEN,
                "{\"error\":\"PLAN_UPGRADE_REQUIRED\",\"message\":\"Publishing needs the STARTER plan\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("plan");
        });
    }

    @Test
    @DisplayName("a 403 that is NOT a plan refusal stays an ERROR - a provider's own auth failure")
    void otherForbiddenStaysError() {
        // The mirror: a provider rejecting our token must not read as a billing prompt, and must
        // not go quiet either.
        callWith(HttpStatus.FORBIDDEN, "{\"error\":\"invalid_token\"}");

        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    @DisplayName("a 402 for lack of credits is a WARN, with no stack trace")
    void insufficientCreditsIsWarn() {
        ExecutionResult result = callWith(HttpStatus.PAYMENT_REQUIRED,
                "{\"error\":\"INSUFFICIENT_CREDITS\",\"message\":\"Out of credits\"}");

        // It had no catch of its own, so it landed in the generic handler: ERROR *plus* a stack.
        // Only the LEVEL moved: the step must read exactly what it read before.
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0))
            .as("the generic handler's shape is unchanged - this is a log-level fix, not a contract change")
            .containsEntry("type", "execution_error");
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getThrowableProxy())
                .as("a refusal does not need a stack trace")
                .isNull();
        });
    }

    @Test
    @DisplayName("a credential-choice refusal (422) is a WARN")
    void credentialSelectionIsWarn() {
        callWith(HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"error\":\"CREDENTIAL_SELECTION_UNRESOLVED\",\"message\":\"no active credential named 'Client Z'\"}");

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    @DisplayName("another 4xx reaching the generic handler keeps ERROR - the WARN is bound to 402")
    void otherClientErrorStaysError() {
        // Without the status check, ANY HttpClientErrorException landing in the generic handler
        // would go quiet: a catalog 400, 404, 409 or 429 would stop being reported.
        callWith(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":\"rate_limited\"}");

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    @DisplayName("a 404 TOOL_NOT_FOUND is a WARN and tells the step the tool no longer exists")
    void toolNotFoundIsWarnWithReadableMessage() {
        // The catalogue used to answer a stale tool id with a 500; now it answers 404 with the
        // TOOL_NOT_FOUND code, which is a stale reference in the workflow, not an incident.
        ExecutionResult result = callWith(HttpStatus.NOT_FOUND,
                "{\"success\":false,\"error\":\"TOOL_NOT_FOUND\",\"message\":\"Tool not found: instagram/publish\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(err -> {
            assertThat(err).containsEntry("type", "tool_not_found");
            assertThat(err.get("message")).contains("instagram/publish").contains("no longer exists")
                    .contains("search the catalog").contains("update the step's tool id");
        });
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    @DisplayName("a 404 WITHOUT the TOOL_NOT_FOUND code keeps ERROR and the generic reading")
    void other404StaysError() {
        ExecutionResult result = callWith(HttpStatus.NOT_FOUND, "{\"error\":\"NOT_FOUND\"}");

        assertThat(result.errors()).singleElement()
                .satisfies(err -> assertThat(err).containsEntry("type", "execution_error"));
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    @DisplayName("a 500 from the catalogue keeps ERROR and its stack - that one is ours")
    void serverErrorStaysErrorWithStack() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));

        gateway.executeTool(new ToolRef("instagram/publish", 1), Map.of(), "tenant-1", Map.of());

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getThrowableProxy()).isNotNull();
        });
    }
}
