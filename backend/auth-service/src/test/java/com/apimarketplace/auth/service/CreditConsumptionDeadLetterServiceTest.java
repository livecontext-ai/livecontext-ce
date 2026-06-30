package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditConsumptionDeadLetterEntity;
import com.apimarketplace.auth.domain.CreditConsumptionDeadLetterEntity.Status;
import com.apimarketplace.auth.repository.CreditConsumptionDeadLetterRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreditConsumptionDeadLetterService - retry and reconciliation")
class CreditConsumptionDeadLetterServiceTest {

    @Mock
    private CreditConsumptionDeadLetterRepository repository;

    @Mock
    private CreditConsumptionClient creditClient;

    @Captor
    private ArgumentCaptor<CreditConsumptionDeadLetterEntity> entityCaptor;

    private CreditConsumptionDeadLetterService service;

    private static final String TENANT_ID = "42";
    private static final String SOURCE_TYPE = "AGENT_EXECUTION";
    private static final String SOURCE_ID = "exec-abc";
    private static final String PROVIDER = "openai";
    private static final String MODEL = "gpt-4o";
    // Round-8 audit fix: post-V263 every dead-letter persist requires a non-null
    // orgId. Tests now pass an explicit fixture orgId; the 8-arg interface default
    // is exercised by integration tests with a bound HTTP request context.
    private static final String ORG_ID = "11111111-2222-3333-4444-555555555555";

    @BeforeEach
    void setUp() {
        service = new CreditConsumptionDeadLetterService(repository, creditClient, new SimpleMeterRegistry());
    }

    // =====================================================================
    // persistFailedConsumption
    // =====================================================================

    @Nested
    @DisplayName("persistFailedConsumption")
    class PersistFailedConsumption {

