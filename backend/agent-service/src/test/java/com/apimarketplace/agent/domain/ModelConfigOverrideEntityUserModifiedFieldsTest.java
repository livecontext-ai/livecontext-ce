package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ModelConfigOverrideEntity#addUserModifiedField(String)}.
 */
@DisplayName("ModelConfigOverrideEntity - addUserModifiedField")
class ModelConfigOverrideEntityUserModifiedFieldsTest {

    @Test
    @DisplayName("Adds field to empty array")
    void addsFieldToEmptyArray() {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setUserModifiedFields(new String[0]);

        e.addUserModifiedField("ranking");

        assertThat(e.getUserModifiedFields()).containsExactly("ranking");
    }

    @Test
    @DisplayName("Adds field to null array")
    void addsFieldToNullArray() {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setUserModifiedFields(null);

        e.addUserModifiedField("ranking");

        assertThat(e.getUserModifiedFields()).containsExactly("ranking");
    }

    @Test
    @DisplayName("Deduplicates existing field")
    void deduplicatesExistingField() {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setUserModifiedFields(new String[]{"ranking", "enabled"});

        e.addUserModifiedField("ranking");

        assertThat(e.getUserModifiedFields()).containsExactly("ranking", "enabled");
    }

    @Test
    @DisplayName("Appends new field to existing array")
    void appendsNewField() {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setUserModifiedFields(new String[]{"enabled"});

        e.addUserModifiedField("ranking");

        assertThat(e.getUserModifiedFields()).containsExactly("enabled", "ranking");
    }

    @Test
    @DisplayName("Null field is a no-op")
    void nullFieldIsNoOp() {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setUserModifiedFields(new String[]{"enabled"});

        e.addUserModifiedField(null);

        assertThat(e.getUserModifiedFields()).containsExactly("enabled");
    }

    @Test
    @DisplayName("Multiple distinct fields accumulate")
    void multipleDistinctFieldsAccumulate() {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setUserModifiedFields(new String[0]);

        e.addUserModifiedField("ranking");
        e.addUserModifiedField("enabled");
        e.addUserModifiedField("priceInput");
        e.addUserModifiedField("ranking");

        assertThat(e.getUserModifiedFields())
                .containsExactly("ranking", "enabled", "priceInput");
    }
}
