package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What an agent is told when the tool_id it sends names no tool any more.
 *
 * <p>The catalog used to answer that call with a 500, which reached this module as a
 * server error and was answered with TOOL_CALL_FAILED and "send it once more", advice
 * that can never work: the same dead id fails identically every time. The catalog now
 * answers 404 with the TOOL_NOT_FOUND code, and the only useful remedy is to look the
 * tool up again.
 */
@DisplayName("CatalogExecuteModule - tool_id that names no tool")
class CatalogExecuteModuleToolNotFoundTest {

    private static final String TOOL_ID = "0d9c1f7e-5b1a-4a57-9e0c-3f4b2a1c9d88";

    private CatalogExecuteModule module;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() throws Exception {
        module = new CatalogExecuteModule(new ObjectMapper(), mock(CredentialClient.class));
        restTemplate = mock(RestTemplate.class);
        Field rtField = CatalogExecuteModule.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        rtField.set(module, restTemplate);
        Field portField = CatalogExecuteModule.class.getDeclaredField("serverPort");
        portField.setAccessible(true);
        portField.setInt(module, 8081);

        // The contract lookup for a missing tool fails too; the pre-flight treats that as
        // "nothing to gate on" and proceeds to the execute call, exactly as in production.
        when(restTemplate.exchange(contains("/api/catalog/tools/" + TOOL_ID + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                        HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
    }

    private void executeAnswers(RuntimeException e) {
        when(restTemplate.exchange(contains("/catalog/v1/tools/" + TOOL_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(e);
    }

    private static HttpClientErrorException notFound(String body) {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private ToolExecutionResult run() {
        return module.execute("execute",
                Map.of("tool_id", TOOL_ID, "params", Map.of("q", "x")),
                "user-1", ToolExecutionContext.of("user-1")).orElseThrow();
    }

    @Test
    @DisplayName("404 TOOL_NOT_FOUND -> RESOURCE_NOT_FOUND result telling the agent to search again, never to retry")
    void toolNotFoundTellsAgentToSearchAgain() {
        executeAnswers(notFound("""
                {"success":false,"error":"TOOL_NOT_FOUND",
                 "message":"Tool not found: %s","toolId":"%s"}
                """.formatted(TOOL_ID, TOOL_ID)));

        ToolExecutionResult r = run();

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        assertThat(r.error())
                .startsWith(CatalogExecuteModule.TOOL_NOT_FOUND_CODE + ":")
                .contains(TOOL_ID)
                .contains("nothing ran and nothing was charged")
                .contains("catalog(action='search'")
                .doesNotContain(CatalogExecuteModule.TOOL_CALL_FAILED_CODE)
                .doesNotContain("once more")
                .doesNotContainIgnoringCase("retry");
        assertThat(r.metadata()).containsEntry("toolId", TOOL_ID);
    }

    @Test
    @DisplayName("a 404 WITHOUT the TOOL_NOT_FOUND code keeps the generic upstream refusal (keyed on the code, not the status)")
    void other404KeepsGenericReading() {
        executeAnswers(notFound("{\"success\":false,\"error\":\"SOMETHING_ELSE\",\"message\":\"gone\"}"));

        ToolExecutionResult r = run();

        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.error()).startsWith(CatalogExecuteModule.UPSTREAM_REJECTED_CODE);
    }

    @Test
    @DisplayName("a real 5xx is unchanged: TOOL_CALL_FAILED with its one-retry advice")
    void serverErrorUnchanged() {
        executeAnswers(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom",
                HttpHeaders.EMPTY, "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        ToolExecutionResult r = run();

        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.error()).startsWith(CatalogExecuteModule.TOOL_CALL_FAILED_CODE);
    }
}
