package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.common.classification.DataSensitivity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Regression review 2026-09-29 (CASA LC-066): the restricted-data tag of an execution did not
 * survive the hop to the service that runs the tool. {@code forwardCredentials} copies a fixed
 * list of keys and the tag was not on it, so a chat or agent holding Gmail content wrote its table
 * rows as ordinary data, which any model could later read.
 */
@DisplayName("RemoteToolExecutionService forwards the restricted-data tag to the tool's service")
class RemoteToolExecutionServiceRestrictedTagTest {

    private RemoteToolExecutionService service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        service = new RemoteToolExecutionService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "datasourceUrl", "http://datasource.test");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    private ToolResult callTable(Map<String, Object> credentials) {
        return service.executeTool(
            new ToolCall("call-1", "table", Map.of("action", "insert_rows"), null),
            ToolDefinition.builder().name("table").build(),
            "tenant-1",
            credentials);
    }

    @Test
    @DisplayName("regression: a restricted execution's table call carries dataSensitivity=RESTRICTED")
    void restrictedExecutionForwardsTheTag() {
        server.expect(requestTo("http://datasource.test/api/agent-tools/execute"))
            .andExpect(jsonPath("$.dataSensitivity").value("RESTRICTED"))
            .andRespond(withSuccess("{\"success\":true,\"data\":{}}", MediaType.APPLICATION_JSON));

        ToolResult result = callTable(Map.of(DataSensitivity.CREDENTIAL_KEY, DataSensitivity.RESTRICTED.name()));

        assertThat(result.success()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("an ordinary execution sends no tag at all (its absence is what NORMAL means)")
    void ordinaryExecutionSendsNoTag() {
        server.expect(requestTo("http://datasource.test/api/agent-tools/execute"))
            .andExpect(jsonPath("$.dataSensitivity").doesNotExist())
            .andRespond(withSuccess("{\"success\":true,\"data\":{}}", MediaType.APPLICATION_JSON));

        callTable(Map.of(DataSensitivity.CREDENTIAL_KEY, DataSensitivity.NORMAL.name()));

        server.verify();
    }
}
