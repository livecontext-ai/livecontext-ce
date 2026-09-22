package com.apimarketplace.catalog.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.repository.query.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail unit test on the SQL of
 * {@link ApiRepository#existsSharedIntegrationWithCredentialKey(String, String)}.
 *
 * <p>The two callers of that method are stubbed in {@code CustomApiRegistrationServiceTest}, so
 * those tests can only prove the owner reaches the repository. What decides the outcome is the
 * query text, and it is the query text that was wrong: it asked whether the key was owned by ANY
 * custom API, which meant the FIRST squatter disabled the guard for that key for everyone after
 * them, and it read only {@code catalog.credentials}, which is the very row a collision destroys.
 *
 * <p>Both holes were live on production on 2026-09-17: the built-in Ghost integration had lost its
 * {@code catalog.credentials} row and all 41 of its {@code tool_credentials} links, and a probe
 * registering a custom API named "Ghost" was accepted with {@code credential_name = 'ghost'}.
 *
 * <p>This inspects the annotation rather than running the SQL, and that is a FIRST STEP, not a
 * considered ceiling. A behavioural test is available today: {@code
 * schema-catalog-bundle-postgres.sql} already declares both {@code catalog.apis} and {@code
 * catalog.credentials}, and the ci.yml step that runs this class exports
 * {@code ORCHESTRATOR_TEST_PG_URL} and already runs real-Postgres classes there. Six rows
 * (shipped-with-credential, shipped-with-only-icon_slug, native-template-with-no-api-row, the
 * caller's own custom, another user's custom, nothing) would cover every branch. Until that
 * exists, what this class must do is pin the SHAPE of the predicate and not merely its
 * vocabulary, because a string containing all the right words can still express the bug: swapping
 * the top-level OR for AND does exactly that, and so does turning the shipped half's inner AND
 * into an OR, which would refuse every registration on the installation.
 *
 * <p>Note the annotation is the Spring Data JDBC one, not the JPA one.
 */
@DisplayName("ApiRepository.existsSharedIntegrationWithCredentialKey - both halves of the predicate")
class SharedCredentialKeyGuardQueryTest {

    private static String sqlOfGuard() throws NoSuchMethodException {
        Method m = ApiRepository.class.getMethod(
                "existsSharedIntegrationWithCredentialKey", String.class, String.class);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("the guard must still be a @Query method").isNotNull();
        return q.value();
    }

    @Test
    @DisplayName("a SHIPPED api row holding the key is a collision, even with no credential row left")
    void aShippedApiRowIsEnoughToRefuse() throws NoSuchMethodException {
        String sql = sqlOfGuard();
        assertThat(sql)
                .as("a collision DELETES the credentials row, so that table cannot be the only "
                        + "witness: the shipped catalog.apis row is what survives it")
                .contains("catalog.apis")
                .contains("source IS DISTINCT FROM 'custom'");
        assertThat(sql)
                .as("a shipped row can carry the key as either column")
                .contains("platform_credential_name = :key")
                .contains("icon_slug = :key");
    }

    @Test
    @DisplayName("the exemption is scoped to the caller, so one squatter cannot open the key to all")
    void theExemptionIsScopedToTheOwner() throws NoSuchMethodException {
        String sql = sqlOfGuard();
        assertThat(sql)
                .as("exempting any custom API let the first squatter disable the guard for "
                        + "everybody; only the caller's own rows may exempt them")
                .contains("created_by = :ownerId");
        assertThat(sql)
                .as("the native templates imap and smtp have no api row at all, so the "
                        + "credentials table is still read")
                .contains("catalog.credentials");
        assertThat(sql)
                .as("the exemption must SUBTRACT from the credentials half, so dropping the NOT "
                        + "would invert it into 'refuse only what I own'")
                .contains("NOT EXISTS");
    }

    @Test
    @DisplayName("the two halves are joined by OR, because AND re-accepts the case this exists for")
    void theTwoHalvesAreJoinedByOr() throws NoSuchMethodException {
        // The reason this assertion is worth its line: swapping the top-level OR for AND leaves
        // every other assertion in this class passing (both EXISTS present, all substrings
        // present) while restoring the production behaviour exactly. The guard would then fire
        // only when a shipped row AND an unowned credential row both exist, and the Ghost
        // incident is precisely a shipped row whose credential row had been destroyed.
        String normalised = sqlOfGuard().replaceAll("\\s+", " ");
        assertThat(normalised)
                .as("the shipped half must stand alone: %s", normalised)
                .contains(") OR EXISTS (");
        assertThat(normalised)
                .as("an AND here would require both halves and re-accept the incident case")
                .doesNotContain(") AND EXISTS (");
        assertThat(normalised)
                .as("the shipped half's own predicate must stay a conjunction: turning its AND "
                        + "into an OR matches every shipped row and refuses every registration "
                        + "on the installation, while leaving every other assertion here passing")
                .contains("FROM 'custom' AND (");
    }

    @Test
    @DisplayName("the identity-free variant asks the shipped half only, and nothing about owners")
    void theIdentityFreeVariantAsksOnlyTheShippedHalf() throws NoSuchMethodException {
        Method m = ApiRepository.class.getMethod(
                "existsShippedIntegrationWithCredentialKey", String.class);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("the identity-free variant must still be a @Query method").isNotNull();
        String sql = q.value();
        assertThat(sql)
                .as("it exists because a null owner makes the ownership half match nothing, "
                        + "which turns a fail-OPEN site into a fail-CLOSED one")
                .contains("catalog.apis")
                .contains("source IS DISTINCT FROM 'custom'");
        assertThat(sql)
                .as("it must not reference an owner it is not given: %s", sql)
                .doesNotContain(":ownerId")
                .doesNotContain("created_by");
    }
}
