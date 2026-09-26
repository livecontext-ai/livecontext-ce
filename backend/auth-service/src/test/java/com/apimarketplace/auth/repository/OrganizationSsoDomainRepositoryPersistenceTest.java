package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationSsoDomain;
import com.apimarketplace.auth.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two hand-written JPQL queries of {@link OrganizationSsoDomainRepository} fail at CONTEXT
 * STARTUP if wrong (auth-service would not boot), and every service test hands the repository to
 * Mockito. This runs them against a real EntityManager.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = OrganizationSsoDomainRepositoryPersistenceTest.JpaOnly.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sso_domains;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("OrganizationSsoDomainRepository - verified-domain queries")
class OrganizationSsoDomainRepositoryPersistenceTest {

    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = OrganizationSsoDomain.class)
    @EnableJpaRepositories(basePackageClasses = OrganizationSsoDomainRepository.class,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = OrganizationSsoDomainRepository.class))
    static class JpaOnly {
    }

    @Autowired private OrganizationSsoDomainRepository repository;
    @Autowired private EntityManager em;

    private Organization acme;
    private Organization other;

    @BeforeEach
    void setUp() {
        acme = organization("acme", "kc-a");
        other = organization("other", "kc-b");
    }

    @Test
    @DisplayName("findVerifiedByDomain ignores pending claims and returns the verified owner")
    void findVerifiedIgnoresPending() {
        domain(other, "acme.com", false);
        assertThat(repository.findVerifiedByDomain("acme.com")).isEmpty();

        OrganizationSsoDomain verified = domain(acme, "acme.com", true);

        assertThat(repository.findVerifiedByDomain("acme.com")).get()
                .extracting(OrganizationSsoDomain::getId).isEqualTo(verified.getId());
    }

    @Test
    @DisplayName("isVerifiedForOrganization is true only for THIS workspace's verified row")
    void verifiedForOrganizationIsScoped() {
        domain(acme, "acme.com", true);
        domain(other, "other.com", false);

        assertThat(repository.isVerifiedForOrganization(acme.getId(), "acme.com")).isTrue();
        assertThat(repository.isVerifiedForOrganization(other.getId(), "acme.com")).isFalse();
        assertThat(repository.isVerifiedForOrganization(other.getId(), "other.com")).isFalse();
    }

    private Organization organization(String slug, String sub) {
        User owner = new User(slug + "-owner", slug + "@example.com", AuthProvider.KEYCLOAK, sub);
        em.persist(owner);
        Organization org = new Organization(slug, slug, false, owner);
        em.persist(org);
        return org;
    }

    private OrganizationSsoDomain domain(Organization org, String name, boolean verified) {
        OrganizationSsoDomain d = new OrganizationSsoDomain(org, name, "tok-" + org.getSlug());
        if (verified) {
            d.setVerifiedAt(Instant.now());
        }
        return repository.saveAndFlush(d);
    }
}
