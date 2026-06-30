package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.CeLinkAudit;
import com.apimarketplace.auth.repository.CeLinkAuditRepository;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeLinkAuditService")
class CeLinkAuditServiceTest {

    @Mock private CeLinkAuditRepository repository;
    @Mock private AuditLogger auditLogger;
    @Mock private AuditLogger.Builder builder;

    private CeLinkAuditService service;

    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        service = new CeLinkAuditService(repository, auditLogger);
        // Fluent-builder stubs - lenient because the NPE-path tests never reach emitLoki.
        // user(Long) overload disambiguates from user(String).
        lenient().when(auditLogger.event(anyString())).thenReturn(builder);
        lenient().when(builder.user(nullable(Long.class))).thenReturn(builder);
        lenient().when(builder.result(anyString())).thenReturn(builder);
        lenient().when(builder.detail(anyString(), any())).thenReturn(builder);
    }

    @Test
    @DisplayName("npeOnNullInstallId - failing fast on NOT NULL columns is preferable to a vague ConstraintViolationException at flush")
    void npeOnNullInstallId() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.record(null, 42L, CeLinkAudit.ActorRole.OWNER,
                        CeLinkAudit.Event.REGISTER, 1, null, null, Map.of()))
                .withMessageContaining("installId");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("npeOnNullActorRole - same fail-fast contract for NOT NULL actor_role column")
    void npeOnNullActorRole() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.record(INSTALL, 42L, null,
                        CeLinkAudit.Event.REGISTER, 1, null, null, Map.of()))
                .withMessageContaining("actorRole");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("npeOnNullEvent - same fail-fast contract for NOT NULL event column")
    void npeOnNullEvent() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.record(INSTALL, 42L, CeLinkAudit.ActorRole.OWNER,
                        null, 1, null, null, Map.of()))
                .withMessageContaining("event");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("npeOnNullKeyVersion - same fail-fast contract for NOT NULL key_version column")
    void npeOnNullKeyVersion() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.record(INSTALL, 42L, CeLinkAudit.ActorRole.OWNER,
                        CeLinkAudit.Event.REGISTER, null, null, null, Map.of()))
                .withMessageContaining("keyVersion");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("emitsLokiOkWhenDbWriteSucceeds - happy path stores the row + emits Loki sink line")
    void emitsLokiOkWhenDbWriteSucceeds() {
        service.record(INSTALL, 42L, CeLinkAudit.ActorRole.OWNER,
                CeLinkAudit.Event.REGISTER, 1, "iphash", "ua", Map.of("k", "v"));

        verify(repository).saveAndFlush(any(CeLinkAudit.class));
        verify(auditLogger).event("ce_link.register");
        verify(builder).result("ok");
    }

    @Test
    @DisplayName("ceLinkAuditIsImmutable - JSON metadata dirty checks must not update the append-only audit table after insert")
    void ceLinkAuditIsImmutable() {
        assertThat(CeLinkAudit.class).hasAnnotation(Immutable.class);
    }

    @Test
    @DisplayName("emitsDbWriteFailedAndRethrowsWhenFlushFails - Loki sink survives DB failure so the trail is preserved")
    void emitsDbWriteFailedAndRethrowsWhenFlushFails() {
        when(repository.saveAndFlush(any(CeLinkAudit.class)))
                .thenThrow(new RuntimeException("CHECK violation"));

        assertThatThrownBy(() -> service.record(INSTALL, 42L, CeLinkAudit.ActorRole.OWNER,
                CeLinkAudit.Event.SUSPECTED_CROSS_USER_RESET, 1, null, null, Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("CHECK violation");

        verify(builder).result("db_write_failed");
    }
}
