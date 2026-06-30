package com.apimarketplace.datasource.services;

import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VectorFeatureGate} - edition resolution and the snapshot-clone
 * sanitizer. Vector columns are a self-hosted-only feature: CE Free and
 * Self-Hosted Enterprise allow them, managed cloud (CLOUD / DEDICATED_CLOUD)
 * blocks them.
 */
@DisplayName("VectorFeatureGate")
class VectorFeatureGateTest {

    private static VectorFeatureGate gateFor(String appEdition) {
        MockEnvironment env = new MockEnvironment();
        if (appEdition != null) {
            env.setProperty("app.edition", appEdition);
        }
        return new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env));
    }

    private static ColumnMappingSpec spec(ColumnType type) {
        return new ColumnMappingSpec("data.col", type, ColumnStructure.SCALAR, Map.of(), Map.of());
    }

    @Test
    @DisplayName("self-hosted editions allow vector columns (ce, self-hosted-enterprise)")
    void selfHostedAllows() {
        assertThat(gateFor("ce").isVectorAllowed()).isTrue();
        assertThat(gateFor("self-hosted-enterprise").isVectorAllowed()).isTrue();
    }

    @Test
    @DisplayName("managed cloud blocks vector columns (cloud, dedicated-cloud, and the no-config default)")
    void managedCloudBlocks() {
        assertThat(gateFor("cloud").isVectorAllowed()).isFalse();
        assertThat(gateFor("dedicated-cloud").isVectorAllowed()).isFalse();
        assertThat(gateFor(null).isVectorAllowed()).isFalse(); // default resolution = CLOUD
    }

    @Test
    @DisplayName("invalid app.edition fails CLOSED at the SSOT - AppEditionProvider refuses to construct, so no gate can exist unlocked")
    void invalidEditionFailsClosed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateFor("not-an-edition"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ce Spring profile fallback (no app.edition) resolves to allowed")
    void ceProfileFallbackAllows() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("ce", "docker");
        assertThat(new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env)).isVectorAllowed()).isTrue();
    }

    @Test
    @DisplayName("snapshot strip removes ONLY vector columns on managed cloud, preserving order")
    void stripRemovesOnlyVectorColumns() {
        VectorFeatureGate cloud = gateFor("cloud");
        Map<String, ColumnMappingSpec> mapping = new LinkedHashMap<>();
        mapping.put("title", spec(ColumnType.TEXT));
        mapping.put("embedding", spec(ColumnType.VECTOR));
        mapping.put("score", spec(ColumnType.NUMBER));

        Map<String, ColumnMappingSpec> stripped = cloud.stripDisallowedVectorColumns(mapping);

        assertThat(stripped.keySet()).containsExactly("title", "score");
    }

    @Test
    @DisplayName("snapshot strip is identity on self-hosted and for null/vector-free specs")
    void stripIsIdentityWhenAllowedOrIrrelevant() {
        Map<String, ColumnMappingSpec> withVector = new LinkedHashMap<>();
        withVector.put("embedding", spec(ColumnType.VECTOR));

        assertThat(gateFor("ce").stripDisallowedVectorColumns(withVector)).isSameAs(withVector);
        assertThat(gateFor("cloud").stripDisallowedVectorColumns(null)).isNull();

        Map<String, ColumnMappingSpec> noVector = new LinkedHashMap<>();
        noVector.put("title", spec(ColumnType.TEXT));
        assertThat(gateFor("cloud").stripDisallowedVectorColumns(noVector)).isSameAs(noVector);
    }

    @Test
    @DisplayName("findVectorColumn surfaces the first vector column or null")
    void findVectorColumn() {
        Map<String, ColumnMappingSpec> mapping = new LinkedHashMap<>();
        mapping.put("title", spec(ColumnType.TEXT));
        mapping.put("embedding", spec(ColumnType.VECTOR));

        assertThat(VectorFeatureGate.findVectorColumn(mapping)).isEqualTo("embedding");
        assertThat(VectorFeatureGate.findVectorColumn(Map.of("t", spec(ColumnType.TEXT)))).isNull();
        assertThat(VectorFeatureGate.findVectorColumn(null)).isNull();
    }
}
