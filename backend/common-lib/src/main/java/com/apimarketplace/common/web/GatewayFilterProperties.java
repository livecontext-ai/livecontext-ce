package com.apimarketplace.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the gateway authentication filter.
 * Each service declares its own public paths in application.yml:
 *
 * <pre>
 * gateway:
 *   filter:
 *     public-paths:
 *       - /health
 *       - /actuator
 *       - /api/internal/
 * </pre>
 */
@ConfigurationProperties(prefix = "gateway.filter")
public class GatewayFilterProperties {

    public static final String DEFAULT_SECRET_KEY = "";

    /**
     * List of path prefixes that do not require gateway authentication.
     * A request path matching any prefix (via {@code startsWith}) is considered public.
     */
    private List<String> publicPaths = new ArrayList<>();

    /**
     * Public path prefixes that still require gateway HMAC authentication.
     */
    private List<String> hmacRequiredPaths = new ArrayList<>();

    /**
     * Whether gateway secret verification is enabled.
     * Set to false for local development or test profiles.
     */
    private boolean verificationEnabled = true;

    /**
     * HMAC secret key shared between gateway and backend services.
     */
    private String secretKey = DEFAULT_SECRET_KEY;

    /**
     * Fail startup when verification is enabled with an unsafe built-in default.
     */
    private boolean rejectDefaultSecrets = false;

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public List<String> getHmacRequiredPaths() {
        return hmacRequiredPaths;
    }

    public void setHmacRequiredPaths(List<String> hmacRequiredPaths) {
        this.hmacRequiredPaths = hmacRequiredPaths;
    }

    public boolean isVerificationEnabled() {
        return verificationEnabled;
    }

    public void setVerificationEnabled(boolean verificationEnabled) {
        this.verificationEnabled = verificationEnabled;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public boolean isRejectDefaultSecrets() {
        return rejectDefaultSecrets;
    }

    public void setRejectDefaultSecrets(boolean rejectDefaultSecrets) {
        this.rejectDefaultSecrets = rejectDefaultSecrets;
    }
}
