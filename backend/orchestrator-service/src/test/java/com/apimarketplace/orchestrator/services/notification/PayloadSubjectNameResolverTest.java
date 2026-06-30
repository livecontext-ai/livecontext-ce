package com.apimarketplace.orchestrator.services.notification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PayloadSubjectNameResolver} (via concrete
 * {@link TriggerSubjectNameResolver}). Validates SQL parameter binding,
 * fallback semantics, and failure isolation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PayloadSubjectNameResolver (TRIGGER concrete)")
class PayloadSubjectNameResolverTest {

    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;

    private TriggerSubjectNameResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        resolver = new TriggerSubjectNameResolver();
        // Inject the mocked EntityManager into the abstract base class field via reflection.
        Field f = PayloadSubjectNameResolver.class.getDeclaredField("entityManager");
        f.setAccessible(true);
        f.set(resolver, entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
    }

    @Test
    @DisplayName("Empty subject_id set → empty map (no DB call)")
    void emptyInputNoOp() {
        Map<UUID, String> result = resolver.resolveNames(Set.of());

        assertThat(result).isEmpty();
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("Null subject_id set → empty map (no DB call)")
    void nullInputNoOp() {
        Map<UUID, String> result = resolver.resolveNames(null);

        assertThat(result).isEmpty();
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("Resolves payload.subjectName per subject_id from latest row")
    void resolvesNamesFromLatestPayload() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) new Object[]{ s1, "Slack Webhook" },
                (Object) new Object[]{ s2, "Daily Schedule" }));

        Map<UUID, String> result = resolver.resolveNames(Set.of(s1, s2));

        assertThat(result)
                .containsEntry(s1, "Slack Webhook")
                .containsEntry(s2, "Daily Schedule");
        // Bound to the resolver's subject_type - guards against UUID collision across types.
        verify(nativeQuery).setParameter("st", "TRIGGER");
        verify(nativeQuery).setParameter("ids", Set.of(s1, s2));
    }

    @Test
    @DisplayName("Null payload.subjectName falls back to default name (not null/empty in map)")
    void nullPayloadNameFallsBackToDefault() {
        UUID s1 = UUID.randomUUID();
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) new Object[]{ s1, null }));

        Map<UUID, String> result = resolver.resolveNames(Set.of(s1));

        assertThat(result).containsEntry(s1, PayloadSubjectNameResolver.DEFAULT_NAME_FALLBACK);
    }

    @Test
    @DisplayName("Blank payload.subjectName falls back to default")
    void blankPayloadNameFallsBackToDefault() {
        UUID s1 = UUID.randomUUID();
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) new Object[]{ s1, "   " }));

        Map<UUID, String> result = resolver.resolveNames(Set.of(s1));

        assertThat(result).containsEntry(s1, PayloadSubjectNameResolver.DEFAULT_NAME_FALLBACK);
    }

    @Test
    @DisplayName("DB exception is swallowed → empty map (read path stays up)")
    void dbExceptionSwallowed() {
        when(nativeQuery.getResultList()).thenThrow(new RuntimeException("DB down"));

        Map<UUID, String> result = resolver.resolveNames(Set.of(UUID.randomUUID()));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Subject IDs not in DB → absent from result map (caller renders DELETED_WORKFLOW_LABEL)")
    void missingIdsAbsentFromMap() {
        UUID present = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(nativeQuery.getResultList()).thenReturn(Arrays.asList(
                (Object) new Object[]{ present, "The One That Exists" }));

        Map<UUID, String> result = resolver.resolveNames(Set.of(present, missing));

        assertThat(result).containsOnlyKeys(present);
    }

    @Test
    @DisplayName("Payload resolvers expose distinct subject_types")
    void distinctSubjectTypesAcrossConcreteResolvers() {
        assertThat(new TriggerSubjectNameResolver().subjectType()).isEqualTo("TRIGGER");
        assertThat(new CredentialSubjectNameResolver().subjectType()).isEqualTo("CREDENTIAL");
        assertThat(new AgentTaskSubjectNameResolver().subjectType()).isEqualTo("AGENT_TASK");
        assertThat(new ApplicationSubjectNameResolver().subjectType()).isEqualTo("APPLICATION");
        assertThat(new OrganizationInvitationSubjectNameResolver().subjectType()).isEqualTo("ORG_INVITATION");
    }
}
