package com.apimarketplace.auth.domain;

public enum AuthProvider {
    GOOGLE("google"),
    GITHUB("github"),
    LOCAL("local"),
    KEYCLOAK("keycloak");

    private final String provider;

    AuthProvider(String provider) {
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }

    public static AuthProvider fromRegistrationId(String id) {
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "google" -> GOOGLE;
            case "github" -> GITHUB;
            case "keycloak" -> KEYCLOAK;
            default -> throw new IllegalArgumentException("Unknown provider: " + id);
        };
    }
}
