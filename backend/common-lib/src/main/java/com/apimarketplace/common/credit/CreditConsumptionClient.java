package com.apimarketplace.common.credit;

import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified HTTP client for consuming credits via auth-service.
 *
 * Used by both orchestrator-service and conversation-service.
 * Each service creates a bean of this class with its own config.
 *
 * Fire-and-forget: all credit consumption calls are async to avoid
 * blocking workflow/chat execution.
 *
 * Fail-closed: if auth-service is unreachable and no recent cache entry exists,
 * checkCredits returns false (block execution).
 *
 * On permanent failure after retries, persists to dead-letter via
 * CreditDeadLetterHandler for later reconciliation.
 */
public class CreditConsumptionClient {

    private static final Logger log = LoggerFactory.getLogger(CreditConsumptionClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final long CACHE_TTL_SECONDS = 30;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 1000;
    private static final String INTERNAL_PROVIDER_ID = "internal-credit-client";

    /**
     * Source type for chat/agent turn gates. Pass to {@link #checkCredits(String, String)}
     * so the server applies the FREE-plan bucket scoping (chat draws the PAYG
     * bucket alone on FREE). Shared constant so the four chat gate call-sites
     * and any future one cannot drift by typo (a typo would be fail-safe -
     * more restrictive - but silently wrong).
     */
    public static final String SOURCE_TYPE_CHAT_CONVERSATION = "CHAT_CONVERSATION";

    /**
     * A CE install's LLM traffic relayed through cloud. Deliberately NOT funded by the
     * FREE AI allowance (see {@code CreditService.AI_ALLOWANCE_SOURCE_TYPES}), which is
     * exactly why a relay pre-flight must name it rather than gating as a chat turn.
     */
    public static final String SOURCE_TYPE_CE_LLM_RELAY = "CE_LLM_RELAY";

    private final RestTemplate restTemplate;
    private final String authServiceUrl;
    private final boolean enabled;
    private final String gatewaySecretKey;

    /**
     * Upper bound on {@link #creditCheckCache}.
     *
     * <p>The map is only ever written to, never swept: entries fall out of use after
     * {@link #CACHE_TTL_SECONDS} but stay resident for the life of the process. Its
     * keys were already high-cardinality (the chat-budget key carries the estimated
     * token counts, which differ on every turn) and V494 added the model to the
     * generic key as well, so on a busy pod it grows all day. The cap keeps that
     * bounded; expired entries go first, and only if every remaining entry is still
     * live does the whole map go, which costs one extra round trip per key and
     * nothing else - the answers are re-fetchable by definition.
     */
    private static final int CACHE_MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, CachedCheck> creditCheckCache = new ConcurrentHashMap<>();

    /** Dead-letter handler - injected after construction to avoid circular deps. */
    private CreditDeadLetterHandler deadLetterHandler;

    /**
     * Spring context - used to look up the proxied self bean so the @Async
     * annotation on {@link #consumeCreditsAsyncInternalAsync} actually engages
     * (intra-class {@code this.} calls bypass the CGLIB proxy and run sync,
     * blocking the caller for up to 7s on Thread.sleep retries - audit
     * round-6 fix). Field-injected so existing @Bean constructors in the
     * service-level auto-configs don't change. Null in unit tests where no
     * Spring context boots; we fall back to {@code this.} (still correct,
     * just synchronous - fine for tests).
     */
    private org.springframework.context.ApplicationContext applicationContext;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setApplicationContext(org.springframework.context.ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    record CachedCheck(boolean allowed, Instant timestamp) {
        boolean isValid() {
            return Duration.between(timestamp, Instant.now()).getSeconds() < CACHE_TTL_SECONDS;
        }
    }

    private HttpHeaders userHeaders(String userId, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        if (userId != null && !userId.isBlank()) {
            headers.set("X-User-ID", userId);
        }
        OrgContextHeaderForwarder.forward(headers);
        applyGatewaySignature(headers);
        return headers;
    }

    private static String scopedUserKey(String userId) {
        String orgId = TenantResolver.currentRequestOrganizationId();
        return userId + "|org:" + (orgId != null && !orgId.isBlank() ? orgId : "<default>");
    }

    /**
     * Full constructor with enabled flag (used by orchestrator which has the config property).
     */
    public CreditConsumptionClient(String authServiceUrl, boolean enabled) {
        this(authServiceUrl, enabled, null);
    }

    public CreditConsumptionClient(String authServiceUrl, boolean enabled, String gatewaySecretKey) {
        this.enabled = enabled;
        this.gatewaySecretKey = gatewaySecretKey;
        if (enabled) {
            this.restTemplate = new RestTemplateBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .readTimeout(READ_TIMEOUT)
                    .build();
        } else {
            this.restTemplate = null;
            log.info("CreditConsumptionClient: disabled");
        }
        this.authServiceUrl = stripTrailingSlash(authServiceUrl);
    }

    /**
     * Simple constructor - always enabled.
     */
    public CreditConsumptionClient(String authServiceUrl) {
        this(authServiceUrl, true, null);
    }

    public CreditConsumptionClient(String authServiceUrl, String gatewaySecretKey) {
        this(authServiceUrl, true, gatewaySecretKey);
    }

    /**
     * Set the dead-letter handler. Called by each service's dead-letter service @PostConstruct.
     */
    public void setDeadLetterHandler(CreditDeadLetterHandler handler) {
        this.deadLetterHandler = handler;
    }

    /**
     * Persist a synchronous credit-consumption rejection (e.g. 402 insufficient credits)
     * directly to dead-letter, without going through the async retry path.
     *
     * <p>Why a dedicated method: when {@link #consumeCredits} returns
     * {@code {success:false, error:"402 Insufficient credits"}} the caller has token-usage
     * data that must not be silently lost. Routing the rejection through
     * {@link #consumeCreditsAsync} would burn 3 retries hitting the same 402 before
     * the dead-letter is written. This bypasses the retries because a 402 is a hard
     * business decision (insufficient funds) - re-asking the auth-service won't change
     * the answer until the user is refilled. Best-effort: swallows handler exceptions so
     * the caller's main path is never broken by dead-letter persistence.
     *
     * @param errorReason short reason string stored on the dead-letter row (e.g.
     *                    {@code "402 Insufficient credits"} or {@code "Non-2xx: 503"})
     */
    public void persistRejection(String tenantId, String sourceType, String sourceId,
                                  String provider, String model,
                                  Integer promptTokens, Integer completionTokens,
                                  String errorReason) {
        // Phase 6 MIGRATION_ORG_ID_NOT_NULL (CC-2): capture orgId on the
        // caller's thread BEFORE delegating - this method is invoked
        // synchronously from chat/agent post-flight billing, so the request
        // is still bound here. The 9-arg overload below threads it through
        // to the handler explicitly.
        persistRejection(tenantId, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, errorReason,
                TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Phase 6 MIGRATION_ORG_ID_NOT_NULL (CC-2, 2026-05-19) - explicit-org
     * overload of {@link #persistRejection}. Use this from daemon/async
     * threads where {@link com.apimarketplace.common.web.TenantResolver#currentRequestOrganizationId()}
     * would resolve null. Otherwise the 8-arg form is fine.
     */
    public void persistRejection(String tenantId, String sourceType, String sourceId,
                                  String provider, String model,
                                  Integer promptTokens, Integer completionTokens,
                                  String errorReason, String organizationId) {
        persistRejection(tenantId, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, errorReason, organizationId, null);
    }

    /**
     * Explicit-org overload carrying the key route the debit was meant to run under, so a
     * dead-letter replay bills an own-key turn its flat fee and not the token rate.
     */
    public void persistRejection(String tenantId, String sourceType, String sourceId,
                                  String provider, String model,
                                  Integer promptTokens, Integer completionTokens,
                                  String errorReason, String organizationId, String keyRoute) {
        if (!enabled || deadLetterHandler == null) return;
        try {
            deadLetterHandler.persistFailedConsumption(tenantId, sourceType, sourceId,
                    provider, model, promptTokens, completionTokens, errorReason, organizationId, keyRoute);
            log.warn("Persisted credit rejection to dead-letter: tenant={}, source={}/{}, org={}, reason={}",
                    tenantId, sourceType, sourceId, organizationId, errorReason);
        } catch (Exception e) {
            log.error("Failed to persist rejection to dead-letter for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Check if user has sufficient credits (legacy total-balance form, used by
     * workflow launch gates where the FREE monthly bucket IS eligible).
     * Fail-closed: returns false if auth-service is unreachable and no cache.
     */
    public boolean checkCredits(String userId) {
        return checkCredits(userId, null);
    }

    /**
     * Source-type-aware credit check. Pass the spend's source type (e.g.
     * {@code CHAT_CONVERSATION} from the internal/scheduled chat gates) so the
     * server applies the FREE-plan bucket scoping: a Free user with monthly
     * workflow-only credits but no PAYG top-up is refused up-front instead of
     * running the LLM and overshooting the PAYG bucket negative post-flight.
     * {@code null} keeps the legacy total-balance semantics.
     *
     * <p>The cache key includes the sourceType - the scoped and unscoped
     * questions have different answers for a FREE account, so a cached
     * workflow-gate "allowed" must never be served back to a chat gate.
     */
    public boolean checkCredits(String userId, String sourceType) {
        return checkCredits(userId, sourceType, null, null);
    }

    /**
     * Model-aware variant (V494). A caller that knows which model the turn will run
     * on MUST use it: a Free account's monthly AI allowance pays for chat and agent
     * turns on the models opened to the free tier, and auth-service cannot count
     * that allowance toward the gate without knowing the model. Omitting it refuses
     * a turn the debit would have funded.
     *
     * <p>The model is part of the cache key, since the verdict now differs between
     * two models for the same account and source type.
     */
    public boolean checkCredits(String userId, String sourceType, String provider, String model) {
        if (!enabled) return true;
        if (userId == null || userId.isBlank()) return true;

        // URI TEMPLATE, not a hand-encoded string. RestTemplate expands the url through
        // DefaultUriBuilderFactory, which encodes what it is given - so a value we had
        // already percent-encoded came out encoded TWICE ("meta%2Fllama-3" became
        // "meta%252Fllama-3"), and auth-service then looked up a model id that does not
        // exist, failed closed, and refused a turn the allowance would have paid for.
        // Exactly the divergence the model parameter was added to remove, on the ids
        // most likely to need it (openrouter/z-ai style "vendor/model" names).
        // Placeholders leave the single encoding pass to the one component that knows
        // which characters are reserved where.
        StringBuilder query = new StringBuilder();
        Map<String, Object> uriVars = new HashMap<>();
        if (sourceType != null && !sourceType.isBlank()) {
            query.append(query.isEmpty() ? "?" : "&").append("sourceType={sourceType}");
            uriVars.put("sourceType", sourceType);
        }
        if (provider != null && !provider.isBlank()) {
            query.append(query.isEmpty() ? "?" : "&").append("provider={provider}");
            uriVars.put("provider", provider);
        }
        if (model != null && !model.isBlank()) {
            query.append(query.isEmpty() ? "?" : "&").append("model={model}");
            uriVars.put("model", model);
        }
        String url = authServiceUrl + "/api/credits/check" + query;
        String cacheKey = scopedUserKey(userId)
                + (sourceType != null && !sourceType.isBlank() ? ":" + sourceType : "")
                + (provider != null && !provider.isBlank() ? ":" + provider : "")
                + (model != null && !model.isBlank() ? ":" + model : "");
        HttpHeaders headers = userHeaders(userId, null);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class, uriVars);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object allowed = response.getBody().get("allowed");
                boolean result = Boolean.TRUE.equals(allowed);
                rememberCheck(cacheKey, result);
                return result;
            }
            if (response.getStatusCode().value() == 402) {
                rememberCheck(cacheKey, false);
                return false;
            }
            return useCacheOrFailClosed(cacheKey, "unexpected status " + response.getStatusCode());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 402) {
                rememberCheck(cacheKey, false);
                return false;
            }
            return useCacheOrFailClosed(cacheKey, e.getMessage());
        } catch (Exception e) {
            return useCacheOrFailClosed(cacheKey, e.getMessage());
        }
    }

    /**
     * Cost-aware pre-flight check for a chat turn. Asks auth-service whether the
     * user's balance covers the projected cost of {@code (provider, model,
     * estimatedPromptTokens, estimatedCompletionTokens)}. Returns {@code false} on 402
     * (insufficient balance for the estimate). Fail-closed on transport failure: if
     * auth-service is unreachable and no cache entry exists, returns {@code false}
     * so we never dispatch inference that can't be paid for.
     *
     * <p>Fail-closed on missing provider/model: we don't substitute a sentinel and
     * ask the server to guess - the server would then price the call at default
     * (mid-tier) rates and pass the gate for any sufficiently-funded user, defeating
     * the point of the check for frontier/bridge models. Null/blank ⇒ deny.
     *
     * <p>Uses a cache key distinct from {@link #checkCredits} so a chat-budget
     * rejection for "too expensive for this turn" does NOT block a subsequent
     * generic "balance &gt; 0" check (different questions, different answers).
     */
    /**
     * Back-compat overload: gates as {@code CHAT_CONVERSATION}. Callers that debit as
     * something else MUST use the 6-arg form, or the gate answers about a different
     * bucket set than the debit will draw (V494).
     */
    public boolean checkChatBudget(String userId, String provider, String model,
                                   int estimatedPromptTokens, int estimatedCompletionTokens) {
        return checkChatBudget(userId, provider, model,
                estimatedPromptTokens, estimatedCompletionTokens, SOURCE_TYPE_CHAT_CONVERSATION);
    }

    /**
     * @param sourceType what the caller will DEBIT this turn as. The gate scopes the
     *                   balance by source type (the FREE AI allowance funds some LLM
     *                   sources and not others), so passing anything but the real one
     *                   asks about a bucket set the debit will not draw.
     */
    public boolean checkChatBudget(String userId, String provider, String model,
                                    int estimatedPromptTokens, int estimatedCompletionTokens,
                                    String sourceType) {
        if (!enabled) return true;
        if (userId == null || userId.isBlank()) return true;
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            log.warn("Chat budget check blocked (fail-closed): userId={} provider={} model={} - cannot price without both",
                    userId, provider, model);
            return false;
        }

        String cacheKey = chatBudgetCacheKey(userId, provider, model,
                estimatedPromptTokens, estimatedCompletionTokens, sourceType);
        String url = authServiceUrl + "/api/credits/check-chat";
        HttpHeaders headers = userHeaders(userId, MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("provider", provider);
        body.put("model", model);
        body.put("estimatedPromptTokens", estimatedPromptTokens);
        body.put("estimatedCompletionTokens", estimatedCompletionTokens);
        if (sourceType != null && !sourceType.isBlank()) {
            body.put("sourceType", sourceType);
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object allowed = response.getBody().get("allowed");
                boolean result = Boolean.TRUE.equals(allowed);
                rememberCheck(cacheKey, result);
                return result;
            }
            if (response.getStatusCode().value() == 402) {
                rememberCheck(cacheKey, false);
                return false;
            }
            return useCacheOrFailClosed(cacheKey, "unexpected status " + response.getStatusCode());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 402) {
                rememberCheck(cacheKey, false);
                return false;
            }
            return useCacheOrFailClosed(cacheKey, e.getMessage());
        } catch (Exception e) {
            return useCacheOrFailClosed(cacheKey, e.getMessage());
        }
    }

    /**
     * Cache key for chat-budget checks - distinct namespace from generic
     * {@link #checkCredits} so the two questions don't poison each other's cache.
     * Keyed on {@code (userId, provider, model, estPrompt, estCompletion, sourceType)}
     * so a cached answer is only served back for the same cost question. The source
     * type is part of it because the answer genuinely differs by it: the FREE AI
     * allowance funds a chat turn on an open model and not a CE relay turn on the same
     * model, so one verdict must never be served for the other.
     */
    private static String chatBudgetCacheKey(String userId, String provider, String model,
                                              int estPrompt, int estCompletion, String sourceType) {
        return "chat:" + scopedUserKey(userId) + ":" + provider + ":" + model
                + ":" + estPrompt + ":" + estCompletion
                + ":" + (sourceType == null || sourceType.isBlank() ? SOURCE_TYPE_CHAT_CONVERSATION : sourceType);
    }

    /** Stores a verdict, keeping the cache within {@link #CACHE_MAX_ENTRIES}. */
    private void rememberCheck(String cacheKey, boolean allowed) {
        if (creditCheckCache.size() >= CACHE_MAX_ENTRIES) {
            creditCheckCache.values().removeIf(entry -> !entry.isValid());
            if (creditCheckCache.size() >= CACHE_MAX_ENTRIES) {
                // Evict the KEYS OF THE CALLER THAT FILLED IT, not the whole map.
                //
                // Key cardinality is driven by request content: the model is part of it
                // (V494) and reaches this from a request body, so one authenticated user
                // sending 10 000 distinct model strings inside the 30s TTL can push the
                // cache to its cap. Clearing everything there would let that user flush
                // every OTHER tenant's verdict, and the next auth-service blip would fail
                // closed for all of them instead of riding out on cache. Dropping only
                // this caller's own entries keeps the blast radius on whoever caused it.
                String owner = ownerPrefix(cacheKey);
                creditCheckCache.keySet().removeIf(k -> ownerPrefix(k).equals(owner));
                if (creditCheckCache.size() >= CACHE_MAX_ENTRIES) {
                    // Genuinely many distinct callers, all live: nothing better is
                    // available without an age index, and the answers are re-fetchable
                    // by definition (one extra round trip per key, once). Reaching here
                    // takes 10 000 live entries spread across enough OWNERS that evicting
                    // one owner's keys does not get under the cap, which is why the unit
                    // test cannot drive it from a single caller.
                    creditCheckCache.clear();
                }
            }
        }
        creditCheckCache.put(cacheKey, new CachedCheck(allowed, Instant.now()));
    }

    /**
     * The "{@code <userId>|org:<orgId>}" head of a cache key, which is what identifies
     * the caller an entry belongs to. Every key goes through {@link #scopedUserKey}, so
     * the marker is always present - including on the "{@code chat:}"-prefixed keys,
     * where it simply sits further in. A key without it would fall back to scanning from
     * index 4, which is arbitrary rather than safe; it is unreachable today, and the day
     * a second key shape appears this needs revisiting rather than inheriting.
     */
    private static String ownerPrefix(String cacheKey) {
        int end = cacheKey.indexOf(":", cacheKey.indexOf("|org:") + 5);
        return end < 0 ? cacheKey : cacheKey.substring(0, end);
    }

    /** Visible for tests: how many verdicts are currently held. */
    int cachedCheckCount() {
        return creditCheckCache.size();
    }

    private boolean useCacheOrFailClosed(String cacheKey, String errorReason) {
        CachedCheck cached = creditCheckCache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            log.warn("Credit check failed for key {}, using cached result (allowed={}): {}",
                    cacheKey, cached.allowed(), errorReason);
            return cached.allowed();
        }
        log.warn("Credit check failed for key {}, no valid cache - blocking execution (fail-closed): {}",
                cacheKey, errorReason);
        return false;
    }

    /**
     * Existence check for an auth.model_pricing row keyed on
     * {@code (provider, model)}. Tools that bill from model-pricing rows (for
     * example image generation) call this before invoking the upstream provider so a
     * missing migration or catalog drift surfaces as a fail-fast
     * {@code QUOTA_EXCEEDED} response to the agent rather than as a silent
     * default-rate fallback or a post-flight 402.
     *
     * <p>Fail-closed: returns {@code false} on transport failure so a flaky
     * auth-service can't accidentally enable un-priced tool calls. Symmetric
     * with {@link #checkChatBudget}'s posture; opposite of
     * {@code tryReserveMarkup}'s fail-open (the latter is for non-blocking
     * pre-flight reservation, this is for blocking pricing existence).
     */
    public boolean hasPricing(String provider, String model) {
        if (!enabled) return true;
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            return false;
        }
        String url = authServiceUrl + "/api/credits/pricing/" + provider + "/" + model + "/exists";
        HttpHeaders headers = userHeaders(null, null);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Boolean.TRUE.equals(response.getBody().get("exists"));
            }
            return false;
        } catch (Exception e) {
            log.warn("hasPricing({}, {}) failed, fail-closed: {}", provider, model, e.getMessage());
            return false;
        }
    }

    /**
     * Fetch the current credit balance for a user.
     * Returns ZERO if auth-service is unreachable (fail-closed).
     * Returns a large sentinel value for null/blank userId (no restriction).
     */
    public BigDecimal fetchBalance(String userId) {
        if (!enabled || userId == null || userId.isBlank()) {
            return new BigDecimal("999999999");
        }

        String url = authServiceUrl + "/api/credits/balance";
        HttpHeaders headers = userHeaders(userId, null);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object balance = response.getBody().get("balance");
                if (balance instanceof Number) {
                    return new BigDecimal(balance.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch balance for user {}, returning ZERO (fail-closed): {}", userId, e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    /**
     * Spending power for LLM work: the wallet PLUS the monthly AI allowance (V494).
     *
     * <p>Deliberately a separate method rather than widening {@link #fetchBalance}.
     * The allowance can only pay for agent/chat turns on a free-tier model, so folding
     * it into the general figure hands a phantom budget to callers it can never fund:
     * the orchestrator's workflow run budget would start a run on credits no
     * WORKFLOW_NODE debit can draw (the run then dies mid-execution instead of being
     * refused up front), and the image-generation pre-flight would pass a gate the
     * debit refuses.
     *
     * <p>Use this ONLY from LLM budget guards. It is a safety net, not the authoritative
     * gate - the pre-flight check and the debit both re-resolve - but it is model-aware:
     * the allowance only funds the models an admin opened, so the server is asked what
     * the pot is worth for THIS model rather than adding it blindly. Adding it blindly
     * budgets an agent loop against money no debit on a closed model can draw, which is
     * the same error as withholding it, pointing the other way.
     *
     * <p>The model-blind overload remains for callers that genuinely have no model; it
     * reports the wallet alone, which is the pre-V494 answer and always safe.
     */
    public BigDecimal fetchLlmSpendableBalance(String userId) {
        return fetchLlmSpendableBalance(userId, null, null);
    }

    public BigDecimal fetchLlmSpendableBalance(String userId, String provider, String model) {
        if (!enabled || userId == null || userId.isBlank()) {
            return new BigDecimal("999999999");
        }
        // ONE request, both keys. Calling fetchBalance and then re-fetching the same
        // endpoint would double the round trip on a path that runs every few agent-loop
        // iterations, and the two reads could land on different snapshots - so the two
        // halves of one balance would not be the same balance.
        boolean modelAware = provider != null && !provider.isBlank()
                && model != null && !model.isBlank();
        String url = authServiceUrl + "/api/credits/balance"
                + (modelAware ? "?provider={provider}&model={model}" : "");
        Map<String, Object> uriVars = modelAware
                ? Map.of("provider", provider, "model", model)
                : Map.of();
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(userHeaders(userId, null)), Map.class, uriVars);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // The server answered the model-aware question: it already decided whether
                // the allowance counts here, so take it verbatim.
                if (response.getBody().get("llmSpendableBalance") instanceof Number spendable) {
                    return new BigDecimal(spendable.toString());
                }
                if (response.getBody().get("balance") instanceof Number wallet) {
                    BigDecimal total = new BigDecimal(wallet.toString());
                    // No model to ask about (or an auth-service that predates the field):
                    // the pot is added, which is the permissive direction and the one the
                    // model-blind callers were written for.
                    if (!modelAware && response.getBody().get("aiBalance") instanceof Number ai) {
                        total = total.add(new BigDecimal(ai.toString()));
                    }
                    return total;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch balance for user {}, returning ZERO (fail-closed): {}",
                    userId, e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    /**
     * Consume credits asynchronously with retry and dead-letter on permanent failure.
     * Retries up to 3 times with exponential backoff (1s, 2s, 4s).
     * On 402 (insufficient credits), does not retry.
     * After all retries fail, persists to dead-letter for later reconciliation.
     */
    public void consumeCreditsAsync(String userId, String sourceType, String sourceId,
                                     String provider, String model,
                                     Integer promptTokens, Integer completionTokens,
                                     String keyRoute) {
        String capturedOrgId = TenantResolver.currentRequestOrganizationId();
        TenantResolver.requireOrgId(capturedOrgId);
        asyncSelf().consumeCreditsAsyncInternalAsync(userId, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, /* imageCount */ null, capturedOrgId, keyRoute);
    }

    /** Route-less form: see the overload above. */
    public void consumeCreditsAsync(String userId, String sourceType, String sourceId,
                                     String provider, String model,
                                     Integer promptTokens, Integer completionTokens) {
        // Phase 6 MIGRATION_ORG_ID_NOT_NULL (CC-2, 2026-05-19): capture orgId
        // on the producer's thread BEFORE Spring's @Async proxy crosses to a
        // worker thread (where RequestContextHolder is empty). The captured
        // orgId is passed as an explicit arg to consumeCreditsAsyncInternalAsync
        // so retry exhaustion can stamp the V263-NOT-NULL dead-letter row.
        //
        // Audit round-6 fix: route through ApplicationContext.getBean to
        // engage the CGLIB proxy on @Async. Intra-class self-invocation would
        // bypass the proxy → retry loop runs synchronously on the caller
        // thread, blocking up to 7s on Thread.sleep backoff.
        //
        // Audit round-8 fix: requireOrgId fails-fast on the PRODUCER thread so
        // the caller sees a real stack trace. Without it, a null orgId hops
        // into the @Async pool, makes it to persistFailedConsumption, throws
        // NPE on its own requireNonNull, which then gets swallowed by the
        // outer try/catch - silent log-and-drop, dead-letter row lost.
        String capturedOrgId = TenantResolver.currentRequestOrganizationId();
        TenantResolver.requireOrgId(capturedOrgId);
        asyncSelf().consumeCreditsAsyncInternalAsync(userId, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, /* imageCount */ null, capturedOrgId);
    }

    /**
     * Image-generation overload of {@link #consumeCreditsAsync}. Posts a
     * distinct {@code imageCount} field in the JSON body so the wire format
     * is self-describing &mdash; readers don't have to know that
     * {@code promptTokens} doubles as a "units billed" slot for the
     * {@code IMAGE_GENERATION} sourceType.
     *
     * <p>The controller (see {@code CreditController.consume} switch case
     * {@code IMAGE_GENERATION}) reads {@code imageCount} from the body and
     * forwards it to {@code consumeForImageGeneration(... actualImageCount)}.
     *
     * <p><b>Dead-letter contract:</b> on retry exhaustion the dead-letter
     * payload reuses the existing 8-arg
     * {@link CreditDeadLetterHandler#persistFailedConsumption} signature with
     * {@code imageCount} aliased into the {@code promptTokens} slot. The
     * subsequent dead-letter reconciliation job replays via the same wire
     * format and the controller's {@code IMAGE_GENERATION} case interprets
     * the slot consistently. No new dead-letter signature is introduced.
     */
    public void consumeCreditsAsync(String userId, String sourceType, String sourceId,
                                     String provider, String model,
                                     Integer imageCount) {
        String capturedOrgId = TenantResolver.currentRequestOrganizationId();
        // Round-8: same fail-fast as the 7-arg overload above - null orgId
        // would land in persistFailedConsumption and be swallowed by the
        // async-pool catch. Surface it on the producer thread.
        TenantResolver.requireOrgId(capturedOrgId);
        // Round-6: same proxy-routing as the 7-arg overload above.
        asyncSelf().consumeCreditsAsyncInternalAsync(userId, sourceType, sourceId, provider, model,
                /* promptTokens */ imageCount, /* completionTokens */ null, imageCount,
                capturedOrgId);
    }

    /**
     * Look up the proxied self bean so {@link #consumeCreditsAsyncInternalAsync}
     * actually runs on the @Async executor. Returns {@code this} as fallback
     * when no Spring context is wired (unit tests) - in that case the call
     * runs synchronously, which is fine for tests but never the prod path.
     */
    private CreditConsumptionClient asyncSelf() {
        if (applicationContext != null) {
            try {
                return applicationContext.getBean(CreditConsumptionClient.class);
            } catch (Exception e) {
                log.warn("Could not resolve self bean for @Async dispatch, falling back to sync: {}",
                        e.getMessage());
            }
        }
        return this;
    }

    /**
     * Phase 6 MIGRATION_ORG_ID_NOT_NULL (CC-2, 2026-05-19) - internal @Async
     * worker. Spring's @Async proxy captures method arguments at invocation
     * time, so the {@code orgId} param threads the producer-thread value into
     * the worker thread. We also bind it to the listener ThreadLocal via
     * {@link com.apimarketplace.common.web.TenantResolver#runWithOrgScope}
     * so the JPA filet fires for any persist that happens during retries.
     */
    @Async
    public void consumeCreditsAsyncInternalAsync(String userId, String sourceType, String sourceId,
                                                  String provider, String model,
                                                  Integer promptTokens, Integer completionTokens,
                                                  Integer imageCount, String orgId) {
        consumeCreditsAsyncInternalAsync(userId, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, imageCount, orgId, null);
    }

    /** Same, carrying the key route the debit is billed under (own-key turns: flat fee). */
    @Async
    public void consumeCreditsAsyncInternalAsync(String userId, String sourceType, String sourceId,
                                                  String provider, String model,
                                                  Integer promptTokens, Integer completionTokens,
                                                  Integer imageCount, String orgId, String keyRoute) {
        TenantResolver.runWithOrgScope(orgId, () ->
                consumeCreditsAsyncInternal(userId, sourceType, sourceId, provider, model,
                        promptTokens, completionTokens, imageCount, orgId, keyRoute));
    }

    private void consumeCreditsAsyncInternal(String userId, String sourceType, String sourceId,
                                              String provider, String model,
                                              Integer promptTokens, Integer completionTokens,
                                              Integer imageCount, String orgId, String keyRoute) {
        if (!enabled) return;
        if (userId == null || userId.isBlank()) return;

        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                Map<String, Object> result = consumeCredits(userId, sourceType, sourceId,
                        provider, model, promptTokens, completionTokens, imageCount,
                        /* cacheTokens */ null, keyRoute);
                if (Boolean.TRUE.equals(result.get("success"))) {
                    return;
                }
                String error = String.valueOf(result.get("error"));
                if (error.contains("402") || error.contains("Insufficient")) {
                    log.warn("Credit consumption permanently rejected (402) for user {}: {}", userId, error);
                    return;
                }
                lastException = new RuntimeException(error);
            } catch (Exception e) {
                lastException = e;
            }

            if (attempt < MAX_RETRIES - 1) {
                long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                log.info("Retrying credit consumption for user {} (attempt {}/{})", userId, attempt + 2, MAX_RETRIES);
            }
        }

        String errorMsg = lastException != null ? lastException.getMessage() : "unknown";
        log.error("Credit consumption failed after {} retries for user {}: {}", MAX_RETRIES, userId, errorMsg);

        if (deadLetterHandler != null) {
            try {
                deadLetterHandler.persistFailedConsumption(userId, sourceType, sourceId,
                        provider, model, promptTokens, completionTokens, errorMsg, orgId, keyRoute);
            } catch (Exception e) {
                // Round-8 audit fix: include exception class + stack trace so a
                // post-V263 DataIntegrityViolationException or a stray
                // NullPointerException from requireNonNull surfaces in error
                // budgets instead of being a single-line ERROR with just
                // e.getMessage(). The producer-side requireOrgId in
                // consumeCreditsAsync should catch null-orgId before it ever
                // hops here, but this stays as defense-in-depth.
                log.error("Failed to persist dead-letter entry for user {} org {}: {} ({})",
                        userId, orgId, e.getMessage(), e.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Consume credits synchronously. Returns the response from auth-service.
     */
    public Map<String, Object> consumeCredits(String userId, String sourceType, String sourceId,
                                               String provider, String model,
                                               Integer promptTokens, Integer completionTokens) {
        return consumeCredits(userId, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, /* imageCount */ null, /* cacheTokens */ null);
    }

    /**
     * Cache-aware variant: forwards the cache/reasoning breakdown so auth-service
     * bills cache reads/writes and cached prompt subsets at the provider's true
     * relative price instead of full input rate. {@code null} breakdown keeps the
     * legacy prompt+completion-only billing.
     */
    public Map<String, Object> consumeCredits(String userId, String sourceType, String sourceId,
                                               String provider, String model,
                                               Integer promptTokens, Integer completionTokens,
                                               LlmCacheTokens cacheTokens) {
        return consumeCredits(userId, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, /* imageCount */ null, cacheTokens);
    }

    /**
     * Consume credits synchronously with an explicit {@code imageCount} for
     * the {@code IMAGE_GENERATION} sourceType. Other sourceTypes can pass
     * {@code null}.
     */
    public Map<String, Object> consumeCredits(String userId, String sourceType, String sourceId,
                                               String provider, String model,
                                               Integer promptTokens, Integer completionTokens,
                                               Integer imageCount) {
        return consumeCredits(userId, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, imageCount, /* cacheTokens */ null);
    }

    /**
     * Full-form synchronous consume - {@code imageCount} for IMAGE_GENERATION,
     * {@code cacheTokens} for LLM sourceTypes (both nullable).
     */
    public Map<String, Object> consumeCredits(String userId, String sourceType, String sourceId,
                                               String provider, String model,
                                               Integer promptTokens, Integer completionTokens,
                                               Integer imageCount, LlmCacheTokens cacheTokens) {
        return consumeCredits(userId, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, imageCount, cacheTokens, /* keyRoute */ null);
    }

    /**
     * Full form. {@code keyRoute} is the route the execution was pinned to
     * ({@code "OWN_KEY"} = the tenant's own provider key, billed a flat fee per turn;
     * null or {@code "PLATFORM"} = token rate). The response carries {@code creditsUsed}
     * (the debit) and {@code consumptionCredits} (what the turn consumed at the platform
     * rate, what counters and budgets meter).
     */
    public Map<String, Object> consumeCredits(String userId, String sourceType, String sourceId,
                                               String provider, String model,
                                               Integer promptTokens, Integer completionTokens,
                                               Integer imageCount, LlmCacheTokens cacheTokens,
                                               String keyRoute) {
        if (!enabled) {
            return Map.of("success", true, "skipped", true, "reason", "credit consumption disabled");
        }
        if (userId == null || userId.isBlank()) {
            log.warn("Skipping credit consumption: userId is null or blank");
            return Map.of("success", false, "error", "userId is null or blank");
        }

        String url = authServiceUrl + "/api/credits/consume";

        HttpHeaders headers = userHeaders(userId, MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("sourceType", sourceType != null ? sourceType : "UNKNOWN");
        body.put("sourceId", sourceId != null ? sourceId : "");
        body.put("provider", provider != null ? provider : "unknown");
        body.put("model", model != null ? model : "unknown");
        body.put("promptTokens", promptTokens != null ? promptTokens : 0);
        body.put("completionTokens", completionTokens != null ? completionTokens : 0);
        if (imageCount != null) {
            body.put("imageCount", imageCount);
        }
        if (cacheTokens != null && cacheTokens.hasAny()) {
            if (cacheTokens.cacheCreationTokens() != null) body.put("cacheCreationTokens", cacheTokens.cacheCreationTokens());
            if (cacheTokens.cacheReadTokens() != null) body.put("cacheReadTokens", cacheTokens.cacheReadTokens());
            if (cacheTokens.cachedTokens() != null) body.put("cachedTokens", cacheTokens.cachedTokens());
            if (cacheTokens.reasoningTokens() != null) body.put("reasoningTokens", cacheTokens.reasoningTokens());
        }
        if (keyRoute != null && !keyRoute.isBlank()) {
            body.put("keyRoute", keyRoute);
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("Credit consumed for user {}: {} ({}/{})", userId, sourceType, provider, model);
                return response.getBody();
            } else {
                log.warn("Credit consumption returned {}: {}", response.getStatusCode(), response.getBody());
                return Map.of("success", false, "error", "Non-2xx response: " + response.getStatusCode());
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 402) {
                log.warn("Credit consumption rejected (402) for user {}: insufficient credits", userId);
                return Map.of("success", false, "error", "402 Insufficient credits");
            }
            String errorMsg2 = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("Failed to consume credits for user {}: {}", userId, errorMsg2);
            return Map.of("success", false, "error", errorMsg2);
        } catch (Exception e) {
            String errorMsg2 = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("Failed to consume credits for user {}: {}", userId, errorMsg2);
            return Map.of("success", false, "error", errorMsg2);
        }
    }

    /**
     * Consume a fixed credit amount (e.g. marketplace purchase).
     * Uses the same auth-service endpoint with MARKETPLACE_PURCHASE sourceType.
     */
    public Map<String, Object> consumeFixedCredits(String userId, String sourceId, int credits) {
        if (!enabled) {
            return Map.of("success", true, "skipped", true, "reason", "credit consumption disabled");
        }
        if (userId == null || userId.isBlank()) {
            log.warn("Skipping fixed credit consumption: userId is null or blank");
            return Map.of("success", false, "error", "userId is null or blank");
        }
        if (credits <= 0) {
            log.warn("Skipping fixed credit consumption: credits must be positive (got {})", credits);
            return Map.of("success", true, "skipped", true, "reason", "zero or negative credits");
        }

        String url = authServiceUrl + "/api/credits/consume";

        HttpHeaders headers = userHeaders(userId, MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("sourceType", "MARKETPLACE_PURCHASE");
        body.put("sourceId", sourceId != null ? sourceId : "");
        body.put("cost", credits);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("Fixed credits consumed for user {}: {} credits (source={})", userId, credits, sourceId);
                return response.getBody();
            } else {
                log.warn("Fixed credit consumption returned {}: {}", response.getStatusCode(), response.getBody());
                return Map.of("success", false, "error", "Non-2xx response: " + response.getStatusCode());
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 402) {
                log.warn("Fixed credit consumption rejected (402) for user {}: insufficient credits", userId);
                return Map.of("success", false, "error", "402 Insufficient credits",
                        "required", credits);
            }
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("Failed to consume fixed credits for user {}: {}", userId, errorMsg);
            return Map.of("success", false, "error", errorMsg);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("Failed to consume fixed credits for user {}: {}", userId, errorMsg);
            return Map.of("success", false, "error", errorMsg);
        }
    }

    // ========== Platform Credential Markup ==========

    /**
     * Debit platform markup for one MCP tool call. Synchronous because the
     * orchestrator needs the {@code success} flag to decide whether to continue
     * the run on budget-exhausted. Idempotent on {@code sourceId}.
     *
     * <p>{@code userId} is typed {@link Long} to match the auth endpoint binding
     * ({@code @RequestHeader("X-User-ID") Long userId}). Taking a {@code String}
     * here would let a non-numeric tenantId slip through, cause a 400 at the
     * server, and get swallowed by the fail-open catch - silently dropping
     * markup revenue. With a {@code Long}, the compiler rejects the mistake.
     *
     * <p>Returns {@code {success=false, error=...}} on 402 / transport failure -
     * never throws. Callers must treat {@code success=false} as budget-exhausted.
     */
    public Map<String, Object> consumePlatformMarkup(Long userId, String sourceId,
                                                      String apiToolName,
                                                      BigDecimal amount, String runId) {
        if (!enabled || userId == null) {
            return Map.of("success", true, "skipped", true);
        }
        String url = authServiceUrl + "/api/credits/markup/consume";
        HttpHeaders headers = userHeaders(String.valueOf(userId), MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("sourceId", sourceId);
        body.put("apiToolName", apiToolName);
        body.put("amount", amount);
        body.put("runId", runId);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            return response.getBody() != null ? response.getBody() : Map.of("success", false);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 402) {
                Map<String, Object> r = new HashMap<>();
                r.put("success", false);
                r.put("error", "402 Insufficient credits for markup");
                return r;
            }
            log.warn("Failed to consume markup for user {}, sourceId={}: {}", userId, sourceId, e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to consume markup for user {}, sourceId={}: {}", userId, sourceId, e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * Alias for {@link #fetchBalance} that reads slightly better at call sites
     * that reason about remaining budget for markup decisions.
     */
    public BigDecimal getRemainingCredits(String userId) {
        return fetchBalance(userId);
    }

    // ========== V148+ Scope Reservation Lifecycle ==========

    /**
     * Result of {@link #scopeReserve}. {@code success=false} with
     * {@code delinquent=true} signals "account delinquent - top up to resume",
     * which the catalog surfaces to the user with a different error than a
     * generic 402.
     */
    public record ScopeReserveResult(boolean success, String error, boolean delinquent,
                                      java.math.BigDecimal remainingCredits) {}

    /**
     * Pre-flight reservation. Catalog calls this BEFORE the upstream HTTP call.
     * Returns {@code success=false} on 402 (insufficient credits, delinquent,
     * no subscription) without throwing - callers branch on the result.
     */
    public ScopeReserveResult scopeReserve(Long userId, String sourceId, String provider, String model,
                                            BigDecimal projected, Long pinId, int ttlMinutes,
                                            String scopeKind, String scopeId, boolean hasExistingPin) {
        if (!enabled || userId == null) {
            return new ScopeReserveResult(true, null, false, BigDecimal.valueOf(999_999_999L));
        }
        String url = authServiceUrl + "/api/credits/markup/scope-reserve";
        HttpHeaders headers = userHeaders(String.valueOf(userId), MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("sourceId", sourceId);
        body.put("provider", provider);
        body.put("model", model);
        body.put("projected", projected);
        body.put("pinId", pinId);
        body.put("ttlMinutes", ttlMinutes);
        body.put("scopeKind", scopeKind);
        body.put("scopeId", scopeId);
        body.put("hasExistingPin", hasExistingPin);

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            return parseReserveResult(resp.getBody(), true);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 402) {
                Map<String, Object> errBody;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = e.getResponseBodyAs(Map.class);
                    errBody = parsed != null ? parsed : Map.of();
                } catch (Exception ignored) {
                    errBody = Map.of();
                }
                return parseReserveResult(errBody, false);
            }
            log.warn("scopeReserve transport failure for sourceId={}: {}", sourceId, e.getMessage());
            return new ScopeReserveResult(false, "transport: " + e.getMessage(), false, BigDecimal.ZERO);
        } catch (Exception e) {
            log.warn("scopeReserve transport failure for sourceId={}: {}", sourceId, e.getMessage());
            return new ScopeReserveResult(false, "transport: " + e.getMessage(), false, BigDecimal.ZERO);
        }
    }

    private ScopeReserveResult parseReserveResult(Map<?, ?> body, boolean success) {
        if (body == null) return new ScopeReserveResult(success, null, false, BigDecimal.ZERO);
        Object err = body.get("error");
        Object delinquent = body.get("delinquent");
        Object remaining = body.get("remainingCredits");
        BigDecimal rem = remaining instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO;
        return new ScopeReserveResult(
                success,
                err != null ? err.toString() : null,
                Boolean.TRUE.equals(delinquent),
                rem);
    }

    /**
     * Outcome of a commit that never reached a ledger, because this deployment does not meter.
     *
     * <p>Distinct from every real outcome so that "did this charge anything" is answerable from the
     * outcome alone, which is what the generation surfaces do before putting a price on screen.
     */
    public static final String BILLING_DISABLED = "BILLING_DISABLED";

    /**
     * Post-flight commit. Returns the outcome enum name (COMMITTED, ALREADY_COMMITTED,
     * RESERVATION_EXPIRED, COMMITTED_PARTIAL, COMMITTED_FLOORED), or {@link #BILLING_DISABLED} when
     * this deployment does not meter at all.
     *
     * <p>The disabled answer is deliberately NOT {@code COMMITTED}. Callers now read this outcome to
     * decide whether an amount may be REPORTED as charged - written onto a generated asset, shown
     * beside it - and "the ledger is switched off" is the one state where nothing was charged and
     * every word for success is a lie. It was safe while nobody read the return value; the moment
     * one did, an install with metering off would have started printing prices nobody paid.
     */
    public String scopeCommit(String sourceId, BigDecimal actualAmount, String provider, String model) {
        if (!enabled) return BILLING_DISABLED;
        String url = authServiceUrl + "/api/credits/markup/scope-commit";
        HttpHeaders headers = userHeaders(null, MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("sourceId", sourceId);
        body.put("actualAmount", actualAmount);
        body.put("provider", provider);
        body.put("model", model);
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            Map<?, ?> rb = resp.getBody();
            return rb != null && rb.get("outcome") != null ? rb.get("outcome").toString() : "RESERVATION_EXPIRED";
        } catch (Exception e) {
            log.warn("scopeCommit transport failure for sourceId={}: {}", sourceId, e.getMessage());
            return "RESERVATION_EXPIRED";
        }
    }

    /** Release. Returns the outcome enum name (RELEASED, ALREADY_RELEASED, ALREADY_COMMITTED). */
    public String scopeRelease(String sourceId, String reason) {
        if (!enabled) return "RELEASED";
        String url = authServiceUrl + "/api/credits/markup/scope-release";
        HttpHeaders headers = userHeaders(null, MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("sourceId", sourceId);
        body.put("reason", reason);
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            Map<?, ?> rb = resp.getBody();
            return rb != null && rb.get("outcome") != null ? rb.get("outcome").toString() : "ALREADY_RELEASED";
        } catch (Exception e) {
            log.warn("scopeRelease transport failure for sourceId={}: {}", sourceId, e.getMessage());
            return "ALREADY_RELEASED";
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Sign the identity the request ACTUALLY carries, never the one the caller
     * had in hand.
     *
     * <p>{@code GatewayAuthenticationFilter} recomputes the HMAC over the
     * {@code X-User-ID} and {@code X-Organization-ID} headers it reads off the
     * wire. So the only safe input here is those same headers, read back after
     * every writer has run. The caller's {@code userId} argument is NOT that
     * value: {@link #userHeaders} runs {@code OrgContextHeaderForwarder.forward}
     * first, which copies {@code X-User-ID} off the inbound servlet request
     * whenever the caller passed none. A method that signs a null user then
     * ships {@code X-User-ID: 1} inherited from its own caller, and the filter
     * rejects it 401 "Invalid gateway secret".
     *
     * <p>That is not hypothetical: it silently killed every
     * {@code scope-commit} / {@code scope-release} in production, because those
     * two pass {@code userId = null}. A platform-credential generation reserved
     * its markup (signed with a real user), then failed to commit it (signed
     * with an empty one), and the reserve sweeper refunded the customer for an
     * asset the provider had already delivered.
     *
     * <p>The org id was already read from the headers for exactly this reason;
     * the user id is now read the same way. The method takes NO identity
     * argument, so there is nothing left to pass that could disagree with what
     * is sent - the bug cannot be written again here.
     */
    /**
     * Stamps the three gateway headers through the shared signer. The whole ritual lives there,
     * including the blank-secret no-op and the header NAMES: a private copy of those beside a
     * delegating signer is the drift this consolidation removes.
     */
    private void applyGatewaySignature(HttpHeaders headers) {
        com.apimarketplace.common.web.InternalGatewaySigner.stamp(
                headers, INTERNAL_PROVIDER_ID, gatewaySecretKey);
    }

}
