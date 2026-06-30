package com.apimarketplace.catalog.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SeedManifest {

    private String version;
    private List<SeedSpec> specs;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<SeedSpec> getSpecs() {
        return specs;
    }

    public void setSpecs(List<SeedSpec> specs) {
        this.specs = specs;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SeedSpec {
        private String id;
        private String file;
        private String version;
        private String category;
        private String subcategory;

        @JsonProperty("credentialName")
        private String credentialName;

        @JsonProperty("authType")
        private String authType;

        @JsonProperty("authHeaderName")
        private String authHeaderName;

        @JsonProperty("authIn")
        private String authIn;

        @JsonProperty("iconSlug")
        private String iconSlug;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getSubcategory() {
            return subcategory;
        }

        public void setSubcategory(String subcategory) {
            this.subcategory = subcategory;
        }

        public String getCredentialName() {
            return credentialName;
        }

        public void setCredentialName(String credentialName) {
            this.credentialName = credentialName;
        }

        public String getAuthType() {
            return authType;
        }

        public void setAuthType(String authType) {
            this.authType = authType;
        }

        public String getAuthHeaderName() {
            return authHeaderName;
        }

        public void setAuthHeaderName(String authHeaderName) {
            this.authHeaderName = authHeaderName;
        }

        public String getAuthIn() {
            return authIn;
        }

        public void setAuthIn(String authIn) {
            this.authIn = authIn;
        }

        public String getIconSlug() {
            return iconSlug;
        }

        public void setIconSlug(String iconSlug) {
            this.iconSlug = iconSlug;
        }
    }
}
