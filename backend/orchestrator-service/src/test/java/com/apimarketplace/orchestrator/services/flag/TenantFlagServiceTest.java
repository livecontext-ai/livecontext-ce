package com.apimarketplace.orchestrator.services.flag;

import com.apimarketplace.orchestrator.domain.TenantFlagEntity;
import com.apimarketplace.orchestrator.repository.TenantFlagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantFlagService} - the P2.3.3 in-memory cache + DB
 * backing for per-tenant kernel-runtime feature flags.
 *
 * <p>Pins the four-arm contract of the service:
 * <ol>
 *   <li>Default-OFF semantics: missing entry → false (no exception).</li>
 *   <li>Cache load on startup: DB rows seed the cache.</li>
 *   <li>Flip writes DB + audit + cache in same TX; audit-throw rolls back DB.</li>
 *   <li>Flip validates inputs (null/empty flagName/tenantId/organizationId rejected).</li>
 * </ol>
 *
 * <p>Round-10 (2026-05-20): flip + recordFlip signatures grew an organizationId
 * arg (V265 NOT NULL on flag_flip_audit). Test fixture uses {@code "org-test"}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantFlagService")
class TenantFlagServiceTest {

    private static final String ORG_ID = "org-test";

    @Mock private TenantFlagRepository repository;
    @Mock private FlagFlipAuditWriter auditWriter;

    private TenantFlagService service;

    @BeforeEach
    void setUp() {
        service = new TenantFlagService(repository, auditWriter);
    }

    @Nested
    @DisplayName("isEnabled - default-OFF read semantics")
    class IsEnabled {

        @Test
        @DisplayName("Unknown (flag, tenant) → false (default OFF, no exception)")
        void unknownReturnsFalse() {
            assertThat(service.isEnabled("any.flag", "tenant-X")).isFalse();
        }

