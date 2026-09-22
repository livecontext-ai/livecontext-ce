package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.client.dto.AgentRunFireDto;
import com.apimarketplace.agent.client.dto.AgentRunWindowDto;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentSkillRepository;
import com.apimarketplace.agent.repository.AgentWebhookTokenRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.service.execution.AgentActivitySnapshotService;
import com.apimarketplace.agent.service.execution.ConversationStopCascadeService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The window endpoint behind the agenda's agent history.
 *
 * <p>It is the only door orchestrator has into the {@code agent} schema for this feature,
 * so what it refuses matters as much as what it returns: a malformed instant must not
 * reach the repository as a null range, a caller with no workspace must not get a
 * tenant-wide read, and a caller asking for a million rows must be clamped rather than
 * allowed to pull a workspace's whole history into one response.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAgentController - GET /executions/window")
class InternalAgentControllerExecutionWindowTest {

    @Mock private AgentService agentService;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository agentExecutionRepository;
    @Mock private OrgAccessGuard orgAccessService;
    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private AgentWebhookTokenRepository webhookTokenRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private SkillService skillService;
    @Mock private SkillFolderService skillFolderService;
    @Mock private AgentObservabilityService observabilityService;
    @Mock private TenantResolver tenantResolver;
    @Mock private RequestParameterExtractor extractor;
    @Mock private ConversationStopCascadeService conversationStopCascadeService;
    @Mock private AgentActivitySnapshotService agentActivitySnapshotService;

    private InternalAgentController controller;

    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-09-30T00:00:00Z";

    @BeforeEach
    void setUp() {
        controller = new InternalAgentController(
                agentService, agentRepository, agentExecutionRepository, orgAccessService, agentSkillRepository,
                webhookTokenRepository, skillRepository, skillService, skillFolderService,
                observabilityService, tenantResolver, extractor, conversationStopCascadeService,
                agentActivitySnapshotService);
    }

    private MockHttpServletRequest request(String orgId) {
        return request(orgId, null);
    }

