package com.apimarketplace.auth.service.oauth;

/**
 * Record immutable representant un profil OAuth normalise.
 * Utilisé pour unifier le traitement des differents providers OAuth.
 */
public record OAuthProfile(
    String provider,
    String providerId,
    String email,
    boolean emailVerified,
    String firstName,
    String lastName,
    String displayName,
    String avatarUrl
) {
    /**
     * Builder pour faciliter la creation d'un OAuthProfile.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String provider;
        private String providerId;
        private String email;
        private boolean emailVerified;
        private String firstName;
        private String lastName;
        private String displayName;
        private String avatarUrl;

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder providerId(String providerId) {
            this.providerId = providerId;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder emailVerified(boolean emailVerified) {
            this.emailVerified = emailVerified;
            return this;
        }

        public Builder firstName(String firstName) {
            this.firstName = firstName;
            return this;
        }

        public Builder lastName(String lastName) {
            this.lastName = lastName;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder avatarUrl(String avatarUrl) {
            this.avatarUrl = avatarUrl;
            return this;
        }

        public OAuthProfile build() {
            return new OAuthProfile(
                provider, providerId, email, emailVerified,
                firstName, lastName, displayName, avatarUrl
            );
        }
    }
}
