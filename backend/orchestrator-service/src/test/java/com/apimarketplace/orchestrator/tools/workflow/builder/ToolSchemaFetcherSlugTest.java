package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/**
 * Pins the {@link ToolSchemaFetcher#checkToolExists} dispatch across three id shapes:
 * real UUID, compound {@code apiSlug/toolSlug} legacy form, and garbage.
 *
 * <p>Prod incident: validator returned 16 TOOL_NOT_FOUND errors for a workflow whose
 * stored {@code id} fields were {@code gmail/gmail-list-messages} style strings. Before
 * this change, the fetcher short-circuited every non-UUID to NOT_FOUND, rejecting valid
 * slug-form ids that the execution layer actually accepts (CatalogV1Controller exposes
 * both a {@code /tools/{uuid}/execute} and a {@code /tools/{apiSlug}/{toolSlug}/execute}
 * endpoint, proving the slug form is a legitimate identifier).
 */
@DisplayName("ToolSchemaFetcher - UUID + slug dispatch")
class ToolSchemaFetcherSlugTest {

    private RestTemplate restTemplate;
    private ToolSchemaFetcher fetcher;

    private static final String CATALOG_URL = "http://catalog:8081";
    private static final String UUID_FORM = "85f92897-77c6-4d94-b2b7-77774eeb6aa7";

    @BeforeEach
    void setUp() {
        fetcher = new ToolSchemaFetcher();
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(fetcher, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(fetcher, "catalogServiceUrl", CATALOG_URL);
    }

    @Test
    @DisplayName("UUID form hits /api/catalog/tools/{uuid}/info and returns EXISTS on 200")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void uuidHitsUuidEndpoint() {
        when(restTemplate.exchange(contains("/api/catalog/tools/" + UUID_FORM + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok((Map) Map.of("id", UUID_FORM, "name", "get_message")));

        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists(UUID_FORM);

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.EXISTS);
        verify(restTemplate).exchange(contains("/api/catalog/tools/" + UUID_FORM + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("apiSlug/toolSlug form hits /api/workflow-inspector/tools/{toolSlug} (slug endpoint)")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void compoundSlugHitsSlugEndpoint() {
        // Stored form in some legacy plans (prod Gmail Auto-Labeler) - not a UUID but
        // a real catalogued tool; the slug endpoint MUST resolve it for the validator
        // to not reject the whole plan.
        when(restTemplate.exchange(contains("/api/workflow-inspector/tools/gmail-list-messages"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok((Map) Map.of("slug", "gmail-list-messages", "apiSlug", "gmail")));

        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists("gmail/gmail-list-messages");

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.EXISTS);
        // Must not have hit the UUID endpoint - that would 400 on a non-UUID id.
        verify(restTemplate, never()).exchange(contains("/api/catalog/tools/"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("Garbage (fabricated Label_1 string) is deterministically NOT_FOUND without HTTP")
    void garbageIsDeterministicNotFound() {
        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists("Label_1");

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
        // No network call - the LLM fabrication is rejected locally.
        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("Slug endpoint 404 → NOT_FOUND (real unknown tool)")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void slugEndpoint404() {
        when(restTemplate.exchange(contains("/api/workflow-inspector/tools/unknown-tool"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenThrow(HttpClientErrorException.NotFound.class);

        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists("api/unknown-tool");

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
    }

    @Test
    @DisplayName("Slug endpoint transient error (5xx) → UNKNOWN (soft fail, validator tolerates)")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void slugEndpointTransientError() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class)))
            .thenThrow(new org.springframework.web.client.ResourceAccessException("conn reset"));

        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists("gmail/gmail-list-messages");

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.UNKNOWN);
    }

    @Test
    @DisplayName("Tool without slash (plain slug) treated as unknown fabrication - not_found")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void plainSlugWithoutSlashIsNotFound() {
        // The stored contract is either UUID or apiSlug/toolSlug - a lone "gmail-list-messages"
        // shouldn't reach this method from a real plan. Keep the strict "deterministic
        // not_found" behaviour so a typo doesn't silently accept a nonexistent tool.
        ToolSchemaFetcher.ToolExistence out = fetcher.checkToolExists("gmail-list-messages");

        assertThat(out).isEqualTo(ToolSchemaFetcher.ToolExistence.NOT_FOUND);
        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Map.class));
    }
}