        @Test
        @DisplayName("should create a PENDING dead-letter entry with all fields")
        void shouldCreatePendingEntry() {
            service.persistFailedConsumption(TENANT_ID, SOURCE_TYPE, SOURCE_ID,
                    PROVIDER, MODEL, 1000, 500, "Connection refused", ORG_ID);

            verify(repository).save(entityCaptor.capture());
            CreditConsumptionDeadLetterEntity entry = entityCaptor.getValue();

            assertThat(entry.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(entry.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(entry.getSourceType()).isEqualTo(SOURCE_TYPE);
            assertThat(entry.getSourceId()).isEqualTo(SOURCE_ID);
            assertThat(entry.getProvider()).isEqualTo(PROVIDER);
            assertThat(entry.getModel()).isEqualTo(MODEL);
            assertThat(entry.getPromptTokens()).isEqualTo(1000);
            assertThat(entry.getCompletionTokens()).isEqualTo(500);
            assertThat(entry.getErrorReason()).isEqualTo("Connection refused");
        }

        @Test
        @DisplayName("should handle null tokens without error")
        void shouldHandleNullTokens() {
            service.persistFailedConsumption(TENANT_ID, SOURCE_TYPE, SOURCE_ID,
                    PROVIDER, MODEL, null, null, "timeout", ORG_ID);

            verify(repository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getPromptTokens()).isNull();
            assertThat(entityCaptor.getValue().getCompletionTokens()).isNull();
        }

        @Test
        @DisplayName("should not throw when repository.save fails (broad catch in impl swallows + logs)")
        void shouldNotThrowWhenRepositoryFails() {
            doThrow(new RuntimeException("DB down")).when(repository).save(any());

            assertThatCode(() ->
                    service.persistFailedConsumption(TENANT_ID, SOURCE_TYPE, SOURCE_ID,
                            PROVIDER, MODEL, 100, 50, "error", ORG_ID)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Round-8 audit fix: null orgId fails-fast with NPE BEFORE the try/catch")
        void shouldFailFastOnNullOrgId() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    service.persistFailedConsumption(TENANT_ID, SOURCE_TYPE, SOURCE_ID,
                            PROVIDER, MODEL, 100, 50, "error", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("organizationId required");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("should handle WORKFLOW_NODE source type")
        void shouldHandleWorkflowNodeSource() {
            service.persistFailedConsumption(TENANT_ID, "WORKFLOW_NODE",
                    "run:node:0:0:0:0", null, null, null, null, "timeout", ORG_ID);

            verify(repository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getSourceType()).isEqualTo("WORKFLOW_NODE");
            assertThat(entityCaptor.getValue().getProvider()).isNull();
        }

        @Test
        @DisplayName("should handle CHAT_CONVERSATION source type")
        void shouldHandleChatConversationSource() {
            service.persistFailedConsumption(TENANT_ID, "CHAT_CONVERSATION",
                    "conv-123", "anthropic", "claude-3", 500, 200, "503", ORG_ID);

            verify(repository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getSourceType()).isEqualTo("CHAT_CONVERSATION");
        }

        @Test
        @DisplayName("Phase 6 CC-2: 9-arg overload stamps organization_id (regression - pre-fix path dropped orgId leaving V261 NOT NULL column null)")
        void shouldStampOrganizationIdOn9ArgOverload() {
            // Pre-fix: persistFailedConsumption only knew the 8-arg signature, so
            // entry.setOrganizationId(...) was never called. With V261 NOT NULL on
            // credit_consumption_dead_letter.organization_id, every async-thread
            // retry exhaustion would crash the daemon. This test pins the 9-arg
            // canonical signature plus the entity.setOrganizationId stamp.
            service.persistFailedConsumption(TENANT_ID, SOURCE_TYPE, SOURCE_ID,
                    PROVIDER, MODEL, 100, 50, "timeout",
                    "11111111-2222-3333-4444-555555555555");

            verify(repository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getOrganizationId())
                    .isEqualTo("11111111-2222-3333-4444-555555555555");
        }
    }

    // =====================================================================
    // retryFailedConsumptions
    // =====================================================================

    @Nested
    @DisplayName("retryFailedConsumptions")
    class RetryFailedConsumptions {

        private CreditConsumptionDeadLetterEntity createEntry(Status status, int retryCount) {
            CreditConsumptionDeadLetterEntity entry = new CreditConsumptionDeadLetterEntity();
            entry.setId(UUID.randomUUID());
            entry.setTenantId(TENANT_ID);
            entry.setSourceType(SOURCE_TYPE);
            entry.setSourceId(SOURCE_ID);
            entry.setProvider(PROVIDER);
            entry.setModel(MODEL);
            entry.setPromptTokens(1000);
            entry.setCompletionTokens(500);
            entry.setStatus(status);
            entry.setRetryCount(retryCount);
            entry.setCreatedAt(Instant.now());
            return entry;
        }

        @Test
        @DisplayName("should do nothing when no pending entries exist")
        void shouldDoNothingWhenEmpty() {
            when(repository.findByStatusInOrderByCreatedAtAsc(anyList()))
                    .thenReturn(List.of());

            service.retryFailedConsumptions();

            verify(creditClient, never()).consumeCredits(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should reconcile entry on successful retry")
        void shouldReconcileOnSuccess() {
            CreditConsumptionDeadLetterEntity entry = createEntry(Status.PENDING, 0);
            when(repository.findByStatusInOrderByCreatedAtAsc(anyList()))
                    .thenReturn(List.of(entry));
            when(creditClient.consumeCredits(TENANT_ID, SOURCE_TYPE, SOURCE_ID,
                    PROVIDER, MODEL, 1000, 500))
                    .thenReturn(Map.of("success", true));

            service.retryFailedConsumptions();

            verify(repository).save(entityCaptor.capture());
            CreditConsumptionDeadLetterEntity saved = entityCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(Status.RECONCILED);
            assertThat(saved.getRetryCount()).isEqualTo(1);
            assertThat(saved.getErrorReason()).isNull();
        }

        @Test
        @DisplayName("should mark as FAILED when 402 (insufficient credits)")
        void shouldMarkFailedOn402() {
            CreditConsumptionDeadLetterEntity entry = createEntry(Status.PENDING, 0);
            when(repository.findByStatusInOrderByCreatedAtAsc(anyList()))
                    .thenReturn(List.of(entry));
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of("success", false, "error", "402 Insufficient credits"));

            service.retryFailedConsumptions();

            verify(repository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getStatus()).isEqualTo(Status.FAILED);
        }

        @Test
        @DisplayName("should mark as FAILED after reaching MAX_RETRIES (10)")
        void shouldMarkFailedAfterMaxRetries() {
            CreditConsumptionDeadLetterEntity entry = createEntry(Status.RETRYING, 9);
            when(repository.findByStatusInOrderByCreatedAtAsc(anyList()))
                    .thenReturn(List.of(entry));
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of("success", false, "error", "service unavailable"));

            service.retryFailedConsumptions();

            verify(repository).save(entityCaptor.capture());
            CreditConsumptionDeadLetterEntity saved = entityCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(Status.FAILED);
            assertThat(saved.getRetryCount()).isEqualTo(10);
        }

        @Test
        @DisplayName("should keep RETRYING when under MAX_RETRIES and non-402 error")
        void shouldKeepRetryingUnderMax() {
            CreditConsumptionDeadLetterEntity entry = createEntry(Status.PENDING, 3);
            when(repository.findByStatusInOrderByCreatedAtAsc(anyList()))
                    .thenReturn(List.of(entry));
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of("success", false, "error", "timeout"));

            service.retryFailedConsumptions();

            verify(repository).save(entityCaptor.capture());
            CreditConsumptionDeadLetterEntity saved = entityCaptor.getValue();
            // Status stays RETRYING (not FAILED since retryCount 4 < 10)
            assertThat(saved.getStatus()).isEqualTo(Status.RETRYING);
            assertThat(saved.getRetryCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("should handle exception from consumeCredits and mark FAILED at max retries")
        void shouldHandleExceptionFromConsumeCredits() {
            CreditConsumptionDeadLetterEntity entry = createEntry(Status.RETRYING, 9);
            when(repository.findByStatusInOrderByCreatedAtAsc(anyList()))
                    .thenReturn(List.of(entry));
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Connection reset"));

            service.retryFailedConsumptions();

            verify(repository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getStatus()).isEqualTo(Status.FAILED);
            assertThat(entityCaptor.getValue().getErrorReason()).isEqualTo("Connection reset");
        }

        @Test
        @DisplayName("should process maximum BATCH_SIZE (50) entries per run")
        void shouldRespectBatchSize() {
            List<CreditConsumptionDeadLetterEntity> entries = new ArrayList<>();
            for (int i = 0; i < 75; i++) {
                entries.add(createEntry(Status.PENDING, 0));
            }
            when(repository.findByStatusInOrderByCreatedAtAsc(anyList()))
                    .thenReturn(entries);
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of("success", true));

            service.retryFailedConsumptions();

            // Only 50 should be processed (BATCH_SIZE)
            verify(creditClient, times(50)).consumeCredits(any(), any(), any(), any(), any(), any(), any());
            verify(repository, times(50)).save(any());
        }

        @Test
        @DisplayName("should process mix of PENDING and RETRYING entries")
        void shouldProcessMixedStatuses() {
            CreditConsumptionDeadLetterEntity pending = createEntry(Status.PENDING, 0);
            CreditConsumptionDeadLetterEntity retrying = createEntry(Status.RETRYING, 5);
            when(repository.findByStatusInOrderByCreatedAtAsc(anyList()))
                    .thenReturn(List.of(pending, retrying));
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of("success", true));

            service.retryFailedConsumptions();

            verify(repository, times(2)).save(entityCaptor.capture());
            List<CreditConsumptionDeadLetterEntity> saved = entityCaptor.getAllValues();
            assertThat(saved).allMatch(e -> e.getStatus() == Status.RECONCILED);
        }

        @Test
        @DisplayName("should set lastRetryAt on each attempt")
        void shouldSetLastRetryAt() {
            Instant before = Instant.now();
            CreditConsumptionDeadLetterEntity entry = createEntry(Status.PENDING, 0);
            when(repository.findByStatusInOrderByCreatedAtAsc(anyList()))
                    .thenReturn(List.of(entry));
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of("success", true));

            service.retryFailedConsumptions();

            verify(repository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getLastRetryAt()).isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("should mark as FAILED on Insufficient credits error message")
        void shouldMarkFailedOnInsufficientErrorMessage() {
            CreditConsumptionDeadLetterEntity entry = createEntry(Status.PENDING, 0);
            when(repository.findByStatusInOrderByCreatedAtAsc(anyList()))
                    .thenReturn(List.of(entry));
            when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Map.of("success", false, "error", "Insufficient credits: balance=0, required=5"));

            service.retryFailedConsumptions();

            verify(repository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getStatus()).isEqualTo(Status.FAILED);
        }
    }

    // =====================================================================
    // countPendingForTenant
    // =====================================================================

    @Nested
    @DisplayName("countPendingForTenant")
    class CountPending {

        @Test
        @DisplayName("should delegate to repository with PENDING and RETRYING statuses")
        void shouldDelegateCorrectly() {
            when(repository.countByTenantIdAndStatusIn(eq(TENANT_ID), anyList()))
                    .thenReturn(7L);

            long count = service.countPendingForTenant(TENANT_ID);

            assertThat(count).isEqualTo(7L);
            verify(repository).countByTenantIdAndStatusIn(
                    eq(TENANT_ID),
                    eq(List.of(Status.PENDING, Status.RETRYING)));
        }
    }
}
