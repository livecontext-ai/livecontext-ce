package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, spec);

        // Verify credential upsert was called
        verify(jdbcTemplate).queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                eq("openweathermap"), eq("openweathermap"), eq("apiKey"), any(String.class), eq("icon"), any());

        // Verify tool_credentials link was created with correct injection metadata for apiKey
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                eq(toolId), eq(credentialId), eq("openweathermap"), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"field\": \"api_key\""), "apiKey must use field=api_key");
        assertTrue(metadata.contains("\"injection\""), "metadata must contain 'injection' sub-object");
        assertTrue(metadata.contains("\"type\": \"header\""), "apiKey must inject as header");
        assertTrue(metadata.contains("\"key\": \"X-API-Key\""), "apiKey must use X-API-Key header");
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
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "bearer", "myicon");

        verify(jdbcTemplate).queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                eq("myapi"), eq("myapi"), eq("bearer"), any(String.class), eq("myicon"), any());

        // Verify bearer metadata: field=access_token, injection type=header, key=Authorization
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                eq(toolId), eq(credentialId), eq("myapi"), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"field\": \"access_token\""), "bearer must use field=access_token");
        assertTrue(metadata.contains("\"type\": \"header\""), "bearer must inject as header");
        assertTrue(metadata.contains("\"key\": \"Authorization\""), "bearer must use Authorization header");
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
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "apiKey", "icon");

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), any(UUID.class), anyString(), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"field\": \"api_key\""), "apiKey must use field=api_key");
        assertTrue(metadata.contains("\"type\": \"header\""), "apiKey must inject as header");
        assertTrue(metadata.contains("\"key\": \"X-API-Key\""), "apiKey must use X-API-Key header");
    }

    @Test
    @DisplayName("should use X-API-Key for unknown auth type (default branch)")
    void shouldUseDefaultInjectionMetadataForUnknownAuthType() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "custom_auth", "icon");

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), any(UUID.class), anyString(), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        // Default should NOT use Authorization (which triggers Bearer prefix)
        assertTrue(metadata.contains("\"key\": \"X-API-Key\""), "default must use X-API-Key, not Authorization");
        assertTrue(metadata.contains("\"field\": \"api_key\""), "default must use field=api_key");
    }

    @Test
    @DisplayName("should use X-API-Key for basic auth (no Basic prefix support in HttpExecutionService)")
    void shouldUseApiKeyHeaderForBasicAuth() {
        UUID apiId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO catalog.credentials"), eq(UUID.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "basic", "icon");

        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), any(UUID.class), anyString(), eq("primary"), metadataCaptor.capture());
        String metadata = metadataCaptor.getValue();
        // basic falls through to default - Authorization would trigger Bearer prefix
        assertTrue(metadata.contains("\"key\": \"X-API-Key\""), "basic must use X-API-Key, not Authorization");
        assertTrue(metadata.contains("\"field\": \"api_key\""), "basic must use field=api_key");
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
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, "myapi", "bearer", "icon");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(UUID.class),
                any(), any(), any(), any(), any(), any());
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
                any(), any(), any(), any(), any(), any()))
                .thenReturn(credentialId);

        service.linkCredentials(apiId, spec);

        // Should link both tools
        verify(jdbcTemplate, times(2)).update(contains("INSERT INTO catalog.tool_credentials"),
                any(UUID.class), eq(credentialId), eq("myApi"), eq("primary"), any(String.class));
    }
}
