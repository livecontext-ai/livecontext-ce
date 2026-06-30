package com.apimarketplace.orchestrator.trigger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression: {@code ProductionRunResolver.resolve()} logged WARN every time it
 * encountered an unpinned workflow. In prod that fired ~11/min × 6 unpinned
 * scheduled workflows = 3982 lines/day, drowning the orchestrator log
 * (11% of total volume - audit 2026-05-13). The outcome is already surfaced via
 * the returned {@link ProductionRunResolver.Resolution} and the dispatch-verdict
 * Prometheus counter - log noise was pure duplication. Post-fix: DEBUG.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductionRunResolver - NOT_PINNED outcome logs at DEBUG, not WARN")
class ProductionRunResolverNotPinnedLogLevelTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;

    private ProductionRunResolver resolver;
    private Logger resolverLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        resolver = new ProductionRunResolver(workflowRepository, runRepository);

        resolverLogger = (Logger) LoggerFactory.getLogger(ProductionRunResolver.class);
        previousLevel = resolverLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        resolverLogger.addAppender(appender);
        resolverLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        resolverLogger.detachAppender(appender);
        resolverLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("Resolving an unpinned workflow logs at DEBUG only - no WARN, the outcome is the signal")
    void unpinnedWorkflowLogsDebugNotWarn() {
        WorkflowEntity unpinned = new WorkflowEntity();
        unpinned.setId(WORKFLOW_ID);
        unpinned.setName("daily-email-digest");
        unpinned.setPinnedVersion(null);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(unpinned));

        ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
            ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

        // Functional outcome unchanged: NOT_PINNED still surfaces - the caller still has
        // the full information to decide skip-vs-disable.
        assertThat(res.isNotPinned()).isTrue();

        boolean warnFired = appender.list.stream().anyMatch(e ->
            e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains("has no pinned version"));
        assertThat(warnFired)
            .as("NOT_PINNED outcome must NOT log at WARN - closes the 11%/day log-noise bug")
            .isFalse();

        boolean debugFired = appender.list.stream().anyMatch(e ->
            e.getLevel() == Level.DEBUG
            && e.getFormattedMessage().contains("has no pinned version"));
        assertThat(debugFired)
            .as("DEBUG kept so ad-hoc tracing of pin-state remains possible")
            .isTrue();
    }
}
