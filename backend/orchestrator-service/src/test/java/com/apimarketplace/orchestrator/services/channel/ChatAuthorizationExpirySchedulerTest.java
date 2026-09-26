package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.orchestrator.repository.ChatAuthorizationRequestRepository;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Expiring a request is not housekeeping.
 *
 * <p>The duplicate rule counts only rows still {@code SENT}, so without this pass
 * the FIRST question an agent asks and nobody answers would stop that agent asking
 * ever again: every later run would be told "you already asked", about a message
 * whose buttons may never be pressed.
 */
class ChatAuthorizationExpirySchedulerTest {

    private ChatAuthorizationRequestRepository repository;
    private ChatChannelConnector connector;
    private ChatAuthorizationExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(ChatAuthorizationRequestRepository.class);
        connector = mock(ChatChannelConnector.class);
        when(connector.channelId()).thenReturn("telegram");
        when(connector.closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(Outcome.of(null));
        scheduler = new ChatAuthorizationExpiryScheduler(repository,
                new ChatChannelConnectorRegistry(List.of(connector)));
    }

    private static ChatAuthorizationRequestEntity overdue() {
        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setId(UUID.randomUUID());
        row.setTenantId("42");
        row.setOrganizationId("org-1");
        row.setChannel("telegram");
        row.setCredentialId(9L);
        row.setChatId("-100123");
        row.setMessageId("555");
        row.setAgentName("Night Publisher");
        row.setStatus(RequestStatus.SENT);
        row.setExpiresAt(Instant.now().minusSeconds(60));
        return row;
    }

    @Test
    @DisplayName("retires an overdue request so the agent may ask again")
    void retiresOverdueRequests() {
        ChatAuthorizationRequestEntity row = overdue();
        when(repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq(RequestStatus.SENT), any(), any()))
                .thenReturn(List.of(row));

        scheduler.expireOverdueRequests();

        assertThat(row.getStatus()).isEqualTo(RequestStatus.EXPIRED);
        verify(repository).save(row);
    }

    @Test
    @DisplayName("takes the buttons off the message it expired")
    void closesTheMessage() {
        ChatAuthorizationRequestEntity row = overdue();
        when(repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq(RequestStatus.SENT), any(), any()))
                .thenReturn(List.of(row));

        scheduler.expireOverdueRequests();

        // Buttons left live on a question that can no longer be answered give the
        // person a press that does nothing and says nothing.
        verify(connector).closeDecisionRequest(eq("42"), eq(9L), eq("-100123"), eq("555"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("retires the row even when the message edit fails")
    void statusWinsOverTheEdit() {
        ChatAuthorizationRequestEntity row = overdue();
        when(repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq(RequestStatus.SENT), any(), any()))
                .thenReturn(List.of(row));
        when(connector.closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenThrow(new IllegalStateException("telegram down"));

        assertThatCode(scheduler::expireOverdueRequests).doesNotThrowAnyException();

        // The row is what the duplicate rule reads. A provider outage must not be able
        // to lock an agent out of asking for a day.
        assertThat(row.getStatus()).isEqualTo(RequestStatus.EXPIRED);
        verify(repository).save(row);
    }

    @Test
    @DisplayName("keeps going when one request cannot be retired")
    void oneFailureDoesNotStopTheRest() {
        ChatAuthorizationRequestEntity poisoned = overdue();
        ChatAuthorizationRequestEntity healthy = overdue();
        when(repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq(RequestStatus.SENT), any(), any()))
                .thenReturn(List.of(poisoned, healthy));
        when(repository.save(poisoned)).thenThrow(new IllegalStateException("row is gone"));

        scheduler.expireOverdueRequests();

        verify(repository).save(healthy);
        assertThat(healthy.getStatus()).isEqualTo(RequestStatus.EXPIRED);
    }

    @Test
    @DisplayName("does nothing at all when nothing is overdue")
    void quietWhenEmpty() {
        when(repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq(RequestStatus.SENT), any(), any()))
                .thenReturn(List.of());

        scheduler.expireOverdueRequests();

        verify(repository, never()).save(any());
        verify(connector, never()).closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("survives a repository that cannot answer")
    void survivesAReadFailure() {
        when(repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(scheduler::expireOverdueRequests).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("asks the database for one pass, not for the whole backlog")
    void capsThePassInTheQuery() {
        when(repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq(RequestStatus.SENT), any(), any()))
                .thenReturn(List.of());

        scheduler.expireOverdueRequests();

        // The cap has to be in the SQL. This sweep is platform-wide, so a provider outage
        // lasting a day makes the overdue set every unanswered request in every workspace,
        // and capping it in the Java loop means loading all of them to then use 200. The
        // finder name carries the ordering (oldest deadline first), without which a cap
        // smaller than the backlog can hand back the same rows forever.
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                eq(RequestStatus.SENT), any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(200);
        assertThat(page.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("retires everything the capped query hands back")
    void retiresTheWholePass() {
        List<ChatAuthorizationRequestEntity> pass = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            pass.add(overdue());
        }
        when(repository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq(RequestStatus.SENT), any(), any()))
                .thenReturn(pass);

        scheduler.expireOverdueRequests();

        // Nothing is dropped between the query and the loop: whatever a pass selects, a
        // pass finishes. The rest of the backlog waits for the next one.
        verify(repository, times(200)).save(any());
    }
}