        @Test
        @DisplayName("Known flag, unknown tenant → false")
        void knownFlagUnknownTenantReturnsFalse() {
            when(repository.save(any(TenantFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            service.flip("flag.A", "tenant-1", ORG_ID, true, "ops", "ramp");

            assertThat(service.isEnabled("flag.A", "tenant-2")).isFalse();
        }

        @Test
        @DisplayName("Empty/null tenantId → false (defensive: never NPE)")
        void emptyTenantReturnsFalse() {
            assertThat(service.isEnabled("flag.A", null)).isFalse();
            assertThat(service.isEnabled("flag.A", "")).isFalse();
            assertThat(service.isEnabled(null, "tenant-1")).isFalse();
        }
    }

    @Nested
    @DisplayName("getValue - three-state lookup")
    class GetValue {

        @Test
        @DisplayName("No row → Optional.empty (lets callers apply flag-specific defaults)")
        void unknownReturnsEmpty() {
            assertThat(service.getValue("any.flag", "tenant-X")).isEmpty();
        }

        @Test
        @DisplayName("Explicit value=true → Optional.of(true)")
        void explicitTrueReturnsOptionalTrue() {
            when(repository.save(any(TenantFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            service.flip("flag.A", "tenant-1", ORG_ID, true, "ops", "ramp");

            assertThat(service.getValue("flag.A", "tenant-1")).contains(true);
        }

        @Test
        @DisplayName("Explicit value=false → Optional.of(false) (distinguishable from missing)")
        void explicitFalseReturnsOptionalFalse() {
            when(repository.save(any(TenantFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            service.flip("flag.A", "tenant-1", ORG_ID, false, "ops", "opt-out");

            assertThat(service.getValue("flag.A", "tenant-1")).contains(false);
        }

        @Test
        @DisplayName("Unknown tenant on a known flag → Optional.empty")
        void knownFlagUnknownTenantReturnsEmpty() {
            when(repository.save(any(TenantFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            service.flip("flag.A", "tenant-1", ORG_ID, true, "ops", "ramp");

            assertThat(service.getValue("flag.A", "tenant-2")).isEmpty();
        }

        @Test
        @DisplayName("Empty/null tenantId or flagName → Optional.empty (defensive: never NPE)")
        void emptyArgsReturnEmpty() {
            assertThat(service.getValue("flag.A", null)).isEmpty();
            assertThat(service.getValue("flag.A", "")).isEmpty();
            assertThat(service.getValue(null, "tenant-1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("loadCache - startup DB warmup")
    class LoadCache {

        @Test
        @DisplayName("Loads all rows from DB into cache; subsequent isEnabled hits cache")
        void loadsAllRowsIntoCache() {
            when(repository.findAll()).thenReturn(List.of(
                    new TenantFlagEntity("flag.A", "tenant-1", true, Instant.now(), "ops"),
                    new TenantFlagEntity("flag.A", "tenant-2", false, Instant.now(), "ops"),
                    new TenantFlagEntity("flag.B", "tenant-1", true, Instant.now(), "ops")
            ));

            service.loadCache();

            assertThat(service.isEnabled("flag.A", "tenant-1")).isTrue();
            assertThat(service.isEnabled("flag.A", "tenant-2")).isFalse();
            assertThat(service.isEnabled("flag.B", "tenant-1")).isTrue();
            assertThat(service.isEnabled("flag.B", "tenant-2")).isFalse();
        }

        @Test
        @DisplayName("DB read failure at startup → cache stays empty, all flags default OFF (graceful)")
        void dbFailureLeavesCacheEmpty() {
            when(repository.findAll()).thenThrow(new RuntimeException("db-host unreachable"));

            // MUST NOT throw - startup must complete cleanly even when flag store is down.
            service.loadCache();

            assertThat(service.isEnabled("any.flag", "any-tenant")).isFalse();
        }
    }

    @Nested
    @DisplayName("flip - DB + audit + cache in same TX")
    class Flip {

        @Test
        @DisplayName("flip writes DB row, audit row, and updates cache atomically")
        void flipWritesAllThree() {
            when(repository.save(any(TenantFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            service.flip("flag.A", "tenant-1", ORG_ID, true, "ops-user", "INC-123 ramp");

            // 1. DB row persisted, with organization_id stamped
            ArgumentCaptor<TenantFlagEntity> dbCaptor = ArgumentCaptor.forClass(TenantFlagEntity.class);
            verify(repository).save(dbCaptor.capture());
            TenantFlagEntity saved = dbCaptor.getValue();
            assertThat(saved.getFlagName()).isEqualTo("flag.A");
            assertThat(saved.getTenantId()).isEqualTo("tenant-1");
            assertThat(saved.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(saved.getValue()).isTrue();
            assertThat(saved.getUpdatedBy()).isEqualTo("ops-user");

            // 2. Audit row written with orgId in slot 3
            verify(auditWriter).recordFlip(
                    "flag.A", "tenant-1", ORG_ID,
                    null,                  // old value (no prior cache entry)
                    "true",
                    "ops-user",
                    "INC-123 ramp");

            // 3. Cache updated
            assertThat(service.isEnabled("flag.A", "tenant-1")).isTrue();
        }

        @Test
        @DisplayName("flip records old value when prior cache entry exists")
        void flipRecordsOldValue() {
            when(repository.save(any(TenantFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            service.flip("flag.A", "tenant-1", ORG_ID, true, "ops", "first flip");
            service.flip("flag.A", "tenant-1", ORG_ID, false, "ops", "rollback");

            ArgumentCaptor<String> oldCaptor = ArgumentCaptor.forClass(String.class);
            // recordFlip signature: (flagName, tenantId, organizationId, oldValue, newValue, actor, reason)
            // oldValue is the 4th arg now.
            verify(auditWriter, org.mockito.Mockito.times(2)).recordFlip(
                    anyString(), anyString(), anyString(),
                    oldCaptor.capture(),
                    anyString(), anyString(), anyString());
            // First flip: no prior → old=null. Second flip: old="true".
            assertThat(oldCaptor.getAllValues()).containsExactly(null, "true");
        }

        @Test
        @DisplayName("audit-row write throws → exception propagates AND cache is NOT updated (in-memory side of fail-the-flip)")
        void auditThrowsHaltsBeforeCacheUpdate() {
            when(repository.save(any(TenantFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("audit DB down"))
                    .when(auditWriter).recordFlip(
                            anyString(), anyString(), anyString(),
                            any(), anyString(), anyString(), anyString());

            assertThatThrownBy(() ->
                    service.flip("flag.A", "tenant-1", ORG_ID, true, "ops", "will fail"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("audit DB down");

            // Cache MUST NOT have been updated - fail-the-flip contract (in-memory side).
            assertThat(service.isEnabled("flag.A", "tenant-1")).isFalse();
        }

        @Test
        @DisplayName("flip rejects null/empty flagName")
        void flipRejectsBadFlagName() {
            assertThatThrownBy(() -> service.flip(null, "tenant-1", ORG_ID, true, "ops", "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.flip("", "tenant-1", ORG_ID, true, "ops", "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(repository, never()).save(any());
            verify(auditWriter, never()).recordFlip(
                    anyString(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("flip rejects null/empty tenantId")
        void flipRejectsBadTenantId() {
            assertThatThrownBy(() -> service.flip("flag.A", null, ORG_ID, true, "ops", "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.flip("flag.A", "", ORG_ID, true, "ops", "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("flip rejects null/empty organizationId (V265 NOT NULL)")
        void flipRejectsBadOrgId() {
            assertThatThrownBy(() -> service.flip("flag.A", "tenant-1", null, true, "ops", "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.flip("flag.A", "tenant-1", "", true, "ops", "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.flip("flag.A", "tenant-1", "  ", true, "ops", "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Cost contract (audit B C5) - O(1) hot-path lookups")
    class CostContract {

        @Test
        @DisplayName("1000 isEnabled calls all hit cache (O(1) - saves run on a hot path 50+ times each)")
        void thousandLookupsAllHitCache() {
            when(repository.save(any(TenantFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            for (int i = 0; i < 100; i++) {
                service.flip("flag.A", "tenant-" + i, ORG_ID, true, "ops", "seed");
            }

            int hits = 0;
            for (int i = 0; i < 1000; i++) {
                if (service.isEnabled("flag.A", "tenant-" + (i % 100))) hits++;
            }

            assertThat(hits).isEqualTo(1000);
            verify(repository, never()).findAll();
            verify(repository, org.mockito.Mockito.times(100)).save(any(TenantFlagEntity.class));
        }
    }
}
