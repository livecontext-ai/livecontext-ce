package com.apimarketplace.auth.credential.web.dto;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Regression guard: {@link MyOAuthAppDto} JSON serialization MUST never leak
 * secret-bearing fields from the source {@link PlatformCredential}.
 *
 * <p>The test uses an explicit allowlist of top-level field names and the
 * exact {@link RecordComponent} count so future contributors who add a field
 * to the record without thinking about user exposure trip the build.
 *
 * <p>Parametrized over {@code clientId} branches: long (full mask shape), short
 * (collapsed to "****"), and null (treated as short).
 */
class MyOAuthAppDtoLeakTest {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String SECRET_VALUE = "shhh-this-is-the-client-secret";
    private static final String API_KEY_VALUE = "ak-this-is-the-api-key-do-not-leak";
    private static final String PASSWORD_VALUE = "this-is-the-password-do-not-leak";
    private static final String CUSTOM_FIELD_VALUE = "custom-field-secret-do-not-leak";

    static Stream<Arguments> clientIdVariants() {
        // expectedMaskShape == null means the DTO must surface clientIdMasked
        // as JSON null (non-OAuth2 row with no clientId concept).
        return Stream.of(
                arguments(
                        "googleabcd-1234567890.apps.googleusercontent.com",
                        "^.{4}\\*{4}.{4}$"),
                arguments("short", "^\\*{4}$"),
                arguments(null, null)
        );
    }

    @ParameterizedTest(name = "clientId={0}")
    @MethodSource("clientIdVariants")
    @DisplayName("JSON serialization respects the explicit field allowlist and never leaks secret values")
    void serializationRespectsAllowlist(String clientIdInput, String expectedMaskShape) throws Exception {
        PlatformCredential row = makeRow(clientIdInput);
        MyOAuthAppDto dto = MyOAuthAppDto.from(row);

        // 1. Allowlist equality on top-level field names - adding a new field to
        //    the record forces a deliberate update here, which forces a review of
        //    whether that field is safe to expose.
        JsonNode tree = objectMapper.valueToTree(dto);
        Set<String> actualFields = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(tree.fieldNames(), 0), false)
                .collect(Collectors.toSet());
        // organizationId (V362) is the workspace scope - an org UUID the caller
        // is already a member of, never a secret. Deliberately allowlisted so the
        // frontend can render the workspace a connection belongs to.
        assertThat(actualFields).isEqualTo(Set.of(
                "id", "integrationName", "displayName", "iconSlug", "authType",
                "clientIdMasked", "hasClientSecret", "hasApiKey", "isEnabled",
                "createdAt", "updatedAt", "createdBy", "organizationId"));

        // 2. Record component count = 13 (fail-loud on future field addition).
        assertThat(MyOAuthAppDto.class.getRecordComponents()).hasSize(13);

        // 3. No secret value byte-sequence appears anywhere in the JSON output.
        String json = objectMapper.writeValueAsString(dto);
        assertThat(json).doesNotContain(SECRET_VALUE);
        assertThat(json).doesNotContain(API_KEY_VALUE);
        assertThat(json).doesNotContain(PASSWORD_VALUE);
        assertThat(json).doesNotContain(CUSTOM_FIELD_VALUE);

        // 4. Mask shape matches the input branch - null source clientId
        //    surfaces as null in the DTO (no misleading "****" placeholder
        //    for non-OAuth2 rows).
        if (expectedMaskShape == null) {
            assertThat(dto.clientIdMasked()).isNull();
        } else {
            assertThat(dto.clientIdMasked()).matches(expectedMaskShape);
        }

        // 5. hasClientSecret/hasApiKey reflect presence-without-value semantics.
        assertThat(dto.hasClientSecret()).isTrue();
        assertThat(dto.hasApiKey()).isTrue();
    }

    @Test
    @DisplayName("hasClientSecret and hasApiKey return false when the source row has blank/null secrets")
    void blankSecretsReportFalse() {
        PlatformCredential row = new PlatformCredential(
                42L, "gmail", "Gmail", AuthType.OAUTH2,
                "client-id-1234567890", null, "  ",
                null, null, null, null, null,
                "gmail", "Email", "desc", true,
                Map.of(), BigDecimal.ZERO, 500,
                Instant.now(), Instant.now(), "tenant-a", "tenant-a", "oauth2");

        MyOAuthAppDto dto = MyOAuthAppDto.from(row);

        assertThat(dto.hasClientSecret()).isFalse();
        assertThat(dto.hasApiKey()).isFalse();
    }

    private static PlatformCredential makeRow(String clientIdInput) {
        return new PlatformCredential(
                42L,
                "gmail",
                "Gmail",
                AuthType.OAUTH2,
                clientIdInput,
                SECRET_VALUE,
                API_KEY_VALUE,
                "user@example.com",
                PASSWORD_VALUE,
                "https://accounts.google.com/o/oauth2/auth",
                "https://oauth2.googleapis.com/token",
                "openid email",
                "gmail",
                "Email",
                "Gmail integration",
                true,
                Map.of("custom_extra", CUSTOM_FIELD_VALUE),
                BigDecimal.ZERO,
                500,
                Instant.parse("2026-01-02T03:04:05Z"),
                Instant.parse("2026-01-02T04:05:06Z"),
                "alice@livecontext.ai",
                "tenant-a",
                "oauth2"
        );
    }
}
