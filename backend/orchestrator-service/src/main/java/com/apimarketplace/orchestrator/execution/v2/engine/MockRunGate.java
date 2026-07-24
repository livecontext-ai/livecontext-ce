package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves whether (and how) the per-node mock mode applies to a run.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@link MockRunMode#OFF} - no mock is ever applied. This is the mode for
 *       every non-editor run (production / trigger-dispatched / pinned fires: the
 *       hard guard), for editor runs explicitly fired with {@code mockMode="off"}
 *       ("run without mocks", config untouched), and whenever the run row cannot
 *       be resolved (fail-closed to real execution).</li>
 *   <li>{@link MockRunMode#DEFAULT} - editor-run default: every node carrying an
 *       ENABLED {@code mock} block returns its mock; all other nodes execute for
 *       real (granular hybrid runs).</li>
 *   <li>{@link MockRunMode#ALL_MCP} - DEFAULT plus every mcp catalog-tool node
 *       WITHOUT a block serves its projected catalog example (full dry-run, no
 *       credentials, no external calls).</li>
 * </ul>
 *
 * <p>Reads {@code workflow_runs.metadata} ({@code __mockMode__} + the
 * {@code __editorRun__} hard guard) through a short-TTL cache: the flag is
 * reconciled by {@code EditorRunResolver} on every editor fire of a REUSED run,
 * so the cache must converge quickly after a refire (15s TTL + same-pod
 * {@link #invalidate}).
 */
@Service
public class MockRunGate {

    private static final Logger logger = LoggerFactory.getLogger(MockRunGate.class);

    /** Run metadata key carrying the run-level mock override ({@code off} / {@code all_mcp}; absent = default). */
    public static final String MOCK_MODE_METADATA_KEY = "__mockMode__";

    /** Metadata value for "ignore all mocks this run". */
    public static final String MODE_OFF = "off";

    /** Metadata value for "mock every mcp catalog-tool node, catalog-example fallback". */
    public static final String MODE_ALL_MCP = "all_mcp";

    private static final String EDITOR_RUN_KEY = "__editorRun__";

    public enum MockRunMode {
        OFF,
        DEFAULT,
        ALL_MCP;

        public boolean isMockingEnabled() {
            return this != OFF;
        }
    }

    private final WorkflowRunRepository runRepository;
    private final Cache<String, MockRunMode> modeCache;

    public MockRunGate(WorkflowRunRepository runRepository) {
        this.runRepository = runRepository;
        this.modeCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Resolves the mock mode for a run. Never throws: any resolution problem
     * (unknown run, repository error) resolves to {@link MockRunMode#OFF} so the
     * engine takes the byte-identical real execution path.
     */
    public MockRunMode mode(String runId) {
        if (runId == null || runId.isBlank()) {
            return MockRunMode.OFF;
        }
        return modeCache.get(runId, this::loadMode);
    }

    /**
     * Same-pod fast path for run refires: {@code EditorRunResolver} calls this
     * right after reconciling {@code __mockMode__} on a reused run so the next
     * node execution sees the fresh flag without waiting out the TTL.
     */
    public void invalidate(String runId) {
        if (runId != null) {
            modeCache.invalidate(runId);
        }
    }

    private MockRunMode loadMode(String runId) {
        try {
            MockRunMode fromMetadata = runRepository.findByRunIdPublic(runId)
                    .map(run -> resolve(run.getMetadata()))
                    .orElse(MockRunMode.OFF);
            if (fromMetadata == MockRunMode.OFF) {
                return MockRunMode.OFF;
            }
            // PRODUCTION NEVER MOCKS. The metadata cannot decide this: pinning
            // promotes an existing run (usually the editor run the user tested with)
            // and strips neither __editorRun__ nor a leftover __mockMode__, so a
            // promoted production run can carry both. The FK is the identity test.
            // Checked only on the non-OFF path, so the common case stays one query.
            // Exposure window: the verdict is cached (modeCache); a run promoted
            // while cached rides out the short TTL before this guard applies.
            if (runRepository.isProductionRunByRunIdPublic(runId)) {
                logger.info("[MockRunGate] Run {} is the workflow's production run - ignoring its {} metadata, "
                        + "production fires never mock", runId, fromMetadata);
                return MockRunMode.OFF;
            }
            return fromMetadata;
        } catch (Exception e) {
            logger.warn("[MockRunGate] Failed to resolve mock mode for run {} - defaulting to OFF: {}",
                    runId, e.getMessage());
            return MockRunMode.OFF;
        }
    }

    private MockRunMode resolve(Map<String, Object> metadata) {
        // First gate: mocks apply to runs carrying __editorRun__ only. NOTE this flag
        // does NOT mean "not production" - pinning promotes an editor run and never
        // strips it - which is why loadMode adds the FK-based production check on the
        // non-OFF path. This metadata gate alone still stops legacy/flag-less runs
        // from ever mocking, even if a published plan carries mock blocks.
        if (metadata == null || !Boolean.TRUE.equals(metadata.get(EDITOR_RUN_KEY))) {
            return MockRunMode.OFF;
        }
        Object raw = metadata.get(MOCK_MODE_METADATA_KEY);
        if (raw instanceof String s) {
            String normalized = s.trim().toLowerCase(Locale.ROOT);
            if (MODE_OFF.equals(normalized)) {
                return MockRunMode.OFF;
            }
            if (MODE_ALL_MCP.equals(normalized)) {
                return MockRunMode.ALL_MCP;
            }
        }
        return MockRunMode.DEFAULT;
    }
}
