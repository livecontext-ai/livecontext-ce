package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspacePurgeSchedulerTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private WorkspacePurgeService purgeService;

    private WorkspacePurgeScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WorkspacePurgeScheduler(organizationRepository, purgeService);
        ReflectionTestUtils.setField(scheduler, "graceDays", 30);
    }

    private Organization orgWithId() {
        Organization o = new Organization("X", "x", false, new User());
        o.setId(UUID.randomUUID());
        return o;
    }

    @Test
    @DisplayName("queries the grace-period cutoff (~now - graceDays) and purges every result")
    void queriesCutoffAndPurgesEach() {
        Organization a = orgWithId();
        Organization b = orgWithId();
        when(organizationRepository.findWorkspacesPastGracePeriod(any())).thenReturn(List.of(a, b));

        scheduler.purgeExpiredWorkspaces();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(organizationRepository).findWorkspacesPastGracePeriod(cutoff.capture());
        assertThat(cutoff.getValue())
                .isBefore(LocalDateTime.now().minusDays(29))
                .isAfter(LocalDateTime.now().minusDays(31));
        verify(purgeService).purgeWorkspace(a.getId());
        verify(purgeService).purgeWorkspace(b.getId());
    }

    @Test
    @DisplayName("one failing purge does not stop the others")
    void oneFailureDoesNotStopOthers() {
        Organization a = orgWithId();
        Organization b = orgWithId();
        when(organizationRepository.findWorkspacesPastGracePeriod(any())).thenReturn(List.of(a, b));
        when(purgeService.purgeWorkspace(a.getId())).thenThrow(new RuntimeException("boom"));

        scheduler.purgeExpiredWorkspaces();

        verify(purgeService).purgeWorkspace(a.getId());
        verify(purgeService).purgeWorkspace(b.getId());
    }

    @Test
    @DisplayName("no eligible workspaces -> no purge calls")
    void emptyIsNoop() {
        when(organizationRepository.findWorkspacesPastGracePeriod(any())).thenReturn(List.of());
        scheduler.purgeExpiredWorkspaces();
        verifyNoInteractions(purgeService);
    }
}
