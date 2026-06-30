package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspacePurgeServiceTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private WorkspaceDataPurger workspaceDataPurger;
    @Mock private OrganizationAuditService auditService;
    @Mock private EntityManager em;
    @Mock private Query query;

    private WorkspacePurgeService service;

    private UUID orgId;

    @BeforeEach
    void setUp() {
        service = new WorkspacePurgeService(organizationRepository, workspaceDataPurger, auditService);
        ReflectionTestUtils.setField(service, "em", em);
        orgId = UUID.randomUUID();
        lenient().when(em.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(anyInt(), any())).thenReturn(query);
    }

    private Organization org(boolean personal, LocalDateTime deletedAt, LocalDateTime purgedAt) {
        User owner = new User();
        owner.setId(1L);
        Organization o = new Organization("Acme", "acme", personal, owner);
        o.setId(orgId);
        o.setDeletedAt(deletedAt);
        o.setPurgedAt(purgedAt);
        o.setDeletedBy(1L);
        return o;
    }

    @Test
    @DisplayName("skips (returns false) when the org does not exist")
    void skipsMissing() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());
        assertThat(service.purgeWorkspace(orgId)).isFalse();
        verify(workspaceDataPurger, never()).purgeOperationalData(anyString());
    }

    @Test
    @DisplayName("refuses to purge the personal workspace")
    void refusesPersonal() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org(true, LocalDateTime.now().minusDays(40), null)));
        assertThat(service.purgeWorkspace(orgId)).isFalse();
        verify(workspaceDataPurger, never()).purgeOperationalData(anyString());
    }

    @Test
    @DisplayName("refuses to purge a workspace that is not soft-deleted")
    void refusesNotDeleted() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org(false, null, null)));
        assertThat(service.purgeWorkspace(orgId)).isFalse();
        verify(workspaceDataPurger, never()).purgeOperationalData(anyString());
    }

    @Test
    @DisplayName("is idempotent: skips a workspace that was already purged")
    void skipsAlreadyPurged() {
        when(organizationRepository.findById(orgId))
                .thenReturn(Optional.of(org(false, LocalDateTime.now().minusDays(40), LocalDateTime.now().minusDays(1))));
        assertThat(service.purgeWorkspace(orgId)).isFalse();
        verify(workspaceDataPurger, never()).purgeOperationalData(anyString());
    }

    @Test
    @DisplayName("purges operational data, removes members, tombstones the org, audits PURGED")
    void purgesHappyPath() {
        Organization o = org(false, LocalDateTime.now().minusDays(40), null);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(o));

        boolean result = service.purgeWorkspace(orgId);

        assertThat(result).isTrue();
        // operational purge ran for THIS org id
        verify(workspaceDataPurger).purgeOperationalData(orgId.toString());
        // memberships deleted via native SQL (org_id is UUID -> ::text cast for the String param)
        verify(em).createNativeQuery("DELETE FROM auth.organization_member WHERE organization_id::text = ?1");
        // org row kept (tombstone) with purged_at stamped, deleted_at preserved
        assertThat(o.isPurged()).isTrue();
        assertThat(o.getDeletedAt()).isNotNull();
        verify(organizationRepository).save(o);
        // audit row (retained table) records the purge
        verify(auditService).record(eq(orgId), eq(1L), eq(OrganizationAuditEvent.Type.PURGED), any());
    }
}
