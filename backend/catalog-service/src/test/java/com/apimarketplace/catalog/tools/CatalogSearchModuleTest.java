package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogSearchModuleTest {

    @Mock
    private CredentialClient credentialClient;
    @Mock
    private RestTemplate restTemplate;

    private CatalogSearchModule module;

    @BeforeEach
    void setUp() {
        module = new CatalogSearchModule(new ObjectMapper(), credentialClient);
        ReflectionTestUtils.setField(module, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(module, "serverPort", 18081);
    }

    @Test
    @DisplayName("forwards tenant and organization headers to scoped catalog self-search")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void forwardsTenantAndOrganizationHeadersToScopedCatalogSelfSearch() {
        String expectedUrl = "http://localhost:18081/api/tools/search?q=list+messages&k=10&api=gmail";
        when(restTemplate.exchange(
            eq(expectedUrl), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)
        )).thenReturn(ResponseEntity.ok("{\"tools\":[]}"));

        Optional<ToolExecutionResult> result = module.execute(
            "search",
            Map.of("query", "list messages", "api", "gmail"),
            "tenant-1",
            new ToolExecutionContext(
                "tenant-1",
                Map.of(),
                Map.of(),
                Set.of(),
                null,
                null,
                "org-1",
                "MEMBER"
            )
        );

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
            eq(expectedUrl), eq(HttpMethod.GET), entityCaptor.capture(), eq(String.class)
        );
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-1");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-1");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-Role")).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("custom mode matches API scope only against API identity fields")
    @SuppressWarnings("unchecked")
    void customModeMatchesApiScopeOnlyAgainstApiIdentityFields() {
        String toolId = "11111111-2222-3333-4444-555555555555";
        String toolJson = """
            {
              "id": "11111111-2222-3333-4444-555555555555",
              "name": "post_message",
              "description": "Mentions Gmail but belongs to Slack",
              "provider": "Slack",
              "iconSlug": "slack",
              "api": {
                "name": "Slack",
                "iconSlug": "slack"
              }
            }
            """;
        when(restTemplate.exchange(
            eq("http://localhost:18081/api/catalog/tools/" + toolId + "/info"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(ResponseEntity.ok(toolJson));

        ToolExecutionContext context = new ToolExecutionContext(
            "tenant-1",
            Map.of("allowedToolIds", List.of(toolId)),
            Map.of(),
            Set.of(),
            null,
            null,
            "org-1",
            "MEMBER"
        );

        Optional<ToolExecutionResult> result = module.execute(
            "search",
            Map.of("query", "message", "api", "gmail"),
            "tenant-1",
            context
        );

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        assertThat(data.get("count")).isEqualTo(0);
        assertThat((List<Map<String, Object>>) data.get("tools")).isEmpty();
        assertThat((List<String>) data.get("api_filters")).containsExactly("gmail");

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
            eq("http://localhost:18081/api/catalog/tools/" + toolId + "/info"),
            eq(HttpMethod.GET),
            entityCaptor.capture(),
            eq(String.class)
        );
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-1");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-1");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-Role")).isEqualTo("MEMBER");
    }
}
