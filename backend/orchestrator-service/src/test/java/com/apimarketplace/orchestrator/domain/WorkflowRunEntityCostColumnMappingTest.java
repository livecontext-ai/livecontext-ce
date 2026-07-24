package com.apimarketplace.orchestrator.domain;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the run-INSERT abort bug.
 *
 * <p>{@code workflow_runs.cost_by_epoch} is {@code NOT NULL DEFAULT '{}'::jsonb}
 * (migration V411). A fresh {@link WorkflowRunEntity} leaves {@code costByEpoch}
 * null, so if the column is {@code insertable}, Hibernate emits an explicit
 * {@code cost_by_epoch = NULL} in the run INSERT, which violates the NOT NULL
 * constraint and aborts the whole transaction (observed live as
 * "current transaction is aborted, commands ignored until end of transaction
 * block"). Marking both cost columns {@code insertable = false} lets the DB
 * DEFAULT apply on INSERT (and keeps the native increment the sole writer).
 *
 * <p>This test pins that mapping without needing a live Postgres: it fails on
 * the pre-fix mapping (insertable defaulted to true) and passes post-fix. The
 * end-to-end proof against the real Flyway-migrated schema lives in the CE slot
 * e2e (the H2 integration profile builds the schema from Hibernate DDL, where
 * the column is nullable, so it cannot reproduce this Postgres-only bug).
 */
@DisplayName("WorkflowRunEntity cost columns are DB-managed (insertable=false) - V411 NOT NULL insert guard")
class WorkflowRunEntityCostColumnMappingTest {

    @Test
    @DisplayName("cost_by_epoch is insertable=false so the DB DEFAULT '{}' satisfies NOT NULL on run INSERT")
    void costByEpochIsNotInsertable() throws NoSuchFieldException {
        Column c = column("costByEpoch");
        assertThat(c.insertable())
                .as("cost_by_epoch must be insertable=false; otherwise Hibernate inserts an explicit "
                        + "NULL into the NOT NULL column and aborts the run INSERT")
                .isFalse();
        assertThat(c.updatable())
                .as("cost_by_epoch must be updatable=false; the native increment is the only writer")
                .isFalse();
    }

    @Test
    @DisplayName("cost_credits is insertable=false + updatable=false (DB default on insert, native increment on write)")
    void costCreditsIsDbManaged() throws NoSuchFieldException {
        Column c = column("costCredits");
        assertThat(c.insertable()).as("cost_credits must be insertable=false (DB DEFAULT 0 applies)").isFalse();
        assertThat(c.updatable()).as("cost_credits must be updatable=false (native increment only)").isFalse();
    }

    private static Column column(String fieldName) throws NoSuchFieldException {
        Field f = WorkflowRunEntity.class.getDeclaredField(fieldName);
        Column c = f.getAnnotation(Column.class);
        assertThat(c).as("@Column present on " + fieldName).isNotNull();
        return c;
    }
}
