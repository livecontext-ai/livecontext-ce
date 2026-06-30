package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.context.RunContextService.PaginatedVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SQL-level JSONB array pagination in RunContextService.
 *
 * <p>The SQL pagination path avoids deserializing the entire array (e.g. 485 emails)
 * into Java - only the requested page leaves PostgreSQL via jsonb_array_elements
 * with LIMIT/OFFSET.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RunContextService - SQL-level array pagination")
class RunContextServicePaginatedTest {

    private static final String RUN_ID = "run_paginated_001";
    private static final String TENANT = "tenant-1";
    private static final int EPOCH = 3;

    @Mock private StorageRepository storageRepository;
    @Mock private TemplateEngine templateEngine;

    private RunContextService service;

    @BeforeEach
    void setUp() {
        service = new RunContextService(storageRepository, templateEngine);
    }

    @Nested
    @DisplayName("Pure reference detection")
    class PureRefDetection {

        @Test
        @DisplayName("Pure reference {{mcp:fetch.output.items}} is detected and uses SQL path")
        void pureReferenceUseSqlPath() {
            when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
                .thenReturn(List.of("mcp:fetch"));
            when(storageRepository.countArrayAtPath(RUN_ID, "mcp:fetch", EPOCH, TENANT, "output.items"))
                .thenReturn(485);
            when(storageRepository.getArraySliceAtPath(
                    eq(RUN_ID), eq("mcp:fetch"), eq(EPOCH), eq(TENANT),
                    eq("output.items"), eq(50), eq(0)))
                .thenReturn(List.of("{\"id\":1}", "{\"id\":2}"));

            PaginatedVariable result = service.resolveVariablePaginated(
                "{{mcp:fetch.output.items}}", RUN_ID, TENANT, EPOCH, 0, 50);

            assertThat(result).isNotNull();
            assertThat(result.totalCount()).isEqualTo(485);
            assertThat(result.items()).hasSize(2);
            assertThat(result.page()).isEqualTo(0);
            assertThat(result.pageSize()).isEqualTo(50);
        }

        @Test
        @DisplayName("Complex SpEL expression returns null (falls back to standard path)")
        void complexSpelFallsBack() {
            PaginatedVariable result = service.resolveVariablePaginated(
                "{{formatDate(mcp:fetch.output.date, 'DD/MM')}}", RUN_ID, TENANT, EPOCH, 0, 50);

            assertThat(result).isNull();
            verifyNoInteractions(storageRepository);
        }

