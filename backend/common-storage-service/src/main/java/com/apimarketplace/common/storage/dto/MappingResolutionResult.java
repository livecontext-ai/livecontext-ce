package com.apimarketplace.common.storage.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DTO representant le resultat d'une resolution de mapping.
 * Classe immutable suivant les bonnes pratiques.
 */
public class MappingResolutionResult {

    private final boolean success;
    private final String error;
    private final Map<String, Object> preview;
    private final int itemCount;
    private final List<String> unresolvedFields;

    private MappingResolutionResult(Builder builder) {
        this.success = builder.success;
        this.error = builder.error;
        this.preview = builder.preview != null ? builder.preview : Collections.emptyMap();
        this.itemCount = builder.itemCount;
        this.unresolvedFields = builder.unresolvedFields != null ? builder.unresolvedFields : Collections.emptyList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static MappingResolutionResult success(Map<String, Object> preview, int itemCount) {
        return builder()
            .success(true)
            .preview(preview)
            .itemCount(itemCount)
            .build();
    }

    public static MappingResolutionResult failure(String error) {
        return builder()
            .success(false)
            .error(error)
            .preview(Collections.emptyMap())
            .itemCount(0)
            .build();
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    public Map<String, Object> getPreview() {
        return preview;
    }

    public int getItemCount() {
        return itemCount;
    }

    public List<String> getUnresolvedFields() {
        return unresolvedFields;
    }

    public static class Builder {
        private boolean success;
        private String error;
        private Map<String, Object> preview;
        private int itemCount;
        private List<String> unresolvedFields;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Builder preview(Map<String, Object> preview) {
            this.preview = preview;
            return this;
        }

        public Builder itemCount(int itemCount) {
            this.itemCount = itemCount;
            return this;
        }

        public Builder unresolvedFields(List<String> unresolvedFields) {
            this.unresolvedFields = unresolvedFields;
            return this;
        }

        public MappingResolutionResult build() {
            return new MappingResolutionResult(this);
        }
    }
}
