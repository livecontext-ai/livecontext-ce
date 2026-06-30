package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.config.GlobalExceptionHandler;
import com.apimarketplace.credential.client.CredentialClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialTemplateController Integration Tests")
class CredentialTemplateControllerIntegrationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private CredentialClient credentialClient;

    private MockMvc mockMvc;

    private static final UUID CREDENTIAL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Default: auth-service reports no disabled variants, so the existing assertions
        // (which predate variant filtering) keep passing unchanged. Tests that exercise
        // the filter explicitly re-stub this per-method.
        lenient().when(credentialClient.listPlatformCredentials()).thenReturn(List.of());
        CredentialTemplateController controller = new CredentialTemplateController(jdbcTemplate, credentialClient);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Map<String, Object> createSampleCredential() {
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("id", CREDENTIAL_ID);
        credential.put("credential_name", "github_oauth");
        credential.put("display_name", "GitHub OAuth");
        credential.put("description", "OAuth credentials for GitHub API");
        credential.put("credential_type", "oauth2");
        credential.put("auth_type", "oauth2_authorization_code");
        credential.put("test_endpoint", "https://api.github.com/user");
        credential.put("documentation_url", "https://docs.github.com/en/apps/oauth-apps");
        credential.put("icon_url", "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png");
        credential.put("icon_slug", "github");
        credential.put("properties", "{}");
        credential.put("extends_", null);
        credential.put("metadata", "{}");
        credential.put("created_at", new java.sql.Timestamp(System.currentTimeMillis()));
        credential.put("updated_at", new java.sql.Timestamp(System.currentTimeMillis()));
        return credential;
    }

    @Nested
    @DisplayName("GET /api/catalog/credentials")
    class GetCredentialTemplates {

        @Test
        @DisplayName("should return paginated credential templates with default parameters")
        void shouldReturnPaginatedCredentials() throws Exception {
            Map<String, Object> credential = createSampleCredential();
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(List.of(credential));

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials", hasSize(1)))
                    .andExpect(jsonPath("$.credentials[0].credential_name").value("github_oauth"))
                    .andExpect(jsonPath("$.credentials[0].display_name").value("GitHub OAuth"))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.pageSize").value(20))
                    .andExpect(jsonPath("$.totalItems").value(1));
        }

        @Test
        @DisplayName("should support custom page and pageSize parameters")
        void shouldSupportCustomPagination() throws Exception {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(50);
            when(jdbcTemplate.queryForList(anyString(), eq(10), eq(10))).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials")
                            .param("page", "2")
                            .param("pageSize", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(2))
                    .andExpect(jsonPath("$.pageSize").value(10))
                    .andExpect(jsonPath("$.totalItems").value(50))
                    .andExpect(jsonPath("$.totalPages").value(5))
                    .andExpect(jsonPath("$.hasNext").value(true))
                    .andExpect(jsonPath("$.hasPrevious").value(true));
        }

        @Test
        @DisplayName("should support search parameter")
        void shouldSupportSearch() throws Exception {
            Map<String, Object> credential = createSampleCredential();
            String searchPattern = "%github%";
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                    eq(searchPattern), eq(searchPattern), eq(searchPattern), eq(searchPattern)))
                    .thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(),
                    eq(searchPattern), eq(searchPattern), eq(searchPattern), eq(searchPattern),
                    eq(20), eq(0)))
                    .thenReturn(List.of(credential));

            mockMvc.perform(get("/api/catalog/credentials")
                            .param("search", "github"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials", hasSize(1)));
        }

        @Test
        @DisplayName("should return empty list when no credentials match")
        void shouldReturnEmptyList() throws Exception {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials", hasSize(0)))
                    .andExpect(jsonPath("$.totalItems").value(0));
        }

        @Test
        @DisplayName("should calculate pagination metadata correctly")
        void shouldCalculatePaginationCorrectly() throws Exception {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(25);
            when(jdbcTemplate.queryForList(anyString(), eq(10), eq(0))).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials")
                            .param("page", "1")
                            .param("pageSize", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.hasNext").value(true))
                    .andExpect(jsonPath("$.hasPrevious").value(false));
        }

        @Test
        @DisplayName("should return hasPrevious false for first page")
        void shouldReturnHasPreviousFalseForFirstPage() throws Exception {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(10);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasPrevious").value(false));
        }

        @Test
        @DisplayName("should return hasNext false for last page")
        void shouldReturnHasNextFalseForLastPage() throws Exception {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(5);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasNext").value(false));
        }

        @Test
        @DisplayName("should return 500 on database error")
        void shouldReturn500OnDatabaseError() throws Exception {
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("Failed to fetch credential templates"));
        }

        @Test
        @DisplayName("Surfaces the variants aggregate so the UI can show a N-auth-methods chip without a second round-trip")
        void surfacesVariantsArray() throws Exception {
            Map<String, Object> alchemy = createSampleCredential();
            alchemy.put("credential_name", "alchemy");
            alchemy.put("display_name", "Alchemy");
            alchemy.put("auth_type", "api_key");
            alchemy.put("variant", "api_key");
            List<Map<String, String>> variants = List.of(
                    Map.of("variant", "api_key", "auth_type", "api_key"),
                    Map.of("variant", "bearer_token", "auth_type", "bearer_token"));
            alchemy.put("variants", variants);

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(List.of(alchemy));

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials", hasSize(1)))
                    .andExpect(jsonPath("$.credentials[0].credential_name").value("alchemy"))
                    .andExpect(jsonPath("$.credentials[0].variants", hasSize(2)))
                    .andExpect(jsonPath("$.credentials[0].variants[0].variant").value("api_key"))
                    .andExpect(jsonPath("$.credentials[0].variants[1].variant").value("bearer_token"));
        }

        @Test
        @DisplayName("Drops an admin-disabled (integration,variant) pair from the variants[] array so users stop seeing an auth method the admin took offline")
        void hidesAdminDisabledVariantFromList() throws Exception {
            Map<String, Object> ably = createSampleCredential();
            ably.put("credential_name", "ably");
            ably.put("display_name", "Ably");
            ably.put("auth_type", "basic_auth");
            ably.put("variant", "basic_auth");
            ably.put("variants", new ArrayList<>(List.of(
                    Map.of("variant", "basic_auth", "auth_type", "basic_auth"),
                    Map.of("variant", "bearer_token", "auth_type", "bearer_token"))));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(List.of(ably));

            // Admin disabled ably::bearer_token in auth.platform_credentials.
            // hasApiKey=true marks the row as actually configured (admin saved a
            // secret then disabled) - the May 2026 placeholder-gate requires a
            // configured row before the disable takes user-visible effect.
            com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto disabled =
                    new com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto();
            disabled.setName("ably");
            disabled.setVariant("bearer_token");
            disabled.setEnabled(false);
            disabled.setHasApiKey(true);
            when(credentialClient.listPlatformCredentials()).thenReturn(List.of(disabled));

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials", hasSize(1)))
                    .andExpect(jsonPath("$.credentials[0].variants", hasSize(1)))
                    .andExpect(jsonPath("$.credentials[0].variants[0].variant").value("basic_auth"));
        }

        @Test
        @DisplayName("Keeps an unconfigured-disabled placeholder row from hiding its integration - phantom-row regression for the May 2026 Salesforce incident where 81 OAuth2 APIs were silently hidden from end users")
        void unconfiguredDisabledPlaceholderDoesNotHideIntegration() throws Exception {
            Map<String, Object> salesforce = createSampleCredential();
            salesforce.put("credential_name", "salesforce");
            salesforce.put("display_name", "Salesforce");
            salesforce.put("auth_type", "oauth2");
            salesforce.put("variant", "oauth2");
            salesforce.put("variants", new ArrayList<>(List.of(
                    Map.of("variant", "oauth2", "auth_type", "oauth2"))));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(List.of(salesforce));

            // Phantom placeholder synthesized by PlatformCredentialRepository
            // .setEnabledForVariant when the admin clicks the per-variant toggle
            // off before ever saving secrets. All hasX flags stay null (default)
            // → isConfigured()==false → must NOT contribute to disabledKeys.
            // Pre-fix: this row hid Salesforce entirely; post-fix: ignored.
            com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto phantom =
                    new com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto();
            phantom.setName("salesforce");
            phantom.setVariant("oauth2");
            phantom.setEnabled(false);
            // No setHasClientSecret / setHasApiKey / setHasBasicAuth /
            // setHasCustomFields - exactly the prod row shape.
            when(credentialClient.listPlatformCredentials()).thenReturn(List.of(phantom));

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials", hasSize(1)))
                    .andExpect(jsonPath("$.credentials[0].credential_name").value("salesforce"))
                    .andExpect(jsonPath("$.credentials[0].variants", hasSize(1)))
                    .andExpect(jsonPath("$.credentials[0].variants[0].variant").value("oauth2"));
        }

        @Test
        @DisplayName("Mixed configured-disabled + phantom-disabled on a multi-variant API - the configured variant drops, the phantom is ignored, the other variants survive")
        void multiVariantMixedConfiguredAndPhantomDisable() throws Exception {
            // Three-variant API: basic_auth is admin-disabled-with-secrets (must drop),
            // bearer_token is admin-disabled-phantom (must be ignored), api_key has no
            // disable row at all (must stay). End state: variants = [api_key].
            Map<String, Object> ably = createSampleCredential();
            ably.put("credential_name", "ably");
            ably.put("display_name", "Ably");
            ably.put("variants", new ArrayList<>(List.of(
                    Map.of("variant", "api_key", "auth_type", "api_key"),
                    Map.of("variant", "basic_auth", "auth_type", "basic_auth"),
                    Map.of("variant", "bearer_token", "auth_type", "bearer_token"))));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(List.of(ably));

            com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto configuredDisabled =
                    new com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto();
            configuredDisabled.setName("ably");
            configuredDisabled.setVariant("basic_auth");
            configuredDisabled.setEnabled(false);
            configuredDisabled.setHasBasicAuth(true);

            com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto phantomDisabled =
                    new com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto();
            phantomDisabled.setName("ably");
            phantomDisabled.setVariant("bearer_token");
            phantomDisabled.setEnabled(false);
            // No hasX - phantom shape.

            when(credentialClient.listPlatformCredentials())
                    .thenReturn(List.of(configuredDisabled, phantomDisabled));

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials", hasSize(1)))
                    .andExpect(jsonPath("$.credentials[0].variants", hasSize(2)))
                    .andExpect(jsonPath("$.credentials[0].variants[*].variant",
                            org.hamcrest.Matchers.containsInAnyOrder("api_key", "bearer_token")));
        }

        @Test
        @DisplayName("Drops the whole row when every variant has been admin-disabled - pre-multivariant behaviour where toggling OAuth2 off removed the credential entirely")
        void dropsRowWhenAllVariantsDisabled() throws Exception {
            Map<String, Object> ably = createSampleCredential();
            ably.put("credential_name", "ably");
            ably.put("display_name", "Ably");
            ably.put("variants", new ArrayList<>(List.of(
                    Map.of("variant", "basic_auth", "auth_type", "basic_auth"),
                    Map.of("variant", "bearer_token", "auth_type", "bearer_token"))));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(List.of(ably));

            com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto d1 =
                    new com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto();
            d1.setName("ably"); d1.setVariant("basic_auth"); d1.setEnabled(false);
            d1.setHasBasicAuth(true);
            com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto d2 =
                    new com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto();
            d2.setName("ably"); d2.setVariant("bearer_token"); d2.setEnabled(false);
            d2.setHasCustomFields(true);
            when(credentialClient.listPlatformCredentials()).thenReturn(List.of(d1, d2));

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials", hasSize(0)));
        }

        @Test
        @DisplayName("Emits a length-1 variants array for single-variant APIs so the frontend falls back to the plain auth_type chip instead of the 'N methods' chip")
        void surfacesSingleVariantAsLengthOneArray() throws Exception {
            Map<String, Object> slack = createSampleCredential();
            slack.put("credential_name", "slack");
            slack.put("display_name", "Slack");
            slack.put("auth_type", "oauth2");
            slack.put("variant", "primary");
            slack.put("variants", List.of(Map.of("variant", "primary", "auth_type", "oauth2")));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(List.of(slack));

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials[0].variants", hasSize(1)))
                    .andExpect(jsonPath("$.credentials[0].variants[0].variant").value("primary"));
        }

        @Test
        @DisplayName("Counts DISTINCT credential_name so multi-variant APIs don't inflate totalItems and pagination stays aligned with the deduped list")
        void countsDistinctCredentialName() throws Exception {
            org.mockito.ArgumentCaptor<String> countSqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            when(jdbcTemplate.queryForObject(countSqlCaptor.capture(), eq(Integer.class))).thenReturn(0);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk());

            String countSql = countSqlCaptor.getValue();
            org.junit.jupiter.api.Assertions.assertTrue(
                    countSql.contains("COUNT(DISTINCT c.credential_name)"),
                    "Count SQL must use COUNT(DISTINCT credential_name), got: " + countSql);
        }

        @Test
        @DisplayName("Re-sorts by display_name in memory - DISTINCT ON forces ORDER BY credential_name at the DB level, but users expect alphabetical labels")
        void reSortsByDisplayNameInMemory() throws Exception {
            Map<String, Object> zapier = createSampleCredential();
            zapier.put("credential_name", "zapier");
            zapier.put("display_name", "Zapier");
            Map<String, Object> airtable = createSampleCredential();
            airtable.put("credential_name", "airtable");
            airtable.put("display_name", "Airtable");
            Map<String, Object> slack = createSampleCredential();
            slack.put("credential_name", "slack");
            slack.put("display_name", "Slack");

            // DB returns them ordered by credential_name (airtable, slack, zapier).
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(3);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0)))
                    .thenReturn(List.of(airtable, slack, zapier));

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials", hasSize(3)))
                    // Final order: alphabetical by display_name - Airtable, Slack, Zapier.
                    .andExpect(jsonPath("$.credentials[0].display_name").value("Airtable"))
                    .andExpect(jsonPath("$.credentials[1].display_name").value("Slack"))
                    .andExpect(jsonPath("$.credentials[2].display_name").value("Zapier"));
        }

        @Test
        @DisplayName("User-facing list OR-s the native core-node credentials (smtp/imap) into the visibility filter so email credentials show in Available integrations - regression for native templates hidden because they have no backing catalog API")
        void listSurfacesNativeCoreCredentials() throws Exception {
            // DB is mocked, so assert on the generated SQL (same approach as
            // countsDistinctCredentialName): the regular-user list must keep the
            // active-catalog-API backing clause AND additionally allow the native
            // core-node credentials. Pre-fix the SQL had only the EXISTS clause, so
            // smtp/imap (no catalog.apis row) were filtered out - this test fails on it.
            org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<String> countSqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            when(jdbcTemplate.queryForObject(countSqlCaptor.capture(), eq(Integer.class))).thenReturn(0);
            when(jdbcTemplate.queryForList(sqlCaptor.capture(), eq(20), eq(0))).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk());

            String sql = sqlCaptor.getValue();
            org.junit.jupiter.api.Assertions.assertTrue(
                    sql.contains("c.credential_name IN ('smtp','imap')"),
                    "User-facing list SQL must OR-in the native core credentials, got: " + sql);
            org.junit.jupiter.api.Assertions.assertTrue(
                    sql.contains("a.platform_credential_name = c.credential_name")
                            && sql.contains("a.is_active = true"),
                    "The native allow-list must EXTEND (OR), not replace, the active-catalog-API backing clause, got: " + sql);
            // The COUNT query shares the same WHERE filter, so it must carry the native
            // allow-list too - otherwise totalItems would diverge from the page slice and
            // pagination would drift. Lock it explicitly.
            org.junit.jupiter.api.Assertions.assertTrue(
                    countSqlCaptor.getValue().contains("c.credential_name IN ('smtp','imap')"),
                    "Count SQL must apply the same native-core filter as the list, got: " + countSqlCaptor.getValue());
        }

        @Test
        @DisplayName("Admin list (includeInactive=true) applies neither the API-backing nor the native-core filter so every template stays addressable for the platform-credentials admin page")
        void adminListSkipsVisibilityFilters() throws Exception {
            org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
            when(jdbcTemplate.queryForList(sqlCaptor.capture(), eq(20), eq(0))).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials").param("includeInactive", "true"))
                    .andExpect(status().isOk());

            String sql = sqlCaptor.getValue();
            org.junit.jupiter.api.Assertions.assertFalse(
                    sql.contains("c.credential_name IN ('smtp','imap')"),
                    "Admin list must not apply the native-core allow-list, got: " + sql);
            // `a.is_active = true` is the active-catalog-API filter's distinctive marker -
            // unlike `a.platform_credential_name = c.credential_name`, it never appears in the
            // `source` projection subquery, so its absence proves the WHERE filter is gone.
            org.junit.jupiter.api.Assertions.assertFalse(
                    sql.contains("a.is_active = true"),
                    "Admin list must not apply the API-backing filter, got: " + sql);
        }

        @Test
        @DisplayName("Search path keeps the native-core OR-clause alongside the 4 ILIKE placeholders so a typed term still surfaces smtp/imap and precedence stays (backing OR native) AND (search)")
        void searchPathRetainsNativeOrClause() throws Exception {
            org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            String pattern = "%smtp%";
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                    eq(pattern), eq(pattern), eq(pattern), eq(pattern))).thenReturn(0);
            when(jdbcTemplate.queryForList(sqlCaptor.capture(),
                    eq(pattern), eq(pattern), eq(pattern), eq(pattern), eq(20), eq(0)))
                    .thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials").param("search", "smtp"))
                    .andExpect(status().isOk());

            String sql = sqlCaptor.getValue();
            org.junit.jupiter.api.Assertions.assertTrue(
                    sql.contains("c.credential_name IN ('smtp','imap')"),
                    "Search path must retain the native-core OR-clause, got: " + sql);
            // The 4 ILIKE bind placeholders from the search filter must still be present -
            // the native names are inlined literals, so they must NOT change the positional
            // '?' count the call site supplies (4 patterns + pageSize + offset).
            int ilikeCount = sql.split("ILIKE \\?", -1).length - 1;
            org.junit.jupiter.api.Assertions.assertEquals(4, ilikeCount,
                    "Search filter must contribute exactly 4 ILIKE placeholders, got " + ilikeCount + ": " + sql);
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/credentials/resolve")
    class ResolveCredentialTemplate {

        @Test
        @DisplayName("resolves a concrete variant by icon_slug when credential_name changed")
        void resolvesByIconSlugWhenCredentialNameChanged() throws Exception {
            Map<String, Object> credential = createSampleCredential();
            credential.put("id", CREDENTIAL_ID);
            credential.put("credential_name", "twitterx");
            credential.put("icon_slug", "twitter");
            credential.put("variant", "oauth2");
            credential.put("auth_type", "oauth2");
            when(jdbcTemplate.queryForList(anyString(),
                    eq("twitter"), eq("twitter"), eq("oauth2"), eq("twitter")))
                    .thenReturn(List.of(credential));

            mockMvc.perform(get("/api/catalog/credentials/resolve")
                            .param("key", "twitter")
                            .param("variant", "oauth2")
                            .param("includeInactive", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(CREDENTIAL_ID.toString()))
                    .andExpect(jsonPath("$.credential_name").value("twitterx"))
                    .andExpect(jsonPath("$.icon_slug").value("twitter"))
                    .andExpect(jsonPath("$.variant").value("oauth2"));
        }

        @Test
        @DisplayName("returns 404 when no stable key and variant pair exists")
        void returns404WhenStableKeyDoesNotExist() throws Exception {
            when(jdbcTemplate.queryForList(anyString(),
                    eq("missing"), eq("missing"), eq("oauth2"), eq("missing")))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/catalog/credentials/resolve")
                            .param("key", "missing")
                            .param("variant", "oauth2")
                            .param("includeInactive", "true"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/credentials/{id}")
    class GetCredentialTemplate {

        @Test
        @DisplayName("should return credential template by ID")
        void shouldReturnCredentialById() throws Exception {
            Map<String, Object> credential = createSampleCredential();
            when(jdbcTemplate.queryForList(anyString(), any(UUID.class))).thenReturn(List.of(credential));

            mockMvc.perform(get("/api/catalog/credentials/{id}", CREDENTIAL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credential_name").value("github_oauth"))
                    .andExpect(jsonPath("$.display_name").value("GitHub OAuth"))
                    .andExpect(jsonPath("$.credential_type").value("oauth2"));
        }

        @Test
        @DisplayName("should return 404 when credential not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(jdbcTemplate.queryForList(anyString(), any(UUID.class))).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials/{id}", CREDENTIAL_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 500 on database error")
        void shouldReturn500OnDatabaseError() throws Exception {
            when(jdbcTemplate.queryForList(anyString(), any(UUID.class)))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/catalog/credentials/{id}", CREDENTIAL_ID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("Failed to fetch credential template"));
        }

        @Test
        @DisplayName("Returns 404 - not 500 - for a non-UUID path segment so external probes like /api/catalog/credentials/template-gmail stop spamming stacktraces")
        void shouldReturn404ForInvalidUuid() throws Exception {
            // No jdbcTemplate stubbing - UUID.fromString must throw BEFORE we reach the DB,
            // short-circuiting to 404. If the controller regresses to reaching the DB, the
            // unstubbed call returns null and the assertion still fails loudly.
            mockMvc.perform(get("/api/catalog/credentials/{id}", "template-gmail"))
                    .andExpect(status().isNotFound());
            verify(jdbcTemplate, never()).queryForList(anyString(), any(UUID.class));
        }
    }

    @Nested
    @DisplayName("GET /api/catalog/credentials/{credentialName}/variants")
    class GetCredentialVariants {

        private Map<String, Object> createVariantRow(String variant, String authType) {
            Map<String, Object> row = createSampleCredential();
            row.put("credential_name", "gmail");
            row.put("display_name", "Gmail");
            row.put("variant", variant);
            row.put("auth_type", authType);
            return row;
        }

        @Test
        @DisplayName("Returns both variants in ORDER BY variant ASC for a multi-variant API so the wizard can render tabs")
        void returnsOrderedVariantList() throws Exception {
            Map<String, Object> apiKey = createVariantRow("api_key", "api_key");
            Map<String, Object> oauth2 = createVariantRow("oauth2", "oauth2");
            when(jdbcTemplate.queryForList(anyString(), eq("gmail")))
                    .thenReturn(List.of(apiKey, oauth2));

            mockMvc.perform(get("/api/catalog/credentials/{name}/variants", "gmail"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].variant").value("api_key"))
                    .andExpect(jsonPath("$[0].auth_type").value("api_key"))
                    .andExpect(jsonPath("$[1].variant").value("oauth2"))
                    .andExpect(jsonPath("$[1].auth_type").value("oauth2"));
        }

        @Test
        @DisplayName("Returns a single-element array for single-variant APIs so the wizard simply skips the tab UI")
        void returnsSingleElementForSingleVariant() throws Exception {
            Map<String, Object> primary = createVariantRow("primary", "api_key");
            when(jdbcTemplate.queryForList(anyString(), eq("slack")))
                    .thenReturn(List.of(primary));

            mockMvc.perform(get("/api/catalog/credentials/{name}/variants", "slack"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].variant").value("primary"));
        }

        @Test
        @DisplayName("Returns empty array (not 404) when name is unknown so the wizard can call opportunistically before confirming existence")
        void returnsEmptyArrayWhenUnknownName() throws Exception {
            when(jdbcTemplate.queryForList(anyString(), eq("does-not-exist")))
                    .thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/catalog/credentials/{name}/variants", "does-not-exist"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("Preserves the DB's ORDER BY variant ASC ordering when mixing legacy primary + modern variants so tabs render deterministically")
        void preservesOrderWithThreeVariantsIncludingPrimary() throws Exception {
            // Driver returns rows in the SQL's ORDER BY order; the controller must pass
            // them through unchanged so alphabetical (api_key, oauth2, primary) is what
            // the wizard sees.
            Map<String, Object> apiKey = createVariantRow("api_key", "api_key");
            Map<String, Object> oauth2 = createVariantRow("oauth2", "oauth2");
            Map<String, Object> primary = createVariantRow("primary", "basic_auth");
            when(jdbcTemplate.queryForList(anyString(), eq("gmail")))
                    .thenReturn(List.of(apiKey, oauth2, primary));

            mockMvc.perform(get("/api/catalog/credentials/{name}/variants", "gmail"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[0].variant").value("api_key"))
                    .andExpect(jsonPath("$[1].variant").value("oauth2"))
                    .andExpect(jsonPath("$[2].variant").value("primary"));
        }

        @Test
        @DisplayName("Propagates a 500 with a message when the DB query fails")
        void returns500OnDbError() throws Exception {
            when(jdbcTemplate.queryForList(anyString(), eq("gmail")))
                    .thenThrow(new RuntimeException("connection refused"));

            mockMvc.perform(get("/api/catalog/credentials/{name}/variants", "gmail"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("Failed to fetch credential variants"));
        }

        @Test
        @DisplayName("Drops admin-disabled variants from the tab strip so users only see auth methods they are allowed to pick")
        void filtersAdminDisabledVariants() throws Exception {
            Map<String, Object> basic = createVariantRow("basic_auth", "basic_auth");
            basic.put("credential_name", "ably");
            Map<String, Object> bearer = createVariantRow("bearer_token", "bearer_token");
            bearer.put("credential_name", "ably");
            when(jdbcTemplate.queryForList(anyString(), eq("ably")))
                    .thenReturn(List.of(basic, bearer));

            com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto disabled =
                    new com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto();
            disabled.setName("ably");
            disabled.setVariant("bearer_token");
            disabled.setEnabled(false);
            disabled.setHasCustomFields(true);
            when(credentialClient.listPlatformCredentials()).thenReturn(List.of(disabled));

            mockMvc.perform(get("/api/catalog/credentials/{name}/variants", "ably"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].variant").value("basic_auth"));
        }
    }

    @Nested
    @DisplayName("Fail-open behaviour")
    class FailOpen {

        @Test
        @DisplayName("Falls through to show every variant when auth-service is unreachable - catalog must stay visible during an auth outage instead of silently hiding everything")
        void showsAllVariantsWhenAuthServiceThrows() throws Exception {
            Map<String, Object> ably = createSampleCredential();
            ably.put("credential_name", "ably");
            ably.put("display_name", "Ably");
            ably.put("variants", new ArrayList<>(List.of(
                    Map.of("variant", "basic_auth", "auth_type", "basic_auth"),
                    Map.of("variant", "bearer_token", "auth_type", "bearer_token"))));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(List.of(ably));
            when(credentialClient.listPlatformCredentials())
                    .thenThrow(new RuntimeException("auth-service unreachable"));

            mockMvc.perform(get("/api/catalog/credentials"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials", hasSize(1)))
                    .andExpect(jsonPath("$.credentials[0].variants", hasSize(2)));
        }
    }

    @Nested
    @DisplayName("includeInactive admin bypass")
    class IncludeInactiveBypass {

        @Test
        @DisplayName("Skips the variant-disabled filter when includeInactive=true so the platform-credentials admin page can still see disabled variants it needs to re-enable")
        void skipsVariantFilterForAdmin() throws Exception {
            Map<String, Object> ably = createSampleCredential();
            ably.put("credential_name", "ably");
            ably.put("display_name", "Ably");
            ably.put("variants", new ArrayList<>(List.of(
                    Map.of("variant", "basic_auth", "auth_type", "basic_auth"),
                    Map.of("variant", "bearer_token", "auth_type", "bearer_token"))));

            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq(20), eq(0))).thenReturn(List.of(ably));

            // Note: NO stub on credentialClient.listPlatformCredentials() - the admin
            // bypass must short-circuit before calling auth-service at all, which the
            // never() verify below asserts.

            mockMvc.perform(get("/api/catalog/credentials").param("includeInactive", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentials[0].variants", hasSize(2)));

            verify(credentialClient, never()).listPlatformCredentials();
        }

        @Test
        @DisplayName("by-name lookup returns 404 by default when every variant is admin-disabled")
        void byNameReturns404WhenAllDisabled() throws Exception {
            Map<String, Object> ably = new LinkedHashMap<>(createSampleCredential());
            ably.put("credential_name", "ably");
            ably.put("variants", new ArrayList<>(List.of(
                    Map.of("variant", "bearer_token", "auth_type", "bearer_token"))));
            when(jdbcTemplate.queryForList(anyString(), eq("ably"))).thenReturn(List.of(ably));

            com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto disabled =
                    new com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto();
            disabled.setName("ably");
            disabled.setVariant("bearer_token");
            disabled.setEnabled(false);
            disabled.setHasCustomFields(true);
            when(credentialClient.listPlatformCredentials()).thenReturn(List.of(disabled));

            mockMvc.perform(get("/api/catalog/credentials").param("name", "ably"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("by-name lookup respects includeInactive so the admin page can still resolve a single credential whose only variant has been disabled")
        void byNameSkipsFilterForAdmin() throws Exception {
            Map<String, Object> ably = new LinkedHashMap<>(createSampleCredential());
            ably.put("credential_name", "ably");
            ably.put("variants", new ArrayList<>(List.of(
                    Map.of("variant", "bearer_token", "auth_type", "bearer_token"))));
            when(jdbcTemplate.queryForList(anyString(), eq("ably"))).thenReturn(List.of(ably));

            mockMvc.perform(get("/api/catalog/credentials")
                            .param("name", "ably")
                            .param("includeInactive", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credential_name").value("ably"))
                    .andExpect(jsonPath("$.variants", hasSize(1)));

            verify(credentialClient, never()).listPlatformCredentials();
        }
    }

    @Nested
    @DisplayName("By-id 404 when all variants disabled")
    class ByIdAllDisabled {

        @Test
        @DisplayName("Returns 404 when every variant of the requested template has been admin-disabled - matches the by-name and list endpoints")
        void returns404WhenAllVariantsDisabled() throws Exception {
            Map<String, Object> ably = createSampleCredential();
            ably.put("credential_name", "ably");
            ably.put("variants", new ArrayList<>(List.of(
                    Map.of("variant", "bearer_token", "auth_type", "bearer_token"))));
            when(jdbcTemplate.queryForList(anyString(), any(UUID.class))).thenReturn(List.of(ably));

            com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto disabled =
                    new com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto();
            disabled.setName("ably");
            disabled.setVariant("bearer_token");
            disabled.setEnabled(false);
            disabled.setHasCustomFields(true);
            when(credentialClient.listPlatformCredentials()).thenReturn(List.of(disabled));

            mockMvc.perform(get("/api/catalog/credentials/{id}", CREDENTIAL_ID))
                    .andExpect(status().isNotFound());
        }
    }
}
