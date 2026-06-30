package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the generic, migration-driven <b>sub-resource token resolution</b>
 * ({@link HttpExecutionService#resolveSubResourceToken}).
 *
 * <p>Canonical case: Facebook Pages - page-scoped calls (carrying {@code page_id}) need the per-Page
 * {@code access_token} (from {@code GET /me/accounts}), not the connected user token. The rule is
 * declared 100% in the catalog (tool {@code runtime_metadata.credentialResolution}, strategy
 * {@code sub_resource_token}); these tests assert the engine applies it generically and is a strict
 * no-op without it.
 *
 * <p><b>Params shape matters:</b> at runtime {@code filteredParameters} is an <i>array of single-key
 * wrapper objects</i> (e.g. {@code [{"page_id":"222"},{"message":"hi"}]}) - the same shape every
 * sibling reader (path/query/header) uses. These tests feed that exact shape (a flat object would
 * let a broken extractor pass while the feature is dead at runtime).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - sub-resource token resolution")
class HttpExecutionServiceSubResourceTokenTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private ObjectMapper mapper;
    private HttpExecutionService service;

    private static final String RULE_RUNTIME = "{"
            + "\"type\":\"http\",\"baseUrl\":\"https://graph.facebook.com/v25.0\","
            + "\"credentialResolution\":{\"strategy\":\"sub_resource_token\","
            + "\"lookup\":{\"endpoint\":\"/me/accounts\",\"itemsPath\":\"data\",\"matchField\":\"id\",\"tokenField\":\"access_token\"},"
            + "\"trigger\":{\"pathParam\":\"page_id\"}}}";

    /** Same rule plus a trigger.deriveFrom: post-scoped tools (no page_id) recover it from the composite post id. */
    private static final String RULE_RUNTIME_DERIVE = "{"
            + "\"type\":\"http\",\"baseUrl\":\"https://graph.facebook.com/v25.0\","
            + "\"credentialResolution\":{\"strategy\":\"sub_resource_token\","
            + "\"lookup\":{\"endpoint\":\"/me/accounts\",\"itemsPath\":\"data\",\"matchField\":\"id\",\"tokenField\":\"access_token\"},"
            + "\"trigger\":{\"pathParam\":\"page_id\",\"deriveFrom\":["
            + "{\"param\":\"post_id\",\"split\":\"_\",\"index\":0},{\"param\":\"object_id\",\"split\":\"_\",\"index\":0}]}}}";

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        service = new HttpExecutionService(apiToolParameterRepository, userCredentialService,
                encryptionService, mapper, jdbcTemplate, restTemplate);
    }

    private ApiEntity fbApi() {
        ApiEntity api = new ApiEntity();
        api.setBaseUrl("https://graph.facebook.com/v25.0");
        return api;
    }

    private ApiToolEntity toolWith(String runtimeMetadata) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setRuntimeMetadata(runtimeMetadata);
        return tool;
    }

    /** Builds the runtime param shape: an array of single-key wrapper objects. */
    private JsonNode params(String... kv) {
        ArrayNode arr = mapper.createArrayNode();
        for (int i = 0; i < kv.length; i += 2) {
            arr.add(mapper.createObjectNode().put(kv[i], kv[i + 1]));
        }
        return arr;
    }

    private void stubAccountsLookup(String json) {
        try {
            JsonNode body = mapper.readTree(json);
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    @DisplayName("page-scoped call (array params): swaps the user token for the matching Page access_token; looks it up with the Bearer base token")
    void resolvesPageTokenFromArrayParams() {
        stubAccountsLookup("{\"data\":[{\"id\":\"111\",\"access_token\":\"PAGE_111\"},{\"id\":\"222\",\"access_token\":\"PAGE_222\"}]}");

        Optional<String> out = service.resolveSubResourceToken(
                fbApi(), toolWith(RULE_RUNTIME), params("page_id", "222", "message", "hi"),
                Optional.of("USER_TOKEN"), "user1", "facebook");

        assertEquals(Optional.of("PAGE_222"), out, "must return the page-222 access_token, not the user token or page-111's");

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), entity.capture(), eq(JsonNode.class));
        assertEquals("https://graph.facebook.com/v25.0/me/accounts", uri.getValue().toString());
        assertEquals("Bearer USER_TOKEN", entity.getValue().getHeaders().getFirst("Authorization"));
    }

    @Test
    @DisplayName("no credentialResolution rule → strict no-op (base token unchanged, no lookup)")
    void noRuleIsNoOp() {
        Optional<String> out = service.resolveSubResourceToken(
                fbApi(), toolWith("{\"type\":\"http\",\"baseUrl\":\"https://graph.facebook.com/v25.0\"}"),
                params("page_id", "222"), Optional.of("USER_TOKEN"), "user1", "facebook");

        assertEquals(Optional.of("USER_TOKEN"), out);
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("rule present but call carries no trigger param → base token (the /me/accounts lookup itself must keep the user token)")
    void missingTriggerParamIsNoOp() {
        Optional<String> out = service.resolveSubResourceToken(
                fbApi(), toolWith(RULE_RUNTIME), params("fields", "id,name,access_token"),
                Optional.of("USER_TOKEN"), "user1", "facebook");

        assertEquals(Optional.of("USER_TOKEN"), out);
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("no Page matches the page_id → falls back to the base token (never an empty/blank token)")
    void noMatchFallsBackToBaseToken() {
        stubAccountsLookup("{\"data\":[{\"id\":\"111\",\"access_token\":\"PAGE_111\"}]}");

        Optional<String> out = service.resolveSubResourceToken(
                fbApi(), toolWith(RULE_RUNTIME), params("page_id", "999"),
                Optional.of("USER_TOKEN"), "user1", "facebook");

        assertEquals(Optional.of("USER_TOKEN"), out);
    }

    @Test
    @DisplayName("second call for the same page is served from cache (lookup runs once)")
    void cachesPerPage() {
        stubAccountsLookup("{\"data\":[{\"id\":\"222\",\"access_token\":\"PAGE_222\"}]}");
        ApiEntity api = fbApi();
        ApiToolEntity tool = toolWith(RULE_RUNTIME);

        Optional<String> first = service.resolveSubResourceToken(api, tool, params("page_id", "222"), Optional.of("USER_TOKEN"), "user1", "facebook");
        Optional<String> second = service.resolveSubResourceToken(api, tool, params("page_id", "222"), Optional.of("USER_TOKEN"), "user1", "facebook");

        assertEquals(Optional.of("PAGE_222"), first);
        assertEquals(Optional.of("PAGE_222"), second);
        verify(restTemplate, times(1)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    @DisplayName("expired cache entry → re-resolves (lookup runs again)")
    void expiredCacheReResolves() {
        stubAccountsLookup("{\"data\":[{\"id\":\"222\",\"access_token\":\"PAGE_222\"}]}");
        service.subTokenTtlMs = -1L; // entries expire immediately
        ApiEntity api = fbApi();
        ApiToolEntity tool = toolWith(RULE_RUNTIME);

        service.resolveSubResourceToken(api, tool, params("page_id", "222"), Optional.of("USER_TOKEN"), "user1", "facebook");
        service.resolveSubResourceToken(api, tool, params("page_id", "222"), Optional.of("USER_TOKEN"), "user1", "facebook");

        verify(restTemplate, times(2)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    @DisplayName("lookup HTTP error → falls back to the base token (must never break the call)")
    void lookupErrorFallsBackToBaseToken() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(new RuntimeException("graph 500"));

        Optional<String> out = service.resolveSubResourceToken(
                fbApi(), toolWith(RULE_RUNTIME), params("page_id", "222"),
                Optional.of("USER_TOKEN"), "user1", "facebook");

        assertEquals(Optional.of("USER_TOKEN"), out);
    }

    @Test
    @DisplayName("empty base value → no-op (nothing to swap, no lookup)")
    void emptyBaseValueIsNoOp() {
        Optional<String> out = service.resolveSubResourceToken(
                fbApi(), toolWith(RULE_RUNTIME), params("page_id", "222"), Optional.empty(), "user1", "facebook");

        assertTrue(out.isEmpty());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("extractParamValue reads the array-of-wrappers shape, a flat object, and returns empty when absent")
    void extractParamValueShapes() {
        assertEquals("222", HttpExecutionService.extractParamValue(params("a", "1", "page_id", "222"), "page_id"));
        assertEquals("222", HttpExecutionService.extractParamValue(mapper.createObjectNode().put("page_id", "222"), "page_id"));
        assertEquals("", HttpExecutionService.extractParamValue(params("message", "hi"), "page_id"));
        assertEquals("", HttpExecutionService.extractParamValue(null, "page_id"));
    }

    @Test
    @DisplayName("post-scoped call (no page_id): derives page_id from the composite post_id '{pageId}_{postId}' and resolves that Page's token")
    void derivesPageTokenFromCompositePostId() {
        stubAccountsLookup("{\"data\":[{\"id\":\"222\",\"access_token\":\"PAGE_222\"}]}");

        Optional<String> out = service.resolveSubResourceToken(
                fbApi(), toolWith(RULE_RUNTIME_DERIVE), params("post_id", "222_999", "fields", "id"),
                Optional.of("USER_TOKEN"), "user1", "facebook");

        assertEquals(Optional.of("PAGE_222"), out,
                "page_id 222 must be derived from post_id '222_999' so list_post_comments uses the Page token, not the user token");
    }

    @Test
    @DisplayName("post-scoped call via object_id (create_comment path): derives the page_id and resolves the Page token")
    void derivesPageTokenFromObjectId() {
        stubAccountsLookup("{\"data\":[{\"id\":\"222\",\"access_token\":\"PAGE_222\"}]}");

        Optional<String> out = service.resolveSubResourceToken(
                fbApi(), toolWith(RULE_RUNTIME_DERIVE), params("object_id", "222_555", "message", "hi"),
                Optional.of("USER_TOKEN"), "user1", "facebook");

        assertEquals(Optional.of("PAGE_222"), out);
    }

    @Test
    @DisplayName("non-composite post id (no '_'): derives the whole id, no Page matches → falls back to the base token (never blank)")
    void nonCompositePostIdFallsBack() {
        stubAccountsLookup("{\"data\":[{\"id\":\"222\",\"access_token\":\"PAGE_222\"}]}");

        Optional<String> out = service.resolveSubResourceToken(
                fbApi(), toolWith(RULE_RUNTIME_DERIVE), params("post_id", "999"),
                Optional.of("USER_TOKEN"), "user1", "facebook");

        assertEquals(Optional.of("USER_TOKEN"), out, "no page matches '999' → base token");
    }

    @Test
    @DisplayName("explicit page_id wins over deriveFrom (pathParam takes precedence over the derived value)")
    void pathParamWinsOverDerive() {
        stubAccountsLookup("{\"data\":[{\"id\":\"111\",\"access_token\":\"PAGE_111\"},{\"id\":\"222\",\"access_token\":\"PAGE_222\"}]}");

        Optional<String> out = service.resolveSubResourceToken(
                fbApi(), toolWith(RULE_RUNTIME_DERIVE), params("page_id", "111", "post_id", "222_999"),
                Optional.of("USER_TOKEN"), "user1", "facebook");

        assertEquals(Optional.of("PAGE_111"), out, "explicit page_id 111 must win over the post_id-derived 222");
    }

    @Test
    @DisplayName("resolveTriggerMatchValue: pathParam direct, deriveFrom split, raw-without-separator, miss, and pathParam precedence")
    void resolveTriggerMatchValueUnit() throws Exception {
        JsonNode direct = mapper.readTree("{\"pathParam\":\"page_id\"}");
        assertEquals("222", HttpExecutionService.resolveTriggerMatchValue(direct, params("page_id", "222")));

        JsonNode derive = mapper.readTree(
                "{\"pathParam\":\"page_id\",\"deriveFrom\":[{\"param\":\"post_id\",\"split\":\"_\",\"index\":0}]}");
        assertEquals("222", HttpExecutionService.resolveTriggerMatchValue(derive, params("post_id", "222_999")),
                "page_id absent → derive first '_'-segment of post_id");
        assertEquals("999", HttpExecutionService.resolveTriggerMatchValue(derive, params("post_id", "999")),
                "raw without the separator → the whole value (single-element split, index 0)");
        assertEquals("", HttpExecutionService.resolveTriggerMatchValue(derive, params("message", "hi")),
                "neither param present → blank");
        assertEquals("111", HttpExecutionService.resolveTriggerMatchValue(derive, params("page_id", "111", "post_id", "222_999")),
                "explicit pathParam wins");
    }

    @Test
    @DisplayName("resolveTriggerMatchValue derive edge cases: empty split returns raw, out-of-range index skips, blank leading segment skips, multi-entry advances")
    void resolveTriggerMatchValueDeriveEdgeCases() throws Exception {
        // split == "" (or absent) → no transform → return the raw source value
        JsonNode noSplit = mapper.readTree("{\"deriveFrom\":[{\"param\":\"post_id\"}]}");
        assertEquals("222_999", HttpExecutionService.resolveTriggerMatchValue(noSplit, params("post_id", "222_999")),
                "no split → raw value returned unchanged");

        // index past the end of the split array → that entry yields nothing → overall blank (single entry)
        JsonNode badIndex = mapper.readTree("{\"deriveFrom\":[{\"param\":\"post_id\",\"split\":\"_\",\"index\":5}]}");
        assertEquals("", HttpExecutionService.resolveTriggerMatchValue(badIndex, params("post_id", "222_999")),
                "out-of-range index → no match value (never an array-bounds exception)");

        // blank leading segment ("_999".split("_") == ["","999"], index 0 is blank) → must be skipped
        JsonNode leadingSep = mapper.readTree("{\"deriveFrom\":[{\"param\":\"post_id\",\"split\":\"_\",\"index\":0}]}");
        assertEquals("", HttpExecutionService.resolveTriggerMatchValue(leadingSep, params("post_id", "_999")),
                "a blank first segment must not be returned as the match value");

        // multi-entry deriveFrom: first source absent, second present → the loop advances past entry 0 to entry 1
        JsonNode multi = mapper.readTree("{\"deriveFrom\":["
                + "{\"param\":\"post_id\",\"split\":\"_\",\"index\":0},"
                + "{\"param\":\"object_id\",\"split\":\"_\",\"index\":0}]}");
        assertEquals("222", HttpExecutionService.resolveTriggerMatchValue(multi, params("object_id", "222_555")),
                "post_id absent → fall through to the object_id derive entry");
    }

    @Test
    @DisplayName("cache is bounded: distinct sub-resources beyond the cap never grow it past the limit")
    void boundsCacheSize() {
        stubAccountsLookup("{\"data\":[{\"id\":\"1\",\"access_token\":\"T1\"},{\"id\":\"2\",\"access_token\":\"T2\"},{\"id\":\"3\",\"access_token\":\"T3\"}]}");
        service.subTokenCacheMax = 2;
        ApiEntity api = fbApi();
        ApiToolEntity tool = toolWith(RULE_RUNTIME);

        for (String pid : new String[]{"1", "2", "3"}) {
            service.resolveSubResourceToken(api, tool, params("page_id", pid), Optional.of("USER_TOKEN"), "user1", "facebook");
        }

        assertTrue(service.subTokenCacheSize() <= 2, "cache must stay within the configured cap, got " + service.subTokenCacheSize());
    }
}
