package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogSeedCredentialService")
class CatalogSeedCredentialServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApiToolRepository apiToolRepository;

    private CatalogSeedCredentialService service;

    @BeforeEach
    void setUp() {
        service = new CatalogSeedCredentialService(jdbcTemplate, apiToolRepository);
    }

    private SeedManifest.SeedSpec createSpec(String credentialName, String authType) {
        SeedManifest.SeedSpec spec = new SeedManifest.SeedSpec();
        spec.setId("test");
        spec.setCredentialName(credentialName);
        spec.setAuthType(authType);
        spec.setIconSlug("icon");
        return spec;
    }

    @Test
    @DisplayName("a seed spec's declared authIn/authHeaderName decides the placement")
    void aSeedSpecPlacementIsHonoured() {
        // authIn and authHeaderName have been in the manifest all along and were read by nobody,
        // so every seeded credential went out as an X-API-Key header whatever the spec declared.
        // The one seed that ships says authIn "query", authHeaderName "appid", which is exactly
        // how OpenWeatherMap takes its key and exactly what it was not getting.
        SeedManifest.SeedSpec spec = createSpec("openweathermap", "apiKey");
        spec.setAuthIn("query");
        spec.setAuthHeaderName("appid");

        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, spec);

        JsonNode injection = parse(captureMetadata(toolId, credentialId)).path("injection");
        assertEquals("query", injection.path("type").asText(), "the spec asked for query injection");
        assertEquals("appid", injection.path("key").asText(), "and named the parameter");
    }

    @Test
    @DisplayName("a seed spec declaring a header name uses it instead of the X-API-Key default")
    void aSeedSpecHeaderNameIsHonoured() {
        SeedManifest.SeedSpec spec = createSpec("someapi", "apiKey");
        spec.setAuthIn("header");
        spec.setAuthHeaderName("X-Custom-Key");

        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, spec);

        JsonNode injection = parse(captureMetadata(toolId, credentialId)).path("injection");
        assertEquals("header", injection.path("type").asText());
        assertEquals("X-Custom-Key", injection.path("key").asText());
    }

    @Test
    @DisplayName("a seed spec that declares no placement keeps the default for its auth type")
    void aSeedSpecWithoutAPlacementIsUnchanged() {
        // The no-op guarantee for every seed that declares nothing, which is all but one.
        SeedManifest.SeedSpec spec = createSpec("someapi", "apiKey");

        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, spec);

        JsonNode injection = parse(captureMetadata(toolId, credentialId)).path("injection");
        assertEquals("header", injection.path("type").asText());
        assertEquals("X-API-Key", injection.path("key").asText());
    }

    @Test
    @DisplayName("an oauth2 block is stored under metadata.oauth2Config, where the OAuth engine reads it")
    void theOAuthBlockIsWrittenToTheCredentialMetadata() {
        // The single missing write that made oauth2 unusable for a custom API: the provider's
        // endpoints are resolved from the credential template's metadata, and this path wrote no
        // metadata at all, so the connection could never start.
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), metadataCaptor.capture()))
                .thenReturn(credentialId);

        ObjectNode oauth = MAPPER.createObjectNode();
        oauth.put("authorizationUrl", "https://example.com/oauth/authorize");
        oauth.put("tokenUrl", "https://example.com/oauth/token");
        service.linkCredentials(apiId, "myapi", "oauth2", "icon", null, null, oauth);

        JsonNode metadata = parse(metadataCaptor.getValue());
        assertEquals("https://example.com/oauth/token",
                metadata.path("oauth2Config").path("tokenUrl").asText(),
                "the OAuth engine reads metadata.oauth2Config and nothing else");
    }

    @Test
    @DisplayName("a declared placement is kept in metadata so it can be read back and re-submitted")
    void thePlacementIsAlsoRecordedForReadback() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), metadataCaptor.capture()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "apikey", "icon", null,
                new CatalogSeedCredentialService.ApiKeyConfig("header", "X-Api-Token", null, null, ""), null);

        JsonNode declared = parse(metadataCaptor.getValue()).path("apiKeyConfig");
        assertEquals("X-Api-Token", declared.path("headerName").asText());
        assertTrue(declared.has("prefix"), "a declared empty prefix is part of the declaration");
        assertEquals("", declared.path("prefix").asText());
    }

    @Test
    @DisplayName("deleting a credential removes its row whatever variant it was written under")
    void deleteIsNotFilteredByVariant() {
        // A variant filter was tried here and removed: the native templates actually at risk
        // (imap and smtp, both present on production) are THEMSELVES variant='primary', so it
        // protected nothing, while it stopped removing a custom API's own row when that row
        // predates V103 and sits under its auth type. Ownership is the right question and the
        // CALLER answers it before calling, so this stays a plain delete by name.
        service.deleteCredentialByName("myapi");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), eq("myapi"));
        for (String sql : sqlCaptor.getAllValues()) {
            assertFalse(sql.contains("variant"),
                    "a variant filter here guards nothing and orphans pre-V103 rows: " + sql);
        }
    }

    @Test
    @DisplayName("should skip when no credential name")
    void shouldSkipWhenNoCredentialName() {
        SeedManifest.SeedSpec spec = createSpec(null, "apiKey");
        UUID apiId = UUID.randomUUID();

        service.linkCredentials(apiId, spec);

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(apiToolRepository);
    }

    @Test
    @DisplayName("should skip when no auth type")
    void shouldSkipWhenNoAuthType() {
        SeedManifest.SeedSpec spec = createSpec("myCredential", null);
        UUID apiId = UUID.randomUUID();

        service.linkCredentials(apiId, spec);

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(apiToolRepository);
    }

    @Test
    @DisplayName("should upsert credential and link tools")
    void shouldUpsertAndLink() {
        SeedManifest.SeedSpec spec = createSpec("openweathermap", "apiKey");
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));

        // Mock the upsert credential returning a UUID
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, spec);

        // Verify credential upsert was called
        verify(jdbcTemplate).queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                eq("openweathermap"), eq("openweathermap"), eq("apiKey"), any(String.class), eq("icon"), any(), any());

        // Verify tool_credentials link was created with correct injection metadata for apiKey
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                eq(toolId), eq(credentialId), eq("openweathermap"), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"field\":\"api_key\""), "apiKey must use field=api_key");
        assertTrue(metadata.contains("\"injection\""), "metadata must contain 'injection' sub-object");
        assertTrue(metadata.contains("\"type\":\"header\""), "apiKey must inject as header");
        assertTrue(metadata.contains("\"key\":\"X-API-Key\""), "apiKey must use X-API-Key header");
    }

    @Test
    @DisplayName("should upsert and link via direct params overload with bearer metadata")
    void shouldUpsertAndLinkViaDirectParams() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));

        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "bearer", "myicon");

        verify(jdbcTemplate).queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                eq("myapi"), eq("myapi"), eq("bearer"), any(String.class), eq("myicon"), any(), any());

        // Verify bearer metadata: field=access_token, injection type=header, key=Authorization
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                eq(toolId), eq(credentialId), eq("myapi"), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"field\":\"access_token\""), "bearer must use field=access_token");
        assertTrue(metadata.contains("\"type\":\"header\""), "bearer must inject as header");
        assertTrue(metadata.contains("\"key\":\"Authorization\""), "bearer must use Authorization header");
    }

    @Test
    @DisplayName("should use apikey injection metadata for apiKey auth type")
    void shouldUseApiKeyInjectionMetadata() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "apiKey", "icon");

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), any(UUID.class), anyString(), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"field\":\"api_key\""), "apiKey must use field=api_key");
        assertTrue(metadata.contains("\"type\":\"header\""), "apiKey must inject as header");
        assertTrue(metadata.contains("\"key\":\"X-API-Key\""), "apiKey must use X-API-Key header");
    }

    @Test
    @DisplayName("an unrecognised auth type keeps the X-API-Key fallback the seed path relies on, and is refused earlier on the paths that accept user input")
    void shouldUseDefaultInjectionMetadataForUnknownAuthType() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "custom_auth", "icon");

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), any(UUID.class), anyString(), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        // Default should NOT use Authorization (which triggers Bearer prefix)
        assertTrue(metadata.contains("\"key\":\"X-API-Key\""), "default must use X-API-Key, not Authorization");
        assertTrue(metadata.contains("\"field\":\"api_key\""), "default must use field=api_key");
    }

    @Test
    @DisplayName("basic auth injects as basic_auth and asks for username + password, not a single API key")
    void basicAuthUsesTheBasicAuthInjectionAndATwoFieldForm() throws Exception {
        // This test used to assert the OPPOSITE, justified by "no Basic prefix support in
        // HttpExecutionService". That stopped being true when the basic_auth branch was added
        // there, and nothing brought this assertion along - so it went on certifying the broken
        // behaviour: a basic API got a one-field "API Key" form and an X-API-Key header, which the
        // engine's basic_auth path can never read. It logged "username/password missing in
        // credential data" and sent NO auth header at all.
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        ArgumentCaptor<String> propertiesCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), propertiesCaptor.capture(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "basic", "icon");

        JsonNode injection = parse(captureMetadata(toolId, credentialId)).path("injection");
        assertEquals("basic_auth", injection.path("type").asText(),
                "basic must use the engine's basic_auth injection");

        JsonNode properties = MAPPER.readTree(propertiesCaptor.getValue());
        assertTrue(properties.has("username"), "the connection must ask for a username");
        assertTrue(properties.has("password"), "the connection must ask for a password");
        assertFalse(properties.has("api_key"),
                "a single API key field cannot satisfy basic auth and must not be offered");
    }

    @ParameterizedTest(name = "authType ''{0}'' injects into Authorization")
    @ValueSource(strings = {"bearer", "bearer_token", "bearerToken", "bearer-token", "BEARER"})
    @DisplayName("every spelling of bearer injects into Authorization, including bearer_token")
    void everyBearerSpellingInjectsIntoAuthorization(String spelling) {
        // The defect a customer spent hours on. The switch matched the RAW string, so only the
        // exact word "bearer" reached the Authorization arm. "bearer_token" - the spelling
        // catalog(action='schema') reports for every built-in API, and the one in every seed
        // file - fell through to the X-API-Key default. The token went out in a header the
        // provider ignores, the provider answered 401, and registration reported success.
        //
        // Parameterized rather than looped: @Mock fields are rebuilt per test METHOD, so a loop
        // shares one set of mocks across iterations and reports only the first failing spelling.
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", spelling, "icon");

        JsonNode metadata = parse(captureMetadata(toolId, credentialId));
        assertEquals("Authorization", metadata.path("injection").path("key").asText(),
                "spelling '" + spelling + "' must inject into Authorization");
        assertEquals("access_token", metadata.path("field").asText(),
                "spelling '" + spelling + "' must read the access_token field");
    }

    @Test
    @DisplayName("a declared apiKeyConfig places the credential where the provider expects it")
    void declaredApiKeyConfigOverridesTheDefaultPlacement() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "apikey", "icon", null,
                new CatalogSeedCredentialService.ApiKeyConfig("header", "X-Api-Token", null, "token", null));

        JsonNode metadata = parse(captureMetadata(toolId, credentialId));
        assertEquals("header", metadata.path("injection").path("type").asText());
        assertEquals("X-Api-Token", metadata.path("injection").path("key").asText(),
                "a declared header name must be used verbatim");
        assertEquals("token", metadata.path("field").asText(),
                "a declared keyName names the field the connection asks the user to fill");
    }

    @Test
    @DisplayName("a query-injected credential is declared as such and reserves no header")
    void queryLocationInjectsIntoTheQueryString() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        CatalogSeedCredentialService.ApiKeyConfig queryConfig =
                new CatalogSeedCredentialService.ApiKeyConfig("query", null, "api_key", null, null);
        service.linkCredentials(apiId, "myapi", "apikey", "icon", null, queryConfig);

        JsonNode injection = parse(captureMetadata(toolId, credentialId)).path("injection");
        assertEquals("query", injection.path("type").asText());
        assertEquals("api_key", injection.path("key").asText());
        assertNull(CatalogSeedCredentialService.credentialHeaderName("apikey", queryConfig),
                "a query-injected credential occupies no header, so none must be reserved");
    }

    @Test
    @DisplayName("a declared empty prefix survives; an absent one is omitted so the engine can default it")
    void anExplicitEmptyPrefixIsDistinctFromAnAbsentOne() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "bearer", "icon", null,
                new CatalogSeedCredentialService.ApiKeyConfig(null, null, null, null, ""));

        JsonNode injection = parse(captureMetadata(toolId, credentialId)).path("injection");
        assertTrue(injection.has("prefix"), "a declared empty prefix must be emitted, not dropped");
        assertEquals("", injection.path("prefix").asText(),
                "an empty prefix means 'send the value raw'; collapsing it to absent restores 'Bearer '");
    }

    @Test
    @DisplayName("an absent prefix is not written, leaving the engine free to default it")
    void anAbsentPrefixIsNotWritten() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "bearer", "icon");

        JsonNode injection = parse(captureMetadata(toolId, credentialId)).path("injection");
        assertFalse(injection.has("prefix"),
                "with nothing declared the metadata must stay silent on prefix, which is how "
                        + "Authorization keeps its historical 'Bearer ' default");
    }

    @ParameterizedTest(name = "authType ''{0}'': reserved header == injected header")
    @ValueSource(strings = {"bearer", "bearer_token", "oauth2", "apikey", "api_key",
            "basic", "basic_auth", "whatever"})
    @DisplayName("the reserved header and the injected header are one derivation, for every spelling")
    void reservedHeaderAlwaysMatchesTheInjectedHeader(String authType) {
        // These were two independent switches in two classes and they drifted: both sent
        // bearer_token to the X-API-Key default, so the header the secret actually travels in was
        // left unreserved. Pin them against EACH OTHER rather than against a hand-written
        // expectation, so a future change to one cannot silently un-guard the other.
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", authType, "icon");

        JsonNode injection = parse(captureMetadata(toolId, credentialId)).path("injection");
        // basic_auth carries no header name of its own: the engine builds the Basic scheme on
        // Authorization, which is what the reservation must therefore claim.
        String injectedInto = "basic_auth".equals(injection.path("type").asText())
                ? "authorization"
                : injection.path("key").asText().toLowerCase(java.util.Locale.ROOT);

        assertEquals(injectedInto,
                CatalogSeedCredentialService.credentialHeaderName(authType, null),
                "auth type '" + authType + "': the reserved header must be the injected one");
    }

    /** The metadata blob written for the single tool link of this API. */
    private String captureMetadata(UUID toolId, UUID credentialId) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                eq(toolId), eq(credentialId), anyString(), eq("primary"), captor.capture());
        return captor.getValue();
    }

    private JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("credential metadata is not valid JSON: " + json, e);
        }
    }

    @Test
    @DisplayName("should skip direct params when credential name is blank")
    void shouldSkipDirectParamsWhenBlank() {
        UUID apiId = UUID.randomUUID();

        service.linkCredentials(apiId, "", "bearer", "icon");

        verifyNoInteractions(apiToolRepository);
    }

    @Test
    @DisplayName("should delete tool_credentials and credential by name")
    void shouldDeleteCredentialByName() {
        when(jdbcTemplate.update(contains("DELETE FROM catalog.tool_credentials"), eq("myapi")))
                .thenReturn(3);
        when(jdbcTemplate.update(contains("DELETE FROM catalog.credentials"), eq("myapi")))
                .thenReturn(1);

        service.deleteCredentialByName("myapi");

        // Verify tool_credentials deleted first, then credentials template
        var inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).update(contains("DELETE FROM catalog.tool_credentials"), eq("myapi"));
        inOrder.verify(jdbcTemplate).update(contains("DELETE FROM catalog.credentials"), eq("myapi"));
    }

    @Test
    @DisplayName("should skip delete when credential name is null")
    void shouldSkipDeleteWhenNull() {
        service.deleteCredentialByName(null);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("upsert SQL targets (credential_name, variant) UNIQUE - regression for V103 multi-variant schema; pre-fix used ON CONFLICT (credential_name) which Postgres rejects with bad SQL grammar after V103 dropped that constraint")
    void upsertSqlTargetsCredentialNameVariantUniqueConstraint() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(anyString(), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "bearer", "icon");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any());
        String sql = sqlCaptor.getValue();

        assertTrue(sql.contains("INSERT INTO catalog.credentials"),
                "SQL must insert into catalog.credentials");
        assertTrue(sql.contains("variant"),
                "SQL must include the variant column (post-V103 NOT NULL)");
        assertTrue(sql.contains("'primary'"),
                "Custom-API path is single-variant by design - must hard-code variant='primary'");
        assertTrue(sql.contains("ON CONFLICT (credential_name, variant)"),
                "ON CONFLICT must target (credential_name, variant) - post-V103 only this UNIQUE exists; "
                        + "the legacy ON CONFLICT (credential_name) makes Postgres throw bad SQL grammar in prod");
        assertFalse(sql.matches("(?s).*ON\\s+CONFLICT\\s*\\(\\s*credential_name\\s*\\).*"),
                "Must NOT reference the legacy ON CONFLICT (credential_name) clause");
    }

    @Test
    @DisplayName("the upsert MERGES metadata instead of replacing it, so keys it does not own survive")
    void upsertMergesMetadataRatherThanReplacingIt() {
        // This class emits only oauth2Config and apiKeyConfig. A row written by another path
        // carries keys it depends on - the importer's source marker, which the CE bootstrap uses
        // as its DELETE predicate, plus fakeAuth, rateLimits, authVariants and the
        // stableCredential* set. Assigning EXCLUDED.metadata would drop every one of them on a
        // name collision, with nothing to show for it. Asserted on the SQL because the statement
        // is the contract here, the same way the ON CONFLICT target is asserted below.
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(anyString(), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "bearer", "icon");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any());
        String sql = sqlCaptor.getValue();

        assertTrue(sql.contains("metadata = catalog.credentials.metadata || EXCLUDED.metadata"),
                "metadata must be merged into the existing row, not assigned over it: " + sql);
        assertFalse(sql.contains("metadata = EXCLUDED.metadata"),
                "a bare assignment discards every key this class does not emit");
    }

    @Test
    @DisplayName("should link multiple tools to same credential")
    void shouldLinkMultipleTools() {
        SeedManifest.SeedSpec spec = createSpec("myApi", "bearer");
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();

        ApiToolEntity tool1 = new ApiToolEntity();
        tool1.setId(UUID.randomUUID());
        ApiToolEntity tool2 = new ApiToolEntity();
        tool2.setId(UUID.randomUUID());
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool1, tool2));

        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, spec);

        // Should link both tools
        verify(jdbcTemplate, times(2)).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), eq(credentialId), eq("myApi"), eq("primary"), any(String.class));
    }
}
