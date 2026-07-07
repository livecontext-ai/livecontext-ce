package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.ModelExecutionLinkService;
import com.apimarketplace.agent.service.execution.SubAgentBridgeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal (service-to-service) read access to model execution links (CLOUD only).
 *
 * <p>Consumer: orchestrator's {@code BrowserAgentModule}. The browser agent's LLM
 * stepping happens inside the websearch runner against a direct provider API key
 * resolved orchestrator-side, so it never passes through
 * {@code AgentRemoteExecutionService} - without this lookup it would silently
 * bypass every execution link (running on the exact key the admin linked away
 * from). The module calls this BEFORE resolving the key and swaps the runner's
 * {@code llm.provider}/{@code llm.model} to the returned target; observability
 * keeps the billed pair (it reads the caller's original parameters).
 *
 * <p>Gated behind the same {@code model-catalog.execution-links.enabled} flag as
 * {@link ModelExecutionLinkService}: absent in the CE monolith, where the client's
 * 404 resolves to "no route" and the direct-key path runs unchanged.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/model-config/execution-links")
@ConditionalOnProperty(name = "model-catalog.execution-links.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class InternalExecutionLinkController {

    private final ModelExecutionLinkService service;

    /**
     * Resolve the execution route for a billed pair, restricted to DIRECT-API
     * targets. Only {@code ALL}-scoped links can match (the calling consumers carry
     * no activity source). Returns 204 when the pair is unlinked OR the link targets
     * a CLI bridge: a bridge owns its own agent loop and cannot serve the caller's
     * raw completions, so the caller keeps the billed pair (logged, never silent).
     */
    @GetMapping("/resolve-api-target")
    public ResponseEntity<Map<String, Object>> resolveApiTarget(
            @RequestParam String billedProvider, @RequestParam String billedModel) {
        ModelExecutionLinkService.ExecutionRoute route =
                service.resolve(billedProvider, billedModel, null).orElse(null);
        if (route == null) {
            return ResponseEntity.noContent().build();
        }
        if (SubAgentBridgeClient.isBridgeProvider(route.executionProvider())) {
            log.warn("Execution link for {}/{} targets CLI bridge {}, which cannot serve a direct-API consumer "
                    + "(browser agent); the run stays on the billed pair's own provider key",
                billedProvider, billedModel, route.executionProvider());
            return ResponseEntity.noContent().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionProvider", route.executionProvider());
        body.put("executionModel", route.executionModel());
        return ResponseEntity.ok(body);
    }
}
