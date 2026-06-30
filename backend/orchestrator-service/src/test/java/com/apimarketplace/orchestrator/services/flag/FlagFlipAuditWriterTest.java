package com.apimarketplace.orchestrator.services.flag;

import com.apimarketplace.orchestrator.domain.FlagFlipAuditEntity;
import com.apimarketplace.orchestrator.repository.FlagFlipAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for the audit writer (P2.1.7).
 *
 * <p>The end-to-end Propagation.MANDATORY rollback contract is verified by an
 * integration test ({@code FlagFlipAuditTransactionalTest}, separate file) that
 * runs against an actual transaction manager. Unit-level scope here:
 * <ul>
 *   <li>The {@code @Transactional(propagation = MANDATORY)} annotation IS present
 *       on {@code recordFlip} (compile-time + reflection assertion).</li>
 *   <li>The entity is constructed with the right fields and persisted via the
 *       repository.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlagFlipAuditWriter")
class FlagFlipAuditWriterTest {

    @Mock private FlagFlipAuditRepository repository;
    private FlagFlipAuditWriter writer;

    @BeforeEach
    void setUp() {
        writer = new FlagFlipAuditWriter(repository);
    }

    @Test
    @DisplayName("recordFlip persists an entity carrying all 7 caller-supplied fields including organizationId")
    void recordFlipPersistsFields() {
        Instant before = Instant.now();
        when(repository.save(org.mockito.ArgumentMatchers.any(FlagFlipAuditEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        writer.recordFlip(
                "state-snapshot.elide-running-nodes",
                "tenant-T1",
                "org-test",
                "false",
                "true",
                "ops-user-42",
                "INC-12345 - staging ramp"
        );

        ArgumentCaptor<FlagFlipAuditEntity> captor = ArgumentCaptor.forClass(FlagFlipAuditEntity.class);
        verify(repository).save(captor.capture());
        FlagFlipAuditEntity saved = captor.getValue();

        assertEquals("state-snapshot.elide-running-nodes", saved.getFlagName());
        assertEquals("tenant-T1", saved.getTenantId());
        assertEquals("org-test", saved.getOrganizationId());
        assertEquals("false", saved.getOldValue());
        assertEquals("true", saved.getNewValue());
        assertEquals("ops-user-42", saved.getActor());
        assertEquals("INC-12345 - staging ramp", saved.getReason());
        assertNotNull(saved.getCreatedAt());
        assertFalse(saved.getCreatedAt().isBefore(before));
    }

    @Test
    @DisplayName("recordFlip allows a global flag (tenantId=null) - schema accepts NULL on tenant_id")
    void recordFlipAllowsNullTenant() {
        writer.recordFlip("global.kill-switch", null, "org-test", "off", "on", "ops", "incident response");
        ArgumentCaptor<FlagFlipAuditEntity> captor = ArgumentCaptor.forClass(FlagFlipAuditEntity.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getTenantId());
        assertEquals("org-test", captor.getValue().getOrganizationId());
    }

    @Test
    @DisplayName("recordFlip carries @Transactional(propagation = MANDATORY) - fail-the-flip-if-write-fails contract")
    void recordFlipIsMandatoryTransactional() throws NoSuchMethodException {
        Method m = FlagFlipAuditWriter.class.getMethod(
                "recordFlip", String.class, String.class, String.class, String.class, String.class, String.class, String.class);
        Transactional annotation = m.getAnnotation(Transactional.class);
        assertNotNull(annotation, "recordFlip must be @Transactional");
        assertEquals(Propagation.MANDATORY, annotation.propagation(),
                "Propagation must be MANDATORY - caller TX is required, " +
                "throw IllegalTransactionStateException if not present, " +
                "fail-the-flip-if-audit-fails per §7.6 write contract");
    }
}
