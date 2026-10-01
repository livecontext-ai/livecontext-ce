package com.apimarketplace.monolith;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Set;

/**
 * Community Edition monolith entry point.
 *
 * Combines all microservices into a single Spring Boot application.
 * Uses fully-qualified bean names to avoid conflicts between services
 * that have duplicate class names in different packages.
 *
 * Activated with profile: ce
 * Key properties: deployment.mode=monolith, auth.mode=embedded, storage.type=s3
 */
@SpringBootApplication(exclude = {
    org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class,
    org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
})
@EnableScheduling
@EnableAsync
@ComponentScan(
    basePackages = {
        "com.apimarketplace.monolith",
        "com.apimarketplace.orchestrator",
        "com.apimarketplace.auth",
        "com.apimarketplace.agent",
        "com.apimarketplace.conversation",
        "com.apimarketplace.catalog",
        "com.apimarketplace.datasource",
        "com.apimarketplace.interfaces",
        "com.apimarketplace.trigger",
        "com.apimarketplace.publication",
        "com.apimarketplace.storage",
        "com.apimarketplace.common.security",
        "com.apimarketplace.common.event",
        "com.apimarketplace.common.storage",
        // shared-sse-lib: WebClientSseConsumer (@Component) is consumed by
        // catalog-service's StreamingResponseHandler. Microservice mode picks
        // it up via CatalogApplication's scan; monolith must register it
        // explicitly here (webflux is on the classpath via catalog-service).
        "com.apimarketplace.sse"
    },
    excludeFilters = {
        // A @ComponentScan declared here replaces the one @SpringBootApplication carries, and with it
        // Boot's TypeExcludeFilter: a @SpringBootTest context would then scan the test classes too (and a
        // slice test such as @WebMvcTest would load every component). Same filter as
        // ConversationServiceApplication; inert at runtime.
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        // Exclude individual service Application classes to avoid multiple @SpringBootApplication
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\..+\\..+Application"),
        // Exclude gateway (WebFlux-based, not compatible with monolith servlet container)
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.gateway\\..*"),
        // Exclude cloud/WebFlux conversation streaming auto-scan. CE wires the small
        // Redis-backed adapters it needs explicitly in MonolithAdapterConfig.
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.conversation\\.streaming\\..*"),
        // Exclude only conversation controllers that depend on reactive stream state.
        // AttachmentController stays mounted in CE because it uses the regular storage service.
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.conversation\\.controller\\.v3\\.(ChatControllerV3|StreamControllerV3)"),
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.conversation\\.controller\\.internal\\..*"),
        // Exclude conversation Redis config. CE defines servlet-safe Redis adapters explicitly.
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.conversation\\.config\\.RedisConfig"),
        // Exclude storage-service SecurityConfig (MonolithSecurityConfig handles all security)
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.storage\\.config\\.SecurityConfig"),
        // Exclude auth-service SecurityConfig (MonolithSecurityConfig handles all security)
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.auth\\.config\\.SecurityConfig"),
        // Exclude the live catalog-sync REST surface. The LiteLLM/OpenRouter
        // sync is a CLOUD operation (its own javadoc says "never exposed to
        // CE"); CE receives the resulting catalog through the signed-bundle
        // and seed flows instead. Left mounted, a CE admin could POST
        // ?mode=apply and have the feeds rewrite the local catalog - including
        // renaming the curated seed names ("Kimi K2.6" -> "kimi-k2.6"), which
        // V416 only protects for rows that existed when it ran, not for the
        // ones a fresh CE inserts from the seed afterwards. Only the controller
        // is filtered: the rest of the package holds plain @Component beans
        // that are inert without it, and a package-wide regex would be a
        // broader change than the exposure warrants.
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.agent\\.catalog\\.sync\\.ModelCatalogSyncController")
    },
    nameGenerator = MonolithApplication.FullyQualifiedBeanNameGenerator.class
)
@EntityScan(basePackages = {
    "com.apimarketplace.orchestrator",
    "com.apimarketplace.auth.domain",
    "com.apimarketplace.auth.credential.domain",
    "com.apimarketplace.auth.ce",  // CeInstallState entity (V121)
    "com.apimarketplace.agent",
    "com.apimarketplace.conversation",
    "com.apimarketplace.catalog",
    "com.apimarketplace.datasource",
    "com.apimarketplace.interfaces",
    "com.apimarketplace.trigger",
    "com.apimarketplace.publication",
    "com.apimarketplace.common.storage",
    "com.apimarketplace.storage"
})
@EnableJpaRepositories(basePackages = {
    "com.apimarketplace.orchestrator",
    "com.apimarketplace.auth.repository",
    "com.apimarketplace.auth.credential.repository",
    "com.apimarketplace.auth.ce",  // CeInstallStateRepository
    "com.apimarketplace.agent",
    "com.apimarketplace.conversation",
    "com.apimarketplace.catalog",
    "com.apimarketplace.datasource",
    "com.apimarketplace.interfaces",
    "com.apimarketplace.trigger",
    "com.apimarketplace.publication",
    "com.apimarketplace.common.storage",
    "com.apimarketplace.storage"
})
public class MonolithApplication {

