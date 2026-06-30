package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.WorkflowCategoryEntity;
import com.apimarketplace.orchestrator.repository.WorkflowCategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link WorkflowCategoryRepository}.
 * Tests category CRUD, slug lookup, display order, and active filtering.
 */
@DataJpaIntegrationTest
class WorkflowCategoryRepositoryIntegrationTest {

    @Autowired
    private WorkflowCategoryRepository categoryRepository;

    @Autowired
    private TestEntityManager entityManager;

    private WorkflowCategoryEntity createCategory(String slug, String name, int displayOrder, boolean active) {
        WorkflowCategoryEntity cat = new WorkflowCategoryEntity();
        cat.setSlug(slug);
        cat.setName(name);
        cat.setDisplayOrder(displayOrder);
        cat.setIsActive(active);
        cat.setDescription("Description for " + name);
        return cat;
    }

    private WorkflowCategoryEntity persistCategory(String slug, String name, int displayOrder, boolean active) {
        WorkflowCategoryEntity cat = createCategory(slug, name, displayOrder, active);
        entityManager.persist(cat);
        entityManager.flush();
        return cat;
    }

    @Nested
    @DisplayName("Basic CRUD operations")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve category")
        void shouldSaveAndRetrieve() {
            WorkflowCategoryEntity saved = persistCategory("automation", "Automation", 1, true);
            entityManager.clear();

            WorkflowCategoryEntity found = categoryRepository.findById(saved.getId()).orElseThrow();

            assertThat(found.getSlug()).isEqualTo("automation");
            assertThat(found.getName()).isEqualTo("Automation");
            assertThat(found.getDisplayOrder()).isEqualTo(1);
            assertThat(found.getIsActive()).isTrue();
            assertThat(found.getDescription()).isEqualTo("Description for Automation");
            assertThat(found.getCreatedAt()).isNotNull();
            assertThat(found.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should persist optional fields (icon, color)")
        void shouldPersistOptionalFields() {
            WorkflowCategoryEntity cat = createCategory("data", "Data Processing", 2, true);
            cat.setIconSlug("database");
            cat.setColor("#3498db");
            entityManager.persist(cat);
            entityManager.flush();
            entityManager.clear();

            WorkflowCategoryEntity found = categoryRepository.findById(cat.getId()).orElseThrow();
            assertThat(found.getIconSlug()).isEqualTo("database");
            assertThat(found.getColor()).isEqualTo("#3498db");
        }
    }

    @Nested
    @DisplayName("Slug-based queries")
    class SlugQueries {

        @Test
        @DisplayName("should find category by slug")
        void shouldFindBySlug() {
            persistCategory("ai-ml", "AI & ML", 1, true);
            persistCategory("integrations", "Integrations", 2, true);
            entityManager.clear();

            Optional<WorkflowCategoryEntity> found = categoryRepository.findBySlug("ai-ml");

            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("AI & ML");
        }

        @Test
        @DisplayName("should return empty for non-existent slug")
        void shouldReturnEmptyForNonExistentSlug() {
            Optional<WorkflowCategoryEntity> result = categoryRepository.findBySlug("nonexistent");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should check slug existence")
        void shouldCheckSlugExistence() {
            persistCategory("automation", "Automation", 1, true);
            entityManager.clear();

            assertThat(categoryRepository.existsBySlug("automation")).isTrue();
            assertThat(categoryRepository.existsBySlug("nonexistent")).isFalse();
        }
    }

    @Nested
    @DisplayName("Active category queries")
    class ActiveCategoryQueries {

        @Test
        @DisplayName("should find active categories ordered by display order")
        void shouldFindActiveCategoriesOrdered() {
            persistCategory("cat-c", "Category C", 3, true);
            persistCategory("cat-a", "Category A", 1, true);
            persistCategory("cat-b", "Category B", 2, true);
            persistCategory("cat-inactive", "Inactive", 4, false);
            entityManager.clear();

            List<WorkflowCategoryEntity> result = categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc();

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getSlug()).isEqualTo("cat-a");
            assertThat(result.get(1).getSlug()).isEqualTo("cat-b");
            assertThat(result.get(2).getSlug()).isEqualTo("cat-c");
        }

        @Test
        @DisplayName("should return empty when no active categories exist")
        void shouldReturnEmptyWhenNoActiveCats() {
            persistCategory("inactive", "Inactive", 1, false);
            entityManager.clear();

            List<WorkflowCategoryEntity> result = categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc();
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("All categories queries")
    class AllCategoriesQueries {

        @Test
        @DisplayName("should find all categories ordered by display order")
        void shouldFindAllCategoriesOrdered() {
            persistCategory("cat-c", "Category C", 3, true);
            persistCategory("cat-a", "Category A", 1, true);
            persistCategory("cat-inactive", "Inactive", 2, false);
            entityManager.clear();

            List<WorkflowCategoryEntity> result = categoryRepository.findAllByOrderByDisplayOrderAsc();

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getSlug()).isEqualTo("cat-a");
            assertThat(result.get(1).getSlug()).isEqualTo("cat-inactive");
            assertThat(result.get(2).getSlug()).isEqualTo("cat-c");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle categories with same display order")
        void shouldHandleSameDisplayOrder() {
            persistCategory("cat-a", "A", 1, true);
            persistCategory("cat-b", "B", 1, true);
            entityManager.clear();

            List<WorkflowCategoryEntity> result = categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc();

            assertThat(result).hasSize(2);
            // Both have same display order, so they both appear
        }

        @Test
        @DisplayName("should update display order")
        void shouldUpdateDisplayOrder() {
            WorkflowCategoryEntity cat = persistCategory("sortable", "Sortable", 5, true);
            entityManager.clear();

            WorkflowCategoryEntity toUpdate = categoryRepository.findById(cat.getId()).orElseThrow();
            toUpdate.setDisplayOrder(1);
            categoryRepository.save(toUpdate);
            entityManager.flush();
            entityManager.clear();

            WorkflowCategoryEntity updated = categoryRepository.findById(cat.getId()).orElseThrow();
            assertThat(updated.getDisplayOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle toggling active status")
        void shouldToggleActiveStatus() {
            WorkflowCategoryEntity cat = persistCategory("toggle", "Toggle", 1, true);
            entityManager.clear();

            // Deactivate
            WorkflowCategoryEntity toDeactivate = categoryRepository.findById(cat.getId()).orElseThrow();
            toDeactivate.setIsActive(false);
            categoryRepository.save(toDeactivate);
            entityManager.flush();
            entityManager.clear();

            assertThat(categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).isEmpty();

            // Reactivate
            WorkflowCategoryEntity toActivate = categoryRepository.findById(cat.getId()).orElseThrow();
            toActivate.setIsActive(true);
            categoryRepository.save(toActivate);
            entityManager.flush();
            entityManager.clear();

            assertThat(categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).hasSize(1);
        }
    }
}
