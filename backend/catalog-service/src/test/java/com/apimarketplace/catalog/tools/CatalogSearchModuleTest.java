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
    @DisplayName("Bug B8: search with api and no query lists that API's tools instead of failing 'query is required'")
    @SuppressWarnings("unchecked")
    void searchWithApiAndNoQueryListsThatApisTools() {
        String expectedUrl = "http://localhost:18081/api/tools/search?q=composio&k=10&api=composio";
        when(restTemplate.exchange(
            eq(expectedUrl), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)
        )).thenReturn(ResponseEntity.ok("{\"tools\":[{\"id\":\"t-1\",\"name\":\"list_tools\",\"description\":\"d\"}]}"));

        Optional<ToolExecutionResult> result = module.execute(
            "search", Map.of("api", "composio"), "tenant-1", null);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        assertThat(data.get("count")).isEqualTo(1);
        assertThat(data.get("note")).isEqualTo(CatalogSearchModule.API_LISTING_NOTE);
        assertThat((List<String>) data.get("api_filters")).containsExactly("composio");
    }

    @Test
    @DisplayName("Bug B8: an API listing that matches nothing says so and points at an unscoped search")
    @SuppressWarnings("unchecked")
    void apiListingThatMatchesNothingExplainsWhy() {
        when(restTemplate.exchange(
            eq("http://localhost:18081/api/tools/search?q=nosuchapi&k=10&api=nosuchapi"),
            eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)
        )).thenReturn(ResponseEntity.ok("{\"tools\":[]}"));

        Map<String, Object> data = (Map<String, Object>) module.execute(
            "search", Map.of("api", "nosuchapi"), "tenant-1", null).get().data();

        assertThat(data.get("count")).isEqualTo(0);
        assertThat((String) data.get("note")).contains("No API matched").contains("query='nosuchapi'");
    }

    @Test
    @DisplayName("an empty KEYWORD search carries no listing note")
    @SuppressWarnings("unchecked")
    void emptyKeywordSearchHasNoListingNote() {
        when(restTemplate.exchange(
            eq("http://localhost:18081/api/tools/search?q=zzz&k=10&api=gmail"),
            eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)
        )).thenReturn(ResponseEntity.ok("{\"tools\":[]}"));

        Map<String, Object> data = (Map<String, Object>) module.execute(
            "search", Map.of("api", "gmail", "query", "zzz"), "tenant-1", null).get().data();

        assertThat(data).doesNotContainKey("note");
    }

    @Test
    @DisplayName("Bug B8: custom-mode API listing honours limit, as the note promises")
    @SuppressWarnings("unchecked")
    void customModeApiListingHonoursLimit() {
        List<String> ids = List.of("a1", "a2", "a3");
        for (String id : ids) {
            when(restTemplate.exchange(
                eq("http://localhost:18081/api/catalog/tools/" + id + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)
            )).thenReturn(ResponseEntity.ok("{\"id\":\"" + id + "\",\"name\":\"t\",\"api\":{\"name\":\"Slack\"}}"));
        }
        ToolExecutionContext context = new ToolExecutionContext(
            "tenant-1", Map.of("allowedToolIds", ids), Map.of(), Set.of(), null, null, "org-1", "MEMBER");

        Map<String, Object> data = (Map<String, Object>) module.execute(
            "search", Map.of("api", "slack", "limit", 2), "tenant-1", context).get().data();

        assertThat(data.get("count")).isEqualTo(2);
    }

    @Test
    @DisplayName("custom-mode KEYWORD search is not capped by limit (only the no-query listing is)")
    @SuppressWarnings("unchecked")
    void customModeKeywordSearchStaysUncapped() {
        List<String> ids = List.of("a1", "a2", "a3");
        for (String id : ids) {
            when(restTemplate.exchange(
                eq("http://localhost:18081/api/catalog/tools/" + id + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)
            )).thenReturn(ResponseEntity.ok("{\"id\":\"" + id + "\",\"name\":\"post_message\",\"api\":{\"name\":\"Slack\"}}"));
        }
        ToolExecutionContext context = new ToolExecutionContext(
            "tenant-1", Map.of("allowedToolIds", ids), Map.of(), Set.of(), null, null, "org-1", "MEMBER");

        Map<String, Object> data = (Map<String, Object>) module.execute(
            "search", Map.of("api", "slack", "query", "post message", "limit", 2), "tenant-1", context).get().data();

        assertThat(data.get("count")).isEqualTo(3);
    }

    @Test
    @DisplayName("custom-mode API listing with no allowed tool of that API says the agent is restricted")
    @SuppressWarnings("unchecked")
    void customModeApiListingWithNoMatchNamesTheRestriction() {
        when(restTemplate.exchange(
            eq("http://localhost:18081/api/catalog/tools/a1/info"),
            eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)
        )).thenReturn(ResponseEntity.ok("{\"id\":\"a1\",\"name\":\"t\",\"api\":{\"name\":\"Slack\"}}"));
        ToolExecutionContext context = new ToolExecutionContext(
            "tenant-1", Map.of("allowedToolIds", List.of("a1")), Map.of(), Set.of(), null, null, "org-1", "MEMBER");

        Map<String, Object> data = (Map<String, Object>) module.execute(
            "search", Map.of("api", "gmail"), "tenant-1", context).get().data();

        assertThat((String) data.get("note")).contains("this agent may use");
    }

    @Test
    @DisplayName("search with neither query nor api still fails with MISSING_PARAMETER")
    void searchWithoutQueryOrApiStillFails() {
        Optional<ToolExecutionResult> result = module.execute("search", Map.of(), "tenant-1", null);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().errorCode()).isEqualTo(com.apimarketplace.agent.tools.ToolErrorCode.MISSING_PARAMETER);
        assertThat(result.get().error()).contains("query is required").contains("api=");
    }

    @Test
    @DisplayName("Bug B8: custom mode with api and no query keeps every allowed tool of that API")
    @SuppressWarnings("unchecked")
    void customModeApiWithoutQueryKeepsEveryToolOfThatApi() {
        String toolId = "11111111-2222-3333-4444-555555555555";
        // The API is spelled 'google-sheets' in the scope and "Google Sheets" in the tool text: matching the
        // scope-derived keyword against the text would have dropped this tool.
        String toolJson = """
            {
              "id": "11111111-2222-3333-4444-555555555555",
              "name": "append_row",
              "description": "Append a row",
              "apiSlug": "google-sheets",
              "api": { "name": "Google Sheets" }
            }
            """;
        when(restTemplate.exchange(
            eq("http://localhost:18081/api/catalog/tools/" + toolId + "/info"),
            eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)
        )).thenReturn(ResponseEntity.ok(toolJson));

        ToolExecutionContext context = new ToolExecutionContext(
            "tenant-1", Map.of("allowedToolIds", List.of(toolId)), Map.of(), Set.of(),
            null, null, "org-1", "MEMBER");

        Optional<ToolExecutionResult> result = module.execute(
            "search", Map.of("api", "google-sheets"), "tenant-1", context);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        assertThat(data.get("count")).isEqualTo(1);
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
