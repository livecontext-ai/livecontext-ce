package com.apimarketplace.orchestrator.services.activity;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.auth.UserSummaryDto;
import com.apimarketplace.common.recentactivity.RecentActivityItemDto;
import com.apimarketplace.common.recentactivity.RecentActivityScopeResultDto;
import com.apimarketplace.common.recentactivity.ResourceKind;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Focused unit test for the two mapping seams of {@link RecentActivityAggregatorService}
 * that carry the APPLICATION routing id:
 *
 * <ol>
 *   <li>{@code fetchWorkflowsAndApplications} must stamp an APPLICATION row's
 *       {@code publicationId} from {@code WorkflowEntity.sourcePublicationId}
 *       (and leave it null for WORKFLOW rows) - the frontend routes an
 *       APPLICATION row to {@code /app/applications/{publicationId}}, NOT the
 *       workflow id, so without this the row fails to load.</li>
 *   <li>{@code enrichWithActors} rebuilds the DTO to attach the resolved actor
 *       name, and MUST carry the {@code publicationId} across that rebuild -
 *       dropping it would silently re-break the link for every actor whose
 *       name resolves.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecentActivityAggregatorService - APPLICATION publicationId mapping")
class RecentActivityAggregatorServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private InterfaceClient interfaceClient;
    @Mock private AgentClient agentClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private AuthClient authClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ExecutorService executor;

    private RecentActivityAggregatorService service;

    @BeforeEach
    void setUp() {
        service = new RecentActivityAggregatorService(
                workflowRepository, interfaceClient, agentClient, dataSourceClient,
                authClient, redisTemplate, new ObjectMapper(), executor,
                new SimpleMeterRegistry());
    }

    private WorkflowEntity workflow(UUID id, String name, WorkflowEntity.WorkflowType type, UUID sourcePublicationId) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(id);
        w.setName(name);
        w.setWorkflowType(type);
        w.setSourcePublicationId(sourcePublicationId);
        w.setUpdatedAt(Instant.parse("2026-06-04T10:00:00Z"));
        w.setTenantId("1");
        return w;
    }

    @Test
    @DisplayName("APPLICATION row carries source_publication_id; WORKFLOW row has a null publicationId")
    void mapsApplicationPublicationId() {
        UUID appId = UUID.randomUUID();
        UUID pubId = UUID.randomUUID();
        UUID wfId = UUID.randomUUID();
        when(workflowRepository.findRecentByOrganizationIdStrict(anyString(), any(Pageable.class)))
                .thenReturn(List.of(
                        workflow(appId, "My App", WorkflowEntity.WorkflowType.APPLICATION, pubId),
                        workflow(wfId, "My Workflow", WorkflowEntity.WorkflowType.WORKFLOW, null)));

        RecentActivityScopeResultDto result = service.fetchWorkflowsAndApplications("1", "org-1");

        RecentActivityItemDto app = result.items().stream()
                .filter(i -> i.kind() == ResourceKind.APPLICATION).findFirst().orElseThrow();
        assertThat(app.resourceId()).isEqualTo(appId.toString());
        assertThat(app.publicationId())
                .as("APPLICATION row must route via the publication id, not the workflow id")
                .isEqualTo(pubId.toString());

        RecentActivityItemDto wf = result.items().stream()
                .filter(i -> i.kind() == ResourceKind.WORKFLOW).findFirst().orElseThrow();
        assertThat(wf.publicationId()).isNull();
    }

    @Test
    @DisplayName("APPLICATION row with no source publication has a null publicationId (UI falls back to the editor)")
    void applicationWithoutPublicationIsNull() {
        UUID appId = UUID.randomUUID();
        when(workflowRepository.findRecentByOrganizationIdStrict(anyString(), any(Pageable.class)))
                .thenReturn(List.of(
                        workflow(appId, "Legacy App", WorkflowEntity.WorkflowType.APPLICATION, null)));

        RecentActivityScopeResultDto result = service.fetchWorkflowsAndApplications("1", "org-1");

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).publicationId()).isNull();
    }

    @Test
    @DisplayName("enrichWithActors preserves publicationId while attaching the resolved actor name")
    void enrichPreservesPublicationId() {
        RecentActivityItemDto item = RecentActivityItemDto.builder()
                .kind(ResourceKind.APPLICATION)
                .resourceId("workflow-uuid")
                .name("My App")
                .lastEditedAt(Instant.parse("2026-06-04T10:00:00Z"))
                .actorId("1")
                .publicationId("pub-123")
                .build();

        List<RecentActivityItemDto> out = service.enrichWithActors(
                List.of(item),
                Map.of("1", new UserSummaryDto("1", "Alice", null)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).actorDisplayName()).isEqualTo("Alice");
        assertThat(out.get(0).publicationId())
                .as("publicationId must survive the enrichment rebuild")
                .isEqualTo("pub-123");
    }

    @Test
    @DisplayName("enrichWithActors keeps publicationId on rows whose actor does not resolve")
    void enrichKeepsPublicationIdWhenActorUnresolved() {
        RecentActivityItemDto item = RecentActivityItemDto.builder()
                .kind(ResourceKind.APPLICATION)
                .resourceId("workflow-uuid")
                .name("My App")
                .lastEditedAt(Instant.parse("2026-06-04T10:00:00Z"))
                .actorId("999")
                .publicationId("pub-456")
                .build();

        // Non-empty user map (so enrichment runs), but no entry for actor "999".
        List<RecentActivityItemDto> out = service.enrichWithActors(
                List.of(item),
                Map.of("1", new UserSummaryDto("1", "Someone Else", null)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).actorDisplayName()).isNull();
        assertThat(out.get(0).publicationId()).isEqualTo("pub-456");
    }
}
