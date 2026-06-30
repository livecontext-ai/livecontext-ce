package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * Regression for the e2e-caught apply failure: {@code deactivateAll()} is a
 * JPA {@code @Modifying} bulk update; the applier is deliberately not
 * {@code @Transactional} as a whole, so before the fix the post-merge
 * bookkeeping threw {@code InvalidDataAccessApiUsageException} ("Executing an
 * update/delete query") AFTER a fully successful merge - the bundle row was
 * never recorded and every sync tick re-ran the full merge forever.
 *
 * <p>The mock-based {@link ApiCatalogBundleApplierTest} cannot catch this
 * class of bug (mocked repositories have no transaction semantics), so this
 * test drives the REAL JPA repositories on an entity-generated H2 schema,
 * with the surrounding test transaction explicitly DISABLED
 * ({@code Propagation.NOT_SUPPORTED}) to reproduce the production call
 * context of the sync scheduler. It fails on the pre-fix code.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        // Self-contained H2: the app's default_schema is `catalog`, so create it
        // up front; skip any schema.sql/data.sql init (Postgres-flavored files
        // on the test classpath); let Hibernate create the entity tables.
        "spring.datasource.url=jdbc:h2:mem:txregression;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS catalog",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("bundle-tx-slice")
@DisplayName("ApiCatalogBundleApplier - bundle-row bookkeeping runs in a real transaction")
class ApiCatalogBundleApplierTxRegressionTest {

    /**
     * Minimal slice bootstrap: the real CatalogApplication drags Redis and
     * test-classpath bean collisions into @DataJpaTest, so this nested
     * configuration scopes the context to exactly the two bundle JPA
     * repositories + the JPA entities (Hibernate ignores the Spring Data JDBC
     * @Table classes in the same package).
     */
    @SpringBootConfiguration
    @Profile("bundle-tx-slice")
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ApiCatalogBundleEntity.class)
    @EnableJpaRepositories(
            basePackageClasses = ApiCatalogBundleRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {ApiCatalogBundleRepository.class, ApiCatalogBundleSyncStatusRepository.class}))
    static class SliceConfig {
    }

    @Autowired private ApiCatalogBundleRepository bundleRepo;
    @Autowired private ApiCatalogBundleSyncStatusRepository syncStatusRepo;
    @Autowired private PlatformTransactionManager txManager;

    @Test
    @DisplayName("apply() records and activates the bundle row with NO surrounding transaction")
    void applyRecordsBundleRowOutsideCallerTransaction() throws Exception {
        ApiCatalogMergeService mergeService = Mockito.mock(ApiCatalogMergeService.class);
        Mockito.when(mergeService.merge(anyList(), anyList())).thenReturn(
                new ApiCatalogMergeService.MergeResult(1, 0, 0, 0, 0, 0, 0, List.of()));
        ApiCatalogBundleApplier applier = new ApiCatalogBundleApplier(
                mergeService, bundleRepo, syncStatusRepo, new ObjectMapper(), txManager);

        String json = "{\"apis\":[{\"id\":\"11111111-1111-1111-1111-111111111111\"," +
                "\"apiName\":\"Demo\",\"tools\":[]}],\"credentialTemplates\":[]}";
        byte[] gz = gzip(json.getBytes(StandardCharsets.UTF_8));
        ApiCatalogSignedBundle bundle = new ApiCatalogSignedBundle(
                42L, 1, "c".repeat(64), "sig", "test-key", "test-issuer", 1, 0, gz.length,
                java.util.Base64.getEncoder().encodeToString(gz));

        // Pre-fix: throws InvalidDataAccessApiUsageException at deactivateAll().
        ApiCatalogBundleApplier.ApplyResult result = applier.apply(bundle, gz, "http://test");

        assertThat(result.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLIED);
        ApiCatalogBundleEntity recorded = bundleRepo.findByVersion(42L).orElseThrow();
        assertThat(recorded.isActive()).isTrue();
        assertThat(syncStatusRepo.findById(
                com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .hasValueSatisfying(s -> assertThat(s.getLastAppliedVersion()).isEqualTo(42L));
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        }
        return bos.toByteArray();
    }
}
