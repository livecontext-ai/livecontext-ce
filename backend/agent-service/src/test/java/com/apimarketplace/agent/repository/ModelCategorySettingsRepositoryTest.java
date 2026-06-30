package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelCategorySettingsId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static-analysis coverage of the V156 sidecar contract. Mirrors the existing
 * {@link AgentRepositoryResetQueryTest} pattern (no Spring slice in agent-service).
 *
 * <p>What is asserted here:
 * <ul>
 *   <li>Composite-PK shape: {@link ModelCategorySettingsEntity} carries
 *       {@link IdClass} pointing at {@link ModelCategorySettingsId}.</li>
 *   <li>{@link ModelCategorySettingsId} equality + hashCode form a valid
 *       composite-key contract (required by JPA).</li>
 *   <li>Column mapping matches the V156 DDL exactly (table name + key columns).</li>
 *   <li>Repository declares the category-aware query methods callers rely on.</li>
 * </ul>
 *
 * <p>Runtime DB behaviour (cascade delete, ASC NULLS LAST, CHECK constraint) is
 * validated by Flyway on first boot + by service-level tests against a mocked
 * repository - not duplicated here.
 */
@DisplayName("ModelCategorySettings - entity + repository contract")
class ModelCategorySettingsRepositoryTest {

    @Test
    @DisplayName("Entity is mapped to model_category_settings with @IdClass(ModelCategorySettingsId)")
    void entityHasCompositePkPointingAtIdClass() {
        Entity entity = ModelCategorySettingsEntity.class.getAnnotation(Entity.class);
        assertThat(entity).isNotNull();

        Table table = ModelCategorySettingsEntity.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("model_category_settings");

        IdClass idClass = ModelCategorySettingsEntity.class.getAnnotation(IdClass.class);
        assertThat(idClass).isNotNull();
        assertThat(idClass.value()).isEqualTo(ModelCategorySettingsId.class);
    }

    @Test
    @DisplayName("Both @Id columns are model_config_id and category, matching V156 PK")
    void compositeKeyColumnsMatchV156Ddl() throws NoSuchFieldException {
        Field modelConfigId = ModelCategorySettingsEntity.class.getDeclaredField("modelConfigId");
        Field category = ModelCategorySettingsEntity.class.getDeclaredField("category");

        assertThat(modelConfigId.getAnnotation(Id.class)).isNotNull();
        assertThat(category.getAnnotation(Id.class)).isNotNull();

        assertThat(modelConfigId.getAnnotation(Column.class).name()).isEqualTo("model_config_id");
        assertThat(category.getAnnotation(Column.class).name()).isEqualTo("category");
        assertThat(category.getAnnotation(Column.class).length()).isEqualTo(32);
    }

    @Test
    @DisplayName("ModelCategorySettingsId - equals/hashCode honour both fields (JPA composite key contract)")
    void compositeKeyEqualityRespectsBothFields() {
        ModelCategorySettingsId a = new ModelCategorySettingsId(1L, "chat");
        ModelCategorySettingsId b = new ModelCategorySettingsId(1L, "chat");
        ModelCategorySettingsId differentCategory = new ModelCategorySettingsId(1L, "browser_agent");
        ModelCategorySettingsId differentModel = new ModelCategorySettingsId(2L, "chat");

        assertThat(a)
                .isEqualTo(b)
                .hasSameHashCodeAs(b)
                .isNotEqualTo(differentCategory)
                .isNotEqualTo(differentModel);
    }

    @Test
    @DisplayName("Repository extends JpaRepository<ModelCategorySettingsEntity, ModelCategorySettingsId>")
    void repositoryHasCorrectGenericBinding() {
        ParameterizedType jpa = (ParameterizedType) ModelCategorySettingsRepository.class.getGenericInterfaces()[0];
        assertThat(jpa.getRawType()).isEqualTo(JpaRepository.class);
        assertThat(jpa.getActualTypeArguments())
                .containsExactly(ModelCategorySettingsEntity.class, ModelCategorySettingsId.class);
    }

    @Test
    @DisplayName("Repository declares findByCategoryOrderByRankAscNullsLast - hot path for ranked-by-category reads")
    void repositoryDeclaresCategoryOrderedQuery() throws NoSuchMethodException {
        Method m = ModelCategorySettingsRepository.class
                .getMethod("findByCategoryOrderByRankAscNullsLast", String.class);
        assertThat(m).isNotNull();
        assertThat(m.getReturnType().getName()).isEqualTo("java.util.List");
    }

    @Test
    @DisplayName("REGRESSION (startup ApplicationContext failure): findByCategoryOrderByRankAscNullsLast carries an explicit @Query - Spring Data does NOT understand 'NullsLast' as a derivation keyword and would crash on context load")
    void categoryOrderedQueryUsesExplicitJpqlNotMethodNameDerivation() throws NoSuchMethodException {
        Method m = ModelCategorySettingsRepository.class
                .getMethod("findByCategoryOrderByRankAscNullsLast", String.class);
        org.springframework.data.jpa.repository.Query q =
                m.getAnnotation(org.springframework.data.jpa.repository.Query.class);
        assertThat(q).as("Spring Data cannot derive ORDER BY ... NULLS LAST from a method name; "
                + "an explicit @Query is required to avoid a context-load failure at startup").isNotNull();
        assertThat(q.value())
                .containsIgnoringCase("ORDER BY")
                .containsIgnoringCase("ASC NULLS LAST");
    }

    @Test
    @DisplayName("Repository declares findByModelConfigId + deleteByModelConfigId - admin UI per-row path")
    void repositoryDeclaresPerRowAdminQueries() throws NoSuchMethodException {
        assertThat(ModelCategorySettingsRepository.class.getMethod("findByModelConfigId", Long.class))
                .isNotNull();
        assertThat(ModelCategorySettingsRepository.class.getMethod("deleteByModelConfigId", Long.class))
                .isNotNull();
    }

    @Test
    @DisplayName("Entity defaults enabled to TRUE so an admin-added category row is opt-out, not opt-in")
    void enabledDefaultsToTrue() {
        ModelCategorySettingsEntity entity = new ModelCategorySettingsEntity();
        assertThat(entity.getEnabled()).isTrue();
    }
}
