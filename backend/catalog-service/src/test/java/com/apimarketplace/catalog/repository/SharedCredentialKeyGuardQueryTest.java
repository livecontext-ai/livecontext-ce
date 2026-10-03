package com.apimarketplace.catalog.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.repository.query.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail on the SQL text of the credential-key ownership predicate. What the SQL DOES is
 * executed against a real Postgres by {@link CredentialKeyOwnershipPostgresTest}; this class only
 * pins the shape a reviewer would otherwise have to re-derive, and runs without a database.
 *
 * <p>History: the first version asked whether ANY custom API owned the key (the first squatter
 * disabled the guard for everyone), the second exempted a template whenever the caller's own
 * custom API carried the key (circular: an API named {@code imap} owned the native imap
 * template). Ownership is now decided by {@code created_by} plus provenance markers on the
 * template (CASA readiness, LC-002 / LC-057).
 */
@DisplayName("ApiRepository credential-key guard - query shape")
class SharedCredentialKeyGuardQueryTest {

    private static String sql(String method, Class<?>... params) throws NoSuchMethodException {
        Method m = ApiRepository.class.getMethod(method, params);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("%s must stay a @Query method", method).isNotNull();
        return q.value().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("an API row is foreign by created_by, not by source (a submission body sets source)")
    void apiRowOwnershipIsByCreatedBy() throws NoSuchMethodException {
        String sql = sql("existsSharedIntegrationWithCredentialKey", String.class, String.class);
        assertThat(sql)
                .contains("a.platform_credential_name = :key OR a.icon_slug = :key")
                .contains("a.created_by IS DISTINCT FROM :ownerId")
                .doesNotContain("source IS DISTINCT FROM 'custom'");
    }

    @Test
    @DisplayName("a migration-seeded / imported template is never the caller's (provenance markers)")
    void seededTemplatesAreAlwaysForeign() throws NoSuchMethodException {
        String sql = sql("existsSharedIntegrationWithCredentialKey", String.class, String.class);
        assertThat(sql)
                .contains("c.credential_type IS NULL")
                .contains("c.metadata ->> 'provider' IS NULL")
                .contains("c.metadata ->> 'category' IS NULL")
                .contains("c.metadata ->> 'source' IS NULL")
                .contains("customApiOwner");
        assertThat(sql)
                .as("the old circular exemption must be gone")
                .doesNotContain("mine.platform_credential_name = c.credential_name");
    }

    @Test
    @DisplayName("the two halves are joined by OR: an API row alone, or a template alone, is enough")
    void halvesJoinedByOr() throws NoSuchMethodException {
        String sql = sql("existsSharedIntegrationWithCredentialKey", String.class, String.class);
        assertThat(sql).contains(") OR EXISTS (").doesNotContain(") AND EXISTS (");
    }

    @Test
    @DisplayName("the identity-free variant fails closed: any API row or any template is a collision")
    void identityFreeFailsClosed() throws NoSuchMethodException {
        String sql = sql("existsShippedIntegrationWithCredentialKey", String.class);
        assertThat(sql)
                .contains("catalog.apis")
                .contains("catalog.credentials c WHERE c.credential_name = :key")
                .doesNotContain(":ownerId");
    }
}
