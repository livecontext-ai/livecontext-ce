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

    /**
     * Hard upper bound on polling - even if config says higher, never exceed this.
     *
     * <p>Public because it is half of how long a generation can legitimately
     * stay in flight, and every budget downstream of it has to be sized on that
     * number rather than on a copy of it: a caller that gives up first abandons
     * a call this service goes on to finish and CHARGE for. See
     * {@code ToolExecutionManager.GENERATION_WORST_CASE_MS}.
     */
    public static final long ABSOLUTE_MAX_WAIT_MS = 10 * 60 * 1000L; // 10 minutes
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
        // The job id comes from the upstream's response and is spliced into the url, and the poll
        // carries the submit call's credential headers, so the poll target gets the same SSRF
        // check as every other outbound call (LC-006), on EVERY attempt below, the first one
        // included: polling can last minutes, long enough for the name to be re-pointed (DNS
        // rebinding). The pinned transport additionally dials only the address it vetted (LC-073).

        // 3. Status config
        JsonNode statusCfg = asyncConfig.path("status");
        String statusPath = statusCfg.path("path").asText("$.status");
        List<String> successValues = readStringList(statusCfg.path("successValues"));
        List<String> failureValues = readStringList(statusCfg.path("failureValues"));
        if (successValues.isEmpty()) {
            throw new AsyncPollFailureException("execution.async.status.successValues must be non-empty");
        }

        String resultPath = asyncConfig.path("resultPath").asText(null);

        long deadline = clock.getAsLong() + maxWaitMs;
        int attempt = 0;

        while (true) {
            attempt++;
            // A refusal (AsyncPollFailureException) is final and propagates; a lookup that failed
            // only skips this attempt: nothing is sent on it.
            boolean resolved = true;
            try {
                checkPollTarget(pollUrl);
            } catch (com.apimarketplace.common.web.UnresolvableHostException
                     | com.apimarketplace.common.web.UrlResolutionException unresolved) {
                resolved = false;
                log.warn("AsyncPollExecutor: poll host for job {} did not resolve on attempt {}, nothing sent: {}",
                        jobId, attempt, com.apimarketplace.common.web.UrlLogRedaction.redact(unresolved.getMessage()));
            }
            if (resolved) {
                try {
                    ResponseEntity<Object> response = transport().exchange(
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
                    // The exception message of a client error quotes the full poll url, query included.
                    log.warn("AsyncPollExecutor: poll attempt {} failed for job {}: {}", attempt, jobId,
                            com.apimarketplace.common.web.UrlLogRedaction.redact(e.getMessage()));
                }
            }
            // An interrupt that landed during the attempt (a DNS lookup keeps the flag and reports
            // it as a failed lookup) ends the poll as what it is, not as a timeout at the deadline.
            if (Thread.currentThread().isInterrupted()) {
                throw new AsyncPollFailureException("Polling interrupted for job " + jobId);
            }
            // The pause never runs past the deadline, and one last attempt is made AT the deadline:
            // a host that comes back during the final pause still gets its answer read.
            long remainingMs = deadline - clock.getAsLong();
            if (remainingMs <= 0) {
                break;
            }
            long pauseMs = Math.min(resolved ? intervalMs : Math.max(intervalMs, UNRESOLVED_RETRY_MS), remainingMs);
            try {
                pauser.pause(pauseMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AsyncPollFailureException("Polling interrupted for job " + jobId);
            }
        }
        throw new AsyncPollFailureException("Async job " + jobId + " exceeded maxWaitMs=" + maxWaitMs + "ms");
    }

    /**
     * The SSRF gate for one poll: the same check as every other outbound call of the catalog
     * (egress policy + credential host binding). A refusal is final, never retried, and never
     * echoes the url's query (it can carry the job token or an injected credential).
     *
     * <p>A name that did not resolve, or a lookup that timed out, is NOT a refusal: it is thrown
     * as is, and the poll loop counts it as one failed attempt. The upstream holds a job the user
     * already paid for, and one DNS incident during minutes of polling used to abandon it
     * (regression review 2026-09-29); a poll host that never resolves (it can differ from the
     * submit host) only waits out maxWaitMs. Nothing is sent on such an attempt, and an address
     * that resolves private is still refused at once.
     */
    private static void checkPollTarget(String pollUrl) throws AsyncPollFailureException {
        try {
            com.apimarketplace.catalog.service.http.HttpExecutionService.validatedTarget(pollUrl);
        } catch (com.apimarketplace.common.web.UnresolvableHostException
                 | com.apimarketplace.common.web.UrlResolutionException unresolved) {
            throw unresolved;
        } catch (IllegalArgumentException e) {
            throw new AsyncPollFailureException("Refusing to poll "
                    + com.apimarketplace.common.web.UrlLogRedaction.origin(pollUrl) + ": "
                    + com.apimarketplace.common.web.UrlLogRedaction.redact(e.getMessage()));
        }
    }

    /**
     * Wait after an attempt whose poll host did not resolve. The JVM caches a failed lookup for
     * 10 s ({@code networkaddress.cache.negative.ttl}), so asking sooner learns nothing, while
     * every lookup that hangs holds one of the few JVM-wide DNS slots every SSRF check needs.
     */
    static final long UNRESOLVED_RETRY_MS = 10_000;

    /** Waits between attempts; replaced in tests so the deadline logic runs on a fake clock. */
    interface Pauser {
        void pause(long millis) throws InterruptedException;
    }

    /** Monotonic: a wall-clock step (NTP) must neither shorten nor stretch maxWaitMs. */
    private java.util.function.LongSupplier clock = () -> System.nanoTime() / 1_000_000;
    private Pauser pauser = Thread::sleep;

    /** Test seam: a fake clock and the pause that advances it. */
    void setTimeSource(java.util.function.LongSupplier clock, Pauser pauser) {
        this.clock = clock;
        this.pauser = pauser;
    }

    /** Pinned transport in production (OutboundHttpClients); the constructor one in unit tests. */
    private RestTemplate outboundRestTemplate;

    /** Required, as in HttpExecutionService: the poll carries the submit call's credentials. */
    @org.springframework.beans.factory.annotation.Autowired
    void setOutboundHttpClients(com.apimarketplace.catalog.service.http.OutboundHttpClients clients) {
        this.outboundRestTemplate = clients == null ? null : clients.restTemplate();
    }

    RestTemplate transport() {
        return outboundRestTemplate != null ? outboundRestTemplate : restTemplate;
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
