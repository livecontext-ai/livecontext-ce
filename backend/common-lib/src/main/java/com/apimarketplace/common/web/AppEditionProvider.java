package com.apimarketplace.common.web;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Single source of truth for the deployment edition.
 *
 * <p>Resolution: {@code app.edition} is the canonical explicit signal. When it
 * is absent, the legacy fallback is the presence of the {@code ce} token in
 * {@code SPRING_PROFILES_ACTIVE} (additive - coexists with {@code prod},
 * {@code docker}, test profiles, etc.). Anything else resolves to CLOUD.
 *
 * <p>Spring profile names are case-sensitive ({@code Profiles.of("ce")} does
 * NOT match {@code CE}). Whitespace around the token is handled by Spring's
 * own tokenizer.
 *
 * <p>CE topology = {@code monolith-service} only. Other services have no
 * {@code application-ce.yml}; activating the {@code ce} profile on them is a
 * no-op for property loading. Without {@code app.edition}, this provider still
 * resolves that profile to {@link AppEdition#CE_FREE} for backward
 * compatibility.
 *
 * <p>The boot-summary {@link #logSummary()} runs once at bean construction.
 * It will NOT re-fire on {@code @RefreshScope} reload - acceptable for a
 * static deployment-mode flag.
 */
public class AppEditionProvider {

    private static final Logger log = LoggerFactory.getLogger(AppEditionProvider.class);

    /**
     * Drift-warning message format used when an explicit override clashes with
     * CE Free expectations. Exposed as a constant so regression tests can pin
     * it without relying on volatile prose.
     *
     * <p>Args: {@code (flagName, actualValue, expectedValue, flagEnvVarName, expectedValue)}.
     */
    public static final String DRIFT_WARN_FORMAT =
            "[edition] profile=ce active but %s=%s (expected %s). "
                    + "Either set %s=%s or remove 'ce' from SPRING_PROFILES_ACTIVE.";

    public static final String EDITION_DRIFT_WARN_FORMAT =
            "[edition] app.edition=%s but %s=%s (expected %s). "
                    + "Set %s=%s or choose app.edition=ce for Community Edition behavior.";

    public static final String INVALID_EDITION_ERROR_FORMAT =
            "[edition] Invalid app.edition=%s. Valid values: ce, self-hosted-enterprise, cloud, dedicated-cloud.";

    public enum AppEdition {
        CE_FREE("ce"),
        SELF_HOSTED_ENTERPRISE("self-hosted-enterprise"),
        CLOUD("cloud"),
        DEDICATED_CLOUD("dedicated-cloud");

        private final String configValue;

        AppEdition(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }

        static Optional<AppEdition> fromConfig(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String normalized = raw.trim()
                    .toLowerCase(Locale.ROOT)
                    .replace('_', '-');
            return switch (normalized) {
                case "ce", "ce-free", "community", "community-edition" -> Optional.of(CE_FREE);
                case "self-hosted-enterprise", "self-hosted", "selfhosted-enterprise",
                        "selfhosted", "enterprise-self-hosted" -> Optional.of(SELF_HOSTED_ENTERPRISE);
                case "cloud", "saas" -> Optional.of(CLOUD);
                case "dedicated-cloud", "dedicated", "dedicated-instance" -> Optional.of(DEDICATED_CLOUD);
                default -> Optional.empty();
            };
        }
    }

    /** A flag whose effective value diverges between editions. */
    private record FlagSpec(
            String name,
            String ceFreeExpected,
            String selfHostedEnterpriseExpected,
            String cloudExpected,
            String dedicatedCloudExpected,
            boolean criticalFailClosed) {

        String envVarName() {
            return name.toUpperCase().replace('.', '_').replace('-', '_');
        }

        String expectedFor(AppEdition edition) {
            return switch (edition) {
                case CE_FREE -> ceFreeExpected;
                case SELF_HOSTED_ENTERPRISE -> selfHostedEnterpriseExpected;
                case CLOUD -> cloudExpected;
                case DEDICATED_CLOUD -> dedicatedCloudExpected;
            };
        }
    }

    /**
     * The derived flags that together encode the edition surface. The order
     * here is the order used in the INFO boot summary.
     */
    private static final List<FlagSpec> FLAGS = List.of(
            new FlagSpec("deployment.mode", "monolith", "monolith", "microservice", "microservice", false),
            new FlagSpec("auth.mode", "embedded", "keycloak", "keycloak", "keycloak", true),
            new FlagSpec("credit.unlimited", "true", "false", "false", "false", true),
            new FlagSpec("credit.consumption.enabled", "true", "true", "true", "true", false),
            new FlagSpec("plan-limits.enabled", "false", "true", "true", "true", true),
            new FlagSpec("billing.provider", "none", "none", "stripe", "stripe", false),
            new FlagSpec("marketplace.mode", "remote", "remote", "local", "local", false)
    );

    private final AppEdition edition;
    private final Environment env;

    public AppEditionProvider(Environment env) {
        this.env = Objects.requireNonNull(env, "Environment must not be null");
        this.edition = resolveEdition(env);
    }

    private AppEdition resolveEdition(Environment env) {
        String explicit = env.getProperty("app.edition", "");
        Optional<AppEdition> explicitEdition = AppEdition.fromConfig(explicit);
        if (explicitEdition.isPresent()) {
            return explicitEdition.get();
        }
        if (explicit != null && !explicit.isBlank()) {
            String message = String.format(INVALID_EDITION_ERROR_FORMAT, explicit.trim());
            log.error(message);
            throw new IllegalArgumentException(message);
        }
        return env.acceptsProfiles(Profiles.of("ce")) ? AppEdition.CE_FREE : AppEdition.CLOUD;
    }

    public AppEdition get() {
        return edition;
    }

    /**
     * Legacy name retained for existing callers. It intentionally means
     * Community Edition only, not Self-Hosted Enterprise.
     */
    public boolean isCe() {
        return isCeFree();
    }

    public boolean isCeFree() {
        return edition == AppEdition.CE_FREE;
    }

    public boolean isSelfHostedEnterprise() {
        return edition == AppEdition.SELF_HOSTED_ENTERPRISE;
    }

    public boolean isSelfHosted() {
        return edition == AppEdition.CE_FREE || edition == AppEdition.SELF_HOSTED_ENTERPRISE;
    }

    public boolean isCloud() {
        return edition == AppEdition.CLOUD;
    }

    public boolean isDedicatedCloud() {
        return edition == AppEdition.DEDICATED_CLOUD;
    }

    public boolean isManagedCloud() {
        return edition == AppEdition.CLOUD || edition == AppEdition.DEDICATED_CLOUD;
    }

    public boolean hasCeFreeUnlimitedLocalResources() {
        return edition == AppEdition.CE_FREE;
    }

    @PostConstruct
    void logSummary() {
        StringBuilder summary = new StringBuilder("[edition] ")
                .append(edition)
                .append(" app.edition=")
                .append(edition.configValue());
        List<String> fatalDrifts = new ArrayList<>();
        for (FlagSpec flag : FLAGS) {
            String expected = flag.expectedFor(edition);
            String actual = env.getProperty(flag.name(), expected);
            summary.append(' ').append(flag.name()).append('=').append(actual);
            if (expected.equalsIgnoreCase(actual)) {
                continue;
            }
            if (isCeFree()) {
                log.warn(String.format(
                        DRIFT_WARN_FORMAT,
                        flag.name(), actual, expected, flag.envVarName(), expected));
                if (flag.criticalFailClosed()) {
                    fatalDrifts.add(flag.name() + "=" + actual + " (expected " + expected + ")");
                }
            } else if (flag.criticalFailClosed() || isSelfHostedEnterprise()) {
                log.warn(String.format(
                        EDITION_DRIFT_WARN_FORMAT,
                        edition.configValue(), flag.name(), actual, expected, flag.envVarName(), expected));
                if (flag.criticalFailClosed()) {
                    fatalDrifts.add(flag.name() + "=" + actual + " (expected " + expected + ")");
                }
            }
        }
        if (!fatalDrifts.isEmpty()) {
            throw new IllegalStateException(
                    "[edition] Refusing to start " + edition + " with incompatible flags: "
                            + String.join(", ", fatalDrifts));
        }
        log.info(summary.toString());
    }
}
