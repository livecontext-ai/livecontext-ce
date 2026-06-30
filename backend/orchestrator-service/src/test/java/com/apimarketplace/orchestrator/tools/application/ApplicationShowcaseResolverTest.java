package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.InterfaceDef;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApplicationShowcaseResolver} - the shared resolution of the showcase
 * interface (entry interface) and showcase run that both {@code application(action='create')}
 * and {@code workflow(action='publish')} rely on to turn a workflow into an application.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApplicationShowcaseResolver")
class ApplicationShowcaseResolverTest {

    @Mock private WorkflowRunRepository workflowRunRepository;

    private ApplicationShowcaseResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ApplicationShowcaseResolver(workflowRunRepository);
    }

    private static Map<String, Object> iface(String id, String label, boolean entry) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("isEntryInterface", entry);
        return m;
    }

    private static WorkflowPlan planWith(List<Map<String, Object>> interfaces) {
        Map<String, Object> planMap = new HashMap<>();
        planMap.put("interfaces", interfaces);
        return WorkflowPlanParser.parse(planMap, "wf-1", "tenant-1");
    }

    private static WorkflowRunEntity run(String runId, RunStatus status, boolean stepByStep, String source) {
        WorkflowRunEntity r = mock(WorkflowRunEntity.class);
        lenient().when(r.getRunIdPublic()).thenReturn(runId);
        lenient().when(r.getStatus()).thenReturn(status);
        lenient().when(r.isStepByStepMode()).thenReturn(stepByStep);
        lenient().when(r.getSource()).thenReturn(source);
        return r;
    }

    @Nested
    @DisplayName("resolveEntryInterface")
    class EntryInterface {

        @Test
        @DisplayName("Picks the interface flagged isEntryInterface, not just the first")
        void picksEntryFlaggedInterface() {
            WorkflowPlan plan = planWith(List.of(
                iface("a", "First", false),
                iface("b", "The Entry", true),
                iface("c", "Third", false)));

            Optional<InterfaceDef> entry = resolver.resolveEntryInterface(plan);

            assertThat(entry).isPresent();
            assertThat(entry.get().id()).isEqualTo("b");
        }

        @Test
        @DisplayName("Falls back to the first interface when none is flagged as entry")
        void fallsBackToFirstWhenNoEntryFlag() {
            WorkflowPlan plan = planWith(List.of(
                iface("a", "First", false),
                iface("b", "Second", false)));

            assertThat(resolver.resolveEntryInterface(plan)).map(InterfaceDef::id).hasValue("a");
        }

        @Test
        @DisplayName("Empty when the plan has no interface (it is not an application)")
        void emptyWhenNoInterfaces() {
            assertThat(resolver.resolveEntryInterface(planWith(List.of()))).isEmpty();
        }

        @Test
        @DisplayName("Empty for a null plan")
        void emptyForNullPlan() {
            assertThat(resolver.resolveEntryInterface(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("isShowcaseableStatus / isShowcaseableRun")
    class Showcaseable {

        @Test
        @DisplayName("COMPLETED, PARTIAL_SUCCESS and WAITING_TRIGGER are showcaseable; others are not")
        void statusMatrix() {
            assertThat(resolver.isShowcaseableStatus(RunStatus.COMPLETED)).isTrue();
            assertThat(resolver.isShowcaseableStatus(RunStatus.PARTIAL_SUCCESS)).isTrue();
            assertThat(resolver.isShowcaseableStatus(RunStatus.WAITING_TRIGGER)).isTrue();
            assertThat(resolver.isShowcaseableStatus(RunStatus.FAILED)).isFalse();
            assertThat(resolver.isShowcaseableStatus(RunStatus.RUNNING)).isFalse();
            assertThat(resolver.isShowcaseableStatus(null)).isFalse();
        }

        @Test
        @DisplayName("An automatic, successful, non-clone run is showcaseable")
        void acceptsAutomaticSuccessfulRun() {
            assertThat(resolver.isShowcaseableRun(run("r1", RunStatus.COMPLETED, false, "execute"))).isTrue();
        }

        @Test
        @DisplayName("A step-by-step run is NOT showcaseable")
        void rejectsStepByStep() {
            assertThat(resolver.isShowcaseableRun(run("r1", RunStatus.COMPLETED, true, "execute"))).isFalse();
        }

        @Test
        @DisplayName("A FAILED run is NOT showcaseable")
        void rejectsFailed() {
            assertThat(resolver.isShowcaseableRun(run("r1", RunStatus.FAILED, false, "execute"))).isFalse();
        }

        @Test
        @DisplayName("A showcase-clone run is NOT itself showcaseable")
        void rejectsShowcaseClone() {
            assertThat(resolver.isShowcaseableRun(run("r1", RunStatus.COMPLETED, false, "showcase"))).isFalse();
        }

        @Test
        @DisplayName("A null run is NOT showcaseable")
        void rejectsNull() {
            assertThat(resolver.isShowcaseableRun(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("resolveLatestShowcaseRunId")
    class LatestShowcaseRun {

        @Test
        @DisplayName("Returns the first showcaseable run in newest-first order, skipping non-showcaseable")
        void returnsFirstShowcaseableNewestFirst() {
            UUID wfId = UUID.randomUUID();
            WorkflowRunEntity newestFailed = run("newest-failed", RunStatus.FAILED, false, "execute");
            WorkflowRunEntity completed = run("completed-run", RunStatus.COMPLETED, false, "execute");
            WorkflowRunEntity olderWaiting = run("older-waiting", RunStatus.WAITING_TRIGGER, false, "execute");
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(wfId), any()))
                .thenReturn(new PageImpl<>(List.of(newestFailed, completed, olderWaiting)));

            assertThat(resolver.resolveLatestShowcaseRunId(wfId)).hasValue("completed-run");
        }

        @Test
        @DisplayName("Empty when no run is showcaseable")
        void emptyWhenNoneShowcaseable() {
            UUID wfId = UUID.randomUUID();
            WorkflowRunEntity failed = run("r1", RunStatus.FAILED, false, "execute");
            WorkflowRunEntity running = run("r2", RunStatus.RUNNING, false, "execute");
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(wfId), any()))
                .thenReturn(new PageImpl<>(List.of(failed, running)));

            assertThat(resolver.resolveLatestShowcaseRunId(wfId)).isEmpty();
        }

        @Test
        @DisplayName("Empty for a null workflow id (no repository hit)")
        void emptyForNullWorkflowId() {
            assertThat(resolver.resolveLatestShowcaseRunId(null)).isEmpty();
        }
    }
}
