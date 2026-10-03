package com.apimarketplace.datasource.controllers.tools;

import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.classification.DataSensitivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression review 2026-09-29 (CASA LC-066): the table tools stamp the rows they write RESTRICTED
 * when the calling execution's credentials carry the restricted-data tag
 * ({@code DataSourceRowModule}, {@code DataSourceTableModule}). This controller rebuilt those
 * credentials without the tag, so rows written by a chat or agent holding Gmail content were
 * stored as ordinary data. Only RESTRICTED is honoured: a caller can tighten, never relax.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceToolsController (datasource) restores the forwarded restricted-data tag")
class ServiceToolsControllerRestrictedTagTest {

    @Mock private ToolsProvider toolsProvider;
    private ServiceToolsController controller;

    @BeforeEach
    void setUp() {
        controller = new ServiceToolsController(toolsProvider);
    }

    private Map<String, Object> credentialsFor(Object dataSensitivity) {
        when(toolsProvider.execute(eq("table"), any(), any()))
            .thenReturn(ToolExecutionResult.success(Map.of()));
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-User-ID", "tenant-1");
        Map<String, Object> body = new HashMap<>();
        body.put("tool", "table");
        body.put("parameters", Map.of("action", "insert_rows"));
        if (dataSensitivity != null) {
            body.put("dataSensitivity", dataSensitivity);
        }
        controller.executeTool(http, body);
        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolsProvider).execute(eq("table"), any(), ctx.capture());
        return ctx.getValue().credentials();
    }

    @Test
    @DisplayName("regression: a forwarded RESTRICTED tag reaches the table tool, so its rows are stored restricted")
    void restrictedTagReachesTheTableTool() {
        assertThat(DataSensitivity.fromCredentials(credentialsFor("RESTRICTED")).isRestricted()).isTrue();
    }

    @Test
    @DisplayName("a body saying NORMAL writes nothing: it cannot relax a restriction")
    void normalWritesNothing() {
        assertThat(credentialsFor("NORMAL")).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }

    @Test
    @DisplayName("no field leaves the call untagged")
    void absentFieldStaysUntagged() {
        assertThat(credentialsFor(null)).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }
}