        @Test
        @DisplayName("Expression with surrounding text returns null")
        void surroundingTextFallsBack() {
            PaginatedVariable result = service.resolveVariablePaginated(
                "Hello {{mcp:fetch.output.name}}!", RUN_ID, TENANT, EPOCH, 0, 50);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Expression with whitespace around ref is accepted")
        void whitespaceAccepted() {
            when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
                .thenReturn(List.of("mcp:fetch"));
            when(storageRepository.countArrayAtPath(RUN_ID, "mcp:fetch", EPOCH, TENANT, "output.items"))
                .thenReturn(10);
            when(storageRepository.getArraySliceAtPath(
                    eq(RUN_ID), eq("mcp:fetch"), eq(EPOCH), eq(TENANT),
                    eq("output.items"), eq(50), eq(0)))
                .thenReturn(List.of("{\"id\":1}"));

            PaginatedVariable result = service.resolveVariablePaginated(
                "{{  mcp:fetch.output.items  }}", RUN_ID, TENANT, EPOCH, 0, 50);

            assertThat(result).isNotNull();
            assertThat(result.totalCount()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("SQL delegation")
    class SqlDelegation {

        @Test
        @DisplayName("Alias token resolves to full step_key before SQL query")
        void aliasTokenResolution() {
            when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
                .thenReturn(List.of("mcp:fetch_emails", "table:processed"));
            when(storageRepository.countArrayAtPath(RUN_ID, "mcp:fetch_emails", EPOCH, TENANT, "output.items"))
                .thenReturn(100);
            when(storageRepository.getArraySliceAtPath(
                    eq(RUN_ID), eq("mcp:fetch_emails"), eq(EPOCH), eq(TENANT),
                    eq("output.items"), eq(20), eq(40)))
                .thenReturn(List.of("{\"email\":\"a@b.com\"}"));

            PaginatedVariable result = service.resolveVariablePaginated(
                "{{fetch_emails.output.items}}", RUN_ID, TENANT, EPOCH, 2, 20);

            assertThat(result).isNotNull();
            assertThat(result.totalCount()).isEqualTo(100);
            assertThat(result.page()).isEqualTo(2);
            assertThat(result.pageSize()).isEqualTo(20);
            // Offset = page * pageSize = 2 * 20 = 40
            verify(storageRepository).getArraySliceAtPath(
                RUN_ID, "mcp:fetch_emails", EPOCH, TENANT, "output.items", 20, 40);
        }

        @Test
        @DisplayName("Clamps requested page to the last available SQL page")
        void clampsRequestedPageToLastAvailableSqlPage() {
            when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
                .thenReturn(List.of("mcp:fetch_emails"));
            when(storageRepository.countArrayAtPath(RUN_ID, "mcp:fetch_emails", EPOCH, TENANT, "output.items"))
                .thenReturn(45);
            when(storageRepository.getArraySliceAtPath(
                    eq(RUN_ID), eq("mcp:fetch_emails"), eq(EPOCH), eq(TENANT),
                    eq("output.items"), eq(20), eq(40)))
                .thenReturn(List.of("{\"email\":\"last@page.com\"}"));

            PaginatedVariable result = service.resolveVariablePaginated(
                "{{fetch_emails.output.items}}", RUN_ID, TENANT, EPOCH, 99, 20);

            assertThat(result).isNotNull();
            assertThat(result.page()).isEqualTo(2);
            assertThat(result.pageSize()).isEqualTo(20);
            verify(storageRepository).getArraySliceAtPath(
                RUN_ID, "mcp:fetch_emails", EPOCH, TENANT, "output.items", 20, 40);
        }

        @Test
        @DisplayName("Returns null when step_key not found in epoch")
        void stepKeyNotFound() {
            when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
                .thenReturn(List.of("mcp:other_step"));

            PaginatedVariable result = service.resolveVariablePaginated(
                "{{mcp:missing_step.output.items}}", RUN_ID, TENANT, EPOCH, 0, 50);

            assertThat(result).isNull();
            verify(storageRepository, never()).countArrayAtPath(any(), any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("Returns null when array count is 0")
        void emptyArray() {
            when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
                .thenReturn(List.of("mcp:fetch"));
            when(storageRepository.countArrayAtPath(RUN_ID, "mcp:fetch", EPOCH, TENANT, "output.items"))
                .thenReturn(0);

            PaginatedVariable result = service.resolveVariablePaginated(
                "{{mcp:fetch.output.items}}", RUN_ID, TENANT, EPOCH, 0, 50);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Returns null when path doesn't exist in JSONB (countArrayAtPath returns null)")
        void pathNotExists() {
            when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
                .thenReturn(List.of("mcp:fetch"));
            when(storageRepository.countArrayAtPath(RUN_ID, "mcp:fetch", EPOCH, TENANT, "output.nonexistent"))
                .thenReturn(null);

            PaginatedVariable result = service.resolveVariablePaginated(
                "{{mcp:fetch.output.nonexistent}}", RUN_ID, TENANT, EPOCH, 0, 50);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("No storage rows yet for this epoch returns null")
        void noStorageRows() {
            when(storageRepository.findDistinctStepKeysByRunIdAndEpoch(RUN_ID, EPOCH, TENANT))
                .thenReturn(List.of());

            PaginatedVariable result = service.resolveVariablePaginated(
                "{{mcp:fetch.output.items}}", RUN_ID, TENANT, EPOCH, 0, 50);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Null / invalid inputs")
    class NullInputs {

        @Test
        @DisplayName("Null expression returns null")
        void nullExpression() {
            assertThat(service.resolveVariablePaginated(null, RUN_ID, TENANT, EPOCH, 0, 50)).isNull();
        }

        @Test
        @DisplayName("Null runId returns null")
        void nullRunId() {
            assertThat(service.resolveVariablePaginated("{{mcp:fetch.output.items}}", null, TENANT, EPOCH, 0, 50)).isNull();
        }

        @Test
        @DisplayName("Null tenantId returns null")
        void nullTenantId() {
            assertThat(service.resolveVariablePaginated("{{mcp:fetch.output.items}}", RUN_ID, null, EPOCH, 0, 50)).isNull();
        }

        @Test
        @DisplayName("Expression with only step key (no field path) returns null")
        void noFieldPath() {
            assertThat(service.resolveVariablePaginated("{{mcp:fetch}}", RUN_ID, TENANT, EPOCH, 0, 50)).isNull();
        }
    }
}
