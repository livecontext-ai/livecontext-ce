package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.execution.OutputProjector;
import com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The WIRING that carries a response header from the HTTP call to the projection.
 *
 * <p>OutputProjectorTest proves the projector reads a header-sourced field correctly. It cannot
 * see the bug this change exists for, which was one layer above: ToolExecutionManager passed only
 * {@code executionResult.get("data")} onward and dropped {@code executionResult.get("headers")},
 * so no endpoint could ever declare a header-sourced field however correct the projector was.
 *
 * <p>Every test here fails if the headers stop being read out of the execution result or stop
 * being forwarded through the orchestrator, which is exactly how the original defect would return.
 */
class HeaderSourcedOutputWiringTest {

    // ── the line that used to drop the headers ────────────────────────────────────────────

    @Test
    @DisplayName("the execution result's headers are read out, not discarded")
    void headersAreReadOutOfTheExecutionResult() {
        Map<String, Object> executionResult = new HashMap<>();
        executionResult.put("data", Map.of("id", "x"));
        executionResult.put("headers", Map.of("etag", "e1"));

        Map<String, String> headers = ToolExecutionManager.responseHeadersOf(executionResult);

        assertEquals(Map.of("etag", "e1"), headers,
            "dropping these is the defect this change exists to fix");
    }

    @Test
    @DisplayName("a result with no headers entry yields null rather than throwing")
    void missingHeadersEntryIsNull() {
        assertNull(ToolExecutionManager.responseHeadersOf(Map.of("data", Map.of())));
    }

    @Test
    @DisplayName("a null execution result yields null rather than throwing")
    void nullExecutionResultIsTolerated() {
        assertNull(ToolExecutionManager.responseHeadersOf(null));
    }

    @Test
    @DisplayName("a headers entry that is not a map is ignored rather than cast blindly")
    void nonMapHeadersEntryIsIgnored() {
        Map<String, Object> executionResult = new HashMap<>();
        executionResult.put("headers", "not-a-map");

        assertNull(ToolExecutionManager.responseHeadersOf(executionResult),
            "a ClassCastException here would fail a call that had already succeeded");
    }

    // ── the orchestrator hop ──────────────────────────────────────────────────────────────

    private static final String SCHEMA =
        "[{\"key\":\"etag\",\"type\":\"string\",\"source\":\"header\"}]";

    @Test
    @DisplayName("the sync path forwards the headers it was given to the projector")
    void syncModeForwardsHeaders() {
        OutputProjector projector = mock(OutputProjector.class);
        ToolExecutionOrchestrator orchestrator = new ToolExecutionOrchestrator(projector);
        Map<String, String> headers = Map.of("etag", "e1");
        when(projector.project(any(), eq(SCHEMA), any())).thenReturn(Map.of("etag", "e1"));

        orchestrator.projectResult(Map.of(), SCHEMA, "sync", headers);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(projector).project(any(), eq(SCHEMA), captor.capture());
        assertEquals(headers, captor.getValue(),
            "forwarding null here would reproduce the original bug one layer down");
    }

    @Test
    @DisplayName("the upload path forwards them too, since an upload is where a part ETag comes from")
    void uploadModeForwardsHeaders() {
        OutputProjector projector = mock(OutputProjector.class);
        ToolExecutionOrchestrator orchestrator = new ToolExecutionOrchestrator(projector);
        Map<String, String> headers = Map.of("etag", "e2");

        orchestrator.projectResult(Map.of(), SCHEMA, "upload", headers);

        verify(projector).project(any(), eq(SCHEMA), eq(headers));
    }

    /**
     * The three-argument overload is what every pre-existing caller uses (MockToolExecutionService,
     * and the whole ToolExecutionOrchestratorTypedTest suite). It must keep working and must not
     * start inventing headers.
     */
    @Test
    @DisplayName("the pre-existing 3-argument call still works and passes no headers")
    void legacyOverloadPassesNoHeaders() {
        OutputProjector projector = mock(OutputProjector.class);
        ToolExecutionOrchestrator orchestrator = new ToolExecutionOrchestrator(projector);

        orchestrator.projectResult(Map.of("a", 1), SCHEMA, "sync");

        verify(projector).project(any(), eq(SCHEMA), eq(null));
    }

    // ── the two hops together, with the real projector ────────────────────────────────────

    /**
     * End to end across the layers this change touches, with nothing mocked but the HTTP result:
     * an execution result shaped exactly as HttpExecutionService builds it for an upload whose body
     * is empty and whose value lives in a header.
     */
    @Test
    @DisplayName("an empty-bodied upload surfaces its ETag through the whole chain")
    void emptyBodyUploadSurfacesItsEtagEndToEnd() {
        ToolExecutionOrchestrator orchestrator =
            new ToolExecutionOrchestrator(new OutputProjector(new ObjectMapper()));
        Map<String, Object> executionResult = new LinkedHashMap<>();
        executionResult.put("success", true);
        executionResult.put("status", 200);
        executionResult.put("data", Map.of());                       // LinkedIn returns no body
        executionResult.put("headers", Map.of("etag", "/ambry-video/signedId/AQ.bin"));

        Object projected = orchestrator.projectResult(
            executionResult.get("data"), SCHEMA, "sync",
            ToolExecutionManager.responseHeadersOf(executionResult));

        assertTrue(projected instanceof Map, "the projection must be an object to carry the field");
        assertEquals("/ambry-video/signedId/AQ.bin", ((Map<?, ?>) projected).get("etag"),
            "the part id must survive both hops; the value is passed through unquoted because "
                + "LinkedIn sends it unquoted and finalize compares it verbatim");
    }
}
