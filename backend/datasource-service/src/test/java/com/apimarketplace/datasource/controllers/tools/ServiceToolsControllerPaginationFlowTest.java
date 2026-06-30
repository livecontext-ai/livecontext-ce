package com.apimarketplace.datasource.controllers.tools;

import com.apimarketplace.datasource.config.DataSourceAgentDefaultsConfig;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.services.DataSourceService;
import com.apimarketplace.datasource.tools.datasource.DataSourceRowModule;
import com.apimarketplace.datasource.tools.datasource.DataSourceSchemaModule;
import com.apimarketplace.datasource.tools.datasource.DataSourceTableModule;
import com.apimarketplace.datasource.tools.datasource.DataSourceToolsProvider;
import com.apimarketplace.datasource.tools.datasource.TablePublishModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Cross-layer e2e of the agent's table-pagination path as it runs in cloud / microservice mode:
 * the REAL {@link ServiceToolsController} → REAL {@link DataSourceToolsProvider} → REAL
 * {@link DataSourceTableModule}. Only the DB ({@link DataSourceService}) is mocked.
 *
 * <p>This is the controller that prod (the bridge agent on the microservice stack) actually hits:
 * it copies the request's {@code conversationId} into {@code context.variables()}, which is where
 * {@code DataSourceTableModule}'s loop-detection guard reads it. (The CE monolith instead serves
 * {@code /api/agent-tools/execute} via agent-service's {@code AgentToolsController}, which passes
 * EMPTY variables - so the guard is dormant there and the monolith never reproduced this bug.)
 *
 * <p>Reproduces the 2026-06-05 prod bug: an agent listing 28 tables (page size 25) could not reach
 * the last 3 because the second {@code list} call - advancing {@code offset} exactly as the first
 * response instructed - was rejected with {@code ALREADY_LISTED}. The guard now keys on the page
 * signature, so paging forward works while an exact re-list of the same page is still caught.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceToolsController → table pagination flow (cloud/microservice path)")
class ServiceToolsControllerPaginationFlowTest {

    private static final String TENANT = "tenant-e2e";

    @Mock private DataSourceService dataSourceService;
    @Mock private DataSourceRowModule rowModule;
    @Mock private DataSourceSchemaModule schemaModule;
    @Mock private TablePublishModule publishModule;

    private ServiceToolsController controller;

    @BeforeEach
    void setUp() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        var vectorGate = new com.apimarketplace.datasource.services.VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env));
        DataSourceTableModule tableModule = new DataSourceTableModule(
            dataSourceService, new ObjectMapper(), new DataSourceAgentDefaultsConfig(), vectorGate);
        DataSourceToolsProvider provider = new DataSourceToolsProvider(
            tableModule, rowModule, schemaModule, publishModule, vectorGate);
        controller = new ServiceToolsController(provider);
    }

    private DataSource fakeDs(long id) {
        return new DataSource(id, TENANT, "T" + id, "desc", DataSourceType.INLINE, Map.of(),
            DataSourceStatus.ACTIVE, null, null, TENANT, null, null, null, null, null, null);
    }

    private List<DataSource> nTables(int n) {
        List<DataSource> all = new ArrayList<>();
        for (long i = 1; i <= n; i++) all.add(fakeDs(i));
        return all;
    }

    private MockHttpServletRequest req() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("X-User-ID", TENANT);
        return r;
    }

    /** POST /api/agent-tools/execute with tool=table, action=list, the given page + a conversationId. */
    private Map<String, Object> listPage(int offset, int limit, String conversationId) {
        Map<String, Object> request = new HashMap<>();
        request.put("tool", "table");
        request.put("parameters", Map.of("action", "list", "offset", offset, "limit", limit));
        request.put("conversationId", conversationId);
        ResponseEntity<Map<String, Object>> response = controller.executeTool(req(), request);
        assertThat(response.getBody()).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return data;
    }

    @Test
    @DisplayName("Agent can page forward: list(offset=25) after list(offset=0) returns the remaining rows (prod bug 2026-06-05)")
    void agentCanPageForwardThroughTheHttpExecuteEndpoint() {
        when(dataSourceService.getDataSources(TENANT, null, null)).thenReturn(nTables(28));

        // Page 1 - same conversation the prod bridge agent used.
        Map<String, Object> page1 = listPage(0, 25, "conv-e2e");
        assertThat(page1.get("status")).isEqualTo("OK");
        assertThat(page1.get("count")).isEqualTo(25);
        assertThat(page1.get("hasMore")).isEqualTo(true);

        // Page 2 - advancing offset within the cooldown. Pre-fix this returned ALREADY_LISTED
        // through this exact controller path; post-fix it must return the last 3 tables.
        Map<String, Object> page2 = listPage(25, 25, "conv-e2e");
        assertThat(page2.get("status")).isEqualTo("OK");
        assertThat(page2.get("count")).isEqualTo(3);
        assertThat(page2.get("hasMore")).isEqualTo(false);
    }

    @Test
    @DisplayName("Loop guard survives the HTTP layer: re-listing the SAME page returns ALREADY_LISTED")
    void repeatingTheSamePageThroughHttpIsStillGuarded() {
        when(dataSourceService.getDataSources(TENANT, null, null)).thenReturn(nTables(28));

        Map<String, Object> first = listPage(0, 25, "conv-loop");
        assertThat(first.get("status")).isEqualTo("OK");

        Map<String, Object> second = listPage(0, 25, "conv-loop");
        assertThat(second.get("status")).isEqualTo("ALREADY_LISTED");
    }
}