    private static final Set<String> PGVECTOR_REPAIRABLE_FAILED_VERSIONS = Set.of("74", "75");

    /**
     * Migrations whose CE build content deliberately differs from the cloud one, so their
     * recorded checksum can legitimately mismatch on an install created by an older image.
     *
     * <p>V44 shipped ~1 MB of captured third-party API response bodies (real usernames)
     * inside the published image. The CE build now substitutes the byte-identical no-op the
     * public export already writes (see the {@code ce} profile in migration-service/pom.xml),
     * which changes the file's checksum. Flyway refuses to start on a checksum mismatch, so
     * without this an install created by an earlier image would stop booting after a routine
     * {@code docker compose pull}, with nothing telling the operator why.
     *
     * <p>V328/V346/V366/V367 are the same story for a cloud-only launch promo: V328 seeded an
     * uncapped 20k-credit code, V346 retired it, and V366/V367 reworked then retired the reward
     * codes that carried its name. All four shipped readable inside the image even though the
     * public repo has never carried the seed. The CE build now substitutes the scrubbed copies,
     * so installs from an earlier image hit the same mismatch. They are neutralised to no-ops
     * rather than dropped: ignore-missing-migrations would tolerate their removal, but keeping
     * the versions leaves the image and the public repo describing the same history, and it is
     * the neutralised body that strips the promo name from the image.
     *
     * <p>This set MUST list every version under
     * {@code migration-service/src/main/ce-overrides/db/migration}. Adding an override without
     * adding its version here is silent: the repair bean never fires, and every install created
     * by an earlier image refuses to boot on the changed checksum after a routine
     * {@code docker compose pull}. {@code deploy/ce-export/migration-parity.test.sh} derives its
     * assertion from that directory listing so the two cannot drift apart again.
     */
    private static final Set<String> CE_NEUTRALIZED_VERSIONS = Set.of("44", "328", "346", "366", "367");

    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "ce");
        SpringApplication.run(MonolithApplication.class, args);
    }

    /**
     * Repairs the schema history ONLY for the two situations known to need it, then migrates.
     *
     * <p>Deliberately narrow. An unconditional {@code repair()} on every boot would also run
     * Flyway's {@code removeFailedMigrations}, silently deleting the record of a genuinely
     * failed migration on each restart. With {@code mixed: true} and the V204+ migrations
     * that run CREATE INDEX CONCURRENTLY outside a transaction, a failure can leave partial
     * effects, and destroying its history entry on every restart would hide exactly the
     * problem an operator needs to see.
     *
     * <p>Accepted one-off cost: every install created before V44 was neutralised repairs
     * ONCE, on its first boot after that release, because its recorded checksum no longer
     * matches. If such an install also carries a genuinely failed migration, that record is
     * cleared in the same pass. It happens once (afterwards the checksum matches and the
     * condition is false again), and the alternative is refusing to boot at all, but it is a
     * real trade rather than a free one.
     */
    @Bean
    FlywayMigrationStrategy repairKnownPgvectorFailureThenMigrate() {
        return flyway -> {
            if (hasFailedPgvectorMigrationHistory(flyway) || hasNeutralizedMigrationChecksumMismatch(flyway)) {
                flyway.repair();
            }
            flyway.migrate();
        };
    }

    /**
     * True when an applied migration this CE build intentionally neutralised no longer
     * matches its recorded checksum. Scoped to {@link #CE_NEUTRALIZED_VERSIONS} so an
     * unexpected mismatch on any other migration still fails the boot loudly, which is the
     * behaviour you want when a migration was edited after being applied.
     */
    boolean hasNeutralizedMigrationChecksumMismatch(Flyway flyway) {
        for (MigrationInfo migration : flyway.info().all()) {
            if (migration.getVersion() == null) {
                continue;
            }
            if (!CE_NEUTRALIZED_VERSIONS.contains(migration.getVersion().toString())) {
                continue;
            }
            // Only an APPLIED migration can mismatch: a pending one has no recorded
            // checksum to compare against, and isChecksumMatching() reports true for it.
            if (migration.getAppliedChecksum() == null) {
                continue;
            }
            if (!migration.isChecksumMatching()) {
                return true;
            }
        }
        return false;
    }

    boolean hasFailedPgvectorMigrationHistory(Flyway flyway) {
        for (MigrationInfo migration : flyway.info().all()) {
            if (migration.getVersion() == null || migration.getState() == null) {
                continue;
            }
            String version = migration.getVersion().toString();
            String state = migration.getState().name();
            if (PGVECTOR_REPAIRABLE_FAILED_VERSIONS.contains(version) && state.contains("FAILED")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bean name generator that uses fully-qualified class names.
     * This avoids ConflictingBeanDefinitionException when multiple services
     * have identically-named classes (e.g. CreditClientConfig, RedisConfig).
     */
    public static class FullyQualifiedBeanNameGenerator extends AnnotationBeanNameGenerator {
        @Override
        protected String buildDefaultBeanName(BeanDefinition definition, BeanDefinitionRegistry registry) {
            return definition.getBeanClassName();
        }
    }
}
