package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceSource;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findEverPublishedKeys} is a hand-written JPQL query: a mistake in it fails at CONTEXT
 * STARTUP (auth-service would not boot), and every other test of the gap fill hands the repository
 * to Mockito. This runs it against a real EntityManager.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = PricingVersionEntryEverPublishedPersistenceTest.JpaOnly.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pricing_history;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("PricingVersionEntryRepository.findEverPublishedKeys - every (endpoint, model) a credential ever priced")
class PricingVersionEntryEverPublishedPersistenceTest {

    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {PricingVersionEntry.class, PlatformCredentialPricingVersion.class})
    @EnableJpaRepositories(basePackageClasses = PricingVersionEntryRepository.class,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = {PricingVersionEntryRepository.class, PlatformCredentialPricingVersionRepository.class}))
    static class JpaOnly {
    }

    @Autowired private PricingVersionEntryRepository entryRepo;
    @Autowired private PlatformCredentialPricingVersionRepository versionRepo;

    private static final UUID TOOL = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private Long version(Long credentialId, int n) {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setPlatformCredentialId(credentialId);
        v.setVersion(n);
        v.setCreatedBy("test");
        return versionRepo.saveAndFlush(v).getId();
    }

    private void entry(Long versionId, String modelId) {
        PricingVersionEntry e = new PricingVersionEntry();
        e.setPricingVersionId(versionId);
        e.setApiToolId(TOOL);
        e.setModelId(modelId);
        e.setPriceUnit("second");
        e.setMarkupCredits(BigDecimal.ZERO);
        e.setUnitCredits(new BigDecimal("60"));
        e.setSource(PriceSource.ADMIN.wire());
        entryRepo.saveAndFlush(e);
    }

    @Test
    @DisplayName("includes a model removed since, the endpoint-wide row, and nothing of another credential")
    void readsTheWholeHistoryOfOneCredential() {
        Long v1 = version(1L, 1);
        entry(v1, "removed-later");
        entry(v1, "kept");
        Long v2 = version(1L, 2);
        entry(v2, "kept");
        entry(v2, null); // an endpoint-wide row: its null model must come back as null
        Long other = version(2L, 1);
        entry(other, "other-credential-model");

        List<Object[]> keys = entryRepo.findEverPublishedKeys(1L);

        assertThat(keys).extracting(k -> (String) k[1])
                .containsExactlyInAnyOrder("removed-later", "kept", null);
        assertThat(keys).allSatisfy(k -> assertThat(k[0]).isEqualTo(TOOL));
    }
}
