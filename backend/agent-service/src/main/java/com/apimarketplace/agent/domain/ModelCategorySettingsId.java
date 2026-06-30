package com.apimarketplace.agent.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite PK for {@link ModelCategorySettingsEntity}: a model-config row
 * scoped by its category. See V156 migration.
 */
public class ModelCategorySettingsId implements Serializable {

    private Long modelConfigId;
    private String category;

    public ModelCategorySettingsId() {}

    public ModelCategorySettingsId(Long modelConfigId, String category) {
        this.modelConfigId = modelConfigId;
        this.category = category;
    }

    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelCategorySettingsId that)) return false;
        return Objects.equals(modelConfigId, that.modelConfigId)
                && Objects.equals(category, that.category);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelConfigId, category);
    }
}
