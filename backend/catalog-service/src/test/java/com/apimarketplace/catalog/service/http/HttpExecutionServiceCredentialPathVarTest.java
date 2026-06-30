package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runtime proof for the "credential path variable" fix. After the importer rewrites a
 * {@code {{TWILIO_ACCOUNT_SID}}} path default to the single-brace URL template var
 * {@code {twilio_account_sid}}, the existing {@link HttpExecutionService} URL machinery
 * must inject the credential value into the path - exactly like a base-URL template var
 * ({domain}, {project_id}).
 *
 * <p>Also pins the root cause: the original DOUBLE-brace form was rejected by SSRF URL
 * validation (single-brace placeholder substitution left stray braces → "Malformed URL"),
 * which is why every Twilio {@code /Accounts/...} call failed before the fix.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - credential path variable resolution")
class HttpExecutionServiceCredentialPathVarTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private HttpExecutionService service;

    private static final String TWILIO_PATH =
            "/2010-04-01/Accounts/{twilio_account_sid}/Messages.json";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(apiToolParameterRepository.findByApiToolId(any())).thenReturn(List.of());
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate);
    }

    private ApiEntity twilioApi() {
        ApiEntity api = new ApiEntity();
        api.setBaseUrl("https://api.twilio.com");
        return api;
    }

    private ApiToolEntity tool(String endpoint) {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(UUID.randomUUID());
        t.setMethod("GET");
        t.setEndpoint(endpoint);
        return t;
    }

    private String replaceUrlTemplateVariables(String url, String userId, String credentialName) throws Exception {
        return replaceUrlTemplateVariables(url, userId, credentialName, null);
    }

    private String replaceUrlTemplateVariables(String url, String userId, String credentialName, String fallback) throws Exception {
        Method m = HttpExecutionService.class.getDeclaredMethod(
                "replaceUrlTemplateVariables", String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, url, userId, credentialName, fallback);
    }

    @Test
    @DisplayName("buildFullUrl + processPathParameters leave the credential var untouched (no per-call param)")
    void pathParameterStageLeavesCredentialVar() {
        ApiToolEntity tool = tool(TWILIO_PATH);
        String url = service.buildFullUrl(twilioApi(), tool);
        assertThat(url).isEqualTo("https://api.twilio.com" + TWILIO_PATH);

        ArrayNode noParams = objectMapper.createArrayNode();
        String afterPath = service.processPathParameters(url, tool, noParams);
        assertThat(afterPath)
                .as("the credential var is not a user path param - it survives to the credential-resolution stage")
                .isEqualTo("https://api.twilio.com" + TWILIO_PATH);
    }

    @Test
    @DisplayName("replaceUrlTemplateVariables injects the credential value into the path var")
    void resolvesCredentialVarFromCredentialData() throws Exception {
        when(userCredentialService.getCredentialDataMap("user1", "twilio"))
                .thenReturn(Map.of("twilio_account_sid", "AC0123456789abcdef", "password", "secrettoken"));

        String resolved = replaceUrlTemplateVariables(
                "https://api.twilio.com" + TWILIO_PATH, "user1", "twilio");

        assertThat(resolved)
                .isEqualTo("https://api.twilio.com/2010-04-01/Accounts/AC0123456789abcdef/Messages.json");
    }

    @Test
    @DisplayName("SSRF validation TOLERATES the single-brace var (it did NOT for the double-brace form)")
    void ssrfValidationToleratesSingleBraceVar() {
        assertThatCode(() -> UrlSafetyValidator.validateUrl("https://api.twilio.com" + TWILIO_PATH))
                .as("single-brace placeholder is substituted with a safe literal before URI parsing")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MIGRATION: a pre-fix credential lacking the new field resolves to the fallback (wrong) - must be re-configured")
    void missingFieldFallsBackToPrimaryValue() throws Exception {
        // Existing Twilio basic_auth credential created before the fix: only username/password,
        // no twilio_account_sid field. resolveUrlVariable step-4 falls back to the primary
        // credential value, masking the misconfiguration with a wrong-but-non-empty value.
        when(userCredentialService.getCredentialDataMap("user1", "twilio"))
                .thenReturn(Map.of("password", "authtoken"));

        String resolved = replaceUrlTemplateVariables(
                "https://api.twilio.com" + TWILIO_PATH, "user1", "twilio", "PRIMARY_FALLBACK");

        assertThat(resolved)
                .as("documents the migration caveat: the field must be filled, else a wrong id is injected")
                .isEqualTo("https://api.twilio.com/2010-04-01/Accounts/PRIMARY_FALLBACK/Messages.json");
    }

    @Test
    @DisplayName("MIGRATION: missing field AND no fallback → placeholder stays (fails loudly downstream, not a silent wrong call)")
    void missingFieldNoFallbackLeavesPlaceholder() throws Exception {
        when(userCredentialService.getCredentialDataMap("user1", "twilio"))
                .thenReturn(Map.of("password", "authtoken"));

        String resolved = replaceUrlTemplateVariables(
                "https://api.twilio.com" + TWILIO_PATH, "user1", "twilio", null);

        assertThat(resolved)
                .as("an unresolved placeholder breaks URI.create downstream → clean failure, not a wrong upstream call")
                .contains("{twilio_account_sid}");
    }

    @Test
    @DisplayName("FIX: basic_auth injection builds 'Authorization: Basic base64(username:password)' (was wrongly 'Bearer <username>')")
    void basicAuthInjectionBuildsBasicAuthHeader() {
        // The injection metadata for Twilio is {"key":"Authorization","type":"basic_auth"}.
        // Pre-fix, prepareHeadersWithCredentials only handled type=="header"/"query", so basic_auth
        // fell through to the legacy Bearer fallback and sent "Bearer <username>" (the Account SID),
        // never the password → every Twilio call got 401. The fix must build a real Basic header.
        when(userCredentialService.getCredentialDataMap("user1", "twilio"))
                .thenReturn(Map.of("username", "AC0123456789abcdef", "password", "secrettoken"));
        HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("basic_auth", "Authorization", "username", null);

        HttpHeaders headers = service.prepareHeadersWithCredentials(
                twilioApi(), tool(TWILIO_PATH), "user1", "twilio", injection,
                Optional.of("AC0123456789abcdef"));

        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "AC0123456789abcdef:secrettoken".getBytes(StandardCharsets.UTF_8));
        assertThat(headers.getFirst("Authorization"))
                .as("Twilio Basic auth must send base64(AccountSid:AuthToken), never Bearer <AccountSid>")
                .isEqualTo(expected);
        // Pin the exact regression: the pre-fix bug sent the username as a Bearer token.
        assertThat(headers.getFirst("Authorization")).doesNotStartWith("Bearer ");
    }

    @Test
    @DisplayName("basic_auth strips a pasted trailing space/newline from username & password before encoding")
    void basicAuthStripsWhitespaceBeforeEncoding() {
        // The user repeatedly suspected a pasted trailing space/newline; without stripping it
        // corrupts the Base64 and silently 401s. The encoded value must match the clean pair.
        when(userCredentialService.getCredentialDataMap("user1", "twilio"))
                .thenReturn(Map.of("username", " AC0123456789abcdef\n", "password", "secrettoken \n"));
        HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("basic_auth", "Authorization", "username", null);

        HttpHeaders headers = service.prepareHeadersWithCredentials(
                twilioApi(), tool(TWILIO_PATH), "user1", "twilio", injection, Optional.empty());

        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "AC0123456789abcdef:secrettoken".getBytes(StandardCharsets.UTF_8));
        assertThat(headers.getFirst("Authorization")).isEqualTo(expected);
    }

    @Test
    @DisplayName("basic_auth with a missing username adds NO auth header (symmetry with missing password)")
    void basicAuthMissingUsernameSendsNoAuthHeader() {
        when(userCredentialService.getCredentialDataMap("user1", "twilio"))
                .thenReturn(Map.of("password", "secrettoken"));
        HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("basic_auth", "Authorization", "username", null);

        HttpHeaders headers = service.prepareHeadersWithCredentials(
                twilioApi(), tool(TWILIO_PATH), "user1", "twilio", injection, Optional.empty());

        assertThat(headers.containsKey("Authorization")).isFalse();
    }

    @Test
    @DisplayName("basic_auth with a missing password adds NO auth header (never send a half-built credential)")
    void basicAuthMissingPasswordSendsNoAuthHeader() {
        when(userCredentialService.getCredentialDataMap("user1", "twilio"))
                .thenReturn(Map.of("username", "AC0123456789abcdef"));
        HttpExecutionService.CredentialInjection injection =
                new HttpExecutionService.CredentialInjection("basic_auth", "Authorization", "username", null);

        HttpHeaders headers = service.prepareHeadersWithCredentials(
                twilioApi(), tool(TWILIO_PATH), "user1", "twilio", injection, Optional.empty());

        assertThat(headers.containsKey("Authorization"))
                .as("a basic_auth credential missing its password must not fall back to a bogus Bearer header")
                .isFalse();
    }

    @Test
    @DisplayName("FIX: body_field / url_variable injection adds NO Authorization header (no spurious Bearer leaking the credential)")
    void bodyFieldAndUrlVariableSuppressBearerFallback() {
        // Pre-fix, body_field (Authorize.Net merchantAuthentication.name) and url_variable
        // (Telegram/Firebase path vars) fell through to the legacy fallback → spurious
        // "Authorization: Bearer <credential>" (harmful on OAuth-gated hosts like Firebase).
        for (String type : java.util.List.of("body_field", "url_variable")) {
            HttpExecutionService.CredentialInjection injection =
                    new HttpExecutionService.CredentialInjection(type, "merchantAuthentication.name", "api_login_id", null);

            HttpHeaders headers = service.prepareHeadersWithCredentials(
                    twilioApi(), tool(TWILIO_PATH), "user1", "x", injection, Optional.of("SECRET_LOGIN_ID"));

            assertThat(headers.containsKey("Authorization"))
                    .as("%s-injected credential must not be stamped into a bogus Authorization header", type)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("field-aware fallback reads the named field from the data map when the primary value is empty")
    void fieldAwareFallbackReadsNamedField() throws Exception {
        when(userCredentialService.getCredentialDataMap("user1", "x")).thenReturn(Map.of("application_id", "APP123"));
        HttpExecutionService.CredentialInjection inj =
                new HttpExecutionService.CredentialInjection("header", "X-App-Id", "application_id", null);

        Optional<String> result = invokeApplyFieldAwareFallback(inj, Optional.empty(), "user1", "x");

        assertThat(result).contains("APP123");
    }

    @Test
    @DisplayName("field-aware fallback is a NO-OP when the primary value is present (OAuth/api_key) - never touches the data map")
    void fieldAwareFallbackNoOpWhenPrimaryPresent() throws Exception {
        HttpExecutionService.CredentialInjection inj =
                new HttpExecutionService.CredentialInjection("header", "Authorization", "access_token", null);

        Optional<String> result = invokeApplyFieldAwareFallback(inj, Optional.of("realtoken"), "user1", "x");

        assertThat(result).contains("realtoken");
        verify(userCredentialService, never()).getCredentialDataMap(anyString(), anyString());
    }

    @SuppressWarnings("unchecked")
    private Optional<String> invokeApplyFieldAwareFallback(Object injection, Optional<String> credentialValue,
                                                           String userId, String credentialName) throws Exception {
        Method m = HttpExecutionService.class.getDeclaredMethod("applyFieldAwareFallback",
                HttpExecutionService.CredentialInjection.class, Optional.class, String.class, String.class);
        m.setAccessible(true);
        return (Optional<String>) m.invoke(service, injection, credentialValue, userId, credentialName);
    }

    @Test
    @DisplayName("FIX: parseInjectionMetadata extracts ALL customConfig.fields (Algolia: 2 headers + 1 url_variable)")
    void parseInjectionMetadataExtractsCustomFields() throws Exception {
        String metadata = "{\"field\":\"application_id\",\"variant\":\"custom\","
                + "\"injection\":{\"key\":\"X-Algolia-Application-Id\",\"type\":\"header\"},"
                + "\"fakeAuth\":{\"customConfig\":{\"fields\":["
                + "{\"name\":\"application_id\",\"injectionKey\":\"X-Algolia-Application-Id\",\"injectionType\":\"header\"},"
                + "{\"name\":\"api_key\",\"injectionKey\":\"X-Algolia-API-Key\",\"injectionType\":\"header\"},"
                + "{\"name\":\"applicationId\",\"injectionKey\":\"applicationId\",\"injectionType\":\"url_variable\"}"
                + "]}}}";

        HttpExecutionService.CredentialInjection inj = invokeParseInjectionMetadata(metadata);

        assertThat(inj.fields()).hasSize(3);
        assertThat(inj.fields()).extracting(HttpExecutionService.CredentialInjection::key)
                .containsExactly("X-Algolia-Application-Id", "X-Algolia-API-Key", "applicationId");
    }

    @Test
    @DisplayName("blocker-2 isolation: a non-custom (oauth2) row parses to EMPTY fields → multi-field pass stays a no-op")
    void parseInjectionMetadataNoCustomFieldsForNonCustom() throws Exception {
        // DocuSign etc. are oauth2 + custom; the oauth2 variant row has no customConfig (only
        // oauth2Config) → empty fields() → applyCustomFieldHeaderInjections no-ops for oauth2 users.
        String metadata = "{\"field\":\"access_token\",\"variant\":\"oauth2\","
                + "\"injection\":{\"key\":\"Authorization\",\"type\":\"header\",\"prefix\":\"Bearer \"},"
                + "\"fakeAuth\":{\"oauth2Config\":{\"tokenUrl\":\"https://x/token\"}}}";

        HttpExecutionService.CredentialInjection inj = invokeParseInjectionMetadata(metadata);

        assertThat(inj.fields()).isEmpty();
        assertThat(inj.key()).isEqualTo("Authorization");
        assertThat(inj.prefix()).isEqualTo("Bearer ");
    }

    @Test
    @DisplayName("FIX: a multi-field custom integration gets ALL its headers from customConfig (Algolia app-id + api-key), field-aware")
    void customFieldHeaderInjectionsAllApplied() throws Exception {
        // Pre-fix the LIMIT-1 top-level injection sent only X-Algolia-Application-Id; X-Algolia-API-Key
        // (the second customConfig field) was dropped → Algolia 401.
        when(userCredentialService.getCredentialDataMap("user1", "algolia"))
                .thenReturn(Map.of("application_id", "APP123", "api_key", "SECRETKEY"));
        HttpExecutionService.CredentialInjection inj = new HttpExecutionService.CredentialInjection(
                "header", "X-Algolia-Application-Id", "application_id", null,
                List.of(
                        new HttpExecutionService.CredentialInjection("header", "X-Algolia-Application-Id", "application_id", null),
                        new HttpExecutionService.CredentialInjection("header", "X-Algolia-API-Key", "api_key", null),
                        new HttpExecutionService.CredentialInjection("url_variable", "applicationId", "applicationId", null)));
        HttpHeaders headers = new HttpHeaders();

        invokeApplyCustomFieldHeaderInjections(headers, inj, "user1", "algolia", Optional.empty());

        assertThat(headers.getFirst("X-Algolia-Application-Id")).isEqualTo("APP123");
        assertThat(headers.getFirst("X-Algolia-API-Key")).isEqualTo("SECRETKEY");
        // url_variable field is NOT a header - it's resolved into the URL elsewhere.
        assertThat(headers.containsKey("applicationId")).isFalse();
    }

    @Test
    @DisplayName("custom-field pass is a NO-OP for single/non-custom injections (no customConfig.fields)")
    void customFieldPassNoOpForSingleInjection() throws Exception {
        HttpExecutionService.CredentialInjection inj =
                new HttpExecutionService.CredentialInjection("header", "Authorization", "access_token", null);
        HttpHeaders headers = new HttpHeaders();

        invokeApplyCustomFieldHeaderInjections(headers, inj, "user1", "x", Optional.of("tok"));

        assertThat(headers.isEmpty()).isTrue();
        verify(userCredentialService, never()).getCredentialDataMap(anyString(), anyString());
    }

    @Test
    @DisplayName("multi-field custom: a primary-token field reuses primaryValue (OAuth-refresh-safe), not the raw map")
    void customFieldPrimaryTokenUsesPrimaryValue() throws Exception {
        when(userCredentialService.getCredentialDataMap("user1", "datadog"))
                .thenReturn(Map.of("api_key", "STALE", "app_key", "APPKEY"));
        HttpExecutionService.CredentialInjection inj = new HttpExecutionService.CredentialInjection(
                "header", "DD-API-KEY", "api_key", null,
                List.of(
                        new HttpExecutionService.CredentialInjection("header", "DD-API-KEY", "api_key", null),
                        new HttpExecutionService.CredentialInjection("header", "DD-APPLICATION-KEY", "app_key", null)));
        HttpHeaders headers = new HttpHeaders();

        invokeApplyCustomFieldHeaderInjections(headers, inj, "user1", "datadog", Optional.of("REFRESHED"));

        assertThat(headers.getFirst("DD-API-KEY")).isEqualTo("REFRESHED");
        assertThat(headers.getFirst("DD-APPLICATION-KEY")).isEqualTo("APPKEY");
    }

    private HttpExecutionService.CredentialInjection invokeParseInjectionMetadata(String metadata) throws Exception {
        Method m = HttpExecutionService.class.getDeclaredMethod("parseInjectionMetadata", Object.class);
        m.setAccessible(true);
        return (HttpExecutionService.CredentialInjection) m.invoke(service, metadata);
    }

    private void invokeApplyCustomFieldHeaderInjections(HttpHeaders headers, Object injection, String userId,
                                                        String credentialName, Optional<String> primaryValue) throws Exception {
        Method m = HttpExecutionService.class.getDeclaredMethod("applyCustomFieldHeaderInjections",
                HttpHeaders.class, HttpExecutionService.CredentialInjection.class, String.class, String.class, Optional.class);
        m.setAccessible(true);
        m.invoke(service, headers, injection, userId, credentialName, primaryValue);
    }

    @Test
    @DisplayName("REGRESSION: the original {{VAR}} double-brace form is rejected as a Malformed URL")
    void doubleBraceFormWasMalformed() {
        // Pre-fix: the param default {{TWILIO_ACCOUNT_SID}} ended up verbatim in the URL.
        // The single-brace SSRF placeholder substitution rewrote only the inner {...},
        // leaving stray outer braces that URI.create() rejects. This is exactly the prod
        // error "Malformed URL: …/Accounts/{{TWILIO_ACCOUNT_SID}}/Messages.json".
        String doubleBrace = "https://api.twilio.com/2010-04-01/Accounts/{{TWILIO_ACCOUNT_SID}}/Messages.json";
        assertThatThrownBy(() -> UrlSafetyValidator.validateUrl(doubleBrace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed URL");
    }
}