    private MockHttpServletRequest request(String orgId, String orgRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "user-1");
        if (orgId != null) request.addHeader("X-Organization-ID", orgId);
        if (orgRole != null) request.addHeader("X-Organization-Role", orgRole);
        return request;
    }

    /**
     * What a call that gets past validation needs: a resolved caller and a guard that
     * hands back what it was given.
     *
     * <p>Stubbed per test rather than in the fixture, so the refusal tests keep PROVING
     * they stop earlier: Mockito's unnecessary-stubbing check is what tells us the
     * malformed-instant path never reached either of these.
     */
    private void callerResolvesAndGuardAllows() {
        when(tenantResolver.resolve(any())).thenReturn("user-1");
        when(orgAccessService.filterAccessible(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("returns the workspace's runs for the window")
    void returnsRuns() {
        callerResolvesAndGuardAllows();
        AgentRunFireDto row = new AgentRunFireDto(UUID.randomUUID(), UUID.randomUUID(), "Support",
                Instant.parse(FROM), null, "COMPLETED", "CHAT", "conv-1");
        when(agentExecutionRepository.findWorkspaceRunsBetweenStrict(
                eq("org-1"), any(), any(), any(), any())).thenReturn(List.of(row));

        ResponseEntity<?> response = controller.getExecutionWindow(FROM, TO, 500, request("org-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AgentRunWindowDto window = (AgentRunWindowDto) response.getBody();
        assertThat(window.runs()).isEqualTo(List.of(row));
        assertThat(window.available()).isTrue();
    }

    @Test
    @DisplayName("passes the parsed window, the org and the page size straight through")
    void passesArgumentsThrough() {
        callerResolvesAndGuardAllows();
        controller.getExecutionWindow(FROM, TO, 500, request("org-1"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Collection<String>> types = ArgumentCaptor.forClass(Collection.class);
        verify(agentExecutionRepository).findWorkspaceRunsBetweenStrict(
                eq("org-1"), eq(Instant.parse(FROM)), eq(Instant.parse(TO)),
                types.capture(), pageable.capture());
        // capped + 1: the extra row is the probe that answers "is there more" without
        // guessing, and it is trimmed before the caller sees the page.
        assertThat(pageable.getValue().getPageSize()).isEqualTo(501);
        assertThat(pageable.getValue().getPageNumber()).isZero();
        // The allow-list, not a deny-list: an internal agent type added later stays off
        // the calendar until someone decides it belongs there.
        assertThat(types.getValue()).containsExactlyInAnyOrder("agent", "sub_agent");
    }

    @Test
    @DisplayName("clamps an oversized limit rather than letting one call pull a whole history")
    void clampsAnOversizedLimit() {
        callerResolvesAndGuardAllows();
        controller.getExecutionWindow(FROM, TO, 1_000_000, request("org-1"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(agentExecutionRepository).findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(2001);
    }

    @Test
    @DisplayName("clamps a zero or negative limit, which PageRequest would reject outright")
    void clampsANonPositiveLimit() {
        callerResolvesAndGuardAllows();
        controller.getExecutionWindow(FROM, TO, 0, request("org-1"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(agentExecutionRepository).findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("measures truncation BEFORE the deny-list, so a filtered page is not read as complete")
    void truncationIsMeasuredBeforeFiltering() {
        // The cap runs first, then the deny-list shortens the list. Deriving "was this
        // window cut short" from what survives would answer NO for any member restricted
        // from an agent, and their calendar would draw the uncovered days as quiet ones.
        Instant oldest = Instant.parse(FROM);
        // Three rows for a cap of two: the third is the probe that proves there is more.
        List<AgentRunFireDto> full = List.of(
                new AgentRunFireDto(UUID.randomUUID(), UUID.randomUUID(), "A",
                        oldest.plusSeconds(120), null, "COMPLETED", "CHAT", null),
                new AgentRunFireDto(UUID.randomUUID(), UUID.randomUUID(), "B",
                        oldest.plusSeconds(60), null, "COMPLETED", "CHAT", null),
                new AgentRunFireDto(UUID.randomUUID(), UUID.randomUUID(), "C",
                        oldest, null, "COMPLETED", "CHAT", null));
        MockHttpServletRequest request = request("org-1", "MEMBER");
        when(tenantResolver.resolve(any())).thenReturn("user-1");
        when(agentExecutionRepository.findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), any())).thenReturn(full);
        // The member may see neither: the list the caller receives is EMPTY.
        when(orgAccessService.filterAccessible(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        AgentRunWindowDto window = (AgentRunWindowDto) controller
                .getExecutionWindow(FROM, TO, 2, request).getBody();

        assertThat(window.runs()).isEmpty();
        assertThat(window.truncated()).isTrue();
        // The oldest row of the PAGE the caller was given, not of the probe.
        assertThat(window.coveredFrom()).isEqualTo(oldest.plusSeconds(60));
    }

    @Test
    @DisplayName("a window holding EXACTLY the cap is complete, not permanently truncated")
    void exactlyTheCapIsComplete() {
        // The endpoint asks for one row past the page. Asking for exactly `capped` and
        // testing `size() >= capped` calls a window holding exactly that many rows
        // truncated, and the banner then sits forever on a month that is whole.
        callerResolvesAndGuardAllows();
        when(agentExecutionRepository.findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), any())).thenReturn(List.of(row(0), row(1)));

        AgentRunWindowDto window = (AgentRunWindowDto) controller
                .getExecutionWindow(FROM, TO, 2, request("org-1")).getBody();

        assertThat(window.runs()).hasSize(2);
        assertThat(window.truncated()).isFalse();
        assertThat(window.coveredFrom()).isNull();
    }

    @Test
    @DisplayName("asks for one row past the page, and does not hand that row to the caller")
    void asksOnePastThePageAndTrimsIt() {
        callerResolvesAndGuardAllows();
        when(agentExecutionRepository.findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(row(0), row(1), row(2)));

        AgentRunWindowDto window = (AgentRunWindowDto) controller
                .getExecutionWindow(FROM, TO, 2, request("org-1")).getBody();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(agentExecutionRepository).findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(3);
        // The probe row is a detector, not content: handing it over would give the
        // caller one row more than its own cap on every truncated window.
        assertThat(window.runs()).hasSize(2);
        assertThat(window.truncated()).isTrue();
        assertThat(window.coveredFrom()).isEqualTo(window.runs().get(1).startedAt());
    }

    private static AgentRunFireDto row(int minutesBack) {
        return new AgentRunFireDto(UUID.randomUUID(), UUID.randomUUID(), "A",
                Instant.parse(FROM).plusSeconds(600 - minutesBack * 60L), null,
                "COMPLETED", "CHAT", null);
    }

    @Test
    @DisplayName("a partial page is complete, and names no boundary")
    void partialPageIsComplete() {
        callerResolvesAndGuardAllows();
        when(agentExecutionRepository.findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), any())).thenReturn(List.of(
                new AgentRunFireDto(UUID.randomUUID(), UUID.randomUUID(), "A",
                        Instant.parse(FROM), null, "COMPLETED", "CHAT", null)));

        AgentRunWindowDto window = (AgentRunWindowDto) controller
                .getExecutionWindow(FROM, TO, 500, request("org-1")).getBody();

        assertThat(window.truncated()).isFalse();
        assertThat(window.coveredFrom()).isNull();
    }

    @Test
    @DisplayName("refuses a malformed instant with 400 instead of querying a null range")
    void refusesAMalformedInstant() {
        ResponseEntity<?> response = controller.getExecutionWindow("yesterday", TO, 500, request("org-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(agentExecutionRepository, never()).findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("refuses an inverted window with 400")
    void refusesAnInvertedWindow() {
        ResponseEntity<?> response = controller.getExecutionWindow(TO, FROM, 500, request("org-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(agentExecutionRepository, never()).findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("hides the runs of an agent this member was restricted from")
    void appliesThePerMemberDenyList() {
        UUID visible = UUID.randomUUID();
        UUID restricted = UUID.randomUUID();
        AgentRunFireDto ok = new AgentRunFireDto(UUID.randomUUID(), visible, "Support",
                Instant.parse(FROM), null, "COMPLETED", "CHAT", "conv-1");
        AgentRunFireDto hidden = new AgentRunFireDto(UUID.randomUUID(), restricted, "Payroll",
                Instant.parse(FROM), null, "COMPLETED", "CHAT", "conv-2");
        MockHttpServletRequest request = request("org-1", "MEMBER");
        when(tenantResolver.resolve(any())).thenReturn("user-1");
        when(agentExecutionRepository.findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), any())).thenReturn(List.of(ok, hidden));
        // Stand in for the real guard's deny-list on one agent id.
        when(orgAccessService.filterAccessible(any(), eq("org-1"), eq("user-1"), eq("agent"),
                eq("MEMBER"), any())).thenReturn(List.of(ok));

        ResponseEntity<?> response = controller.getExecutionWindow(FROM, TO, 500, request);

        // Otherwise the calendar shows the name, the times, the outcome and a working link
        // into the conversation of an agent this member cannot even see in the catalogue.
        assertThat(((AgentRunWindowDto) response.getBody()).runs()).isEqualTo(List.of(ok));
    }

    @Test
    @DisplayName("asks the deny-list with the caller's own identity and role, not the workspace's")
    void passesTheCallerToTheDenyList() {
        callerResolvesAndGuardAllows();
        controller.getExecutionWindow(FROM, TO, 500, request("org-1", "MEMBER"));

        // The role decides whether the guard short-circuits for an admin; omitting it (the
        // first version of this endpoint did) makes every caller look like a plain member
        // to some guards and like an admin to others.
        verify(orgAccessService).filterAccessible(any(), eq("org-1"), eq("user-1"),
                eq("agent"), eq("MEMBER"), any());
    }

    @Test
    @DisplayName("asks the deny-list about the AGENT, not about the execution")
    void denyListIsKeyedByTheAgent() {
        // The id extractor is what the guard actually compares against its restriction
        // rows, and every other test here matches it with any(). Point it at the
        // execution id instead - a value that appears in no restriction row - and the
        // deny-list stops filtering entirely while all fourteen stay green. That is this
        // endpoint's own stated failure mode: a security filter that looks like success.
        callerResolvesAndGuardAllows();
        AgentRunFireDto run = new AgentRunFireDto(UUID.randomUUID(), UUID.randomUUID(), "A",
                Instant.parse(FROM), null, "COMPLETED", "CHAT", null);
        when(agentExecutionRepository.findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), any())).thenReturn(List.of(run));

        controller.getExecutionWindow(FROM, TO, 500, request("org-1", "MEMBER"));

        ArgumentCaptor<java.util.function.Function<AgentRunFireDto, String>> extractor =
                ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(orgAccessService).filterAccessible(any(), any(), any(), eq("agent"), any(),
                extractor.capture());
        assertThat(extractor.getValue().apply(run)).isEqualTo(run.agentId().toString());
    }

    @Test
    @DisplayName("decodes a pipe-bearing caller id before asking the deny-list")
    void decodesTheCallerIdForTheDenyList() {
        // A legacy auth0 id arrives percent-encoded. Handed to the guard as-is it matches
        // no restriction row, so the filter quietly stops filtering - the one failure
        // mode of a security filter that looks exactly like success. Every other
        // deny-list call site decodes first; this asserts that this one does too.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "auth0%7C123");
        request.addHeader("X-Organization-ID", "org-1");
        request.addHeader("X-Organization-Role", "MEMBER");
        when(tenantResolver.resolve(any())).thenReturn("auth0%7C123");
        when(orgAccessService.filterAccessible(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        controller.getExecutionWindow(FROM, TO, 500, request);

        verify(orgAccessService).filterAccessible(any(), eq("org-1"), eq("auth0|123"),
                eq("agent"), eq("MEMBER"), any());
    }

    @Test
    @DisplayName("refuses a caller with no workspace: this read is org-strict, never tenant-wide")
    void refusesWithoutAnOrganization() {
        MockHttpServletRequest request = request(null);

        assertThatThrownBy(() -> controller.getExecutionWindow(FROM, TO, 500, request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(agentExecutionRepository, never()).findWorkspaceRunsBetweenStrict(
                anyString(), any(), any(), any(), any());
    }
}
