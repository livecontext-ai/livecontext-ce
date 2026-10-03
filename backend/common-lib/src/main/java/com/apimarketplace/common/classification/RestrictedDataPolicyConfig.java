package com.apimarketplace.common.classification;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.env.Environment;

/**
 * Resolves, once at startup, whether this install enforces the restricted-data LLM allow-list
 * ({@link RestrictedDataPolicy#isLlmAllowListEnforced}).
 *
 * <p>Auto-configuration, so every Spring Boot service with common-lib on its classpath (and the
 * CE monolith) resolves the same answer without having to component-scan this package.
 *
 * <p>Resolution: {@code data-classification.restricted.enforce-llm-allowlist} when set;
 * otherwise OFF on the self-hosted edition ({@code auth.mode=embedded}) and ON everywhere else.
 * The static default is ON, so code that runs before this bean (or in a plain unit test) is
 * governed by the strict answer.
 */
@AutoConfiguration
public class RestrictedDataPolicyConfig {

    private static final Logger log = LoggerFactory.getLogger(RestrictedDataPolicyConfig.class);

    public static final String ENFORCE_PROPERTY = "data-classification.restricted.enforce-llm-allowlist";

    private final Environment environment;

    public RestrictedDataPolicyConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void publish() {
        boolean enforced = resolve(environment.getProperty(ENFORCE_PROPERTY),
                environment.getProperty("auth.mode", ""));
        RestrictedDataPolicy.setLlmAllowListEnforced(enforced);
        log.info("Restricted-data LLM allow-list {} (providers allowed to receive Gmail / Drive content: {})",
                enforced ? "ENFORCED" : "not enforced on this install",
                enforced ? RestrictedDataPolicy.RESTRICTED_DATA_LLM_PROVIDERS : "any");
    }

    static boolean resolve(String explicit, String authMode) {
        if (explicit != null && !explicit.isBlank()) {
            return Boolean.parseBoolean(explicit.trim());
        }
        return !"embedded".equalsIgnoreCase(authMode == null ? "" : authMode.trim());
    }
}
