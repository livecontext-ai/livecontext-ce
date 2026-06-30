package com.apimarketplace.orchestrator.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for deferred HTTP responses in webhook-triggered workflows.
 *
 * When a webhook call arrives with sync=true, the controller registers a
 * DeferredResult here keyed by runId. Later, when a RespondToWebhookNode
 * executes in the workflow, it resolves the deferred result with the
 * configured HTTP response, allowing the original webhook caller to
 * receive a custom response body, status code, and headers.
 *
 * If the workflow completes without hitting a RespondToWebhookNode
 * (or times out), the DeferredResult's timeout handler returns the
 * default 202 Accepted response.
 */
@Service
public class WebhookResponseRegistry {

    private static final Logger logger = LoggerFactory.getLogger(WebhookResponseRegistry.class);

    private final ConcurrentHashMap<String, DeferredResult<ResponseEntity<?>>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ResponseEntity<?>> earlyResponses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<String, Boolean> expectedRuns = ConcurrentHashMap.newKeySet();

    /**
     * Mark a run as expecting a synchronous webhook response before execution starts.
     *
     * <p>Webhook dispatch can execute a fast workflow before the controller has returned
     * and registered its DeferredResult. This marker lets RespondToWebhookNode store that
     * early response instead of reporting responded=false.
     */
    public void expect(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        expectedRuns.add(runId);
    }

    /**
     * Cancel a previously registered expectation when dispatch fails before the controller
     * can register a DeferredResult.
     */
    public void cancelExpectation(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        expectedRuns.remove(runId);
        earlyResponses.remove(runId);
    }

    /**
     * Register a deferred result when a synchronous webhook call arrives.
     *
     * @param runId    The workflow run ID
     * @param deferred The DeferredResult to resolve later
     */
    public void register(String runId, DeferredResult<ResponseEntity<?>> deferred) {
        logger.info("Registering deferred webhook response for runId={}", runId);
        deferred.onCompletion(() -> {
            pending.remove(runId, deferred);
            expectedRuns.remove(runId);
            earlyResponses.remove(runId);
            logger.debug("Deferred webhook response completed for runId={}", runId);
        });
        deferred.onTimeout(() -> {
            pending.remove(runId, deferred);
            expectedRuns.remove(runId);
            earlyResponses.remove(runId);
            logger.warn("Deferred webhook response timed out for runId={}", runId);
        });

        pending.put(runId, deferred);

        ResponseEntity<?> earlyResponse = earlyResponses.remove(runId);
        if (earlyResponse != null) {
            pending.remove(runId, deferred);
            expectedRuns.remove(runId);
            boolean resolved = deferred.setResult(earlyResponse);
            logger.info("Resolved early webhook response for runId={}, success={}", runId, resolved);
            return;
        }

        expectedRuns.remove(runId);
    }

    /**
     * Resolve the deferred result when a RespondToWebhookNode executes.
     *
     * @param runId    The workflow run ID
     * @param response The ResponseEntity to send back to the webhook caller
     * @return true if the deferred result was found and resolved, false otherwise
     */
    public boolean resolve(String runId, ResponseEntity<?> response) {
        DeferredResult<ResponseEntity<?>> deferred = pending.remove(runId);
        if (deferred != null) {
            boolean resolved = deferred.setResult(response);
            logger.info("Resolved deferred webhook response for runId={}, success={}", runId, resolved);
            return resolved;
        }
        if (expectedRuns.remove(runId)) {
            earlyResponses.put(runId, response);
            logger.info("Stored early webhook response for runId={} before deferred registration", runId);
            return true;
        }
        logger.debug("No pending deferred webhook response for runId={}", runId);
        return false;
    }

    /**
     * Check if a deferred result exists for a given run.
     *
     * @param runId The workflow run ID
     * @return true if there is a pending deferred result
     */
    public boolean hasPending(String runId) {
        return pending.containsKey(runId);
    }
}
