package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls an upstream long-running job until completion (Phase 10 of the typed-execution refactor).
 *
 * Configured by the endpoint's {@code execution.async} block:
 *
 * <pre>
 * "async": {
 *   "submit":  { "responseIdPath": "$.id" },
 *   "poll":    { "method": "GET", "path": "/v1/scrape/{id}", "intervalMs": 2000, "maxWaitMs": 300000 },
 *   "status":  { "path": "$.status",
 *                "successValues": ["completed","success"],
 *                "failureValues": ["failed","error","cancelled"] },
 *   "resultPath": "$.data"
 * }
 * </pre>
 *
 * <h2>Phase-10 implementation note</h2>
 * This first cut runs an <strong>in-process polling loop</strong> using
 * {@link Thread#sleep(long)} bounded by {@code maxWaitMs}. It is intentionally NOT integrated
 * with the orchestrator's signal system yet - the goal is to ship a working pipeline that we
 * can swap for a proper {@code ASYNC_POLL} signal in a follow-up without changing call sites.
 *
 * <p>Limits the in-process loop to {@code maxWaitMs} so a malformed config can't hang a request
 * thread forever. The orchestrator request thread is occupied for the entire poll duration -
 * acceptable for prototypes but should be replaced by the signal system before high-volume use.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AsyncPollExecutor {

    /** Hard upper bound on polling - even if config says higher, never exceed this. */
    private static final long ABSOLUTE_MAX_WAIT_MS = 10 * 60 * 1000L; // 10 minutes
    /** Hard lower bound on poll interval to avoid hammering the upstream. */
    private static final long MIN_INTERVAL_MS = 250L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Poll the upstream job until terminal status, then return the resolved result body.
     *
     * @param baseUrl       upstream API base URL (used to build the poll URL)
     * @param submitResponse the body returned by the initial submit call (must contain the job id)
     * @param asyncConfig   the {@code execution.async} JsonNode
     * @param headers       headers to send with each poll (typically: same as the submit call)
     * @return the upstream job's final result, extracted via {@code resultPath}
     * @throws AsyncPollFailureException when the job fails or times out
     */
    public Object pollUntilDone(String baseUrl,
                                JsonNode submitResponse,
                                JsonNode asyncConfig,
                                HttpHeaders headers) throws AsyncPollFailureException {
        if (asyncConfig == null || !asyncConfig.isObject()) {
            throw new AsyncPollFailureException("execution.async block missing or malformed");
        }

        // 1. Extract job id
        String responseIdPath = asyncConfig.path("submit").path("responseIdPath").asText("$.id");
        String jobId = readPath(submitResponse, responseIdPath);
        if (jobId == null || jobId.isBlank()) {
            throw new AsyncPollFailureException("Could not extract job id from submit response (path=" + responseIdPath + ")");
        }

        // 2. Poll config
        JsonNode poll = asyncConfig.path("poll");
        String pollMethod = poll.path("method").asText("GET").toUpperCase();
        String pollPath   = poll.path("path").asText("");
        long intervalMs   = Math.max(MIN_INTERVAL_MS, poll.path("intervalMs").asLong(2000));
        long maxWaitMs    = Math.min(ABSOLUTE_MAX_WAIT_MS, poll.path("maxWaitMs").asLong(60_000));
        if (pollPath.isBlank()) {
            throw new AsyncPollFailureException("execution.async.poll.path is required");
        }
        String pollUrl = baseUrl + resolvePollPath(pollPath, responseIdPath, jobId);

        // 3. Status config
        JsonNode statusCfg = asyncConfig.path("status");
        String statusPath = statusCfg.path("path").asText("$.status");
        List<String> successValues = readStringList(statusCfg.path("successValues"));
        List<String> failureValues = readStringList(statusCfg.path("failureValues"));
        if (successValues.isEmpty()) {
            throw new AsyncPollFailureException("execution.async.status.successValues must be non-empty");
        }

        String resultPath = asyncConfig.path("resultPath").asText(null);

        long deadline = System.currentTimeMillis() + maxWaitMs;
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                ResponseEntity<Object> response = restTemplate.exchange(
                    java.net.URI.create(pollUrl),
                    HttpMethod.valueOf(pollMethod),
                    new HttpEntity<>(headers),
                    Object.class
                );
                JsonNode body = objectMapper.valueToTree(response.getBody());
                String status = readPath(body, statusPath);

                if (status != null) {
                    if (failureValues.contains(status)) {
                        throw new AsyncPollFailureException("Async job " + jobId + " ended with failure status: " + status);
                    }
                    if (successValues.contains(status)) {
                        log.info("AsyncPollExecutor: job {} completed after {} attempts", jobId, attempt);
                        if (resultPath != null && !resultPath.isBlank()) {
                            JsonNode result = navigatePath(body, resultPath);
                            return objectMapper.convertValue(result, Object.class);
                        }
                        return objectMapper.convertValue(body, Object.class);
                    }
                }
            } catch (AsyncPollFailureException e) {
                throw e;
            } catch (Exception e) {
                log.warn("AsyncPollExecutor: poll attempt {} failed for job {}: {}", attempt, jobId, e.getMessage());
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AsyncPollFailureException("Polling interrupted for job " + jobId);
            }
        }
        throw new AsyncPollFailureException("Async job " + jobId + " exceeded maxWaitMs=" + maxWaitMs + "ms");
    }

    /**
     * Read a single string value from a JSON tree using a tiny dollar-prefixed path
     * (e.g. {@code $.status}, {@code $.data.id}). Not a full JsonPath implementation -
     * deliberately minimal so the spec stays declarative.
     */
    private String readPath(JsonNode root, String path) {
        JsonNode node = navigatePath(root, path);
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private JsonNode navigatePath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        String trimmed = path.startsWith("$.") ? path.substring(2) : path;
        if (trimmed.startsWith("$")) trimmed = trimmed.substring(1);
        JsonNode current = root;
        for (String segment : trimmed.split("\\.")) {
            if (segment.isBlank()) continue;
            current = current.path(segment);
            if (current.isMissingNode()) return null;
        }
        return current;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        java.util.ArrayList<String> out = new java.util.ArrayList<>(node.size());
        for (JsonNode element : node) {
            out.add(element.asText());
        }
        return out;
    }

    private static String resolvePollPath(String pollPath, String responseIdPath, String jobId) {
        String resolved = pollPath.replace("{id}", jobId);
        String responseIdName = responseIdPathName(responseIdPath);
        if (responseIdName != null) {
            resolved = resolved.replace("{" + responseIdName + "}", jobId);
        }
        return resolved;
    }

    private static String responseIdPathName(String responseIdPath) {
        if (responseIdPath == null || responseIdPath.isBlank()) {
            return null;
        }
        int dot = responseIdPath.lastIndexOf('.');
        String name = dot >= 0 ? responseIdPath.substring(dot + 1) : responseIdPath;
        if (name.startsWith("$")) {
            return null;
        }
        return name.isBlank() ? null : name;
    }

    /**
     * Thrown when the upstream job fails or times out. Caught by ToolExecutionOrchestrator
     * and converted into a normal {success:false, error:...} response.
     */
    public static class AsyncPollFailureException extends Exception {
        public AsyncPollFailureException(String message) { super(message); }
    }
}
