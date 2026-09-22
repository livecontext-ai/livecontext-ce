package com.apimarketplace.catalog.service.http;

import com.apimarketplace.common.scope.GrantedScopes;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.catalog.service.exception.ApiAuthenticationException;
import com.apimarketplace.catalog.service.exception.InsufficientScopesException;
import com.apimarketplace.catalog.service.execution.FileAttachmentException;
import com.apimarketplace.catalog.service.execution.FileAttachmentResolver;
import com.apimarketplace.catalog.service.http.bodypath.BodyPathExecutor;
import com.apimarketplace.catalog.service.http.bodypath.BodyPathParser;
import com.apimarketplace.credential.client.dto.AccessTokenResult;
import com.apimarketplace.credential.client.dto.CredentialScopesDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service responsible for executing HTTP calls to external APIs.
 * Handles URL building, parameter processing, headers, credentials, and body transformation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HttpExecutionService {

    private final ApiToolParameterRepository apiToolParameterRepository;
    private final UserCredentialService userCredentialService;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final ErrorPolicyEngine errorPolicyEngine;

    /**
     * Cache of resource-scoped sub-tokens (e.g. Facebook Page tokens) resolved via the generic
     * {@code sub_resource_token} credential-resolution rule. Keyed by
     * {@code user|requirement|account|baseUrl|matchValue} - the account is part of the
     * key because two credentials of one integration can be admin on the same
     * sub-resource, and serving one account's sub-token to the other would substitute
     * the account after the selection had already been honoured,
     * short TTL. See {@link #resolveSubResourceToken}.
     */
    private final Map<String, CachedSubToken> subResourceTokenCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** Sub-token cache TTL (ms). Package-private + non-final so tests can force expiry. */
    long subTokenTtlMs = 10 * 60 * 1000L;
    /** Hard cap on the sub-token cache to bound growth (users × credentials × sub-resources). Settable for tests. */
    int subTokenCacheMax = 5_000;
    private record CachedSubToken(String token, long expiresAtMs) {}

    /**
     * Retry counter. Optional for the same reason as the strategies below, and because a missing
     * registry must never be the thing that stops a provider call.
     *
     * <p>Without it the retry is invisible: the wait happens on the serving thread, so a
     * provider-wide throttle shows up only as latency with no way to attribute it. The counter is
     * what turns "the catalogue got slow" into "this provider threw 429s for ten minutes".
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    // Typed-execution refactor (Phases 8/9/10) - strategies for binary, multipart, async.
    // Optional so tests using the legacy 6-arg constructor still compile.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.BinaryResponseHandler binaryResponseHandler;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.MultipartBodyEncoder multipartBodyEncoder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.MultipartRelatedBodyEncoder multipartRelatedBodyEncoder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.FormUrlencodedBodyEncoder formUrlencodedBodyEncoder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.RawBinaryBodyEncoder rawBinaryBodyEncoder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.GraphqlBodyEncoder graphqlBodyEncoder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.AwsSigV4Signer awsSigV4Signer;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.AsyncPollExecutor asyncPollExecutor;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.StreamingResponseHandler streamingResponseHandler;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.catalog.service.execution.FileAttachmentResolver fileAttachmentResolver;

    // Platform tenant ID for shared credentials
    private static final String PLATFORM_TENANT_ID =
            com.apimarketplace.catalog.service.credential.PlatformTenant.ID;

    /**
     * Credential-data field names whose value IS the resolved "primary" token. A field-aware
     * injection on one of these reuses the already-resolved {@code credentialValue} (which honors
     * OAuth refresh) rather than re-reading a possibly-stale value from the raw data map.
     */
    private static final java.util.Set<String> PRIMARY_TOKEN_FIELDS =
            java.util.Set.of("access_token", "api_key", "api_token", "bearer_token", "oauth_access_token");

    /**
     * Record for credential injection configuration.
     */
    /**
     * Catalog credential-injection metadata read from {@code tool_credentials.metadata}.
     *
     * @param type   {@code header} or {@code query}
     * @param key    header name (e.g. {@code Authorization}) or query param name
     * @param field  credential JSON field to read (e.g. {@code api_token}, {@code api_key})
     * @param prefix value transport prefix from the migration JSON's
     *               {@code apiKeyConfig.prefix} (e.g. {@code "Bearer "}). May be null when
     *               the API does not declare one (raw header injection like {@code X-API-Key}).
     *               When the resolved credential value already starts with this prefix
     *               (case-insensitive), {@link #prepareHeadersWithCredentials} strips it
     *               before re-applying - guards against users pasting {@code "Bearer xxx"}
     *               into a credential field that already auto-prefixes.
     */
    public record CredentialInjection(String type, String key, String field, String prefix,
                                      List<CredentialInjection> fields) {
        public CredentialInjection(String type, String key, String field, String prefix) {
            this(type, key, field, prefix, List.of());
        }
        public CredentialInjection(String type, String key, String field) {
            this(type, key, field, null, List.of());
        }
    }

    /**
     * Record for parameter metadata (parameterType and dataType).
     * inlineBody=true means the param's converted value IS the entire JSON body
     * (no field-name wrapping). At most one body param per endpoint may set it.
     * encoding="strict" forces full percent-encoding of a path parameter value
     * (':' → %3A, '/' → %2F, …) for endpoints whose {placeholder} is an opaque
     * identifier such as a full URL or URN (Search Console siteUrl/feedpath,
     * Sendbird channel_url, LinkedIn URNs). Null/absent → the conservative
     * default that keeps '/' ':' '@' literal for multi-segment values (S3 keys,
     * GitLab file_path, GCS object). See processPathParameters().
     */
    public record ParameterMetadata(String parameterType, String dataType, String bodyPath, boolean inlineBody,
                                    String encoding, String defaultValue,
                                    FileAttachmentResolver.Spec fileAttachments) {
        public ParameterMetadata(String parameterType, String dataType, String bodyPath) {
            this(parameterType, dataType, bodyPath, false, null, null, null);
        }
        public ParameterMetadata(String parameterType, String dataType, String bodyPath, boolean inlineBody) {
            this(parameterType, dataType, bodyPath, inlineBody, null, null, null);
        }
        public ParameterMetadata(String parameterType, String dataType, String bodyPath, boolean inlineBody,
                                 String encoding, String defaultValue) {
            this(parameterType, dataType, bodyPath, inlineBody, encoding, defaultValue, null);
        }
    }

    /**
     * Builds a standardized httpStatus object for all responses.
     * @param code HTTP status code
     * @param error Error message (null if no error)
     * @return Map with "code" and "error" keys
     */
    public Map<String, Object> buildHttpStatus(int code, String error) {
        Map<String, Object> httpStatus = new HashMap<>();
        httpStatus.put("code", code);
        httpStatus.put("error", error);
        return httpStatus;
    }

    /**
     * Extracts error message from response body (JSON) or falls back to default message.
     * Tries to parse common error formats: {"error": {"message": "..."}}, {"message": "..."}, {"error": "..."}
     * @param responseBody The raw response body
     * @param defaultMessage Fallback message if parsing fails
     * @return Extracted error message
     */
    public String extractErrorMessage(String responseBody, String defaultMessage) {
        if (responseBody == null || responseBody.isBlank()) {
            return defaultMessage;
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Try {"error": {"message": "..."}} format (Google APIs)
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                JsonNode messageNode = errorNode.path("message");
                if (!messageNode.isMissingNode()) {
                    return messageNode.asText();
                }
                // Try {"error": "..."} format
                if (errorNode.isTextual()) {
                    return errorNode.asText();
                }
            }

            // Try {"message": "..."} format
            JsonNode messageNode = root.path("message");
            if (!messageNode.isMissingNode()) {
                return messageNode.asText();
            }

            // Try {"detail": "..."} format
            JsonNode detailNode = root.path("detail");
            if (!detailNode.isMissingNode()) {
                return detailNode.asText();
            }

            // Return the raw body if it's short enough
            if (responseBody.length() < 500) {
                return responseBody;
            }

        } catch (Exception e) {
            log.debug("[HttpExecutionService.extractErrorMessage] Failed to parse error body: {}", e.getMessage());
        }

        return defaultMessage;
    }

    /**
     * V166: preflight check that the user's bound credential has the OAuth scopes
     * required by the tool. Runs BEFORE credential resolution and billing - when this
     * throws, no provider call is dispatched and no credit is deducted.
     *
     * <p>The method is a no-op (returns silently) in any of these cases - keeping the
     * 95% of the catalog that doesn't declare {@code requiredScopes} unchanged:
     * <ul>
     *   <li>tool's {@code requiredScopes} is null or empty</li>
     *   <li>{@code credentialName} is null or blank (platform-only path; covered downstream)</li>
     *   <li>auth-service is unreachable or returns 404 (fail-open - existing 403 path
     *       handles real scope mismatches)</li>
     *   <li>credential is not OAuth2 (api_key, bearer_token, basic_auth, custom)</li>
     * </ul>
     *
     * <p>When the credential IS OAuth2 and the granted scope set does not cover the
     * required scopes, throws {@link InsufficientScopesException}. The caller
     * ({@code ApiService.executeApiTool}) catches this exception explicitly and
     * converts it into a structured error map.
     */
    public void preflightScopeCheck(String userId, String credentialName, ApiEntity api, ApiToolEntity tool) {
        List<String> required = tool.getRequiredScopes();
        if (required == null || required.isEmpty()) {
            return;
        }
        if ("platform".equals(CredentialModeContext.getExplicitSource())) {
            return;
        }
        if (credentialName == null || credentialName.isBlank()) {
            return;
        }
        Optional<CredentialScopesDto> scopesOpt = getCredentialScopesForUserSelection(userId, credentialName);
        if (scopesOpt.isEmpty()) {
            return;
        }
        CredentialScopesDto resp = scopesOpt.get();
        if (resp.getType() == null || !"oauth2".equalsIgnoreCase(resp.getType())) {
            return;
        }
        // Re-split the STORED list rather than trusting its shape. auth-service parses the
        // provider's answer at connect time, but it only learned to split on a comma in Sep
        // 2026 and never re-parses on refresh, so every credential connected before that holds
        // one comma-joined blob and would be refused here on a grant that is entirely correct.
        // An ordinary list passes through untouched, so this repairs the old rows without a
        // migration and without a detection step. See GrantedScopes.
        Set<String> missing = GrantedScopes.missingFrom(required, resp.getScopes());
        if (!missing.isEmpty()) {
            throw new InsufficientScopesException(
                    tool.getToolNameId(),
                    api.getId(),
                    credentialName,
                    api.getPlatformCredentialName(),
                    missing);
        }
    }

    private Optional<CredentialScopesDto> getCredentialScopesForUserSelection(String userId, String credentialName) {
        if (userCredentialService == null) {
            return Optional.empty();
        }
        Long selectedCredentialId = selectedUserCredentialId(userId, credentialName);
        if (selectedCredentialId != null) {
            Optional<CredentialScopesDto> byId = userCredentialService.getCredentialScopesById(userId, selectedCredentialId);
            if (byId.isPresent()) {
                return byId;
            }
            refuseSubstitutionIfStrict(selectedCredentialId, credentialName);
            // Pinned credential deleted → fall through to the user's default
            // credential for this integration (take pinned, else default).
        }
        return userCredentialService.getCredentialScopes(userId, credentialName);
    }

    private Optional<AccessTokenResult> getAccessTokenInfoForUserSelection(String userId, String credentialName) {
        if (userCredentialService == null) {
            return Optional.empty();
        }
        Long selectedCredentialId = selectedUserCredentialId(userId, credentialName);
        if (selectedCredentialId != null) {
            Optional<AccessTokenResult> byId = userCredentialService.getAccessTokenInfoById(userId, selectedCredentialId);
            if (byId.isPresent()) {
                return byId;
            }
            refuseSubstitutionIfStrict(selectedCredentialId, credentialName);
            // Pinned credential deleted → fall back to the integration default.
        }
        return userCredentialService.getAccessTokenInfo(userId, credentialName);
    }

    private Map<String, String> getCredentialDataMapForUserSelection(String userId, String credentialName) {
        if (userCredentialService == null) {
            return Map.of();
        }
        Long selectedCredentialId = selectedUserCredentialId(userId, credentialName);
        if (selectedCredentialId != null) {
            Map<String, String> byId = userCredentialService.getCredentialDataMapById(userId, selectedCredentialId);
            if (!byId.isEmpty()) {
                return byId;
            }
            refuseSubstitutionIfStrict(selectedCredentialId, credentialName);
            // Pinned credential deleted → fall back to the integration default.
        }
        return credentialName != null ? userCredentialService.getCredentialDataMap(userId, credentialName) : Map.of();
    }

    /**
     * The pinned own-key id, but only when it is a key OF THIS ENDPOINT'S
     * PROVIDER.
     *
     * <p><b>Why the second half exists.</b> The id resolves through
     * {@code findActiveCredentialById}, which checks that the caller owns the
     * credential (or shares its organization) and nothing else. Ownership stops
     * one tenant reading another's key; it does NOT stop a caller sending their
     * OWN key for provider Q to provider P. Without this check, a pinned id is
     * a way to have any secret in the account decrypted and put in an
     * {@code Authorization} header aimed at a host the caller chooses through
     * {@code tool_id}. The picker on every surface only ever offers credentials
     * of the bound integration, so this enforces on the server what the screens
     * already promise, for callers that are not screens.
     *
     * <p>A mismatch is treated as "no pin" rather than as a failure. Every
     * caller of this already falls back to the integration's default key, which
     * is the credential the run would have used before anyone pinned anything,
     * so a stale or hostile id costs nothing and changes no working call. The
     * refusal it deserves belongs upstream, where the choice is made and can be
     * corrected.
     *
     * <p>Unresolvable ({@code credentialName} unknown, the lookup failed) is
     * also read as "no pin": the check exists to narrow what a pinned id can
     * reach, and it must never be the reason a call widens.
     */
    private Long selectedUserCredentialId(String userId, String credentialName) {
        if (!"user".equals(CredentialModeContext.getExplicitSource())) {
            // A run-time choice with no explicit user source would otherwise fall
            // through to the agentic user-then-platform fallback and run on the
            // default key, silently. This covers callers that REACH this method; the
            // request-level guarantee lives at CatalogV1Controller, which refuses a
            // strict selection without credentialSource=user before anything runs,
            // because the agentic and platform branches of tryGetCredentialResolution
            // never consult the selection at all. A future IN-PROCESS caller that sets
            // the thread-locals directly and takes one of those branches would still
            // degrade silently: it has to go through the door, or add its own check.
            if (CredentialModeContext.isSelectionStrict()) {
                throw new com.apimarketplace.catalog.service.exception.CredentialSelectionException(
                        "This step selects its credential at run time, but the request does not "
                                + "state that it runs on the caller's own credentials. The call was NOT "
                                + "made: it would have resolved a credential the step did not choose.");
            }
            return null;
        }
        Long pinned = CredentialModeContext.getSelectedCredentialId();
        String namedChoice = CredentialModeContext.getSelectedCredentialName();
        boolean strict = CredentialModeContext.isSelectionStrict();
        if (pinned == null && namedChoice == null) {
            // Strict with nothing to be strict ABOUT is a caller contradiction, and
            // ignoring it would run on the integration default while the request said
            // the account had been chosen for this run. Refused rather than dropped.
            if (strict) {
                throw new com.apimarketplace.catalog.service.exception.CredentialSelectionException(
                        "This request states that its credential was selected for this run but "
                                + "names none. The call was NOT made: it would have run on this "
                                + "account's default credential for the integration while reporting "
                                + "that a specific one had been chosen.");
            }
            return null;
        }
        if (userId == null || credentialName == null || credentialName.isBlank()
                || userCredentialService == null) {
            return refuseIfStrict(strict, describeChoice(pinned, namedChoice),
                    "this call carries no credential requirement to match it against");
        }
        // A NAME rather than an id: what an author has to hand when the choice is
        // made at run time. Resolved HERE and not by the caller, because deciding
        // whether a name belongs to this endpoint's provider needs the requirement,
        // which is catalog-side knowledge.
        if (namedChoice != null) {
            Long named = credentialIdNamed(userId, credentialName, namedChoice);
            if (named == null) {
                // Two refusals, one null. They need opposite advice, so the reason and the
                // advice travel together rather than the second being inferred back out of
                // the wording of the first.
                boolean ambiguous =
                        CredentialModeContext.namedCredentialWasAmbiguous(credentialName.trim());
                return ambiguous
                        ? refuseIfStrict(strict, describeChoice(null, namedChoice),
                                "two or more ACTIVE credentials of this integration carry that name, "
                                        + "so picking one would have picked at random and none was used",
                                Advice.RENAME_THE_DUPLICATE)
                        : refuseIfStrict(strict, describeChoice(null, namedChoice),
                                "no active credential of this integration is named that (either the "
                                        + "name does not match one, ignoring capitalisation and "
                                        + "surrounding spaces, or the credential service could not "
                                        + "be reached)",
                                Advice.FROM_THE_CHOICE);
            }
            return named;
        }
        if (pinnedCredentialBelongsTo(userId, pinned, credentialName)) {
            return pinned;
        }
        return refuseIfStrict(strict, describeChoice(pinned, null),
                "it could not be identified as a credential of this integration");
    }

    /**
     * Null for an author-time pin, an exception for a run-time choice.
     *
     * <p>Returning null here is what every caller reads as "no pin", and they all
     * go on to use the integration's default key. That is the right answer for a pin
     * written months ago. It is the wrong answer for a choice made for THIS run,
     * which is why strict callers get a refusal instead: the alternative is a call
     * that succeeds against an account nobody asked for.
     */
    private Long refuseIfStrict(boolean strict, String choice, String reason) {
        return refuseIfStrict(strict, choice, reason, Advice.FROM_THE_CHOICE);
    }

    private Long refuseIfStrict(boolean strict, String choice, String reason, Advice adviceKind) {
        if (!strict) {
            return null;
        }
        // The closing advice is about NAME matching, so it only belongs on a choice
        // that was made by name. Appended to a numeric-id refusal it sent the reader
        // to check the spelling of a name the step never used.
        String advice;
        if (adviceKind == Advice.RENAME_THE_DUPLICATE) {
            advice = " Rename one of them so the name identifies a single account, or select "
                    + "by credential id instead of by name.";
        } else if (choice != null && choice.startsWith("name ")) {
            advice = " Check that a credential with that name exists and is active for this "
                    + "integration. Capitalisation and surrounding spaces do not matter; "
                    + "nothing else about the name is ignored.";
        } else {
            advice = " Check that this credential still exists, is active, and belongs to this "
                    + "integration.";
        }
        throw new com.apimarketplace.catalog.service.exception.CredentialSelectionException(
                "This step selects its credential at run time (" + choice + ") but " + reason
                        + ". The call was NOT made: running it would have used this account's "
                        + "default credential for the integration, which is a different account from the one "
                        + "the workflow asked for." + advice);
    }

    /**
     * Which closing advice a refusal carries.
     *
     * <p>Passed rather than inferred. The first version re-derived it by testing whether
     * the reason string began with certain words, so a reword or a rewrap would silently
     * return a duplicate-name refusal to advising a spelling check: the exact misdirection
     * the split was written to remove, reappearing as a formatting accident.
     */
    private enum Advice {
        /** The name is right and two accounts answer to it, so checking spelling cannot help. */
        RENAME_THE_DUPLICATE,
        /** Derive it from the choice: a name refusal is about names, an id refusal is not. */
        FROM_THE_CHOICE
    }

    /**
     * Refuses the substitution each fall-through above would otherwise make.
     *
     * <p>{@link #refuseIfStrict} covers the half where the credential cannot be
     * IDENTIFIED. This covers the half where it was identified and then could not be
     * USED: the account exists and carries the right name, but the lookup for its
     * token, scopes or data map comes back empty because it was revoked, never
     * completed its authorisation, or holds no material of that kind.
     *
     * <p>Both halves end in the same place if left alone, the integration default
     * key, so hardening only the first would leave the feature claiming a guarantee
     * it does not have. A no-op unless a credential was actually selected AND the
     * choice was made for this run, so every author-time pin keeps its fallback.
     */
    private void refuseSubstitutionIfStrict(Long selectedCredentialId, String credentialName) {
        if (selectedCredentialId == null || !CredentialModeContext.isSelectionStrict()) {
            return;
        }
        throw new com.apimarketplace.catalog.service.exception.CredentialSelectionException(
                "This step selects its credential at run time and credential " + selectedCredentialId
                        + " was found, but no usable key could be read from it. The call was NOT made: "
                        + "running it would have used this account's default credential for the "
                        + "integration, which is a different account from the one the workflow asked "
                        + "for. Either the selected credential needs to be reconnected, or the "
                        + "credential service could not be reached just now; the call is refused "
                        + "in both cases rather than run on another account.");
    }

    private static String describeChoice(Long pinned, String namedChoice) {
        if (namedChoice != null) {
            return "name " + quotedIdentifier(namedChoice);
        }
        return "credential " + pinned;
    }

    private static String quotedIdentifier(String value) {
        return "\"" + value + "\"";
    }

    /**
     * The id of the caller's credential NAMED {@code chosenName}, among those this
     * endpoint integration would have offered.
     *
     * <p>Two refusals matter as much as the match. Nothing found means the name is
     * wrong, or the credential belongs to another provider, and guessing past that
     * is the whole failure this exists to prevent. SEVERAL found means the account
     * holds two credentials of this integration under one name: picking either
     * would be picking at random, so it refuses too, rather than being right half
     * the time.
     *
     * <p>Works off identities only ({@code CredentialIdentityDto}): id, name,
     * integration. No secret material is fetched in order to decide which
     * credential was meant, which is the same rule
     * {@link #resolvePinnedCredentialOwnership} follows for the id path.
     */
    /**
     * Whether the caller meant THIS credential by name.
     *
     * <p>Deliberately not {@link #sameCredentialIdentity}, which collapses to the
     * canonical icon slug. That normalisation is right for deciding whether two
     * PROVIDER identifiers name the same provider ({@code stability-ai} against
     * {@code stabilityai}), and wrong for a label a person typed: it deletes
     * punctuation and spacing, so {@code Client 1} and {@code Client-1} become the
     * same credential and an account holding both can never select either. It also
     * strips a trailing {@code -api}, which would silently equate {@code Shop} and
     * {@code Shop-API}.
     *
     * <p>So: trimmed and case-insensitive, and nothing else. That is what the refusal
     * message and the field help both promise, and a promise about matching has to be
     * the matcher that runs.
     */
    private static boolean sameChosenName(String chosenName, String credentialName) {
        return chosenName != null && credentialName != null
                && chosenName.trim().equalsIgnoreCase(credentialName.trim());
    }

    private Long credentialIdNamed(String userId, String credentialName, String chosenName) {
        String requirement = credentialName.trim();
        if (CredentialModeContext.hasNamedCredentialVerdict(requirement)) {
            return CredentialModeContext.namedCredentialId(requirement);
        }
        NamedVerdict verdict = resolveNamedCredential(userId, requirement, chosenName);
        CredentialModeContext.rememberNamedCredentialVerdict(
                requirement, verdict.id(), verdict.ambiguous());
        return verdict.id();
    }

    /**
     * A resolved id, or a refusal that knows WHY it refused.
     *
     * <p>"Nothing matched" and "two things matched" both refuse and both leave no id,
     * but they need opposite advice: fix the name, or stop using a name the workspace
     * gives to two accounts. Carried out of the resolver rather than recomputed, because
     * counting again would cost a second credential-service round trip on a path whose
     * whole point is to make exactly one.
     */
    private record NamedVerdict(Long id, boolean ambiguous) {
    }

    private NamedVerdict resolveNamedCredential(String userId, String requirement, String chosenName) {
        String integration = requirement.replaceAll("-credential$", "");
        java.util.List<com.apimarketplace.credential.client.dto.CredentialIdentityDto> matches =
                userCredentialService.listIdentities(userId).stream()
                        .filter(c -> c.getId() != null)
                        // ACTIVE only, because that is the set the id path
                        // resolves from (auth-side findActiveCredentialById and
                        // its by-name sibling both filter on it). Without this
                        // the two paths disagree: a revoked account would be
                        // SELECTED here and then fail to produce a key, and a
                        // revoked namesake would make an unambiguous choice look
                        // ambiguous.
                        .filter(c -> "active".equalsIgnoreCase(c.getStatus()))
                        .filter(c -> credentialIdentityMatchesRequirement(
                                integration, requirement, c.getIntegration(), c.getName()))
                        .filter(c -> sameChosenName(chosenName, c.getName()))
                        .toList();
        if (matches.size() == 1) {
            return new NamedVerdict(matches.get(0).getId(), false);
        }
        if (matches.isEmpty()) {
            log.warn("No credential named {} for a {} call.", quotedIdentifier(chosenName), integration);
            return new NamedVerdict(null, false);
        }
        log.warn("{} credentials of {} carry the name {}, so the run-time choice is ambiguous "
                + "and none was used.", matches.size(), integration, quotedIdentifier(chosenName));
        return new NamedVerdict(null, true);
    }

    /**
     * True when the pinned credential is one the endpoint's own integration
     * would have offered.
     *
     * <p>Matched the same two ways the auth side identifies a credential for an
     * endpoint: by its {@code integration}, or by its {@code name} against the
     * requirement's credential name. The {@code -credential} suffix is stripped
     * because that is how the requirement names it and how the auth side
     * derives the integration from it.
     */
    /** A credential that carries no system-set integration at all. */
    private static boolean isBlankIdentifier(String value) {
        return CredentialIdentityMatcher.isBlankIdentifier(value);
    }

    /**
     * True when two credential identifiers name the same thing.
     *
     * <p>Delegates to {@link CredentialIdentityMatcher}, which holds the rule now
     * that a second reader asks the same question without executing anything (the
     * capability answer that says which accounts could run an endpoint). Kept as a
     * method here so the call sites below read as they did.
     */
    private static boolean sameCredentialIdentity(String a, String b) {
        return CredentialIdentityMatcher.sameCredentialIdentity(a, b);
    }

    /**
     * Whether a credential's own identifiers say it belongs to the integration this
     * endpoint requires.
     *
     * <p>One matcher, used by every way of choosing a credential (by id, by name,
     * and by the capability listing), because a path that matched more loosely than
     * the id path would be a way to reach a credential the id path exists to keep
     * out. The rules it encodes are documented at its caller
     * {@link #resolvePinnedCredentialOwnership} and on
     * {@link CredentialIdentityMatcher}: {@code integration} is system-set and
     * decides whenever it is present; the label is admitted only for a credential
     * that carries no integration at all, which is how the workflow-native
     * connectors identify themselves.
     */
    private static boolean credentialIdentityMatchesRequirement(
            String integration, String requirement, String foundIntegration, String foundName) {
        return CredentialIdentityMatcher.matchesRequirement(
                integration, requirement, foundIntegration, foundName);
    }

    private boolean pinnedCredentialBelongsTo(String userId, Long credentialId, String credentialName) {
        // Asked ONCE per execution. Five helpers resolve a credential during one
        // call and every one of them lands here; without this they each pay a
        // round trip to auth-service and, worse, a blip mid-call could have some
        // of them honour the pin while others fall back, inside a single
        // request. One lookup, one verdict, for the whole call.
        Boolean alreadyDecided = CredentialModeContext.getPinVerdict(credentialName);
        if (alreadyDecided != null) {
            return alreadyDecided;
        }
        boolean verdict = resolvePinnedCredentialOwnership(userId, credentialId, credentialName);
        CredentialModeContext.rememberPinVerdict(credentialName, verdict);
        return verdict;
    }

    private boolean resolvePinnedCredentialOwnership(String userId, Long credentialId, String credentialName) {
        String requirement = credentialName.trim();
        String integration = requirement.replaceAll("-credential$", "");
        // The IDENTITY lookup, not the record. Both answer the question, but
        // the record arrives with its fields decrypted, and asking for a secret
        // in order to decide not to use it is the one shape this check must not
        // have. This one also resolves only an ACTIVE credential, which is the
        // same set the key resolution below will accept.
        Optional<CredentialScopesDto> summary =
                userCredentialService.getCredentialScopesById(userId, credentialId);
        if (summary.isEmpty()) {
            // Deliberately NOT trusted. "We could not check" has to read as "do
            // not use it", or an auth-service hiccup would reopen the very hole
            // this exists to close. It is logged at WARN because the run then
            // uses a DIFFERENT key from the one the plan names, and that
            // substitution is otherwise invisible: a deleted credential, a
            // workspace-shared one this call could not resolve, or a transient
            // failure all look identical from the outside.
            log.warn("Pinned credential {} could not be identified for a '{}' call, so it was not used. "
                            + "This run falls back to the account's default key for that integration; "
                            + "if the pinned credential still exists, check that it is reachable from "
                            + "this execution's organization context.",
                    credentialId, integration);
            return false;
        }
        CredentialScopesDto found = summary.get();
        // Compared on the CANONICAL slug, and against both of the credential's
        // identifying fields in both directions, because that is what the
        // pickers do. A credential can identify itself by integration
        // ('seedance'), by the requirement's own name ('smtp-credential'), or
        // by a spelling that only differs in its separators ('stability-ai' vs
        // 'stabilityai'). A narrower comparison here does not leak anything -
        // it silently drops a LEGITIMATE pin and runs the account's default key
        // instead, which is the same invisible substitution this check exists
        // to prevent, arrived at from the other side.
        // `integration` is set by the system and is the only identifier a
        // caller cannot choose, so when it is present it DECIDES, and a label
        // can never overrule it.
        //
        // The label is admitted in exactly one case: a credential that carries
        // no integration at all, which is how the workflow-native connectors
        // (smtp, ssh, database) identify themselves, matching the auth side's
        // own by-name resolution. Admitting it more widely is a way back into
        // the hole: requirement names for a user-registered API are derived
        // from the API's own name, and both sides collapse to the same slug, so
        // anyone able to register an API could name it after a colleague's
        // org-shared credential and have that key answer their own endpoint.
        boolean matches = credentialIdentityMatchesRequirement(
                integration, requirement, found.getIntegration(), found.getName());
        if (!matches) {
            log.warn("Ignoring pinned credential {} on a '{}' call: it belongs to '{}'. "
                            + "Falling back to the default key for this integration.",
                    credentialId, integration, found.getIntegration());
        }
        return matches;
    }

    /**
     * Executes the HTTP call to the external API (without credentials - legacy)
     */
    public Map<String, Object> executeHttpCall(ApiEntity api, ApiToolEntity tool, JsonNode parameters) {
        return executeHttpCall(api, tool, parameters, null);
    }

    /**
     * Executes the HTTP call to the external API
     * @param allowedParamNames Set of allowed parameter names (can be null to fetch from DB)
     */
    public Map<String, Object> executeHttpCall(ApiEntity api, ApiToolEntity tool, JsonNode parameters, Set<String> allowedParamNames) {
        try {
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, Parameters before filtering: {}", tool.getId(), parameters);

            // Filter parameters to keep only those defined in api_tool_parameters
            JsonNode filteredParameters = filterParametersByToolDefinition(tool, parameters, allowedParamNames);
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, Parameters after filtering: {}", tool.getId(), filteredParameters);

            String url = buildFullUrl(api, tool);
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, Base URL: {}, Endpoint: {}, Full URL before path processing: {}",
                    tool.getId(), api.getBaseUrl(), tool.getEndpoint(), url);

            url = processPathParameters(url, tool, filteredParameters);
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, URL after path parameters: {}", tool.getId(), url);

            // Dynamic-URL endpoints first (placeholder reject + host allow-list, no DNS toward
            // non-allowed hosts), then the generic SSRF validation for every URL.
            enforceDynamicUrlConstraints(tool, url);
            // SSRF protection: validate after path parameter substitution so {placeholders} don't break URI parsing
            UrlSafetyValidator.validateUrl(url);

            url = processQueryParameters(url, tool, filteredParameters);
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, Final URL: {}", tool.getId(), url);

            HttpHeaders headers = prepareHeaders(api, tool);
            applyHeaderParameters(headers, tool, filteredParameters);
            Object body = prepareRequestBody(tool, filteredParameters);
            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, Request body: {}", tool.getId(), body);

            // Check if URL still contains unexpanded variables
            if (url.contains("{") && url.contains("}")) {
                Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
                Matcher matcher = pattern.matcher(url);
                List<String> remainingVars = new ArrayList<>();
                while (matcher.find()) {
                    remainingVars.add(matcher.group(1));
                }
                log.error("[HttpExecutionService.executeHttpCall] Tool: {}, URL still contains unexpanded variables: {}",
                        tool.getId(), remainingVars);
            }

            HttpEntity<Object> request = new HttpEntity<>(body, headers);

            log.info("[HttpExecutionService.executeHttpCall] Tool: {}, About to call REST with URL: {}, Method: {}",
                    tool.getId(), url, tool.getMethod());

            final String requestUrl = url;
            ResponseEntity<Object> response = exchangeWithRetry(
                    () -> restTemplate.exchange(
                            requestUrl,
                            HttpMethod.valueOf(tool.getMethod()),
                            request,
                            Object.class),
                    requestUrl, tool, api);

            // Create mutable Map to allow adding fields later
            int statusCode = response.getStatusCode().value();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("status", statusCode);
            result.put("httpStatus", buildHttpStatus(statusCode, null));
            result.put("data", response.getBody() != null ? response.getBody() : Map.of());
            result.put("headers", response.getHeaders().toSingleValueMap());

            return result;

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // HTTP error with status code - return error info instead of throwing
            int statusCode = e.getStatusCode().value();
            String errorBody = e.getResponseBodyAsString();
            String errorMessage = extractErrorMessage(errorBody, e.getMessage());
            errorMessage = declaredErrorMessage(api, statusCode, errorBody, e.getResponseHeaders(), errorMessage);

            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("status", statusCode);
            result.put("httpStatus", buildHttpStatus(statusCode, errorMessage));
            result.put("data", Map.of());
            result.put("error", errorMessage);
            result.put("errorBody", errorBody);

            log.error("[HttpExecutionService.executeHttpCall] HTTP error: status={}, error={}", statusCode, errorMessage);
            return result;

        } catch (Exception e) {
            // Non-HTTP error (network, etc.)
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("status", 0);
            result.put("httpStatus", buildHttpStatus(0, e.getMessage()));
            result.put("data", Map.of());
            result.put("error", e.getMessage());

            log.error("[HttpExecutionService.executeHttpCall] Error: {}", e.getMessage());
            return result;
        }
    }

    /**
     * Executes the HTTP call with user credentials (OAuth support)
     * @param userId User ID for retrieving OAuth credentials
     * @param credentialName Name of the credential required by the tool
     */
    public Map<String, Object> executeHttpCallWithCredentials(ApiEntity api, ApiToolEntity tool, JsonNode parameters,
                                                               Set<String> allowedParamNames, String userId, String credentialName) {
        // Captured early and injected into success result maps so downstream
        // billing dispatchers can distinguish BYOK (user key) from platform-cost
        // passthrough. Lower-cased ("user" / "platform") for ToolExecutionResponse.metadata.credentialSource.
        String resolvedCredentialSource = null;
        try {
            log.info("[HttpExecutionService.executeHttpCallWithCredentials] Tool: {}, userId: {}, credentialName: {}",
                    tool.getId(), userId, credentialName);

            // Filter parameters
            JsonNode filteredParameters = filterParametersByToolDefinition(tool, parameters, allowedParamNames);

            // Build URL
            String url = buildFullUrl(api, tool);
            url = processPathParameters(url, tool, filteredParameters);

            // Dynamic-URL endpoints first (placeholder reject + host allow-list, no DNS toward
            // non-allowed hosts), then the generic SSRF validation for every URL.
            enforceDynamicUrlConstraints(tool, url);
            // SSRF protection: validate after path parameter substitution so {placeholders} don't break URI parsing
            UrlSafetyValidator.validateUrl(url);

            url = processQueryParameters(url, tool, filteredParameters);

            // Get credential injection metadata - V103 variant-aware so multi-variant
            // APIs (OAuth2 + PAT, …) route through the injection row matching the
            // variant of the credential the user actually configured.
            String variant = resolveCredentialVariant(userId, credentialName, api);
            CredentialInjection injection = getCredentialInjection(tool.getId(), variant);

            // Get the credential value AND its source (user vs platform) - source is
            // injected into the success result map for billing dispatch.
            Optional<CredentialResolution> credResolution = tryGetCredentialResolution(userId, credentialName, api);
            Optional<String> credentialValue = credResolution.map(CredentialResolution::value);
            if (credResolution.isPresent()) {
                resolvedCredentialSource = credResolution.get().source().name().toLowerCase();
            }
            // Field-aware: a custom credential whose secret lives under injection.field() (not a
            // primary token) resolves to an empty primary value above - read that field instead.
            credentialValue = applyFieldAwareFallback(injection, credentialValue, userId, credentialName);

            // Generic, migration-driven sub-resource token resolution (e.g. Facebook Page token):
            // swaps the base token for a resource-scoped sub-token when the tool declares the rule
            // AND the call carries the trigger param. Strict no-op for every other API/tool.
            credentialValue = resolveSubResourceToken(api, tool, filteredParameters, credentialValue, userId, credentialName);

            // Inject credential based on metadata configuration
            if (injection != null && credentialValue.isPresent()) {
                String value = credentialValue.get();

                if ("query".equalsIgnoreCase(injection.type())) {
                    // Add credential as query parameter
                    url += (url.contains("?") ? "&" : "?") + injection.key() + "=" +
                           URLEncoder.encode(value, StandardCharsets.UTF_8);
                    log.info("[HttpExecutionService.executeHttpCallWithCredentials] Injected credential as query parameter: {}", injection.key());
                } else if ("header".equalsIgnoreCase(injection.type())) {
                    // Will be handled in prepareHeadersWithCredentials (pass injection metadata)
                    log.debug("[HttpExecutionService.executeHttpCallWithCredentials] Credential will be injected as header: {}", injection.key());
                }
            }

            // Replace URL template variables ({token}, {domain}, etc.) with credential data
            if (url.contains("{") && url.contains("}")) {
                url = replaceUrlTemplateVariables(url, userId, credentialName, credentialValue.orElse(null));
            }

            log.info("[HttpExecutionService.executeHttpCallWithCredentials] Final URL: {}", url);

            // Prepare headers with OAuth credentials if available
            HttpHeaders headers = prepareHeadersWithCredentials(api, tool, userId, credentialName, injection, credentialValue);
            // Multi-field custom auth (≥2 header fields in customConfig: Algolia, Plaid, Datadog, …)
            // - apply every header from THIS row's customConfig; no-op for single/non-custom auth.
            applyCustomFieldHeaderInjections(headers, injection, userId, credentialName, credentialValue);
            applyHeaderParameters(headers, tool, filteredParameters);
            // userId is the tenant whose storage an attached file lives in - the same
            // value ApiService hands the typed path as tenantId.
            Object body = prepareRequestBody(tool, filteredParameters, userId);
            log.info("[HttpExecutionService.executeHttpCallWithCredentials] Request body: {}", body);

            // AWS SigV4: sign *.amazonaws.com requests from access_key_id/secret_access_key.
            // Mirrors the typed path (executeTyped); without it the legacy sync/JSON path sent an
            // unsigned request (a bogus Bearer/custom header from prepareHeadersWithCredentials) →
            // AWS 403. The signer signs only host + x-amz-* and sets Authorization, so it overrides
            // any stray header and is a no-op for non-AWS hosts.
            maybeSignAws(tool, url, headers, body, userId, credentialName);

            // Check for unexpanded variables
            if (url.contains("{") && url.contains("}")) {
                Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
                Matcher matcher = pattern.matcher(url);
                List<String> remainingVars = new ArrayList<>();
                while (matcher.find()) {
                    remainingVars.add(matcher.group(1));
                }
                log.error("[HttpExecutionService.executeHttpCallWithCredentials] URL still contains unexpanded variables: {}", remainingVars);
            }

            HttpEntity<Object> request = new HttpEntity<>(body, headers);

            log.info("[HttpExecutionService.executeHttpCallWithCredentials] Calling {} {}", tool.getMethod(), url);

            try {
                // Retries the call while the provider says "rejected, come back later" (429, or
                // 503 with a Retry-After), then rethrows so the catch branches below handle the
                // final outcome exactly as they always have.
                final String requestUrl = url;
                ResponseEntity<Object> response = exchangeWithRetry(
                    // URI.create prevents Spring from re-expanding {variables} as URI templates
                    () -> restTemplate.exchange(
                        java.net.URI.create(requestUrl),
                        HttpMethod.valueOf(tool.getMethod()),
                        request,
                        Object.class),
                    requestUrl, tool, api);

                int statusCode = response.getStatusCode().value();
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("status", statusCode);
                result.put("httpStatus", buildHttpStatus(statusCode, null));
                result.put("data", response.getBody() != null ? response.getBody() : Map.of());
                result.put("headers", response.getHeaders().toSingleValueMap());
                if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);

                return result;

            } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
                // 401 Unauthorized - try to refresh token and retry once
                log.warn("[HttpExecutionService.executeHttpCallWithCredentials] Got 401 Unauthorized, attempting token refresh for userId={}", userId);

                Optional<String> newToken = Optional.empty();
                boolean fellBackToPlatform = false;
                String explicitSource = com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource();

                // Refresh strategy mirrors tryGetCredentialResolution exactly:
                //   • explicitSource='user' (workflow toggle) → user-only, no
                //     platform fallback ever.
                //   • explicitSource='platform' → platform-only, skip the user
                //     refresh entirely (the initial lookup never touched the
                //     user pool, so refreshing user creds we never sent is a
                //     wasted RPC).
                //   • explicitSource=null (agentic path) → user first, platform
                //     fallback when a platform credential is configured.
                boolean tryUser = !"platform".equals(explicitSource);
                if (tryUser && userId != null && userCredentialService != null) {
                    Long selectedCredentialId = selectedUserCredentialId(userId, credentialName);
                    if (selectedCredentialId != null) {
                        newToken = userCredentialService.forceRefreshAndGetTokenById(userId, selectedCredentialId);
                    }
                    if (newToken.isEmpty() && credentialName != null) {
                        // Pinned credential gone (or had no token) → refresh the
                        // integration default. Mirrors the resolution fallback so the
                        // 401-retry path picks the same credential.
                        // Same refusal as the resolution path. An expired token
                        // answering 401 is routine on OAuth integrations, so
                        // without this the retry is the likeliest way of all to
                        // end up acting on the wrong account.
                        refuseSubstitutionIfStrict(selectedCredentialId, credentialName);
                        newToken = userCredentialService.forceRefreshAndGetToken(userId, credentialName);
                    }
                }

                if (newToken.isEmpty() && userCredentialService != null) {
                    boolean platformFallbackAllowed = !"user".equals(explicitSource);
                    String platformCredName = api.getPlatformCredentialName();
                    if (platformFallbackAllowed && platformCredName != null && !platformCredName.isBlank()) {
                        log.info("[HttpExecutionService.executeHttpCallWithCredentials] {} attempting PLATFORM credential refresh: {}",
                                "platform".equals(explicitSource) ? "Workflow toggle=platform -" : "User credential refresh failed, trying PLATFORM fallback for",
                                platformCredName);
                        newToken = userCredentialService.forceRefreshAndGetToken(PLATFORM_TENANT_ID, platformCredName);
                        if (newToken.isPresent()) {
                            fellBackToPlatform = true;
                        }
                    } else if (platformCredName != null) {
                        log.info("[HttpExecutionService.executeHttpCallWithCredentials] User credential refresh failed; PLATFORM fallback NOT allowed by explicit source='{}' (workflow toggle: user-only)", explicitSource);
                    }
                }

                if (newToken.isPresent()) {
                    log.info("[HttpExecutionService.executeHttpCallWithCredentials] Token refreshed, retrying request");

                    // Stamp the source as PLATFORM if the retry uses the
                    // platform pool - otherwise downstream billing dispatchers
                    // will see credentialSource='user' and skip the platform
                    // debit for an actually-platform-funded call.
                    if (fellBackToPlatform) {
                        resolvedCredentialSource = CredentialSource.PLATFORM.name().toLowerCase();
                    }

                    // Rebuild headers with new token
                    HttpHeaders retryHeaders = new HttpHeaders();
                    retryHeaders.add("Content-Type", "application/json");
                    retryHeaders.add("Accept", "application/json");
                    retryHeaders.add("Authorization", "Bearer " + newToken.get());
                    // Re-apply tool header params (e.g. Google Ads developer-token / login-customer-id).
                    // This retry rebuilds headers from scratch, so without this the OAuth refresh-retry
                    // (401 → refresh → retry - exactly the Google Ads case) would drop them and fail.
                    applyHeaderParameters(retryHeaders, tool, filteredParameters);

                    HttpEntity<Object> retryRequest = new HttpEntity<>(body, retryHeaders);

                    final String refreshedUrl = url;
                    ResponseEntity<Object> response;
                    try {
                        response = exchangeWithRetry(
                            () -> restTemplate.exchange(
                                refreshedUrl,
                                HttpMethod.valueOf(tool.getMethod()),
                                retryRequest,
                                Object.class),
                            refreshedUrl, tool, api);
                    } catch (org.springframework.web.client.HttpStatusCodeException afterRefresh) {
                        // This call sits INSIDE the Unauthorized catch, so anything it throws would
                        // otherwise skip the sibling catches and land in the generic Exception
                        // handler: status 0, the raw exception message, no declared message and no
                        // Retry-After. A provider that throttles right after a token refresh is
                        // routine on the OAuth integrations this feature targets.
                        int failedStatus = afterRefresh.getStatusCode().value();
                        String failedBody = afterRefresh.getResponseBodyAsString();
                        String failedMessage = declaredErrorMessage(api, failedStatus, failedBody,
                                afterRefresh.getResponseHeaders(),
                                extractErrorMessage(failedBody, afterRefresh.getMessage()));

                        Map<String, Object> failedResult = new HashMap<>();
                        failedResult.put("success", false);
                        failedResult.put("status", failedStatus);
                        failedResult.put("httpStatus", buildHttpStatus(failedStatus, failedMessage));
                        failedResult.put("data", Map.of());
                        failedResult.put("error", failedMessage);
                        failedResult.put("errorBody", failedBody);

                        log.error("[HttpExecutionService.executeHttpCallWithCredentials] "
                                + "HTTP error after token refresh: status={}, error={}",
                                failedStatus, failedMessage);
                        return failedResult;
                    }

                    int statusCode = response.getStatusCode().value();
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("status", statusCode);
                    result.put("httpStatus", buildHttpStatus(statusCode, null));
                    result.put("data", response.getBody() != null ? response.getBody() : Map.of());
                    result.put("headers", response.getHeaders().toSingleValueMap());
                    if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);

                    return result;
                }

                // Refresh failed or not possible - return error result
                String serviceName = api.getIconSlug() != null ? api.getIconSlug() : api.getApiName();
                String errorMessage = "Authentication expired for " + serviceName + ". Please reconnect your account.";
                // A provider whose 401 body says something more specific than "reconnect" (a
                // revoked scope, a suspended app) can say it here through the seed, like every
                // other error return.
                errorMessage = declaredErrorMessage(api, 401, e.getResponseBodyAsString(),
                        e.getResponseHeaders(), errorMessage);

                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("status", 401);
                result.put("httpStatus", buildHttpStatus(401, errorMessage));
                result.put("data", Map.of());
                result.put("error", errorMessage);

                log.error("[HttpExecutionService.executeHttpCallWithCredentials] Auth error: {}", errorMessage);
                return result;

            } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
                // 403 Forbidden - surface provider-specific reason when available (e.g. Google:
                // "Request had insufficient authentication scopes"). Without it, users see only a
                // generic message and cannot tell whether to reconnect, re-consent, or enable the API.
                String serviceName = api.getIconSlug() != null ? api.getIconSlug() : api.getApiName();
                String errorBody = e.getResponseBodyAsString();
                String providerReason = extractErrorMessage(errorBody, null);
                String errorMessage = providerReason != null
                        ? "Access forbidden for " + serviceName + ": " + providerReason
                        : "Access forbidden for " + serviceName + ". Check your permissions.";
                // A 403 is a common carrier of a refusal only the account owner can act on
                // (unapproved app, missing consent). When the seed names it, its wording wins over
                // the provider's raw reason.
                errorMessage = declaredErrorMessage(api, 403, errorBody, e.getResponseHeaders(), errorMessage);

                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("status", 403);
                result.put("httpStatus", buildHttpStatus(403, errorMessage));
                result.put("data", Map.of());
                result.put("error", errorMessage);
                result.put("errorBody", errorBody);

                log.error("[HttpExecutionService.executeHttpCallWithCredentials] Forbidden error: {} body={}", errorMessage, errorBody);
                return result;

            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                // Other HTTP errors (400, 404, 429, 500, etc.) - return error result. A 429 only
                // reaches here once exchangeWithRetry has given up (attempts exhausted, or the
                // provider asked to wait longer than a request thread may be held).
                int statusCode = e.getStatusCode().value();
                String errorBody = e.getResponseBodyAsString();
                String errorMessage = extractErrorMessage(errorBody, e.getMessage());
                errorMessage = declaredErrorMessage(api, statusCode, errorBody, e.getResponseHeaders(), errorMessage);

                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("status", statusCode);
                result.put("httpStatus", buildHttpStatus(statusCode, errorMessage));
                result.put("data", Map.of());
                result.put("error", errorMessage);
                result.put("errorBody", errorBody);

                log.error("[HttpExecutionService.executeHttpCallWithCredentials] HTTP error: status={}, error={}", statusCode, errorMessage);
                return result;
            }

        } catch (com.apimarketplace.catalog.service.exception.CredentialSelectionException e) {
            // Refusal, not a failure of the tool: the step named a credential for
            // this run, it could not be honoured, and the provider was never called.
            // Rethrown for the same reason InsufficientCreditsException is: swallowed
            // into a generic error envelope, the refusal reads as "the API failed",
            // which sends the reader looking at the provider instead of at the
            // account the workflow asked for.
            throw e;
        } catch (Exception e) {
            // Non-HTTP error (network, etc.)
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("status", 0);
            result.put("httpStatus", buildHttpStatus(0, e.getMessage()));
            result.put("data", Map.of());
            result.put("error", e.getMessage());

            log.error("[HttpExecutionService.executeHttpCallWithCredentials] Error: {}", e.getMessage());
            return result;
        }
    }

    /**
     * Sends the request, waiting and re-sending while {@link ErrorPolicyEngine} says the provider
     * rejected the call and asked us to come back.
     *
     * <p>Only the exchange is repeated. Everything above it (credential resolution, header and
     * body building) already happened and is reused, and a multipart body is backed by
     * {@code ByteArrayResource}, so re-sending it is byte-identical rather than a consumed stream.
     *
     * <p>When the engine stops saying RETRY, the last exception is rethrown untouched so the
     * caller's existing catch branches produce exactly the result they always did.
     *
     * <p>The send itself is a {@link java.util.function.Supplier} because the two call sites do not
     * dispatch identically: the credentialed path passes a {@code URI} so Spring cannot re-expand
     * {@code {placeholders}} in an already-substituted URL, while the legacy path still passes the
     * String form. Sharing the loop without touching that difference keeps this change to the
     * retry behaviour alone.
     */
    private <T> ResponseEntity<T> exchangeWithRetry(java.util.function.Supplier<ResponseEntity<T>> send,
                                                   String url, ApiToolEntity tool, ApiEntity api) {
        // The caller's budget can only TIGHTEN the platform's, never raise it. A node that paces
        // itself sends 0, which refuses every wait and therefore every retry: the author owns the
        // retrying, and the platform must not multiply their requests underneath them.
        //
        // The clamp is the load-bearing half. Our caller waits on ONE HTTP read window (the
        // orchestrator's is 30s), and it does not know how long we intend to sleep. Sleep past it
        // and the caller gives up while WE go on to re-send the call, succeed, store the result and
        // commit the charge: the customer is billed for a step the run reports as failed, and
        // nothing releases it because from here nothing failed. That is not hypothetical - it is
        // written up in the orchestrator's own RestTemplateConfig, which met it once with a
        // generation call. The platform's configured budget is chosen to fit inside that window, so
        // honouring a larger one would be honouring a request to break the caller.
        Long callerBudget = ProviderRetryContext.getMaxWaitMs();
        long platformBudgetMs = errorPolicyEngine.getMaxWaitMs();
        long budgetMs = callerBudget == null ? platformBudgetMs : Math.min(callerBudget, platformBudgetMs);
        boolean budgetWasCapped = callerBudget != null && callerBudget > platformBudgetMs;

        long sleptMs = 0L;
        for (int attempt = 0; ; attempt++) {
            try {
                return send.get();
            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                // The engine is asked only WHETHER this refusal is retryable and for how long the
                // provider asked to wait. The budget is applied below, on the running total, which
                // is strictly stronger than a per-wait cap and needs no change to a shared engine
                // signature: a caller can only ever tighten, so a wait the engine allows and the
                // budget does not is refused here, before anything is slept.
                ErrorPolicyEngine.Verdict verdict = errorPolicyEngine.classify(
                        e.getStatusCode().value(),
                        e.getResponseBodyAsString(),
                        e.getResponseHeaders(),
                        api.getErrorPolicy(),
                        attempt,
                        tool.getMethod());

                if (verdict.action() != ErrorPolicyEngine.Action.RETRY) {
                    throw e;
                }

                // The budget is the TOTAL wait for this call, not a per-wait cap: two allowed
                // retries of the cap each would hold the thread for twice what the cap promises,
                // and this request thread is also paying for the dispatches between the waits.
                if (sleptMs + verdict.waitMs() > budgetMs) {
                    // Logged here and nowhere else. A capped budget is an ordinary, documented,
                    // user-configured state that the UI invites, so warning merely because it was
                    // capped would emit one line per node execution - a thousand per run inside a
                    // split, none of them actionable. The moment it decides anything is the moment
                    // a retry is refused, and that is what this says.
                    log.warn("[HttpExecutionService] {} {} answered {} and asked for {}ms more, "
                                    + "over the {}ms budget already {}ms spent - not retrying{}",
                            tool.getMethod(), stripQueryString(url), e.getStatusCode().value(),
                            verdict.waitMs(), budgetMs, sleptMs,
                            budgetWasCapped
                                    ? " (the caller asked for " + callerBudget + "ms, capped at the "
                                            + "platform's " + platformBudgetMs + "ms)"
                                    : "");
                    throw e;
                }

                log.warn("[HttpExecutionService] {} {} answered {} - waiting {}ms and retrying "
                                + "(attempt {} of {})",
                        tool.getMethod(), stripQueryString(url), e.getStatusCode().value(),
                        verdict.waitMs(), attempt + 1, errorPolicyEngine.getMaxRetries());

                countRetry(api, e.getStatusCode().value());
                // Travels back to the node, which stamps it on the step output: the wait happens
                // inside one tool call, so without this a re-sent call is indistinguishable from a
                // slow one.
                ProviderRetryContext.recordRetry();

                try {
                    Thread.sleep(verdict.waitMs());
                    sleptMs += verdict.waitMs();
                } catch (InterruptedException interrupted) {
                    // A shutdown or a cancelled request must not be turned into a retry: restore
                    // the flag and let the provider's own error be the outcome.
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * The message an API's {@code errorPolicy} declares for this refusal, or {@code fallback} when
     * it declares none. Called with the attempts already spent, so a {@code retry} rule can only
     * contribute its wording here, never another wait.
     */
    private String declaredErrorMessage(ApiEntity api, int status, String body,
                                        HttpHeaders headers, String fallback) {
        ErrorPolicyEngine.Verdict verdict = errorPolicyEngine.classifyForMessage(
                status, body, headers, api.getErrorPolicy());
        return verdict.action() == ErrorPolicyEngine.Action.USER_ERROR && verdict.message() != null
                ? verdict.message()
                : fallback;
    }

    /**
     * One counter per (integration, status), so a provider-wide throttle is attributable rather
     * than showing up as unexplained latency on the whole catalogue. Tagged by icon slug because
     * that is the identifier the rest of the platform's dashboards already use.
     */
    private void countRetry(ApiEntity api, int status) {
        if (meterRegistry == null) {
            return;
        }
        String integration = api.getIconSlug() != null ? api.getIconSlug() : api.getApiName();
        meterRegistry.counter("catalog_tool_retry_total",
                "integration", integration == null ? "unknown" : integration,
                "status", String.valueOf(status)).increment();
    }

    /**
     * Drops the query string, which on several providers carries the access token itself. Scoped
     * to the retry log below: it does not undo the full-URL logging this service already does
     * elsewhere.
     */
    private static String stripQueryString(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    /**
     * Legacy single-variant lookup. Prefer {@link #getCredentialInjection(UUID, String)}
     * so the returned injection matches the variant of the credential the user
     * actually configured.
     */
    public CredentialInjection getCredentialInjection(UUID toolId) {
        return getCredentialInjection(toolId, null);
    }

    /**
     * V103 variant-aware injection lookup. Returns the {@code catalog.tool_credentials}
     * row whose {@code metadata->>'variant'} matches {@code variant}. When no row
     * matches (or {@code variant} is null - legacy callers), falls back to any
     * required injection row so single-variant APIs keep working without the
     * caller having to know the variant up-front.
     *
     * @param toolId the API tool id
     * @param variant the auth variant identifier (e.g. "oauth2", "api_key") -
     *                typically {@link #resolveCredentialVariant} of the user's
     *                credential type; may be null
     */
    public CredentialInjection getCredentialInjection(UUID toolId, String variant) {
        try {
            List<Map<String, Object>> results = Collections.emptyList();
            if (variant != null && !variant.isBlank()) {
                String sql = """
                    SELECT tc.metadata
                    FROM catalog.tool_credentials tc
                    WHERE tc.api_tool_id = ?
                      AND tc.is_required = true
                      AND tc.metadata->>'variant' = ?
                    LIMIT 1
                    """;
                results = jdbcTemplate.queryForList(sql, toolId, variant);
            }
            if (results.isEmpty()) {
                // Fallback: single-variant APIs import with variant='primary' or the
                // declared auth_type - the old LIMIT 1 still finds their one row.
                String sql = """
                    SELECT tc.metadata
                    FROM catalog.tool_credentials tc
                    WHERE tc.api_tool_id = ? AND tc.is_required = true
                    LIMIT 1
                    """;
                results = jdbcTemplate.queryForList(sql, toolId);
            }
            if (results.isEmpty()) {
                return null;
            }
            return parseInjectionMetadata(results.get(0).get("metadata"));
        } catch (Exception e) {
            log.warn("[HttpExecutionService] Error getting credential injection for tool {} (variant={}): {}",
                    toolId, variant, e.getMessage());
            return null;
        }
    }

    /** Parse one {@code tool_credentials.metadata} blob into a {@link CredentialInjection} (null if none). */
    private CredentialInjection parseInjectionMetadata(Object metadataObj) {
        if (metadataObj == null) {
            return null;
        }
        try {
            JsonNode metadata = objectMapper.readTree(metadataObj.toString());
            JsonNode injection = metadata.path("injection");
            if (injection.isMissingNode()) {
                return null;
            }
            String type = injection.path("type").asText(null);
            String key = injection.path("key").asText(null);
            String field = metadata.path("field").asText("api_key");
            // Prefix: canonical injection.prefix, else fakeAuth.apiKeyConfig.prefix (older imports);
            // null only when absent from BOTH (downstream then defaults to "Bearer ").
            // An EXPLICIT empty prefix means "send the credential raw" and must survive to the
            // header branch, which defaults a null prefix to "Bearer ". Collapsing "" to null
            // here made that intent unexpressible: ClickUp, Linear, Wix and 7 other catalog
            // files declare prefix:"" precisely because their vendor rejects a scheme, and every
            // one of them was silently sent "Bearer <token>". ClickUp proves the difference is
            // real: a raw personal token answers OAUTH_025 "Token invalid" while the same token
            // behind "Bearer " answers OAUTH_019 "Oauth token not found", i.e. the prefix routes
            // the request into the OAuth-token lookup where a personal token cannot be found.
            // So distinguish ABSENT (fall back, then default to Bearer) from DECLARED-EMPTY (honour it).
            String prefix = null;
            if (injection.has("prefix") && !injection.path("prefix").isNull()) {
                prefix = injection.path("prefix").asText("");
            } else {
                JsonNode fallback = metadata.path("fakeAuth").path("apiKeyConfig").path("prefix");
                if (!fallback.isMissingNode() && !fallback.isNull()) {
                    prefix = fallback.asText("");
                }
            }
            // Multi-field custom auth carries one entry PER field in fakeAuth.customConfig.fields[]
            // (Algolia X-Algolia-Application-Id + X-Algolia-API-Key, Plaid client-id + secret, …),
            // while the top-level injection above is only the FIRST field. Parse them all so the
            // runtime can apply every header - they all belong to this single row's ONE variant.
            List<CredentialInjection> fields = new ArrayList<>();
            JsonNode customFields = metadata.path("fakeAuth").path("customConfig").path("fields");
            if (customFields.isArray()) {
                for (JsonNode f : customFields) {
                    String fName = f.path("name").asText(null);
                    String fType = f.path("injectionType").asText(null);
                    String fKey = f.path("injectionKey").asText(fName);
                    if (fName != null && fType != null && fKey != null) {
                        fields.add(new CredentialInjection(fType, fKey, fName, null));
                    }
                }
            }
            return (type != null && key != null)
                    ? new CredentialInjection(type, key, field, prefix, fields)
                    : null;
        } catch (Exception e) {
            log.warn("[HttpExecutionService] parseInjectionMetadata failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Multi-field custom auth: apply EVERY header field declared in the resolved injection's
     * {@code customConfig.fields} - e.g. Algolia (X-Algolia-Application-Id + X-Algolia-API-Key),
     * Plaid (PLAID-CLIENT-ID + PLAID-SECRET), Datadog (DD-API-KEY + DD-APPLICATION-KEY). These all
     * come from ONE variant's single {@code tool_credentials} row (parsed alongside the top-level
     * injection, no extra query), so there is NO cross-variant leak. Gated to ≥2 header fields:
     * single-field customs and non-custom auth (oauth2/api_key/bearer/basic) carry no
     * {@code customConfig.fields}, so this is a no-op for them. A primary-token field reuses
     * {@code primaryValue} (OAuth-refresh-safe); any other field is read from the decrypted data map.
     */
    private void applyCustomFieldHeaderInjections(HttpHeaders headers, CredentialInjection injection,
                                                  String userId, String credentialName, Optional<String> primaryValue) {
        if (injection == null || injection.fields() == null) {
            return;
        }
        List<CredentialInjection> headerFields = injection.fields().stream()
                .filter(f -> "header".equalsIgnoreCase(f.type()) && f.key() != null)
                .toList();
        if (headerFields.size() < 2) {
            return;
        }
        Map<String, String> dataMap = null;
        for (CredentialInjection f : headerFields) {
            String value;
            if (f.field() != null && PRIMARY_TOKEN_FIELDS.contains(f.field()) && primaryValue.isPresent()) {
                value = primaryValue.get();
            } else {
                if (dataMap == null) {
                    dataMap = getCredentialDataMapForUserSelection(userId, credentialName);
                }
                value = f.field() != null ? dataMap.get(f.field()) : null;
            }
            if (value == null || value.isBlank()) {
                continue;
            }
            headers.set(f.key(), value.strip());
        }
    }

    /**
     * Field-aware single-value fallback. When the resolved primary credential value is empty but the
     * injection names a NON-primary field (a custom API whose single secret is keyed on e.g.
     * application_id / secret_key), read THAT field from the decrypted data map - otherwise a
     * custom-field credential resolves to an empty/Bearer header (the basic_auth class of bug). No-op
     * when the primary value is present (OAuth/api_key) or the field is a primary-token field, so it
     * costs nothing on the working paths and fires only for an otherwise-empty resolution.
     */
    private Optional<String> applyFieldAwareFallback(CredentialInjection injection, Optional<String> credentialValue,
                                                     String userId, String credentialName) {
        if (injection == null || credentialValue.isPresent() || injection.field() == null
                || PRIMARY_TOKEN_FIELDS.contains(injection.field())) {
            return credentialValue;
        }
        String fv = getCredentialDataMapForUserSelection(userId, credentialName).get(injection.field());
        return (fv != null && !fv.isBlank()) ? Optional.of(fv.strip()) : credentialValue;
    }

    /**
     * V103: resolve the variant identifier to filter {@code tool_credentials} by.
     *
     * <p>For {@code user_key}/{@code both} APIs we read the auth type off the
     * user's credential (OAuth2 → "oauth2", API_Key → "api_key", …). For
     * {@code platform_key} mode, variant is decided by admin configuration
     * (Phase 2c) - until then we return null and let the fallback LIMIT 1 pick
     * the sole enabled row.
     *
     * <p>Returns null when the user has no credential yet. Callers MUST treat
     * null as "no variant filter" so APIs with a single variant still resolve.
     */
    public String resolveCredentialVariant(String userId, String credentialName, ApiEntity api) {
        // Workflow toggle 'platform' (or callers that explicitly target the
        // platform pool) bypass the per-user variant lookup - there is no user
        // credential to project a variant from. Same semantic as the legacy
        // 'platform_key' stored mode.
        String explicitSource = com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource();
        if ("platform".equals(explicitSource)) {
            return null;
        }
        if (userId == null || userId.isBlank() || credentialName == null || credentialName.isBlank()) {
            return null;
        }
        return getAccessTokenInfoForUserSelection(userId, credentialName)
                .map(info -> info.getType())
                .map(HttpExecutionService::normalizeVariant)
                .orElse(null);
    }

    /**
     * Normalize a {@code CredentialType} enum name to the variant identifier used
     * in {@code tool_credentials.metadata->>'variant'} and {@code catalog.credentials.variant}.
     * Mapping mirrors {@code IconSlugNormalizer}-style lowercase+underscore:
     * "OAuth2" → "oauth2", "API_Key" → "api_key", "Basic_Auth" → "basic_auth",
     * "Webhook" → "webhook". Returns null when the input is blank.
     *
     * <p>Note: V103 valid variants are {@code oauth2 | api_key | basic_auth | bearer_token | custom}.
     * {@code CredentialType.Webhook} normalizes to {@code "webhook"} which is not a V103 variant,
     * so the variant-filtered query in {@link #getCredentialInjection(UUID, String)} will miss and
     * the unfiltered LIMIT 1 fallback takes over. Conversely, {@code bearer_token} and {@code custom}
     * have no {@code CredentialType} enum value - APIs using those variants exclusively will also
     * fall through. Both paths are acceptable when the API exposes a single variant.
     */
    static String normalizeVariant(String credentialType) {
        if (credentialType == null || credentialType.isBlank()) {
            return null;
        }
        return credentialType.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Filters parameters to keep only those defined in api_tool_parameters
     * @param allowedParamNames Set of allowed parameter names (can be null)
     */
    public JsonNode filterParametersByToolDefinition(ApiToolEntity tool, JsonNode parameters, Set<String> allowedParamNames) {
        if (parameters == null || !parameters.isArray()) {
            return parameters;
        }

        try {
            // If allowedParamNames is not provided, fetch from database
            if (allowedParamNames == null) {
                List<ApiToolParameterEntity> definedParameters = apiToolParameterRepository.findByApiToolId(tool.getId());
                allowedParamNames = definedParameters.stream()
                        .map(ApiToolParameterEntity::getName)
                        .collect(Collectors.toSet());
            }

            if (allowedParamNames.isEmpty()) {
                // A tool whose declared parameter set is empty takes NO input. Earlier this
                // branch forwarded every provided field as a safety net for tools whose param
                // metadata hadn't been imported yet - but that let orchestrator-injected
                // context leak into the request. A manual trigger feeds a no-param downstream
                // node the blob {"trigger": {...}}; for a GET, processQueryParameters() then
                // turned the undeclared "trigger" field into ?trigger=... which Google's API
                // rejected with 400 "Cannot bind query parameter. Field 'trigger' could not be
                // found". Sending zero params for a zero-declared-param tool is the correct
                // contract and stops the leak at the single filtering chokepoint (both query
                // and body builders consume the filtered result).
                //
                // Visibility: a genuine zero-param tool (list_sites, list_calendars-style) is
                // normally called with no provided fields → stay quiet. But if fields WERE
                // provided yet the tool declares none, we are dropping data - that is either
                // the trigger-blob leak (expected, now neutralized) OR a param-import gap
                // (a tool whose JSON declares params but whose rows failed to import, which
                // used to self-heal via forward-all). Warn in that case so an import gap is
                // visible in prod instead of silently sending an empty request.
                // parameters is guaranteed to be a non-null ArrayNode here (the !isArray()
                // guard at the top already returned), so size() is the count of provided fields.
                int providedCount = parameters.size();
                if (providedCount > 0) {
                    log.warn("Tool {} declares zero parameters but {} field(s) were provided - "
                            + "dropping them (trigger-context leak or a param-import gap)", tool.getId(), providedCount);
                }
                return objectMapper.createArrayNode();
            }

            // Filter parameters to keep only those defined
            List<JsonNode> filteredParams = new ArrayList<>();
            for (JsonNode param : parameters) {
                if (!param.fieldNames().hasNext()) continue; // skip empty {} from null values
                String paramName = param.fieldNames().next();
                if (allowedParamNames.contains(paramName)) {
                    filteredParams.add(param);
                } else {
                    log.debug("Filtering out parameter '{}' not defined in tool parameters", paramName);
                }
            }

            return objectMapper.valueToTree(filteredParams);

        } catch (Exception e) {
            // Fail closed, consistent with the zero-declared-param branch above: if we cannot
            // determine the allowed set (e.g. a transient param-metadata fetch error) we must
            // NOT forward arbitrary provided fields, or undeclared context (the {"trigger":...}
            // blob) would leak into the request exactly as in the original bug. Sending no
            // params surfaces a clean upstream failure instead of a corrupted request.
            log.warn("Error filtering parameters for tool {}; sending no params (fail-closed): {}",
                    tool.getId(), e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    /**
     * Prepare HTTP headers with credentials based on injection metadata.
     * Supports header injection with custom header names or OAuth Bearer tokens.
     * @param injection Credential injection metadata (can be null for legacy behavior)
     * @param credentialValue The credential value to inject (can be empty for legacy behavior)
     */
    public HttpHeaders prepareHeadersWithCredentials(ApiEntity api, ApiToolEntity tool, String userId, String credentialName,
                                                      CredentialInjection injection, Optional<String> credentialValue) {
        HttpHeaders headers = new HttpHeaders();

        // Default headers
        headers.add("Content-Type", "application/json");
        headers.add("Accept", "application/json");

        // Basic auth (2-field credentials like Twilio: username=Account SID, password=Auth Token).
        // The injection type is "basic_auth" → build "Authorization: Basic base64(username:password)"
        // from the full credential data map. Without this branch, basic_auth fell through to the
        // legacy Bearer fallback below, which sent "Authorization: Bearer <username>" (just the
        // Account SID) and NEVER transmitted the password → the provider rejected every call with 401.
        if (injection != null && "basic_auth".equalsIgnoreCase(injection.type())) {
            Map<String, String> dataMap = getCredentialDataMapForUserSelection(userId, credentialName);
            // Strip leading/trailing whitespace - a pasted trailing space or newline in the
            // Account SID / Auth Token would otherwise corrupt the Base64 (or split the header)
            // and produce a silent 401, mirroring the stripUserTypedPrefix defense on the
            // Bearer/header branches below.
            String username = dataMap.get("username") != null ? dataMap.get("username").strip() : null;
            String password = dataMap.get("password") != null ? dataMap.get("password").strip() : null;
            if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String encoded = Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.add("Authorization", "Basic " + encoded);
                log.info("[HttpExecutionService.prepareHeadersWithCredentials] Added Basic auth header (2-field credential)");
            } else {
                log.warn("[HttpExecutionService.prepareHeadersWithCredentials] basic_auth injection but username/password missing in credential data - sending no auth header");
            }
            return headers;
        }

        // If injection metadata exists and credential is present, use it for header injection
        if (injection != null && "header".equalsIgnoreCase(injection.type()) && credentialValue.isPresent()) {
            String headerKey = injection.key();
            String value = credentialValue.get();

            // Check if this is a Bearer token (Authorization header)
            if ("Authorization".equalsIgnoreCase(headerKey) || "Bearer".equalsIgnoreCase(headerKey)) {
                // Source-of-truth prefix from the migration JSON's apiKeyConfig.prefix
                // (e.g. "Bearer "). A null prefix means the metadata row predates the prefix
                // column, so it still defaults to "Bearer " - historical behavior of this branch.
                // An EMPTY prefix is different: it is an explicit "send the credential raw",
                // which the vendors that reject a scheme (ClickUp, Linear, Qonto, OpenPix, ...)
                // require. Treating the two the same made a whole class of API unusable while
                // its catalog file looked correct, so the emptiness check is deliberately gone.
                String prefix = injection.prefix() != null ? injection.prefix() : "Bearer ";
                value = stripUserTypedPrefix(value, prefix);
                headers.add("Authorization", prefix + value);
                log.info("[HttpExecutionService.prepareHeadersWithCredentials] Added Authorization header with prefix={}",
                        prefix);
            } else {
                // Custom header injection (e.g., X-API-Key). Some APIs declare a prefix
                // even on custom headers ("Token xxx" in X-Auth-Token); honour it here
                // too and strip the same prefix off the user-pasted value defensively.
                String prefix = injection.prefix();
                if (prefix != null && !prefix.isEmpty()) {
                    value = stripUserTypedPrefix(value, prefix);
                    headers.add(headerKey, prefix + value);
                } else {
                    headers.add(headerKey, value);
                }
                log.info("[HttpExecutionService.prepareHeadersWithCredentials] Added custom header: {}", headerKey);
            }

            return headers;
        }

        // Query-injected credentials (e.g. Google Gemini's ?key=...) are
        // ALREADY embedded in the URL by the caller. The legacy Bearer
        // fallback below would also stamp them into Authorization, which
        // makes Google reject the request as a malformed OAuth token (401).
        // Skip auth header injection entirely in that case - the URL carries
        // the credential.
        if (injection != null && "query".equalsIgnoreCase(injection.type())) {
            log.debug("[HttpExecutionService.prepareHeadersWithCredentials] Query-injected credential - no auth header added");
            return headers;
        }

        // Credentials injected into the request BODY (body_field - e.g. Authorize.Net's
        // merchantAuthentication) or the URL PATH (url_variable - e.g. Telegram's /bot{token},
        // Firebase {project_id}) must NOT also be stamped into an Authorization header. The legacy
        // Bearer fallback below would otherwise leak the credential value into a spurious
        // "Authorization: Bearer <value>" the provider doesn't expect - harmless on hosts that
        // ignore an extra header, but actively breaking on OAuth-gated hosts (e.g. Firebase). The
        // body / URL already carries the credential. (Multi-field body assembly - e.g. Authorize.Net
        // name + transactionKey - is the workflow author's responsibility by design; see the
        // importer's body_field metadata note. A future nested-body injection mode may automate it.)
        if (injection != null
                && ("body_field".equalsIgnoreCase(injection.type())
                    || "url_variable".equalsIgnoreCase(injection.type()))) {
            log.debug("[HttpExecutionService.prepareHeadersWithCredentials] {}-injected credential - no auth header added",
                    injection.type());
            return headers;
        }

        // Legacy behavior: try to get credential and add as Bearer token (for backward compatibility)
        if (credentialValue == null || credentialValue.isEmpty()) {
            credentialValue = tryGetCredentialValue(userId, credentialName, api);
        }

        if (credentialValue.isPresent()) {
            // Same defensive strip as the injection-driven branch above: a user who
            // pastes "Bearer xxx" into the credential field would otherwise produce
            // "Authorization: Bearer Bearer xxx" → 401 from the upstream provider.
            String value = stripUserTypedPrefix(credentialValue.get(), "Bearer ");
            headers.add("Authorization", "Bearer " + value);
            log.info("[HttpExecutionService.prepareHeadersWithCredentials] Added OAuth Bearer token (legacy mode)");
            return headers;
        }

        // Fallback to API-level auth (legacy behavior)
        if (api.getAuthType() != null && !"none".equals(api.getAuthType())) {
            if (api.getAuthHeaderName() != null && api.getAuthHeaderValue() != null && !api.getAuthHeaderValue().isBlank()) {
                headers.add(api.getAuthHeaderName(), encryptionService.decrypt(api.getAuthHeaderValue()));
                log.info("[HttpExecutionService.prepareHeadersWithCredentials] Using API-level auth header: {}", api.getAuthHeaderName());
            }
        }

        return headers;
    }

    /**
     * If {@code value} starts with {@code prefix} (case-insensitive, after a left-trim),
     * return the suffix with leading whitespace stripped. Otherwise return {@code value}
     * unchanged (also left-trimmed if it had stray leading whitespace).
     *
     * <p>Defensive guard for a recurring user mistake: pasting {@code "Bearer apify_..."}
     * (or {@code "Token xxx"}, etc.) into a credential form whose injection metadata
     * already auto-applies the same prefix. Without this strip, the runtime emits
     * {@code "Authorization: Bearer Bearer apify_..."} and the upstream provider returns
     * 401 - which the catalog then surfaces as the misleading "Authentication expired"
     * message because the OAuth2 refresh path can't recover a static bearer.
     */
    static String stripUserTypedPrefix(String value, String prefix) {
        if (value == null) return null;
        String trimmed = value.replaceFirst("^\\s+", "");
        if (prefix == null || prefix.isEmpty()) return trimmed;
        if (trimmed.length() >= prefix.length()
                && trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return trimmed.substring(prefix.length()).replaceFirst("^\\s+", "");
        }
        return trimmed;
    }

    /**
     * Helper method to get user credential token
     */
    public Optional<String> tryGetUserCredential(String tenantId, String credentialName) {
        if (tenantId != null && credentialName != null && userCredentialService != null) {
            Optional<String> accessToken = userCredentialService.getAccessToken(tenantId, credentialName);
            if (accessToken.isPresent()) {
                log.debug("[HttpExecutionService.tryGetUserCredential] Found token for tenant={}, credential={}", tenantId, credentialName);
                return accessToken;
            }
        }
        return Optional.empty();
    }

    /**
     * Generic, migration-driven <b>sub-resource token resolution</b>.
     *
     * <p>Some providers issue a user/app token but require a <i>resource-scoped</i> sub-token for
     * operations on a sub-resource - the canonical case is Facebook Pages: the user token must be
     * exchanged for the per-Page {@code access_token} (returned by {@code GET /me/accounts}) to
     * publish to / read insights of that Page. This is declared <b>entirely in the catalog</b> via
     * a tool's {@code runtime_metadata.credentialResolution} block (strategy
     * {@code sub_resource_token}); there is NO provider-specific code here.
     *
     * <p>Behavior - for a tool that declares the rule AND a call that carries the trigger path param
     * (e.g. {@code page_id}): look up the sub-token once (cached, short TTL) and return it in place
     * of the base token. In every other case - no rule, wrong strategy, missing trigger value, empty
     * base value, lookup miss, or any error - the <b>base value is returned unchanged</b> (the call
     * proceeds with the base token and surfaces the provider's own error). This makes the feature a
     * strict no-op for the entire rest of the catalog.
     */
    Optional<String> resolveSubResourceToken(ApiEntity api, ApiToolEntity tool, JsonNode params,
                                             Optional<String> baseValue, String userId, String credentialName) {
        String runtime = tool == null ? null : tool.getRuntimeMetadata();
        if (baseValue.isEmpty() || runtime == null || runtime.isBlank()) {
            return baseValue;
        }
        try {
            JsonNode rule = objectMapper.readTree(runtime).path("credentialResolution");
            if (!rule.isObject() || !"sub_resource_token".equals(rule.path("strategy").asText())) {
                return baseValue;
            }
            JsonNode trigger = rule.path("trigger");
            String triggerParam = trigger.path("pathParam").asText("");
            // Runtime params are an array of single-key wrapper objects ([{"page_id":"222"}, …]) -
            // the same shape every sibling reader uses; a flat object is also tolerated defensively.
            // The match value is the trigger pathParam when the call carries it, otherwise a value
            // DERIVED from another param per the rule's trigger.deriveFrom (e.g. a Facebook post id
            // "{pageId}_{postId}" → pageId) - so post-scoped tools (create_comment, list_post_comments)
            // that carry no page_id can still resolve the per-Page token.
            String matchValue = resolveTriggerMatchValue(trigger, params);
            if (matchValue.isBlank()) {
                // Tool is sub-resource-scoped but this call didn't carry (or couldn't derive) the trigger value - leave the base token.
                return baseValue;
            }
            String apiKey = api == null ? "" : String.valueOf(api.getBaseUrl());
            // Keyed by the ACCOUNT that produced the base token, not only by the
            // endpoint's requirement. Two credentials of one integration can both be
            // admin on the same sub-resource, and before run-time selection existed
            // nothing in one tenant could ask for the same matchValue under two
            // accounts within the TTL. Now it can: an agency runs this workflow for
            // account A then for account B, and B would be served the sub-token minted
            // from A's user token - a strict choice honoured at resolution and quietly
            // substituted one layer down.
            Long selectedForCache = selectedUserCredentialId(userId, credentialName);
            String accountKey = selectedForCache != null ? String.valueOf(selectedForCache) : "default";
            String cacheKey = userId + "|" + credentialName + "|" + accountKey
                    + "|" + apiKey + "|" + matchValue;
            long now = System.currentTimeMillis();
            CachedSubToken cached = subResourceTokenCache.get(cacheKey);
            if (cached != null && cached.expiresAtMs() > now) {
                return Optional.of(cached.token());
            }
            Optional<String> sub = lookupSubResourceToken(api, rule, matchValue, baseValue.get());
            if (sub.isPresent()) {
                cachePutSubToken(cacheKey, new CachedSubToken(sub.get(), now + subTokenTtlMs), now);
                log.info("[HttpExecutionService.resolveSubResourceToken] Using resource-scoped sub-token for {}={} (credential={})",
                        triggerParam, matchValue, credentialName);
                return sub;
            }
            log.warn("[HttpExecutionService.resolveSubResourceToken] No sub-token for {}={} via {} - using base token",
                    triggerParam, matchValue, rule.path("lookup").path("endpoint").asText());
            return baseValue;
        } catch (Exception e) {
            log.warn("[HttpExecutionService.resolveSubResourceToken] resolution failed ({}) - using base token", e.toString());
            return baseValue;
        }
    }

    /** Calls the rule's lookup endpoint with the base token and extracts the matching sub-token. */
    private Optional<String> lookupSubResourceToken(ApiEntity api, JsonNode rule, String matchValue, String baseToken) {
        JsonNode lookup = rule.path("lookup");
        String endpoint = lookup.path("endpoint").asText("");
        if (endpoint.isBlank() || api == null || api.getBaseUrl() == null) {
            return Optional.empty();
        }
        String itemsPath = lookup.path("itemsPath").asText("data");
        String matchField = lookup.path("matchField").asText("id");
        String tokenField = lookup.path("tokenField").asText("access_token");
        String base = api.getBaseUrl().replaceAll("/+$", "");
        String url = base + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);
        UrlSafetyValidator.validateUrl(url); // SSRF guard, mirroring the main execution path

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + baseToken);
        ResponseEntity<JsonNode> resp = restTemplate.exchange(
                java.net.URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        JsonNode body = resp.getBody();
        JsonNode items = body == null ? null : body.path(itemsPath);
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                if (matchValue.equals(item.path(matchField).asText())) {
                    String t = item.path(tokenField).asText("");
                    return t.isBlank() ? Optional.empty() : Optional.of(t);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a parameter value from the runtime params, which are an array of single-key wrapper
     * objects ({@code [{"page_id":"222"}, …]}) - the canonical shape produced by
     * {@link #filterParametersByToolDefinition} and read by every sibling (path/query/header)
     * processor. A flat object is tolerated defensively for direct callers.
     */
    static String extractParamValue(JsonNode params, String name) {
        if (params == null || name == null || name.isBlank()) return "";
        if (params.isArray()) {
            for (JsonNode p : params) {
                if (p.has(name) && !p.path(name).isNull()) return p.path(name).asText("");
            }
            return "";
        }
        return params.path(name).asText("");
    }

    /**
     * Resolves the value used to match the sub-resource (e.g. a Facebook {@code page_id}).
     *
     * <p>Primary source is {@code trigger.pathParam} read straight from the call params. When that
     * param is absent, the rule may declare a {@code trigger.deriveFrom} list of fallbacks - each
     * names a source {@code param} and an optional {@code split}/{@code index} transform. The
     * canonical case is a Facebook post id of shape {@code {pageId}_{postId}}, from which the page
     * id is the first {@code "_"}-split segment ({@code split:"_", index:0}). Post-scoped tools
     * ({@code create_comment} on {@code /{object_id}/comments}, {@code list_post_comments} on
     * {@code /{post_id}/comments}) carry no {@code page_id} but do carry that composite id, so this
     * lets them resolve the per-Page token too. Returns {@code ""} when nothing resolves - the
     * caller then keeps the base token, a strict no-op.
     */
    static String resolveTriggerMatchValue(JsonNode trigger, JsonNode params) {
        if (trigger == null) return "";
        String pathParam = trigger.path("pathParam").asText("");
        if (!pathParam.isBlank()) {
            String direct = extractParamValue(params, pathParam);
            if (!direct.isBlank()) {
                return direct;
            }
        }
        JsonNode deriveFrom = trigger.path("deriveFrom");
        if (deriveFrom.isArray()) {
            for (JsonNode d : deriveFrom) {
                String srcParam = d.path("param").asText("");
                if (srcParam.isBlank()) continue;
                String raw = extractParamValue(params, srcParam);
                if (raw.isBlank()) continue;
                String split = d.path("split").asText("");
                if (split.isBlank()) {
                    return raw;
                }
                String[] parts = raw.split(java.util.regex.Pattern.quote(split));
                int idx = d.path("index").asInt(0);
                if (idx >= 0 && idx < parts.length && !parts[idx].isBlank()) {
                    return parts[idx];
                }
            }
        }
        return "";
    }

    /** Bounded put: sweep expired entries (then best-effort drop one) before exceeding the cap. */
    private void cachePutSubToken(String key, CachedSubToken value, long now) {
        if (subResourceTokenCache.size() >= subTokenCacheMax) {
            subResourceTokenCache.values().removeIf(v -> v.expiresAtMs() <= now);
            if (subResourceTokenCache.size() >= subTokenCacheMax) {
                java.util.Iterator<String> it = subResourceTokenCache.keySet().iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
        }
        subResourceTokenCache.put(key, value);
    }

    /** Test hook: current sub-token cache size. */
    int subTokenCacheSize() {
        return subResourceTokenCache.size();
    }

    private Optional<String> tryGetSelectedUserCredential(String userId, String credentialName) {
        Long selectedCredentialId = selectedUserCredentialId(userId, credentialName);
        if (userId == null || selectedCredentialId == null || userCredentialService == null) {
            return Optional.empty();
        }
        return userCredentialService.getAccessTokenInfoById(userId, selectedCredentialId)
                .map(AccessTokenResult::getAccessToken);
    }

    /**
     * Identifies which credential pool resolved a tool call's API key -
     * propagated to {@code ToolExecutionResponse.metadata.credentialSource}
     * so downstream billing dispatchers (e.g. orchestrator's
     * {@code CatalogBillingDispatcher}) can decide between platform-cost
     * passthrough and BYOK trace.
     */
    public enum CredentialSource { USER, PLATFORM }

    /**
     * Resolved credential value + the pool it came from. Used by
     * {@link #tryGetCredentialResolution}. Callers that only need the
     * value can use {@link #tryGetCredentialValue} (back-compat wrapper).
     */
    public record CredentialResolution(String value, CredentialSource source) {}

    /**
     * Get credential value (OAuth token or API key) based on credential
     * mode AND record which credential pool answered. Mirrors the legacy
     * {@link #tryGetCredentialValue} branches but augments the result with
     * the source so billing logic can distinguish user-key from
     * platform-key calls.
     */
    public Optional<CredentialResolution> tryGetCredentialResolution(String userId, String credentialName, ApiEntity api) {
        // 1) Workflow node toggle (durci, no fallback) wins over everything.
        //    Set from ToolExecutionRequest.credentialSource via the controller.
        String explicitSource = com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource();
        if (explicitSource != null) {
            switch (explicitSource) {
                case "user": {
                    Long selectedCredentialId = selectedUserCredentialId(userId, credentialName);
                    Optional<String> v = selectedCredentialId != null
                            ? tryGetSelectedUserCredential(userId, credentialName)
                            : Optional.empty();
                    if (v.isEmpty()) {
                        // Pinned credential missing/deleted → fall back to the user's
                        // DEFAULT credential for this integration (take pinned, else
                        // default). Stays on the user pool - an explicit "user" source
                        // never leaks to platform.
                        // Identifying the credential is only half of honouring it.
                        // A name that matched an account whose token then comes
                        // back empty (revoked, never authorised, no token of this
                        // kind) lands here and would be served the DEFAULT
                        // account: the substitution strict mode exists to refuse.
                        refuseSubstitutionIfStrict(selectedCredentialId, credentialName);
                        v = tryGetUserCredential(userId, credentialName);
                    }
                    return v.map(s -> new CredentialResolution(s, CredentialSource.USER));
                }
                case "platform": {
                    String platformCredName = api.getPlatformCredentialName();
                    if (platformCredName == null) return Optional.empty();
                    Optional<String> v = tryGetUserCredential(PLATFORM_TENANT_ID, platformCredName);
                    if (v.isPresent()) {
                        log.debug("[tryGetCredentialResolution] Workflow toggle PLATFORM, using credential: {}", platformCredName);
                    }
                    return v.map(s -> new CredentialResolution(s, CredentialSource.PLATFORM));
                }
                default:
                    log.warn("[tryGetCredentialResolution] Unknown explicit source: {}, ignoring", explicitSource);
            }
        }

        // 2) Agentic path (no explicit source) → user-then-platform fallback.
        //    The legacy `credentialModeOverride` thread-local is whitelisted
        //    only to "both" (CredentialModeContext.AGENTIC_ALLOWED), which is
        //    semantically identical to the default - kept solely so legacy
        //    bodies don't trip the whitelist rejection log. Either way we
        //    apply user→platform fallback, so we don't even branch on it.
        Optional<String> userV = tryGetUserCredential(userId, credentialName);
        if (userV.isPresent()) {
            return userV.map(s -> new CredentialResolution(s, CredentialSource.USER));
        }
        String platformCred = api.getPlatformCredentialName();
        if (platformCred == null) return Optional.empty();
        Optional<String> v = tryGetUserCredential(PLATFORM_TENANT_ID, platformCred);
        if (v.isPresent()) {
            log.debug("[tryGetCredentialResolution] User credential not found, using PLATFORM fallback: {}", platformCred);
        }
        return v.map(s -> new CredentialResolution(s, CredentialSource.PLATFORM));
    }

    /**
     * Back-compat wrapper for callers that only need the credential value
     * (no source). New code should call
     * {@link #tryGetCredentialResolution} directly.
     */
    public Optional<String> tryGetCredentialValue(String userId, String credentialName, ApiEntity api) {
        return tryGetCredentialResolution(userId, credentialName, api).map(CredentialResolution::value);
    }


    /**
     * Replaces URL template variables like {token}, {domain}, {project_id} with credential data values.
     * Looks up each variable name in the full credential_data map. Falls back to common aliases,
     * then to the primary credential value if no match is found.
     */
    private String replaceUrlTemplateVariables(String url, String userId, String credentialName, String credentialValue) {
        // Load full credential data map for field-by-field matching
        Map<String, String> credentialDataMap = getCredentialDataMapForUserSelection(userId, credentialName);

        Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(url);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = resolveUrlVariable(varName, credentialDataMap, credentialValue);
            if (replacement != null) {
                log.info("[HttpExecutionService] Replacing URL variable {{{}}} from credential data", varName);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                log.warn("[HttpExecutionService] No value found for URL variable {{{}}}", varName);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves a URL template variable name to a value from credential data.
     * Tries: exact match → lowercase match → common aliases → fallback to primary credential value.
     */
    private String resolveUrlVariable(String varName, Map<String, String> credentialDataMap, String fallbackValue) {
        if (credentialDataMap.isEmpty()) return fallbackValue;

        // 1. Exact match
        if (credentialDataMap.containsKey(varName)) return credentialDataMap.get(varName);

        // 2. Case-insensitive match
        String lowerVar = varName.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, String> entry : credentialDataMap.entrySet()) {
            if (entry.getKey().toLowerCase(java.util.Locale.ROOT).equals(lowerVar)) return entry.getValue();
        }

        // 3. Common aliases for token-like variables
        List<String> tokenAliases = List.of("api_token", "api_key", "access_token", "bearer_token", "token", "key", "secret");
        if (tokenAliases.stream().anyMatch(a -> a.equalsIgnoreCase(varName))) {
            for (String alias : tokenAliases) {
                for (Map.Entry<String, String> entry : credentialDataMap.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(alias)) return entry.getValue();
                }
            }
        }

        // 4. Fallback to the primary credential value - ONLY for genuinely single-field credentials.
        // For a multi-field credential, injecting the primary value into the URL would silently send
        // a WRONG-but-non-empty secret into the path; return null so the unresolved {var} fails loudly
        // downstream (URI parse / SSRF validation) instead of making a wrong-credential call.
        return credentialDataMap.size() <= 1 ? fallbackValue : null;
    }

    /**
     * Loads parameter metadata (parameterType and dataType) for all parameters of a tool.
     * @param toolId The tool ID
     * @return Map of parameter name to ParameterMetadata
     */
    public Map<String, ParameterMetadata> loadParameterMetadata(UUID toolId) {
        Map<String, ParameterMetadata> metadata = new HashMap<>();
        try {
            List<ApiToolParameterEntity> params = apiToolParameterRepository.findByApiToolId(toolId);
            for (ApiToolParameterEntity param : params) {
                String bodyPath = extractBodyPath(param.getExtras());
                boolean inlineBody = extractInlineBody(param.getExtras());
                String encoding = extractEncoding(param.getExtras());
                FileAttachmentResolver.Spec attachments = fileAttachmentResolver == null
                        ? null
                        : fileAttachmentResolver.parseSpec(param.getExtras());
                metadata.put(param.getName(), new ParameterMetadata(
                    param.getParameterType(),
                    param.getDataType(),
                    bodyPath,
                    inlineBody,
                    encoding,
                    param.getDefaultValue(),
                    attachments
                ));
            }
            log.debug("[HttpExecutionService.loadParameterMetadata] Loaded {} parameter metadata entries for tool {}",
                metadata.size(), toolId);
        } catch (Exception e) {
            log.warn("[HttpExecutionService.loadParameterMetadata] Error loading parameter metadata for tool {}: {}",
                toolId, e.getMessage());
        }
        return metadata;
    }

    /**
     * Where a parameter's value lands in the body, read from its extras.
     *
     * <p>Delegated so the WRITE side and every reader that has to recognise the
     * same field agree on one answer, down to the trimming: a generation
     * descriptor is matched against this string, and two readers disagreeing
     * about a stray space is a dropdown attached to a field the request fills
     * under another name.
     */
    private String extractBodyPath(String extrasJson) {
        return com.apimarketplace.catalog.util.ParameterBodyPath.of(extrasJson);
    }

    private boolean extractInlineBody(String extrasJson) {
        if (extrasJson == null || extrasJson.isBlank() || "{}".equals(extrasJson)) {
            return false;
        }
        try {
            JsonNode extras = objectMapper.readTree(extrasJson);
            JsonNode inlineNode = extras.path("inlineBody");
            return !inlineNode.isMissingNode() && !inlineNode.isNull() && inlineNode.asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reads the optional per-parameter {@code encoding} directive from the param's
     * extras JSON. Currently only {@code "strict"} is meaningful (full path-segment
     * percent-encoding). Returns null when absent → conservative default applies.
     */
    private String extractEncoding(String extrasJson) {
        if (extrasJson == null || extrasJson.isBlank() || "{}".equals(extrasJson)) {
            return null;
        }
        try {
            JsonNode extras = objectMapper.readTree(extrasJson);
            JsonNode encNode = extras.path("encoding");
            return encNode.isMissingNode() || encNode.isNull() ? null : encNode.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private void setNestedValue(Map<String, Object> body, String path, Object value) {
        BodyPathExecutor.apply(body, path, value);
    }

    /**
     * Converts a value to the expected type based on dataType.
     * Handles array conversion: "value" -> ["value"], "[]" -> []
     * @param value The raw value (can be JsonNode or String)
     * @param dataType The expected data type (e.g., "array", "string", "integer")
     * @return The converted value
     */
    public Object convertToExpectedType(Object value, String dataType) {
        if (value == null) {
            return null;
        }

        // Handle JsonNode
        if (value instanceof JsonNode jsonNode) {
            value = extractJsonNodeValue(jsonNode);
        }

        // Convert to array if needed
        if ("array".equalsIgnoreCase(dataType)) {
            if (value instanceof List) {
                return value; // Already a list
            }
            if (value instanceof String strValue) {
                // Handle "[]" as empty array
                if ("[]".equals(strValue.trim())) {
                    return List.of();
                }
                // Handle "[item1, item2]" format
                if (strValue.startsWith("[") && strValue.endsWith("]")) {
                    try {
                        // Try to parse as JSON array
                        JsonNode arrayNode = objectMapper.readTree(strValue);
                        if (arrayNode.isArray()) {
                            List<Object> list = new ArrayList<>();
                            for (JsonNode item : arrayNode) {
                                list.add(extractJsonNodeValue(item));
                            }
                            return list;
                        }
                    } catch (Exception e) {
                        // Not valid JSON, treat as single value
                        log.debug("[HttpExecutionService.convertToExpectedType] Could not parse '{}' as JSON array, wrapping as single element", strValue);
                    }
                }
                // A single JSON OBJECT written as a string (a FileRef that went
                // through a core:transform, which stringifies its values). Parsed
                // here or it falls into the CSV split below and comes out as a
                // list of fragments split on the commas BETWEEN ITS OWN FIELDS -
                // never anything a provider can read, so this branch cannot cost
                // a case that works today.
                if (strValue.startsWith("{") && strValue.endsWith("}")) {
                    try {
                        JsonNode objectNode = objectMapper.readTree(strValue);
                        if (objectNode.isObject()) {
                            return List.of(extractJsonNodeValue(objectNode));
                        }
                    } catch (Exception e) {
                        log.debug("[HttpExecutionService.convertToExpectedType] Could not parse '{}' as a JSON object, falling through", strValue);
                    }
                }
                // CSV-string fallback for legacy plans (pre-2026-05-06): callers
                // who authored "Subject,From" instead of ["Subject","From"] used
                // to land here as List.of("Subject,From") - a single-element
                // list with a literal comma. Form-urlencoded body encoding then
                // emitted `?key=Subject%2CFrom` instead of repeated, and JSON
                // body emitted `{"key":["Subject,From"]}` instead of
                // `{"key":["Subject","From"]}` - rejected/misinterpreted by
                // strict APIs. Split on commas like the query-side helper
                // (extractArrayValues) for shape consistency. Single value
                // (no comma) still produces a 1-element list as before.
                //
                // Contract: callers that need a literal comma inside an element
                // (e.g. tag named "Smith, John") MUST pass a real JSON array -
                // ["Smith, John"] - which is matched by the JSON-array branch
                // above and never reaches this CSV split. This trade-off is
                // identical to and consistent with the query-side helper.
                if (strValue.contains(",")) {
                    List<Object> list = new ArrayList<>();
                    for (String part : strValue.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) list.add(trimmed);
                    }
                    if (!list.isEmpty()) return list;
                }
                // Single value -> wrap in array
                return List.of(strValue);
            }
            // Other types -> wrap in array
            return List.of(value);
        }

        // Convert to object (Map) if needed - mirror of the array branch above.
        // Object-typed body params arrive from workflows as JSON strings
        // (e.g. snippet="{\"title\":...}"). Without parsing, a json-body endpoint
        // emits {"snippet":"{...}"} (a literal string) and strict APIs reject it -
        // e.g. YouTube videos.update: "Invalid value at 'resource.snippet'
        // (type ... VideoSnippet)". The multipart_related encoder already parses
        // these; this brings the plain json-body path to parity. Fails safe:
        // non-JSON-object strings pass through unchanged.
        if ("object".equalsIgnoreCase(dataType)) {
            if (value instanceof Map) {
                return value; // already an object
            }
            if (value instanceof String strValue) {
                String trimmed = strValue.trim();
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        JsonNode objNode = objectMapper.readTree(trimmed);
                        if (objNode.isObject()) {
                            return extractJsonNodeValue(objNode);
                        }
                    } catch (Exception e) {
                        // Not valid JSON object, leave as-is
                        log.debug("[HttpExecutionService.convertToExpectedType] Could not parse '{}' as JSON object, passing through", strValue);
                    }
                }
            }
            return value; // not a JSON-object string - unchanged
        }

        // Coerce stringified scalars when the upstream API declared a typed param.
        // Workflow form inputs and SpEL-rendered expressions arrive as strings
        // even when the param spec says "integer"/"number"/"boolean" - strict
        // validators (e.g. Apify Actors, REST APIs with JSON Schema) then reject
        // with "must be integer". Apply the same strict patterns used by
        // coerceInlineBodyScalars so leading-zero IDs and '+'-prefixed phones
        // stay as strings; ambiguous values pass through untouched.
        if (value instanceof String strValue && dataType != null) {
            // Locale.ROOT defends against the Turkish-locale "i" trap:
            // "INTEGER".toLowerCase() under tr_TR → "ınteger" (dotless ı), which
            // would silently miss the switch arm. Locale.ROOT is canonical
            // ASCII-fold, safe across all JVM locales.
            String dt = dataType.toLowerCase(java.util.Locale.ROOT);
            switch (dt) {
                case "integer", "int", "long" -> {
                    if (STRICT_INT_RE.matcher(strValue).matches()) {
                        try { return Long.parseLong(strValue); } catch (NumberFormatException ignored) {}
                    }
                }
                case "number", "double", "float" -> {
                    if (STRICT_INT_RE.matcher(strValue).matches()) {
                        try { return Long.parseLong(strValue); } catch (NumberFormatException ignored) {}
                    }
                    if (STRICT_DECIMAL_RE.matcher(strValue).matches()) {
                        try { return Double.parseDouble(strValue); } catch (NumberFormatException ignored) {}
                    }
                }
                case "boolean", "bool" -> {
                    if ("true".equals(strValue)) return Boolean.TRUE;
                    if ("false".equals(strValue)) return Boolean.FALSE;
                }
                default -> { /* string/object/unknown: pass through */ }
            }
        }

        return value;
    }

    private static final Pattern STRICT_INT_RE = Pattern.compile("^-?(0|[1-9]\\d*)$");
    private static final Pattern STRICT_DECIMAL_RE = Pattern.compile("^-?(0|[1-9]\\d*)\\.\\d+$");

    /**
     * Recursively coerces stringified scalars in an inline-body value.
     *
     * <p>Workflow form inputs and SpEL-rendered expressions arrive as strings
     * even when the upstream API expects typed scalars. Without coercion,
     * Apify Actors and similar strict validators reject the request with
     * "must be integer" / "must be boolean".
     *
     * <p><b>Coercion rules (strict by design):</b>
     * <ul>
     *   <li>{@code "10"} → {@code 10} (Long); {@code "-3"} → {@code -3}; {@code "0"} → {@code 0}.</li>
     *   <li>{@code "01234"}, {@code "+33"}, {@code "1e3"} → left as string (zip codes,
     *       phone fragments, scientific notation are NOT coerced - too risky).</li>
     *   <li>{@code "3.14"} → {@code 3.14} (Double); {@code "0.5"} → {@code 0.5}.</li>
     *   <li>{@code "true"} / {@code "false"} (lowercase only) → boolean.</li>
     *   <li>Anything else → unchanged.</li>
     * </ul>
     *
     * <p>Maps and Lists are traversed recursively; keys are never coerced.
     * Already-typed values (Integer, Long, Double, Boolean) pass through.
     */
    @SuppressWarnings("unchecked")
    Object coerceInlineBodyScalars(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), coerceInlineBodyScalars(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object item : l) out.add(coerceInlineBodyScalars(item));
            return out;
        }
        if (value instanceof String s) {
            if (s.isEmpty()) return s;
            if ("true".equals(s)) return Boolean.TRUE;
            if ("false".equals(s)) return Boolean.FALSE;
            if (STRICT_INT_RE.matcher(s).matches()) {
                try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
            }
            if (STRICT_DECIMAL_RE.matcher(s).matches()) {
                try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
            }
        }
        return value;
    }

    /**
     * Extracts the Java value from a JsonNode.
     */
    private Object extractJsonNodeValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            if (node.isInt()) {
                return node.asInt();
            }
            if (node.isLong()) {
                return node.asLong();
            }
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(extractJsonNodeValue(item));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), extractJsonNodeValue(entry.getValue())));
            return map;
        }
        return node.asText();
    }

    /**
     * Builds the complete URL
     */
    public String buildFullUrl(ApiEntity api, ApiToolEntity tool) {
        String baseUrl = api.getBaseUrl();
        String endpoint = tool.getEndpoint();

        // Absolute-URL escape hatch: when an endpoint path is itself a full URL
        // (http:// or https://), use it verbatim and ignore the API baseUrl. This
        // lets a single endpoint live on a different host or path prefix than the
        // rest of the API - e.g. Google media uploads, which must target
        // https://www.googleapis.com/upload/youtube/v3/videos (the /upload/ prefix)
        // while the API baseUrl is https://www.googleapis.com/youtube/v3. Path
        // params and credential injection still apply to the returned URL.
        if (endpoint != null
                && (endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            return endpoint;
        }

        // Dynamic-URL escape hatch: when the endpoint path BEGINS with a {placeholder}, the
        // full request URL comes from a runtime parameter (a provider-issued signed URL that a
        // prior call returned) - prepending the API baseUrl would corrupt it into
        // "https://base/https://provider/...". Return the template verbatim; the placeholder is
        // substituted by processPathParameters (declare extras.encoding="verbatim" on the param
        // so its query string survives) and the result is SSRF-validated like any other URL.
        // Users: TikTok Content Posting upload_url, WhatsApp Cloud media_url.
        if (endpoint != null && endpoint.startsWith("{")) {
            return endpoint;
        }

        String fullUrl;
        if (baseUrl.endsWith("/") && endpoint.startsWith("/")) {
            fullUrl = baseUrl + endpoint.substring(1);
        } else if (!baseUrl.endsWith("/") && !endpoint.startsWith("/")) {
            fullUrl = baseUrl + "/" + endpoint;
        } else {
            fullUrl = baseUrl + endpoint;
        }

        return fullUrl;
    }

    /**
     * Security constraints for dynamic-URL endpoints - endpoints whose path template BEGINS
     * with a {placeholder} (see the dynamic-URL escape hatch in {@link #buildFullUrl}), where a
     * RUNTIME parameter chooses the request host. Three checks, all fail-closed:
     *
     * <ol>
     *   <li><b>No residual placeholders.</b> The substituted URL must be a complete literal.
     *       A value like {@code https://{api_key}.evil.com/} would pass a naive SSRF check
     *       (the validator skips DNS on templated hosts) and then have the user's credential
     *       substituted into it by {@code replaceUrlTemplateVariables} downstream - a
     *       credential-exfiltration primitive. Rejecting any remaining '{' closes that hole and
     *       doubles as a clear error when the URL parameter was simply not provided.</li>
     *   <li><b>Host allow-list.</b> The endpoint MUST declare
     *       {@code execution.request.allowedUrlHostSuffixes}; the URL's host must equal one of
     *       the suffixes or end with "." + suffix. Provider-signed URLs live on known provider
     *       hosts (TikTok {@code *.tiktokapis.com}, WhatsApp {@code lookaside.fbsbx.com}) -
     *       any other destination would receive the user's injected credential, so an endpoint
     *       without a declared allow-list is refused outright rather than left open.
     *       Checked BEFORE DNS resolution so attacker-chosen domains are never even resolved.</li>
     *   <li><b>SSRF validation</b> of the final URL (private/loopback/link-local rejection),
     *       same validator as the legacy path.</li>
     * </ol>
     *
     * <p>No-op for fixed-host endpoints (template does not start with '{'): their host is the
     * registered, trusted baseUrl and existing behaviour must not change.
     *
     * @throws IllegalArgumentException when any constraint fails (callers surface it as a
     *         failed tool result; no outbound request is made)
     */
    void enforceDynamicUrlConstraints(ApiToolEntity tool, String url) {
        String endpointTemplate = tool.getEndpoint();
        if (endpointTemplate == null || !endpointTemplate.startsWith("{")) {
            return;
        }
        if (url == null || url.contains("{")) {
            throw new IllegalArgumentException(
                "Dynamic-URL endpoint " + endpointTemplate + " requires its URL parameter to be a "
                + "complete literal URL from the provider's init/lookup response - got unresolved "
                + "placeholders: " + url);
        }
        List<String> suffixes = readAllowedUrlHostSuffixes(tool);
        if (suffixes.isEmpty()) {
            throw new IllegalArgumentException(
                "Dynamic-URL endpoint " + endpointTemplate + " declares no "
                + "execution.request.allowedUrlHostSuffixes - refusing an open-destination request");
        }
        String host;
        try {
            host = java.net.URI.create(url.trim()).getHost();
        } catch (Exception e) {
            host = null;
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Dynamic URL has no parseable host: " + url);
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        boolean allowed = suffixes.stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .anyMatch(s -> lowerHost.equals(s) || lowerHost.endsWith("." + s));
        if (!allowed) {
            throw new IllegalArgumentException(
                "Dynamic URL host '" + host + "' is not among the endpoint's allowed hosts "
                + suffixes + " - refusing the request");
        }
        UrlSafetyValidator.validateUrl(url);
    }

    /** Read execution.request.allowedUrlHostSuffixes off the tool's execution spec (empty when absent). */
    private List<String> readAllowedUrlHostSuffixes(ApiToolEntity tool) {
        JsonNode spec = parseExecutionSpec(tool.getExecutionSpec());
        JsonNode arr = spec.path("request").path("allowedUrlHostSuffixes");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String s = n.asText("").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Conservative URL encoding for path parameter values: only escape the
     * chars that would actively break URL structure ({@code ' '}, {@code '#'},
     * {@code '?'}). Everything else - including {@code '/'}, {@code ':'},
     * {@code '@'}, sub-delims, and unicode - passes through literally so
     * multi-segment tools (S3, Firebase, GitHub Contents, …) keep working.
     *
     * <p>See the comment in {@link #processPathParameters} for the rationale.
     */
    String encodePathValueConservative(String value) {
        if (value == null) return "";
        // Most ids contain no dangerous chars - fast path.
        if (value.indexOf('?') < 0 && value.indexOf('#') < 0 && value.indexOf(' ') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '?' -> sb.append("%3F");
                case '#' -> sb.append("%23");
                case ' ' -> sb.append("%20");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Strict path-segment encoding: treats the value as an OPAQUE identifier and
     * percent-encodes every reserved character, including ':' → %3A and '/' → %2F.
     * Opt-in per parameter via {@code "encoding": "strict"} in the param's JSON
     * (carried through extras → ParameterMetadata.encoding). Required by endpoints
     * whose {placeholder} is a full URL or URN, e.g. Search Console
     * {@code /sites/{siteUrl}/sitemaps/{feedpath}} with siteUrl="https://site/"
     * → "https%3A%2F%2Fsite%2F". Uses URLEncoder (same encoder as the query-param
     * path, line ~1583) then normalizes '+' → %20 since '+' is form-encoding for
     * space and would be taken literally in a path segment.
     */
    String encodePathValueStrict(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Processes path parameters in the URL.
     * SSRF validation runs AFTER path parameter substitution so that
     * template variables like {userId} don't break URI parsing.
     */
    public String processPathParameters(String url, ApiToolEntity tool, JsonNode parameters) {
        try {
            log.info("[HttpExecutionService.processPathParameters] Tool: {}, URL before processing: {}", tool.getId(), url);
            log.info("[HttpExecutionService.processPathParameters] Tool: {}, Parameters JSON: {}", tool.getId(), parameters);

            // Extract expected path parameters from URL
            Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
            Matcher matcher = pattern.matcher(url);
            Set<String> expectedPathParams = new HashSet<>();
            while (matcher.find()) {
                expectedPathParams.add(matcher.group(1));
            }
            log.info("[HttpExecutionService.processPathParameters] Tool: {}, Expected path parameters in URL: {}",
                    tool.getId(), expectedPathParams);

            if (parameters != null && parameters.isArray()) {
                Map<String, String> availableParams = new HashMap<>();
                for (JsonNode param : parameters) {
                    if (!param.fieldNames().hasNext()) continue; // skip empty {} from null values
                    String paramName = param.fieldNames().next();
                    JsonNode valueNode = param.get(paramName);
                    if (valueNode == null || valueNode.isNull()) continue; // skip explicit nulls
                    String value = valueNode.asText();
                    availableParams.put(paramName, value);
                    log.info("[HttpExecutionService.processPathParameters] Tool: {}, Available parameter: {} = {}",
                            tool.getId(), paramName, value);
                }

                log.info("[HttpExecutionService.processPathParameters] Tool: {}, Available parameters map: {}",
                        tool.getId(), availableParams);

                // Per-param metadata drives the encoding strategy below. Loaded once
                // here (same DB source as processQueryParameters) so a param can opt
                // into strict encoding via extras.encoding = "strict".
                Map<String, ParameterMetadata> paramMetadata = loadParameterMetadata(tool.getId());

                // Replace {paramName} in URL. Default = conservative URL encoding;
                // opt-in strict per param via metadata.encoding == "strict".
                //
                // CONSERVATIVE (default) encodes ONLY the chars that would actively
                // break URL structure (terminate path, start a query/fragment, or
                // trigger URI parser failure):  ' ' → %20   #  → %23   ?  → %3F
                //
                // Why not full RFC 3986 path-segment encoding by default:
                // ~50 catalog tools intentionally pass MULTI-SEGMENT values into a
                // single `{path}` placeholder - AWS S3 `/{bucket}/{key}` where
                // key="images/2025/photo.jpg", Firebase RTDB `/{path}.json`,
                // Firestore `{collection_path}`, GitHub `/repos/.../{path}` for
                // `src/main/Foo.java`, dbt artifacts, Cloudinary folders, etc.
                // Encoding '/' as %2F would silently route to a non-existent
                // resource for all of them. Same logic for ':' and '@' - keep
                // literal so URLs like /timestamps/2026:05:06 keep working.
                //
                // STRICT (opt-in) treats the value as an OPAQUE identifier and
                // encodes everything incl. ':' and '/'. Required by endpoints whose
                // {placeholder} is a full URL or URN - Search Console
                // /sites/{siteUrl}/sitemaps/{feedpath}, Sendbird {channel_url},
                // LinkedIn {entityUrn}. Without it the raw "https://site/" splits the
                // path and the upstream API 404s. Declared per-param in the JSON
                // (extras.encoding) so it is data-driven, never hard-coded per API.
                //
                // VERBATIM (opt-in) applies NO encoding at all. Required when the
                // {placeholder} IS the entire request URL (dynamic-URL endpoints whose
                // path starts with the placeholder - TikTok upload_url, WhatsApp
                // media_url): those provider-issued signed URLs carry their own query
                // string, and conservative's '?' → %3F would corrupt it. Only sensible
                // for full-URL params; the substituted result still passes
                // UrlSafetyValidator before any request is sent.
                for (String paramName : expectedPathParams) {
                    if (availableParams.containsKey(paramName)) {
                        String rawValue = availableParams.get(paramName);
                        ParameterMetadata meta = paramMetadata.get(paramName);
                        String encoding = meta != null && meta.encoding() != null
                            ? meta.encoding().toLowerCase(java.util.Locale.ROOT)
                            : "";
                        String value = switch (encoding) {
                            case "strict" -> encodePathValueStrict(rawValue);
                            case "verbatim" -> rawValue;
                            default -> encodePathValueConservative(rawValue);
                        };
                        url = url.replace("{" + paramName + "}", value);
                        log.info("[HttpExecutionService.processPathParameters] Tool: {}, Replaced {{{}}} with {} (encoding={})",
                                tool.getId(), paramName, value, encoding.isEmpty() ? "conservative" : encoding);
                    } else {
                        log.warn("[HttpExecutionService.processPathParameters] Tool: {}, Missing path parameter: {} in available params: {}",
                                tool.getId(), paramName, availableParams.keySet());
                    }
                }
            } else {
                log.warn("[HttpExecutionService.processPathParameters] Tool: {}, Parameters is null or not an array", tool.getId());
            }

            log.info("[HttpExecutionService.processPathParameters] Tool: {}, URL after processing: {}", tool.getId(), url);
        } catch (Exception e) {
            log.error("[HttpExecutionService.processPathParameters] Tool: {}, Error processing path parameters: {}",
                    tool.getId(), e.getMessage(), e);
        }

        return url;
    }

    /**
     * Extract values for a query-array parameter. Supports both input shapes:
     * <ul>
     *   <li>JSON array node - {@code ["a","b"]} → {@code ["a", "b"]}</li>
     *   <li>CSV string node - {@code "a,b"} → {@code ["a", "b"]}
     *       (legacy plans authored before 2026-05-06 still pass strings)</li>
     * </ul>
     *
     * <p>Trims whitespace per element and drops blank entries. Returns an empty
     * list for null / null-node / missing-node input so the caller can iterate
     * without an extra guard.
     */
    private List<String> extractArrayValues(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item == null || item.isNull()) continue;
                String s = item.asText();
                if (s != null && !s.isBlank()) out.add(s.trim());
            }
            return out;
        }
        // String fallback - legacy plans store "a,b" instead of ["a","b"].
        String raw = node.asText();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    /**
     * Processes query parameters.
     * Only processes parameters that have parameterType = 'query' in the database.
     * Falls back to legacy behavior (all non-path params) if no metadata is available.
     */
    public String processQueryParameters(String url, ApiToolEntity tool, JsonNode parameters) {
        try {
            if (parameters != null && parameters.isArray()) {
                List<String> queryParts = new ArrayList<>();

                // Load parameter metadata to check parameterType
                Map<String, ParameterMetadata> paramMetadata = loadParameterMetadata(tool.getId());

                // Extract path parameter names from original endpoint (for fallback)
                Set<String> pathParamNames = new HashSet<>();
                String originalEndpoint = tool.getEndpoint();
                if (originalEndpoint != null) {
                    Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
                    Matcher matcher = pattern.matcher(originalEndpoint);
                    while (matcher.find()) {
                        pathParamNames.add(matcher.group(1));
                    }
                }

                for (JsonNode param : parameters) {
                    if (!param.fieldNames().hasNext()) continue; // skip empty {} from null values
                    String paramName = param.fieldNames().next();
                    JsonNode valueNode = param.get(paramName);
                    if (valueNode == null || valueNode.isNull()) continue; // skip explicit nulls
                    ParameterMetadata meta = paramMetadata.get(paramName);

                    // Determine if this parameter should be in query string
                    boolean isQueryParam;
                    if (meta != null && meta.parameterType() != null) {
                        // Use parameterType from database
                        isQueryParam = "query".equalsIgnoreCase(meta.parameterType());
                    } else {
                        // Legacy fallback: treat as query if not a path param and method is GET
                        // For POST/PUT/PATCH, non-path params go to body by default.
                        //
                        // NOTE (latent footgun): when meta == null the param is UNDECLARED, so
                        // this fallback will happily turn any stray field into ?field=... on a
                        // GET. Today every caller pre-filters via filterParametersByToolDefinition
                        // (undeclared fields are dropped before reaching here), so the only way an
                        // undeclared field arrives is a tool with a non-empty allowed-set whose
                        // matched param row lacks parameterType - rare. This was the mechanism
                        // behind the {"trigger":...} → ?trigger= leak (closed at the filter
                        // chokepoint). If a future caller bypasses the filter, make this branch
                        // fail-closed (drop undeclared/typeless fields) rather than emitting them.
                        isQueryParam = !pathParamNames.contains(paramName) && "GET".equalsIgnoreCase(tool.getMethod());
                    }

                    if (isQueryParam) {
                        boolean isArrayParam = meta != null && "array".equalsIgnoreCase(meta.dataType());

                        if (isArrayParam) {
                            // OpenAPI 3 default for query arrays: style=form, explode=true →
                            // repeated-query (?k=v1&k=v2). Most modern APIs (Gmail
                            // metadataHeaders, Airtable fields[], AWS Tags, …) expect this.
                            // The pre-2026-05-06 code emitted a single CSV value via
                            // .asText(), which Gmail interpreted as a literal header named
                            // "Subject,From" → 0 headers returned. Same shape silently
                            // misfired for ~234 query+array params across the catalog.
                            // See extractArrayValues() for the JSON-array vs legacy-CSV
                            // input handling.
                            List<String> arrayValues = extractArrayValues(valueNode);
                            for (String v : arrayValues) {
                                queryParts.add(paramName + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8));
                            }
                            log.debug("[HttpExecutionService.processQueryParameters] Added repeated-query array param: {} ({} values)",
                                paramName, arrayValues.size());
                        } else {
                            String value = valueNode.asText();
                            queryParts.add(paramName + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
                            log.debug("[HttpExecutionService.processQueryParameters] Added query param: {}={}", paramName, value);
                        }
                    }
                }

                if (!queryParts.isEmpty()) {
                    url += (url.contains("?") ? "&" : "?") + String.join("&", queryParts);
                }
            }
        } catch (Exception e) {
            log.warn("Error processing query parameters: {}", e.getMessage());
        }

        return url;
    }

    /**
     * Headers the HTTP client owns; a caller-supplied value corrupts the request, so they are
     * dropped before sending. PUBLIC because registration refuses a declared header param whose
     * name appears here (it validates against this list UNION its own static-header skip list):
     * accepting a name that is then silently discarded here is exactly the failure this prevents.
     */
    public static final Set<String> TRANSPORT_MANAGED_HEADERS = Set.of(
        // computed/transport headers
        "content-length", "host", "connection", "transfer-encoding", "expect", "upgrade",
        // hop-by-hop headers (RFC 7230 §6.1) - never carried end-to-end, never from a tool param
        "te", "trailer", "keep-alive", "proxy-authenticate", "proxy-authorization");

    /**
     * Applies parameters declared with {@code parameterType='header'} as HTTP request headers.
     *
     * <p>Sibling of {@link #processQueryParameters} but targets headers instead of the URL.
     * Without this, a header-typed param (e.g. Google Ads {@code developer-token} /
     * {@code login-customer-id}) fell through to the query string → the provider 404s/rejects.
     *
     * <p>Values are stripped of CR/LF (RFC 7230 bans them in header values; this also kills a
     * stray trailing {@code \r} from a pasted credential) then trimmed. Uses {@code set()} so a
     * declared header param overrides any default of the same name. No-op for tools without
     * header params, so it is safe to call on every request path.
     */
    public void applyHeaderParameters(HttpHeaders headers, ApiToolEntity tool, JsonNode parameters) {
        if (parameters == null || !parameters.isArray()) return;
        try {
            Map<String, ParameterMetadata> paramMetadata = loadParameterMetadata(tool.getId());
            for (JsonNode param : parameters) {
                if (!param.fieldNames().hasNext()) continue;
                String paramName = param.fieldNames().next();
                JsonNode valueNode = param.get(paramName);
                if (valueNode == null || valueNode.isNull()) continue;
                ParameterMetadata meta = paramMetadata.get(paramName);
                if (meta == null || !"header".equalsIgnoreCase(meta.parameterType())) continue;
                // Never let a tool param override transport/computed headers - the HTTP client owns
                // these and a manual value corrupts the request (e.g. a stale Content-Length, or a
                // Host that breaks routing). Catalog JSONs declare some of these as header params
                // (Content-Length on upload APIs, etc.); skip them so a broad re-import stays safe.
                if (TRANSPORT_MANAGED_HEADERS.contains(paramName.toLowerCase(java.util.Locale.ROOT))) {
                    log.debug("[HttpExecutionService.applyHeaderParameters] Skipping transport-managed header param: {}", paramName);
                    continue;
                }
                // Strip CR/LF (header-injection guard + stray trailing \r from pasted tokens), then trim.
                String value = valueNode.asText().replaceAll("[\\r\\n]", "").trim();
                if (value.isEmpty()) continue;
                headers.set(paramName, value);
                log.debug("[HttpExecutionService.applyHeaderParameters] Set header param: {}", paramName);
            }
            // Inject declared defaults for header params the caller omitted - e.g. version-pin
            // headers (X-Api-Version, SHIPPO-API-VERSION, Ngrok-Version) imported from a JSON
            // `headers`/`requiredHeaders` block. Without this a header default never reaches the
            // wire: the loop above only sets caller-supplied params. A caller value and the
            // auth/transport headers already on `headers` win (we skip names already present);
            // unresolved `{...}` placeholder defaults are never sent.
            for (Map.Entry<String, ParameterMetadata> entry : paramMetadata.entrySet()) {
                ParameterMetadata meta = entry.getValue();
                if (meta == null || !"header".equalsIgnoreCase(meta.parameterType())) {
                    continue;
                }
                String headerName = entry.getKey();
                if (headerName == null || headerName.isBlank() || headers.containsKey(headerName)) {
                    continue;
                }
                if (TRANSPORT_MANAGED_HEADERS.contains(headerName.toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                String def = meta.defaultValue();
                if (def == null) {
                    continue;
                }
                def = def.replaceAll("[\\r\\n]", "").trim();
                if (def.isEmpty() || def.indexOf('{') >= 0 || def.indexOf('}') >= 0) {
                    continue;
                }
                headers.set(headerName, def);
                log.debug("[HttpExecutionService.applyHeaderParameters] Injected header default: {}", headerName);
            }
        } catch (Exception e) {
            log.warn("Error applying header parameters: {}", e.getMessage());
        }
    }

    /**
     * Prepares HTTP headers
     */
    public HttpHeaders prepareHeaders(ApiEntity api, ApiToolEntity tool) {
        HttpHeaders headers = new HttpHeaders();

        // API authentication headers
        if (api.getAuthType() != null && !"none".equals(api.getAuthType())) {
            if (api.getAuthHeaderName() != null && api.getAuthHeaderValue() != null) {
                headers.add(api.getAuthHeaderName(), encryptionService.decrypt(api.getAuthHeaderValue()));
            }
        }

        // Default headers
        headers.add("Content-Type", "application/json");
        headers.add("Accept", "application/json");

        return headers;
    }

    /**
     * Prepares request body for POST/PUT/PATCH.
     * Only includes parameters that have parameterType = 'body' (or null for legacy compatibility).
     * Converts values to their expected types (e.g., array conversion).
     */
    public Object prepareRequestBody(ApiToolEntity tool, JsonNode parameters) {
        return prepareRequestBody(tool, parameters, null);
    }

    /**
     * Same, for a call that knows whose storage its attachments live in.
     *
     * <p>{@code tenantId} is only read to fetch the bytes of a parameter that
     * declares {@code fileAttachments}. Every other parameter ignores it, so the
     * two-argument overload above stays correct for every call that has no file
     * to send.
     */
    public Object prepareRequestBody(ApiToolEntity tool, JsonNode parameters, String tenantId) {
        // GET has no body. DELETE does: 60 catalog endpoints declare body params on DELETE
        // because their vendor requires one (Spotify playlist track removal, Auth0 role
        // removal, Cloudflare bulk key delete, Quickbase record delete, Segment user delete,
        // Coda row delete, Weaviate batch delete, ...). Lumping DELETE in with GET discarded
        // those params before the request was assembled, so the calls went out bodyless: no
        // error, just a delete that removed nothing or a 400 the agent could not diagnose.
        // The transport was never the obstacle. DeleteBodyProbeTest sends a real DELETE with a
        // body through the configured factory and the server records it intact, so this early
        // return was the whole cause.
        if ("GET".equals(tool.getMethod())) {
            return null;
        }

        try {
            if (parameters != null && parameters.isArray()) {
                Map<String, Object> body = new HashMap<>();

                // Load parameter metadata to check parameterType and dataType
                Map<String, ParameterMetadata> paramMetadata = loadParameterMetadata(tool.getId());

                // Extract path parameter names from endpoint (for fallback)
                Set<String> pathParamNames = new HashSet<>();
                String originalEndpoint = tool.getEndpoint();
                if (originalEndpoint != null) {
                    Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
                    Matcher matcher = pattern.matcher(originalEndpoint);
                    while (matcher.find()) {
                        pathParamNames.add(matcher.group(1));
                    }
                }

                // First pass: detect inlineBody passthrough (at most one per endpoint).
                // When set, the param's converted value IS the entire JSON body - no wrapping.
                for (JsonNode param : parameters) {
                    if (!param.fieldNames().hasNext()) continue; // skip empty {} from null values
                    String paramName = param.fieldNames().next();
                    ParameterMetadata meta = paramMetadata.get(paramName);
                    if (meta == null || !meta.inlineBody()) continue;
                    if (!"body".equalsIgnoreCase(meta.parameterType())) continue;

                    Object rawValue = param.get(paramName);
                    Object convertedValue = convertToExpectedType(rawValue, meta.dataType());
                    // Workflow form inputs and SpEL-rendered expressions arrive as strings even
                    // when the upstream API expects typed scalars (Apify Actors validate strictly:
                    // "Field input.maxItems must be integer"). Walk the inline-body tree once and
                    // coerce stringified ints/numbers/booleans to their JSON-typed forms. Strict
                    // patterns only - anything ambiguous (leading zeros, '+' prefix, decimals
                    // without digits) is left as a string to avoid corrupting IDs/zip codes/phones.
                    convertedValue = coerceInlineBodyScalars(convertedValue);
                    log.debug("[HttpExecutionService.prepareRequestBody] Inline body passthrough via param '{}'", paramName);

                    String bodyTransformInline = getBodyTransformType(tool);
                    if (bodyTransformInline != null && convertedValue instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> asMap = (Map<String, Object>) convertedValue;
                        return applyBodyTransform(asMap, bodyTransformInline);
                    }
                    return convertedValue;
                }

                for (JsonNode param : parameters) {
                    if (!param.fieldNames().hasNext()) continue; // skip empty {} from null values
                    String paramName = param.fieldNames().next();
                    ParameterMetadata meta = paramMetadata.get(paramName);

                    // Determine if this parameter should be in body
                    boolean isBodyParam;
                    if (meta != null && meta.parameterType() != null) {
                        // Use parameterType from database
                        isBodyParam = "body".equalsIgnoreCase(meta.parameterType());
                    } else {
                        // Legacy fallback: include if not a path param (for POST/PUT/PATCH)
                        isBodyParam = !pathParamNames.contains(paramName);
                    }

                    if (isBodyParam) {
                        Object rawValue = param.get(paramName);
                        String dataType = (meta != null) ? meta.dataType() : null;

                        // Convert to expected type (handles array conversion)
                        Object convertedValue = convertToExpectedType(rawValue, dataType);

                        // A parameter that carries FILES holds the platform's own
                        // file handles, which no provider understands. Rewrite them
                        // into the objects this endpoint declared before they reach
                        // the body.
                        convertedValue = resolveFileAttachments(paramName, convertedValue, meta, tenantId);

                        // Use bodyPath for nested placement / array indexing / array-mapping
                        // (e.g., "properties.title", "requests[0].addSheet.x",
                        //  "message.toRecipients[].emailAddress.address").
                        String bodyPath = (meta != null) ? meta.bodyPath() : null;
                        if (BodyPathParser.isStructuredPath(bodyPath)) {
                            setNestedValue(body, bodyPath, convertedValue);
                            log.debug("[HttpExecutionService.prepareRequestBody] Added structured body param: {} -> {}={}", paramName, bodyPath, convertedValue);
                        } else {
                            body.put(bodyPath != null ? bodyPath : paramName, convertedValue);
                            log.debug("[HttpExecutionService.prepareRequestBody] Added body param: {}={} (dataType={}, converted={})",
                                paramName, rawValue, dataType, convertedValue);
                        }
                    }
                }

                // Check if body transformation is required (e.g., RFC 2822 for Gmail)
                String bodyTransform = getBodyTransformType(tool);
                if (bodyTransform != null) {
                    return applyBodyTransform(body, bodyTransform);
                }

                return body.isEmpty() ? null : body;
            }
        } catch (FileAttachmentException e) {
            // Must escape this catch. Swallowed, it becomes a null body: the mail
            // sends, the file is missing, and the run is green. See the exception.
            throw e;
        } catch (Exception e) {
            log.warn("Error processing body: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Rewrite a parameter's FileRefs into the attachment objects this endpoint
     * declared, or return the value untouched when it declares none.
     *
     * <p>Every parameter but an attachment one takes the first return.
     */
    private Object resolveFileAttachments(String paramName, Object value, ParameterMetadata meta, String tenantId) {
        if (value == null || meta == null || meta.fileAttachments() == null || fileAttachmentResolver == null) {
            return value;
        }
        return fileAttachmentResolver.resolve(paramName, value, meta.fileAttachments(), tenantId);
    }

    /**
     * Get body transform type from tool's runtime_metadata.
     * Returns null if no transformation is needed.
     */
    public String getBodyTransformType(ApiToolEntity tool) {
        String runtimeMetadata = tool.getRuntimeMetadata();
        if (runtimeMetadata == null || runtimeMetadata.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(runtimeMetadata);
            String transform = node.path("bodyTransform").asText(null);
            return (transform != null && !transform.isBlank()) ? transform : null;
        } catch (Exception e) {
            log.debug("Failed to parse runtime_metadata for bodyTransform: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Body transformations that require imperative Java code because they cannot be
     * expressed declaratively via per-param bodyPath. Adding a value here REQUIRES:
     *   1. A corresponding case in {@link #applyBodyTransform(Map, String)} below.
     *   2. A justification comment of why the transform cannot be a bodyPath.
     *   3. An entry in scripts/api-migrations/validate_apis.py BODY_TRANSFORM_ALLOW_LIST.
     * Symmetric guards: BodyTransformImplementationGuardTest pins JSON->Java; BodyTransformJavaOrphanGuardTest pins Java->JSON.
     */
    public static final java.util.Set<String> IMPLEMENTED_BODY_TRANSFORMS = java.util.Set.of(
            "rfc2822",
            "rfc2822_draft"
    );

    public Object applyBodyTransform(Map<String, Object> body, String transformType) {
        return switch (transformType) {
            case "rfc2822" -> buildRfc2822Body(body);
            case "rfc2822_draft" -> buildRfc2822DraftBody(body);
            default -> {
                log.warn("Unknown body transform type: {} - falling back to body unchanged. "
                        + "Declare the transform in HttpExecutionService.IMPLEMENTED_BODY_TRANSFORMS "
                        + "or express it via per-param bodyPath.", transformType);
                yield body;
            }
        };
    }

    /**
     * The spellings an attachment object may use, owned by {@link FileAttachmentResolver}.
     *
     * <p>Shared rather than duplicated on purpose: this writer and the resolver's
     * ceiling have to accept exactly the same set. While they did not, an
     * attachment written under a spelling the ceiling did not count went out over
     * the limit, and the limit was one field name away from not existing.
     */
    private static final List<String> ATTACHMENT_NAME_FIELDS = FileAttachmentResolver.NAME_FIELDS;
    private static final List<String> ATTACHMENT_CONTENT_FIELDS = FileAttachmentResolver.CONTENT_FIELDS;
    private static final List<String> ATTACHMENT_MIME_FIELDS = FileAttachmentResolver.MIME_FIELDS;

    /**
     * Build the RFC 2822 message Gmail wants, from user-friendly parameters.
     *
     * <p>Returns the Gmail message resource, not just the encoded text: the
     * {@code raw} field plus {@code threadId} when the caller is replying. That
     * second field used to be declared on the endpoint and dropped here, so
     * every "reply in this conversation" silently started a NEW conversation,
     * on a call that reported success.
     *
     * <p>With no attachment the message is a single part, exactly as before.
     * With one or more it becomes {@code multipart/mixed}: the text first, then
     * one base64 part per file. Before that existed the parameter did not exist
     * either, so attaching a file to a Gmail send was simply impossible.
     */
    public Map<String, Object> buildRfc2822Body(Map<String, Object> params) {
        String rawMessage = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(buildMimeMessage(params).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("raw", rawMessage);

        // Gmail nests the sent mail into an existing conversation only when the
        // resource carries the thread id; the headers alone do not do it.
        String threadId = getStringParam(params, "threadId");
        if (threadId != null && !threadId.isBlank()) {
            message.put("threadId", threadId);
        }
        return message;
    }

    /** Assemble the MIME text: headers, then either one body or a multipart mixed. */
    private String buildMimeMessage(Map<String, Object> params) {
        StringBuilder message = new StringBuilder();

        String to = getStringParam(params, "to");
        String subject = getStringParam(params, "subject");
        String body = getStringParam(params, "body");
        String cc = getStringParam(params, "cc");
        String bcc = getStringParam(params, "bcc");
        String replyTo = getStringParam(params, "replyTo");
        boolean isHtml = Boolean.parseBoolean(getStringParam(params, "isHtml"));
        List<Map<String, Object>> attachments = readAttachments(params.get("attachments"));

        if (to != null && !to.isBlank()) {
            message.append("To: ").append(stripHeaderBreaks(to)).append("\r\n");
        }
        if (cc != null && !cc.isBlank()) {
            message.append("Cc: ").append(stripHeaderBreaks(cc)).append("\r\n");
        }
        if (bcc != null && !bcc.isBlank()) {
            message.append("Bcc: ").append(stripHeaderBreaks(bcc)).append("\r\n");
        }
        if (replyTo != null && !replyTo.isBlank()) {
            message.append("Reply-To: ").append(stripHeaderBreaks(replyTo)).append("\r\n");
        }
        if (subject != null) {
            // A header is ASCII by definition, so an accent sent verbatim reaches
            // the recipient as mojibake - which is most subjects, in most of the
            // languages the product ships in.
            message.append("Subject: ").append(encodeHeaderValue(stripHeaderBreaks(subject))).append("\r\n");
        }
        message.append("MIME-Version: 1.0\r\n");

        String bodyContentType = isHtml ? "text/html; charset=utf-8" : "text/plain; charset=utf-8";

        if (attachments.isEmpty()) {
            message.append("Content-Type: ").append(bodyContentType).append("\r\n");
            message.append("\r\n");
            if (body != null) {
                message.append(body);
            }
            return message.toString();
        }

        // Kept short on purpose: the Content-Type line carrying it should stay
        // inside the 78 characters RFC 5322 recommends, and 64 random bits is
        // already far past any chance of appearing inside the content.
        String boundary = "=_LiveContext_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        message.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n");
        message.append("\r\n");

        message.append("--").append(boundary).append("\r\n");
        message.append("Content-Type: ").append(bodyContentType).append("\r\n");
        message.append("\r\n");
        message.append(body == null ? "" : body).append("\r\n");

        for (Map<String, Object> attachment : attachments) {
            appendAttachmentPart(message, boundary, attachment);
        }

        message.append("--").append(boundary).append("--\r\n");
        return message.toString();
    }

    private void appendAttachmentPart(StringBuilder message, String boundary, Map<String, Object> attachment) {
        String name = firstField(attachment, ATTACHMENT_NAME_FIELDS);
        if (name == null || name.isBlank()) name = "attachment";
        String mimeType = sanitizeMediaType(firstField(attachment, ATTACHMENT_MIME_FIELDS));

        // NORMALISE FIRST, then judge. Checking emptiness before stripping the
        // data-URL prefix let "data:image/png;base64," through as non-blank and
        // wrote a zero-byte part: a mail that sends carrying an empty attachment,
        // green, which is the same failure the check below exists to refuse.
        String content = normalizeBase64(firstField(attachment, ATTACHMENT_CONTENT_FIELDS));
        if (content.isEmpty()) {
            throw new FileAttachmentException("The attachment '" + name + "' carries no file. "
                    + "Give the whole file object an earlier step produced, or an object with the "
                    + "file name and its bytes as base64. A link on its own cannot be attached.");
        }
        if (!isBase64(content)) {
            // Written out verbatim under Content-Transfer-Encoding: base64, this
            // reaches the recipient as a corrupt file with nothing reporting it.
            throw new FileAttachmentException("The bytes given for the attachment '" + name
                    + "' are not base64. Give the whole file object an earlier step produced and the "
                    + "platform encodes it for you.");
        }

        String safeName = boundFilenameLength(stripHeaderBreaks(name));
        message.append("--").append(boundary).append("\r\n");
        appendFoldedHeader(message, "Content-Type: " + mimeType, nameParameters("name", safeName));
        message.append("Content-Transfer-Encoding: base64\r\n");
        appendFoldedHeader(message, "Content-Disposition: attachment", nameParameters("filename", safeName));
        message.append("\r\n");
        message.append(wrapBase64(content)).append("\r\n");
    }

    /**
     * A media type reduced to what a MIME header may carry.
     *
     * <p>Interpolated raw, a value like {@code application/pdf"; evil="1} smuggled
     * a parameter of its own into the header, the same hole that was just closed
     * for the file name. Only what a type/subtype can hold survives; anything else
     * means the caller did not give a media type.
     */
    private static final Pattern MEDIA_TYPE =
            // \\s, not \s: in a Java string literal \s is the JLS 15 escape for a
            // single SPACE, so the pattern read as "any whitespace" and compiled as
            // "a space", and a tab before the parameter dropped the whole type.
            Pattern.compile("([A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+)\\s*(;.*)?", Pattern.DOTALL);

    private String sanitizeMediaType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) return "application/octet-stream";
        // Keeps the type/subtype off a parameterised value instead of discarding it.
        // BinaryResponseHandler stores the upstream Content-Type header VERBATIM as
        // a FileRef's media type, parameters and all, so "text/html; charset=utf-8"
        // is the ordinary shape for any downloaded file: rejecting it outright
        // retyped every attached HTML, CSV or PDF as an unrecognised binary.
        java.util.regex.Matcher matcher = MEDIA_TYPE.matcher(mimeType.trim());
        return matcher.matches() ? matcher.group(1) : "application/octet-stream";
    }

    /** Whitespace removed, any data-URL prefix dropped, base64url mapped onto the standard alphabet. */
    private String normalizeBase64(String content) {
        if (content == null) return "";
        String bytes = content.startsWith("data:") && content.indexOf(',') >= 0
                ? content.substring(content.indexOf(',') + 1)
                : content;
        return bytes.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
    }

    private boolean isBase64(String content) {
        try {
            Base64.getDecoder().decode(content);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The parameters carrying a file name, one entry each so every one can be
     * folded onto a line of its own.
     *
     * <p>A MIME parameter is not a header value, so an RFC 2047 encoded word does
     * not belong in this position even though clients tolerate one: RFC 2231 is
     * the form for it. A non-ASCII name therefore goes out as
     * {@code filename*=UTF-8''...}, beside an ASCII-only {@code filename="..."} so
     * a reader that understands neither still shows something.
     *
     * <p>The quoted form escapes quotes and backslashes. Interpolated raw, a name
     * holding a quote closed the parameter early and left the rest of it loose in
     * the header.
     */
    private List<String> nameParameters(String parameter, String name) {
        String ascii = toAsciiFilename(name);
        List<String> parameters = new ArrayList<>(2);
        parameters.add(parameter + "=\"" + ascii + "\"");
        if (!ascii.equals(name)) {
            parameters.add(parameter + "*=UTF-8''" + percentEncodeFilename(name));
        }
        return parameters;
    }

    /**
     * Write a header, folding its parameters onto a continuation line when the
     * whole thing would run long.
     *
     * <p>RFC 5322 recommends 78 characters and hard-limits at 998, and a file name
     * is caller data of any length: a long one produced a single header line well
     * past the recommendation with nothing to break it.
     */
    private void appendFoldedHeader(StringBuilder message, String head, List<String> parameters) {
        message.append(head);
        for (String parameter : parameters) {
            int lineLength = message.length() - message.lastIndexOf("\r\n") - 2;
            if (lineLength + parameter.length() + 2 > 78) {
                message.append(";\r\n ").append(parameter);
            } else {
                message.append("; ").append(parameter);
            }
        }
        message.append("\r\n");
    }

    /**
     * A file name long enough to matter, and no longer.
     *
     * <p>The name is caller data of any length, and it is written twice into one
     * header (quoted, then percent-encoded). Left unbounded, a pathological name
     * reaches RFC 5322's 998-character hard limit, which folding at parameter
     * boundaries cannot rescue. The extension is kept, because that is what a
     * recipient's client opens the file with.
     */
    private String boundFilenameLength(String name) {
        if (utf8Length(name) <= MAX_FILENAME_BYTES) return name;
        int dot = name.lastIndexOf('.');
        String extension = (dot > 0 && name.length() - dot <= 12) ? name.substring(dot) : "";
        int budget = MAX_FILENAME_BYTES - utf8Length(extension);

        // Cut on a code point, never inside one: splitting a surrogate pair emits
        // a replacement character into the name the recipient sees.
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < name.length(); ) {
            String piece = new String(Character.toChars(name.codePointAt(i)));
            int cost = utf8Length(piece);
            if (used + cost > budget) break;
            kept.append(piece);
            used += cost;
            i += piece.length();
        }
        return kept + extension;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Bounded in BYTES, not characters, because that is what the header costs.
     *
     * <p>The name is written twice into one header: quoted, and percent-encoded at
     * three characters per byte. A 120-CHARACTER bound let a CJK name (three bytes
     * each) reach 1080 encoded characters, past RFC 5322's 998 hard limit that the
     * bound exists to respect. 100 bytes puts the worst case at ~300.
     */
    private static final int MAX_FILENAME_BYTES = 100;

    /** The name reduced to what a quoted-string can hold, quotes and backslashes escaped. */
    private String toAsciiFilename(String name) {
        StringBuilder ascii = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c > 127) {
                ascii.append('_');
            } else if (c == '"' || c == '\\') {
                ascii.append('\\').append(c);
            } else {
                ascii.append(c);
            }
        }
        return ascii.toString();
    }

    /** RFC 2231 percent-encoding: everything outside the attribute characters. */
    private String percentEncodeFilename(String name) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean attributeChar = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0;
            if (attributeChar) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return encoded.toString();
    }

    /**
     * The attachments a caller supplied, as a list of objects.
     *
     * <p>A single object is accepted as well as a list: mapping one upstream
     * file into the field is at least as common as mapping several.
     */
    private List<Map<String, Object>> readAttachments(Object value) {
        List<Map<String, Object>> attachments = new ArrayList<>();
        if (value == null) return attachments;

        List<?> items = (value instanceof List<?> list) ? list : List.of(value);
        for (Object item : items) {
            Object candidate = item;
            if (candidate instanceof JsonNode node) {
                candidate = objectMapper.convertValue(node, Object.class);
            }
            if (candidate instanceof Map<?, ?> map) {
                Map<String, Object> typed = new LinkedHashMap<>();
                map.forEach((k, v) -> typed.put(String.valueOf(k), v));
                attachments.add(typed);
            } else if (candidate != null && !String.valueOf(candidate).isBlank()) {
                // Same rule as a part with no bytes: a value this cannot read is
                // a file the caller believes is attached.
                throw new FileAttachmentException("An entry given to 'attachments' is not a file. "
                        + "Give the whole file object an earlier step produced; a bare path or URL is not one, "
                        + "because the bytes have to be read to be attached.");
            }
            // A BLANK entry is skipped rather than refused. An optional field an
            // agent filled with "" arrives as a one-element list holding it, and a
            // template that resolved to nothing arrives the same way: neither is a
            // file the caller believes is attached, so neither should fail a mail.
        }
        return attachments;
    }

    /**
     * Delegates so the writer and the resolver's ceiling choose the same field.
     * Two copies of this rule disagreed on blanks, and the gap was an attachment
     * that counted as nothing and went out in full.
     */
    private String firstField(Map<String, Object> map, List<String> candidates) {
        return FileAttachmentResolver.firstNonBlank(map, candidates);
    }

    /**
     * Re-wrap unbroken base64 into the 76-character lines RFC 2045 requires.
     * Left as one long line, strict servers reject the whole message.
     */
    private String wrapBase64(String base64) {
        // Already compacted and mapped onto the standard alphabet by
        // normalizeBase64, which HAS to run before the emptiness and validity
        // checks. An agent forwarding a file it just read out of a mailbox hands
        // over base64URL ('-' and '_'), and a MIME part is standard base64: left
        // alone, every attachment whose bytes encode one of those two characters
        // arrives corrupt with no error anywhere.
        String compact = base64;
        StringBuilder wrapped = new StringBuilder(compact.length() + compact.length() / 76 * 2);
        for (int i = 0; i < compact.length(); i += 76) {
            if (i > 0) wrapped.append("\r\n");
            wrapped.append(compact, i, Math.min(i + 76, compact.length()));
        }
        return wrapped.toString();
    }

    /**
     * Encode a header value as RFC 2047 encoded words when it is not pure ASCII.
     *
     * <p>Pure-ASCII values are returned untouched, which is the overwhelming
     * majority and keeps them readable in any client. Anything else is split
     * into chunks small enough that each encoded word stays inside the 75
     * characters RFC 2047 allows, split on CHARACTERS so a multi-byte one is
     * never cut in half.
     */
    private String encodeHeaderValue(String value) {
        if (value == null || value.isEmpty()) return value;
        boolean ascii = true;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) { ascii = false; break; }
        }
        if (ascii) return value;

        // "=?UTF-8?B?" + payload + "?=" must stay <= 75 chars, so the base64
        // payload gets 63 chars, which is 45 source bytes (rounded to a multiple
        // of 3 so each chunk encodes without padding in the middle).
        final int maxBytesPerWord = 45;
        List<String> words = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int chunkBytes = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            String piece = new String(Character.toChars(codePoint));
            int pieceBytes = piece.getBytes(StandardCharsets.UTF_8).length;
            if (chunkBytes + pieceBytes > maxBytesPerWord && chunkBytes > 0) {
                words.add(encodeWord(chunk.toString()));
                chunk.setLength(0);
                chunkBytes = 0;
            }
            chunk.append(piece);
            chunkBytes += pieceBytes;
            i += piece.length();
        }
        if (chunkBytes > 0) {
            words.add(encodeWord(chunk.toString()));
        }
        // Folded onto continuation lines: consecutive encoded words are joined by
        // the reader with no space, so the linear whitespace must be the fold.
        return String.join("\r\n ", words);
    }

    private String encodeWord(String text) {
        return "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))
                + "?=";
    }

    /**
     * Remove CR/LF from a header value. A newline in a subject or a recipient is
     * how a caller injects headers of its own (an extra Bcc, a different From).
     */
    private String stripHeaderBreaks(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    /**
     * Build RFC 2822 draft body. Wraps the RFC 2822 message in a draft structure.
     * Used for email APIs like Gmail create_draft.
     */
    public Map<String, Object> buildRfc2822DraftBody(Map<String, Object> params) {
        // Build the RFC 2822 message
        Map<String, Object> messageBody = buildRfc2822Body(params);

        // Wrap in draft structure: { "message": { "raw": "..." } }
        return Map.of("message", messageBody);
    }

    private String getStringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof com.fasterxml.jackson.databind.JsonNode) {
            return ((com.fasterxml.jackson.databind.JsonNode) value).asText();
        }
        return value.toString();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════
    // Typed execution path (Phases 8/9/10 of the typed-execution refactor)
    // ═════════════════════════════════════════════════════════════════════════════════════

    /**
     * Execute a tool whose {@code execution_spec} declares a non-trivial mode (binary response,
     * multipart upload, or async polling). Reuses ALL the existing helpers - only the request
     * body encoding and the response parsing change.
     *
     * <p>This method is dispatched from {@link com.apimarketplace.catalog.service.ApiService}
     * when the tool's execution spec indicates that the legacy JSON-in / JSON-out path is not
     * sufficient. For sync JSON tools (the vast majority), the legacy
     * {@link #executeHttpCallWithCredentials(ApiEntity, ApiToolEntity, JsonNode, java.util.Set, String, String)}
     * path is unchanged.
     *
     * @param tenantId used to scope binary uploads and fileRef downloads in MinIO
     */
    public Map<String, Object> executeHttpCallTyped(ApiEntity api,
                                                    ApiToolEntity tool,
                                                    JsonNode parameters,
                                                    java.util.Set<String> allowedParamNames,
                                                    String userId,
                                                    String credentialName,
                                                    String tenantId) {
        // Captured early; injected into success result maps below for downstream
        // billing dispatch (see executeHttpCallWithCredentials for full rationale).
        String resolvedCredentialSource = null;
        try {
            log.info("[HttpExecutionService.executeTyped] Tool: {}, mode={}, userId={}",
                tool.getId(), tool.getExecutionMode(), userId);

            JsonNode executionSpec = parseExecutionSpec(tool.getExecutionSpec());
            String mode       = executionSpec.path("mode").asText("sync");
            String bodyType   = executionSpec.path("request").path("bodyType").asText("json");
            String responseType = executionSpec.path("response").path("type").asText("json");

            // V52 mode validation - fail-fast on unsupported modes instead of silently
            // routing them to the legacy sync path. Allowed: sync | async_poll | streaming
            // | upload (alias for sync+multipart). The 'webhook' mode was retired in V145
            // and is now rejected here as an unknown mode.
            if (!"sync".equals(mode) && !"async_poll".equals(mode)
                    && !"streaming".equals(mode) && !"upload".equals(mode)) {
                log.error("[HttpExecutionService.executeTyped] Tool {} declares unknown mode='{}'", tool.getId(), mode);
                return failure(0, "Unknown execution.mode: " + mode, tool);
            }

            // 1. Filter parameters
            JsonNode filteredParameters = filterParametersByToolDefinition(tool, parameters, allowedParamNames);

            // 2. Build URL - same helpers as legacy path
            String url = buildFullUrl(api, tool);
            url = processPathParameters(url, tool, filteredParameters);

            // Dynamic-URL endpoints ({upload_url}, {media_url}) let a runtime parameter choose
            // the request host - enforce their dedicated constraints (no residual placeholders,
            // declared host allow-list, SSRF check). No-op for fixed-host endpoints, so every
            // existing typed tool keeps its prior behaviour.
            enforceDynamicUrlConstraints(tool, url);

            url = processQueryParameters(url, tool, filteredParameters);

            // 3. Credential injection (URL/header/query) - V103 variant-aware.
            String variant = resolveCredentialVariant(userId, credentialName, api);
            CredentialInjection injection = getCredentialInjection(tool.getId(), variant);
            Optional<CredentialResolution> credResolution = tryGetCredentialResolution(userId, credentialName, api);
            Optional<String> credentialValue = credResolution.map(CredentialResolution::value);
            if (credResolution.isPresent()) {
                resolvedCredentialSource = credResolution.get().source().name().toLowerCase();
            }
            credentialValue = applyFieldAwareFallback(injection, credentialValue, userId, credentialName);
            // Generic sub-resource token resolution (e.g. Facebook Page token) - same hook as the
            // legacy path; no-op unless the tool declares the rule and the call carries the trigger.
            credentialValue = resolveSubResourceToken(api, tool, filteredParameters, credentialValue, userId, credentialName);
            if (injection != null && credentialValue.isPresent() && "query".equalsIgnoreCase(injection.type())) {
                url += (url.contains("?") ? "&" : "?") + injection.key() + "=" +
                       URLEncoder.encode(credentialValue.get(), StandardCharsets.UTF_8);
            }
            if (url.contains("{") && url.contains("}")) {
                url = replaceUrlTemplateVariables(url, userId, credentialName, credentialValue.orElse(null));
            }

            HttpHeaders headers = prepareHeadersWithCredentials(api, tool, userId, credentialName, injection, credentialValue);
            // Multi-field custom auth (≥2 header fields in customConfig) - apply every header.
            applyCustomFieldHeaderInjections(headers, injection, userId, credentialName, credentialValue);
            applyHeaderParameters(headers, tool, filteredParameters);

            // 4. Build body - JSON / multipart / form_urlencoded / raw_binary.
            //    Content-Type is set explicitly per bodyType; prior default ("application/json")
            //    set by prepareHeadersWithCredentials is overwritten via setContentType.
            Object body;
            if ("multipart".equals(bodyType)) {
                if (multipartBodyEncoder == null) {
                    return failure(0, "Multipart body encoder not available", tool);
                }
                Map<String, Object> paramsMap = jsonNodeToFlatMap(filteredParameters);
                try {
                    body = multipartBodyEncoder.encode(executionSpec.path("request"), paramsMap, tenantId);
                } catch (com.apimarketplace.catalog.service.execution.ByteRangeException e) {
                    // A part that asked for a slice must not go out whole or empty. Fail the
                    // call and name the missing bounds: `required: true` on them is read only
                    // at authoring time, so a step saved before the endpoint gained its range
                    // reaches here and this is the only place left to stop it.
                    return failure(0, e.getMessage(), tool);
                }
                // Spring needs the right Content-Type for multipart so the boundary is set automatically.
                headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
            } else if ("multipart_related".equals(bodyType) || "multipart-related".equals(bodyType)) {
                // multipart/related: JSON metadata part + binary media part. Required by
                // Google media-upload endpoints (YouTube videos.insert uploadType=multipart),
                // which reject the multipart/form-data produced by bodyType=multipart.
                if (multipartRelatedBodyEncoder == null) {
                    return failure(0, "Multipart-related body encoder not available", tool);
                }
                Map<String, Object> paramsMap = jsonNodeToFlatMap(filteredParameters);
                var related = multipartRelatedBodyEncoder.encode(executionSpec.path("request"), paramsMap, tenantId);
                if (related == null) {
                    return failure(0, "Failed to build multipart/related body (missing media fileRef or metadata)", tool);
                }
                body = related.body();
                // Boundary is generated by the encoder and MUST be echoed in the Content-Type.
                headers.setContentType(org.springframework.http.MediaType.parseMediaType(
                    "multipart/related; boundary=" + related.boundary()));
            } else if ("form_urlencoded".equals(bodyType) || "form-urlencoded".equals(bodyType)) {
                if (formUrlencodedBodyEncoder == null) {
                    return failure(0, "Form-urlencoded body encoder not available", tool);
                }
                Object prepared = prepareRequestBody(tool, filteredParameters, tenantId);
                @SuppressWarnings("unchecked")
                Map<String, Object> bodyMap = (prepared instanceof Map<?, ?>) ? (Map<String, Object>) prepared : java.util.Map.of();
                body = formUrlencodedBodyEncoder.encode(bodyMap);
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
            } else if ("raw_binary".equals(bodyType) || "raw".equals(bodyType)) {
                if (rawBinaryBodyEncoder == null) {
                    return failure(0, "Raw binary body encoder not available", tool);
                }
                Object prepared = prepareRequestBody(tool, filteredParameters, tenantId);
                @SuppressWarnings("unchecked")
                Map<String, Object> bodyMap = (prepared instanceof Map<?, ?>) ? (Map<String, Object>) prepared : java.util.Map.of();
                JsonNode requestSpec = executionSpec.path("request");
                try {
                    body = rawBinaryBodyEncoder.encode(requestSpec, bodyMap, tenantId);
                } catch (com.apimarketplace.catalog.service.execution.ByteRangeException e) {
                    return failure(0, e.getMessage(), tool);
                }
                String declaredCt = rawBinaryBodyEncoder.resolveContentType(requestSpec);
                try {
                    headers.setContentType(org.springframework.http.MediaType.parseMediaType(declaredCt));
                } catch (org.springframework.util.InvalidMimeTypeException e) {
                    log.warn("[HttpExecutionService] raw_binary tool {} declared invalid contentType '{}', falling back to application/octet-stream",
                        tool.getId(), declaredCt);
                    headers.setContentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
                }
            } else if ("graphql".equals(bodyType)) {
                if (graphqlBodyEncoder == null) {
                    return failure(0, "GraphQL body encoder not available", tool);
                }
                JsonNode graphqlCfg = executionSpec.path("request").path("graphql");
                String query = graphqlCfg.path("query").asText(null);
                if (query == null || query.isBlank()) {
                    return failure(0,
                        "execution.request.graphql.query is required for bodyType=graphql", tool);
                }
                String operationName = graphqlCfg.path("operationName").asText(null);
                Map<String, Object> paramsMap = jsonNodeToFlatMap(filteredParameters);
                body = graphqlBodyEncoder.encode(query, operationName, paramsMap, graphqlCfg);
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            } else {
                body = prepareRequestBody(tool, filteredParameters, tenantId);
            }

            // 4b. AWS Signature V4 - applied after body is finalized (signature is computed over
            // the exact bytes). Triggered when the host matches *.amazonaws.com AND the request
            // uses credentials with the AWS field set (access_key_id + secret_access_key).
            maybeSignAws(tool, url, headers, body, userId, credentialName);

            HttpEntity<Object> request = new HttpEntity<>(body, headers);
            log.info("[HttpExecutionService.executeTyped] {} {} (bodyType={}, responseType={}, mode={})",
                tool.getMethod(), url, bodyType, responseType, mode);

            // 5a. Streaming mode: aggregate the SSE chunks via WebClient (separate transport
            // from the RestTemplate path because RestTemplate cannot consume SSE incrementally).
            // The aggregated response is then projected by ToolExecutionManager just like sync.
            if ("streaming".equals(mode)) {
                if (streamingResponseHandler == null) {
                    return failure(0, "Streaming response handler not available", tool);
                }
                Map<String, Object> aggregated = streamingResponseHandler.handle(
                        url,
                        HttpMethod.valueOf(tool.getMethod()),
                        headers,
                        body
                );
                Map<String, Object> result = new HashMap<>();
                result.put("success", aggregated.get("error") == null);
                result.put("status", aggregated.get("error") == null ? 200 : 0);
                result.put("httpStatus", buildHttpStatus(aggregated.get("error") == null ? 200 : 0,
                        (String) aggregated.get("error")));
                result.put("data", aggregated);
                result.put("headers", Map.of());
                if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);
                return result;
            }

            // 5b. Issue the request - branch on response type to pick the right Class<?>
            if ("binary".equals(responseType)) {
                return executeBinaryResponse(url, tool, request, executionSpec, tenantId, resolvedCredentialSource, api);
            }

            final String typedUrl = url;
            ResponseEntity<Object> response = exchangeWithRetry(
                () -> restTemplate.exchange(
                    java.net.URI.create(typedUrl),
                    HttpMethod.valueOf(tool.getMethod()),
                    request,
                    Object.class),
                typedUrl, tool, api);
            int statusCode = response.getStatusCode().value();
            Object responseBody = response.getBody() != null ? response.getBody() : Map.of();

            // 6. async_poll: treat the body as the submit response and poll the upstream
            if ("async_poll".equals(mode)) {
                if (asyncPollExecutor == null) {
                    return failure(0, "Async poll executor not available", tool);
                }
                try {
                    Object resolved = asyncPollExecutor.pollUntilDone(
                        api.getBaseUrl(),
                        objectMapper.valueToTree(responseBody),
                        executionSpec.path("async"),
                        headers
                    );
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("status", statusCode);
                    result.put("httpStatus", buildHttpStatus(statusCode, null));
                    result.put("data", resolved == null ? Map.of() : resolved);
                    result.put("headers", response.getHeaders().toSingleValueMap());
                    if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);
                    return result;
                } catch (com.apimarketplace.catalog.service.execution.AsyncPollExecutor.AsyncPollFailureException ape) {
                    return failure(0, ape.getMessage(), tool);
                }
            }

            // 6b. GraphQL auto-unwrap - translate the {data, errors} envelope into either:
            //     - failure(0, ...) when `errors` is non-empty (semantic failure even if HTTP 200)
            //     - the inner `data` payload (so downstream OutputProjector sees `data.x.y`
            //       referenced as `output.x.y`, matching how authors typically write outputSchema)
            // Non-Map responses (e.g. an HTML error page from a misconfigured proxy) pass through
            // unchanged so the existing diagnostic surface is preserved.
            if ("graphql".equals(bodyType) && responseBody instanceof Map<?, ?> bodyMap) {
                Object errorsField = bodyMap.get("errors");
                if (errorsField instanceof java.util.List<?> errorsList && !errorsList.isEmpty()) {
                    String errorsStr;
                    try {
                        errorsStr = objectMapper.writeValueAsString(errorsField);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                        errorsStr = errorsField.toString();
                    }
                    return failure(0, "GraphQL errors: " + errorsStr, tool);
                }
                Object dataField = bodyMap.get("data");
                if (dataField != null) {
                    responseBody = dataField;
                }
            }

            // 7. plain JSON / sync / upload
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("status", statusCode);
            result.put("httpStatus", buildHttpStatus(statusCode, null));
            result.put("data", responseBody);
            result.put("headers", response.getHeaders().toSingleValueMap());
            if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);
            return result;

        } catch (org.springframework.web.client.HttpStatusCodeException httpEx) {
            int statusCode = httpEx.getStatusCode().value();
            String errorMessage = httpEx.getResponseBodyAsString();
            log.error("[HttpExecutionService.executeTyped] HTTP error: status={}, error={}", statusCode, errorMessage);
            // This path returns the provider's RAW body as the error, which is exactly the case
            // errorPolicy exists for: an upload or a publish refused for a reason only the account
            // owner can act on reads as a platform bug otherwise.
            String readerMessage = declaredErrorMessage(
                    api, statusCode, errorMessage, httpEx.getResponseHeaders(), errorMessage);
            Map<String, Object> failed = failure(statusCode, readerMessage, tool);
            if (!readerMessage.equals(errorMessage)) {
                // A rule replaced the provider's own words. Keep them alongside, the way the
                // credentialed path does: if a bodyContains needle ever collides with an unrelated
                // failure, the only thing that says what actually happened is this body.
                failed.put("errorBody", errorMessage);
            }
            return failed;
        } catch (com.apimarketplace.catalog.service.exception.CredentialSelectionException e) {
            // The typed path serves binary responses, multipart uploads, async
            // polling and streaming, which is exactly the endpoint class a
            // per-account publishing step uses. Swallowed here, the refusal became a
            // generic tool failure and the reader was sent looking at the provider
            // instead of at the account name that did not match.
            throw e;
        } catch (Exception e) {
            log.error("[HttpExecutionService.executeTyped] Error: {}", e.getMessage(), e);
            return failure(0, e.getMessage() != null ? e.getMessage() : "Unknown error", tool);
        }
    }

    /**
     * Subcase of executeTyped: response.type=binary. Calls the upstream with byte[].class
     * and uploads the bytes via {@link com.apimarketplace.catalog.service.execution.BinaryResponseHandler}.
     */
    private Map<String, Object> executeBinaryResponse(String url,
                                                      ApiToolEntity tool,
                                                      HttpEntity<Object> request,
                                                      JsonNode executionSpec,
                                                      String tenantId,
                                                      String resolvedCredentialSource,
                                                      ApiEntity api) {
        if (binaryResponseHandler == null) {
            return failure(0, "Binary response handler not available", tool);
        }
        // Image, audio and video generation live behind this branch, which is the endpoint class
        // that gets throttled hardest. It dispatches separately from the JSON typed path, so
        // without its own wrapper the 429 retry would apply everywhere except where it is needed
        // most.
        final String binaryUrl = url;
        ResponseEntity<byte[]> response = exchangeWithRetry(
            () -> restTemplate.exchange(
                java.net.URI.create(binaryUrl),
                HttpMethod.valueOf(tool.getMethod()),
                request,
                byte[].class),
            binaryUrl, tool, api);
        int statusCode = response.getStatusCode().value();
        byte[] bytes = response.getBody();
        String contentType = response.getHeaders().getFirst("Content-Type");

        Map<String, Object> projectedFile = binaryResponseHandler.handle(
            bytes, contentType, tenantId, tool.getOutputSchema(), tool.getToolSlug()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("status", statusCode);
        result.put("httpStatus", buildHttpStatus(statusCode, null));
        result.put("data", projectedFile);
        result.put("headers", response.getHeaders().toSingleValueMap());
        if (resolvedCredentialSource != null) result.put("credentialSource", resolvedCredentialSource);
        return result;
    }

    /** Parse a tool's execution_spec JSON, returning an empty MissingNode if absent/malformed. */
    private JsonNode parseExecutionSpec(String executionSpecJson) {
        if (executionSpecJson == null || executionSpecJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(executionSpecJson);
        } catch (Exception e) {
            log.warn("[HttpExecutionService.executeTyped] Failed to parse execution_spec: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /** Build a uniform failure result. */
    private Map<String, Object> failure(int statusCode, String error, ApiToolEntity tool) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("status", statusCode);
        result.put("httpStatus", buildHttpStatus(statusCode, error));
        result.put("data", Map.of());
        result.put("error", error);
        return result;
    }

    /** Convert {@code [{"k":"v"},{"k2":"v2"}]} or {@code {"k":"v"}} to a flat Map<String,Object>. */
    private Map<String, Object> jsonNodeToFlatMap(JsonNode parameters) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (parameters == null || parameters.isMissingNode() || parameters.isNull()) return out;
        if (parameters.isArray()) {
            for (JsonNode element : parameters) {
                if (element.isObject()) {
                    element.fields().forEachRemaining(e -> out.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class)));
                }
            }
        } else if (parameters.isObject()) {
            parameters.fields().forEachRemaining(e -> out.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class)));
        }
        return out;
    }

    /**
     * Returns true when the tool's execution_spec requires the typed path
     * (anything beyond a plain JSON sync request).
     *
     * <p>The typed path is needed when ANY of the following holds:
     * <ul>
     *   <li>{@code mode != "sync"} (async_poll, streaming, upload)</li>
     *   <li>{@code response.type = "binary"} (BinaryResponseHandler)</li>
     *   <li>{@code request.bodyType} requires a dedicated encoder
     *       (multipart, graphql, form_urlencoded, raw_binary)</li>
     * </ul>
     * The legacy {@code ApiService} path only knows how to JSON-serialize the params
     * map; routing graphql/form_urlencoded/raw_binary there would silently drop the
     * encoder and send the body as plain JSON.
     */
    public boolean needsTypedExecutionPath(ApiToolEntity tool) {
        String specJson = tool.getExecutionSpec();
        if (specJson == null || specJson.isBlank()) return false;
        try {
            JsonNode spec = objectMapper.readTree(specJson);
            String mode = spec.path("mode").asText("sync");
            String bodyType = spec.path("request").path("bodyType").asText("json");
            String responseType = spec.path("response").path("type").asText("json");
            return !"sync".equals(mode)
                || "binary".equals(responseType)
                || "multipart".equals(bodyType)
                || "multipart_related".equals(bodyType)
                || "multipart-related".equals(bodyType)
                || "graphql".equals(bodyType)
                || "form_urlencoded".equals(bodyType)
                || "raw_binary".equals(bodyType);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Apply AWS Signature V4 to the pending request when the host is an AWS service host and
     * the user's credential has {@code access_key_id} + {@code secret_access_key} fields.
     *
     * <p>Called after the body is finalized so the SHA-256 payload hash is computed on the exact
     * bytes that will be sent over the wire (canonical request → string-to-sign dependence).
     *
     * <p>Body object is serialized to the same bytes Spring's RestTemplate will send:
     * <ul>
     *   <li>{@code byte[]} → direct use</li>
     *   <li>{@code String} → UTF-8 bytes</li>
     *   <li>{@code MultiValueMap<String,String>} (form_urlencoded) → URL-encoded form string</li>
     *   <li>{@link Map} / POJO → JSON via {@code objectMapper}</li>
     *   <li>{@code null} or multipart → empty body hash (matches AWS's signature expectation
     *       for streams where the hash cannot be precomputed; this falls back to the UNSIGNED_PAYLOAD
     *       convention on AWS SNS/SQS which does NOT accept multipart, so not an issue in practice)</li>
     * </ul>
     */
    private void maybeSignAws(ApiToolEntity tool, String url, HttpHeaders headers,
                              Object body, String userId, String credentialName) {
        if (awsSigV4Signer == null) return;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null
                || (!host.endsWith(".amazonaws.com") && !host.endsWith(".amazonaws.com.cn"))) {
                return;
            }
            Map<String, String> credentialFields = getCredentialDataMapForUserSelection(userId, credentialName);
            if (credentialFields == null
                || credentialFields.get("access_key_id") == null
                || credentialFields.get("secret_access_key") == null) {
                log.debug("[HttpExecutionService] AWS host {} but no access_key_id/secret_access_key in credential - skipping SigV4", host);
                return;
            }
            // Temporary STS credentials carry a session token that MUST travel as
            // x-amz-security-token. The signer signs every x-amz-* header, so setting it here
            // automatically folds it into the SignedHeaders/signature. Long-lived IAM keys omit it.
            String sessionToken = credentialFields.get("session_token");
            if (sessionToken == null || sessionToken.isBlank()) {
                sessionToken = credentialFields.get("aws_session_token");
            }
            if (sessionToken != null && !sessionToken.isBlank()) {
                headers.set("x-amz-security-token", sessionToken.strip());
            }
            byte[] bodyBytes = serializeBodyForSigning(body);
            awsSigV4Signer.sign(tool.getMethod(), url, headers, bodyBytes, credentialFields);
        } catch (com.apimarketplace.catalog.service.exception.CredentialSelectionException e) {
            // Signing needs the credential MATERIAL, so an unresolvable run-time choice
            // surfaces here. Swallowed, the request went out UNSIGNED and failed at the
            // provider with an auth error pointing at AWS instead of at the account
            // name that did not match. It is the one credential-helper call site that
            // still degraded rather than refused.
            throw e;
        } catch (Exception e) {
            log.warn("[HttpExecutionService] AWS SigV4 signing failed for tool {}: {} - falling through unsigned", tool.getId(), e.getMessage());
        }
    }

    /** Serialize the request body object into the bytes that will be transmitted over the wire. */
    private byte[] serializeBodyForSigning(Object body) {
        if (body == null) return new byte[0];
        if (body instanceof byte[] arr) return arr;
        if (body instanceof String s) return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (body instanceof org.springframework.util.MultiValueMap<?, ?> mvm) {
            // Form-urlencoded serialization matches Spring's FormHttpMessageConverter:
            // join pairs with '&', URL-encode each key/value.
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : mvm.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object vals = entry.getValue();
                if (vals instanceof java.util.List<?> list) {
                    for (Object v : list) {
                        if (sb.length() > 0) sb.append('&');
                        sb.append(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8))
                          .append('=')
                          .append(java.net.URLEncoder.encode(String.valueOf(v), java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            }
            return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        // Fallback: JSON-serialize the body
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            log.warn("[HttpExecutionService] Failed to serialize body for SigV4 signing: {}", e.getMessage());
            return new byte[0];
        }
    }
}
