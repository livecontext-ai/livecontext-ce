package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.datasource.services.VectorFeatureGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage of {@link ToolParameterUtils#validateVectorEdition} - the
 * validator-level edition gate every column-creation path funnels through
 * (agent create/add_columns, REST POST /columns, CRUD create-column).
 */
@DisplayName("ToolParameterUtils - vector edition validation")
class VectorEditionValidationTest {

    @Test
    @DisplayName("cloud (vectorAllowed=false) rejects type=vector with the gate message, including case/whitespace variants")
    void cloudRejectsVectorTypeVariants() {
        for (String variant : List.of("vector", "VECTOR", "Vector", "  vector  ")) {
            assertThat(ToolParameterUtils.validateVectorEdition(variant, false))
                    .as("variant '%s' must be rejected", variant)
                    .isEqualTo(VectorFeatureGate.DISABLED_MESSAGE);
        }
    }

    @Test
    @DisplayName("cloud leaves non-vector types and null untouched")
    void cloudAcceptsNonVectorTypes() {
        assertThat(ToolParameterUtils.validateVectorEdition("text", false)).isNull();
        assertThat(ToolParameterUtils.validateVectorEdition("select", false)).isNull();
        assertThat(ToolParameterUtils.validateVectorEdition(null, false)).isNull();
    }

    @Test
    @DisplayName("self-hosted (vectorAllowed=true) accepts vector")
    void selfHostedAcceptsVector() {
        assertThat(ToolParameterUtils.validateVectorEdition("vector", true)).isNull();
    }

    @Test
    @DisplayName("full validateColumnDefinition rejects a well-formed vector column on cloud (the edition gate fires before the dimension contract)")
    void fullValidationRejectsVectorOnCloud() {
        String error = ToolParameterUtils.validateColumnDefinition(
                "embedding", "vector", Map.of("dimension", 1536), false);
        assertThat(error).isEqualTo(VectorFeatureGate.DISABLED_MESSAGE);
    }
}
