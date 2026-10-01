package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An explicitly declared EMPTY {@code apiKeyConfig.prefix} means "send the credential raw"
 * and must reach the wire that way.
 *
 * <p>Two places used to collapse an empty prefix into null, and null defaults to
 * {@code "Bearer "} for an Authorization header, so the intent was unexpressible: ten shipped
 * catalog files (ClickUp, Linear, Monday, Wix, Backblaze, LaunchDarkly, Nuclino, OpenPhone,
 * Payload CMS, AssemblyAI) declare {@code prefix: ""} precisely because their vendor rejects a
 * scheme, and every one was silently sent {@code Bearer <token>}.
 *
 * <p>ClickUp shows the difference is real rather than cosmetic: a raw personal token answers
 * {@code OAUTH_025 "Token invalid"} while the same token behind {@code Bearer } answers
 * {@code OAUTH_019 "Oauth token not found"}, i.e. the prefix routes the request into the
 * OAuth-token lookup, where a personal token can never be found.
 *
 * <p>These are wire-level tests: a mocked {@link RestTemplate} captures the outgoing
 * {@link HttpEntity}, so they assert what is actually sent rather than what a helper returns.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - an explicit empty auth prefix is honoured")
class HttpExecutionServiceAuthPrefixTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private HttpExecutionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOKEN = "pk_12345_RAWTOKEN";

    @BeforeEach
    void setUp() {
        lenient().when(apiToolParameterRepository.findByApiToolId(any())).thenReturn(List.of());
        lenient().when(userCredentialService.getAccessTokenInfo(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(userCredentialService.getAccessToken(anyString(), anyString()))
                .thenReturn(Optional.of(TOKEN));
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine());
        CredentialModeContext.clear();
    }

    /** Stubs the tool_credentials row the injection lookup reads. */
    private void givenInjectionMetadata(String metadataJson) {
        Map<String, Object> row = new HashMap<>();
        row.put("metadata", metadataJson);
        List<Map<String, Object>> results = new ArrayList<>();
        results.add(row);
        // The Object casts pick the varargs overload; without them the two-arg and three-arg
        // JdbcTemplate signatures are ambiguous at compile time.
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any())).thenReturn(results);
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any(), (Object) any()))
                .thenReturn(results);
    }

    private String sentAuthorizationHeader() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.GET), captor.capture(), eq(Object.class));
        return captor.getValue().getHeaders().getFirst("Authorization");
    }

    private void callTool() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));

        ApiEntity api = new ApiEntity();
        api.setBaseUrl("https://api.clickup.com/api/v2");
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setMethod("GET");
        tool.setEndpoint("/user");

        service.executeHttpCallWithCredentials(
                api, tool, objectMapper.createObjectNode(), Set.of(), "user1", "clickup");
    }

    @Test
    @DisplayName("FIX: an explicit empty prefix sends the credential RAW, with no Bearer scheme")
    void explicitEmptyPrefixSendsRawCredential() {
        givenInjectionMetadata("""
                {"injection":{"type":"header","key":"Authorization","prefix":""},"field":"api_key"}
                """);

        callTool();

        assertThat(sentAuthorizationHeader())
                .as("pre-fix this was \"Bearer %s\", which ClickUp resolves as an OAuth token and rejects", TOKEN)
                .isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("an ABSENT prefix still defaults to Bearer, so no existing integration changes")
    void absentPrefixStillDefaultsToBearer() {
        givenInjectionMetadata("""
                {"injection":{"type":"header","key":"Authorization"},"field":"api_key"}
                """);

        callTool();

        assertThat(sentAuthorizationHeader())
                .as("absent means the row predates the prefix column; that legacy default must survive")
                .isEqualTo("Bearer " + TOKEN);
    }

    @Test
    @DisplayName("a declared non-empty prefix is still applied verbatim")
    void declaredPrefixIsAppliedVerbatim() {
        givenInjectionMetadata("""
                {"injection":{"type":"header","key":"Authorization","prefix":"Token "},"field":"api_key"}
                """);

        callTool();

        assertThat(sentAuthorizationHeader()).isEqualTo("Token " + TOKEN);
    }

    @Test
    @DisplayName("an empty prefix in the older fakeAuth block is honoured too, not just the canonical one")
    void emptyPrefixFromLegacyFakeAuthBlockIsHonoured() {
        // Older imports carry the prefix under fakeAuth.apiKeyConfig instead of injection.
        givenInjectionMetadata("""
                {"injection":{"type":"header","key":"Authorization"},
                 "field":"api_key",
                 "fakeAuth":{"apiKeyConfig":{"prefix":""}}}
                """);

        callTool();

        assertThat(sentAuthorizationHeader())
                .as("the fallback path collapsed \"\" to null as well, so it needed the same distinction")
                .isEqualTo(TOKEN);
    }

    @Test
    @DisplayName("a user who pastes the scheme anyway does not get it doubled")
    void userTypedSchemeIsNotDoubled() {
        lenient().when(userCredentialService.getAccessToken(anyString(), anyString()))
                .thenReturn(Optional.of("Bearer " + TOKEN));
        givenInjectionMetadata("""
                {"injection":{"type":"header","key":"Authorization","prefix":"Bearer "},"field":"api_key"}
                """);

        callTool();

        assertThat(sentAuthorizationHeader()).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    @DisplayName("a non-Authorization header with an empty prefix is unaffected")
    void customHeaderWithEmptyPrefixIsUnchanged() {
        givenInjectionMetadata("""
                {"injection":{"type":"header","key":"X-Api-Key","prefix":""},"field":"api_key"}
                """);

        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));
        ApiEntity api = new ApiEntity();
        api.setBaseUrl("https://api.clickup.com/api/v2");
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setMethod("GET");
        tool.setEndpoint("/user");
        service.executeHttpCallWithCredentials(
                api, tool, objectMapper.createObjectNode(), Set.of(), "user1", "clickup");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.GET), captor.capture(), eq(Object.class));

        assertThat(captor.getValue().getHeaders().getFirst("X-Api-Key")).isEqualTo(TOKEN);
        assertThat(captor.getValue().getHeaders().getFirst("Authorization"))
                .as("the Bearer default belongs to the Authorization branch only")
                .isNull();
    }
}
