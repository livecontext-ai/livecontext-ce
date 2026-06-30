package com.apimarketplace.orchestrator.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guards for {@link WorkflowRunEntity#getOrgId()} /
 * {@link WorkflowRunEntity#getOrgRole()} - pin the column accessors so a
 * future refactor cannot silently drop the column read.
 */
@DisplayName("WorkflowRunEntity org-id column accessors")
class WorkflowRunEntityOrgPrecedenceTest {

    @Nested
    @DisplayName("getOrgId()")
    class GetOrgIdTests {

        @Test
        @DisplayName("column populated → return column")
        void columnPopulatedReturnsColumn() {
            WorkflowRunEntity entity = new WorkflowRunEntity();
            entity.setOrganizationId("org-from-column");

            assertThat(entity.getOrgId()).isEqualTo("org-from-column");
        }

        @Test
        @DisplayName("column null → return null (personal scope)")
        void columnNullReturnsNull() {
            WorkflowRunEntity entity = new WorkflowRunEntity();
            assertThat(entity.getOrgId()).isNull();
        }
    }

    @Nested
    @DisplayName("getOrgRole()")
    class GetOrgRoleTests {

        @Test
        @DisplayName("column populated → return column")
        void columnPopulatedReturnsColumn() {
            WorkflowRunEntity entity = new WorkflowRunEntity();
            entity.setOrganizationRole("OWNER");

            assertThat(entity.getOrgRole()).isEqualTo("OWNER");
        }

        @Test
        @DisplayName("column null → return null")
        void columnNullReturnsNull() {
            WorkflowRunEntity entity = new WorkflowRunEntity();
            assertThat(entity.getOrgRole()).isNull();
        }
    }
}
