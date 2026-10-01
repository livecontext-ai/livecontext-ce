package com.apimarketplace.catalog.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Turns a provider refusal into a message written for the reader, when the API's seed declares
 * one ({@code errorPolicy}, catalog column {@code apis.error_policy}).
 *
 * <p>Rules are evaluated in declaration order, first match wins. The only action is
 * {@code user_error}: replace the provider's raw body with the declared message, so a refusal
 * only the account owner can act on ("your app is not audited", "daily post limit reached") does
 * not read like a platform bug.
 *
 * <h3>The platform never re-sends a refused call</h3>
 * A provider refusal, a {@code 429} included, is returned to the caller on the first answer.
 * Retrying is the caller's decision: a workflow node's {@code retryCount}/{@code retryBackoffMs},
 * or the agent that made the call. A platform that re-sends on its own, invisibly, multiplies the
 * requests a provider just asked to slow down, and repeated hammering is exactly what gets an
 * account throttled harder or suspended.
 *
 * <p>A {@code retry} action may still be present in a stored policy (a row imported before the
 * retry was removed, or a bundle from an older cloud). It is skipped, never executed.
 */
@Slf4j
@Component
public class ErrorPolicyEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mirrors the validator's floor: shorter than this identifies no provider error code. */
    private static final int MIN_BODY_NEEDLE_LENGTH = 4;

    /**
     * The message the API's declared policy gives for this refusal, or {@code null} when no
     * {@code user_error} rule matches.
     *
     * @param status          HTTP status the provider returned
     * @param responseBody    raw response body (may be null)
     * @param errorPolicyJson the API's {@code errorPolicy} array as stored in the catalog, or null
     */
    public String declaredMessage(int status, String responseBody, String errorPolicyJson) {
        if (errorPolicyJson == null || errorPolicyJson.isBlank()) {
            return null;
        }
        JsonNode rules;
        try {
            rules = MAPPER.readTree(errorPolicyJson);
        } catch (Exception e) {
            // A malformed policy must never take an execution down: the call already failed.
            log.warn("Ignoring malformed errorPolicy: {}", e.getMessage());
            return null;
        }
        if (!rules.isArray()) {
            log.warn("Ignoring errorPolicy: expected an array, got {}", rules.getNodeType());
            return null;
        }

        String haystack = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        for (JsonNode rule : rules) {
            if (!matches(rule.path("match"), status, haystack)) {
                continue;
            }
            String action = rule.path("action").asText("");
            if ("retry".equalsIgnoreCase(action)) {
                // Legacy shape, see the class comment. Its message described a re-send that no
                // longer happens, so it is not shown either.
                log.debug("Skipping legacy errorPolicy rule with action 'retry'");
                continue;
            }
            if (!"user_error".equalsIgnoreCase(action)) {
                // An action this build has no code for: skip this rule and keep scanning, so a
                // self-hosted install applying a bundle from a newer cloud keeps every rule it CAN
                // execute.
                log.warn("Ignoring errorPolicy rule with unknown action '{}'", action);
                continue;
            }
            String message = rule.path("message").isTextual() ? rule.path("message").asText() : null;
            if (message == null || message.isBlank()) {
                // The validator refuses this shape in a seed, so it can only arrive from a bundle
                // or a hand-edited row. A blank message would replace the provider's body with
                // nothing. Skip THIS rule and keep scanning.
                log.warn("Ignoring errorPolicy rule with action 'user_error' and no message");
                continue;
            }
            return message;
        }
        return null;
    }

    /**
     * Every criterion present must match. An empty {@code match} matches nothing: a rule that
     * fired on every failure of the API would be a trap, not a shortcut.
     */
    private boolean matches(JsonNode match, int status, String lowercasedBody) {
        if (!match.isObject()) {
            return false;
        }
        // No accumulator shortcut for an empty object: the sawCriterion check below already
        // rejects it, and a second guard doing the same thing lets a test pass while the rule it
        // names is gone.
        boolean sawCriterion = false;

        if (match.has("status")) {
            sawCriterion = true;
            // has(), not hasNonNull(): an explicit null would otherwise drop the criterion and
            // widen the rule to every status, which is what the statusIn branch below refuses.
            if (!match.path("status").isInt() || match.path("status").asInt() != status) {
                return false;
            }
        }
        if (match.has("statusIn")) {
            sawCriterion = true;
            if (!match.path("statusIn").isArray()) {
                // Skipping it would silently widen the rule to whatever criteria remain, which is
                // the opposite of what a malformed declaration should do.
                return false;
            }
            boolean hit = false;
            for (JsonNode s : match.path("statusIn")) {
                // isInt(), not asInt(): "429" as a string parses, and honouring a shape the
                // validator refuses lets a bundle express what a seed cannot.
                if (s.isInt() && s.asInt() == status) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        if (match.has("bodyContains")) {
            sawCriterion = true;
            String needle = match.path("bodyContains").isTextual()
                    ? match.path("bodyContains").asText("").toLowerCase(Locale.ROOT)
                    : "";
            // The same floor the validator enforces on a seed. A one or two character needle
            // matches nearly every error body, so a rule carrying one would rewrite EVERY failed
            // call of the API with a single wording - the trap the empty-match guard exists for,
            // arrived at from the other side. Checked here too because a bundle or a hand-edited
            // row never passed through the validator.
            if (needle.length() < MIN_BODY_NEEDLE_LENGTH || !lowercasedBody.contains(needle)) {
                return false;
            }
        }
        return sawCriterion;
    }
}
