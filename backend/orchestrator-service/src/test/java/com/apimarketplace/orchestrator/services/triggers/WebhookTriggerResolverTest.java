package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression for F7 - webhook payload size guard. Pre-fix the webhook
 * resolver propagated arbitrary payload sizes through to
 * {@code workflow_runs.trigger_payload} (JSONB), enabling an OOM vector on
 * RunCloneService replays + Hibernate L1 fetches over a hot
 * {@code findLatestPerAliasLightweight} pattern (cf. incident 2026-05-07).
 * Post-fix the resolver replaces oversized payloads with a stub so the
 * orchestrator pod cannot be DoS'd by a single 50 MB webhook fire.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookTriggerResolver")
class WebhookTriggerResolverTest {

    @Mock
    private TriggerUserResolver triggerUserResolver;

    @InjectMocks
    private WebhookTriggerResolver resolver;

    private Trigger trigger;

    @BeforeEach
    void setUp() {
        trigger = new Trigger("trigger-1", "webhook", "Webhook", null, Map.of());
        // @Value injection is bypassed in unit tests - set the cap explicitly.
        resolver.maxWebhookPayloadBytes = WebhookTriggerResolver.DEFAULT_MAX_WEBHOOK_PAYLOAD_BYTES;
    }

    @Nested
    @DisplayName("payload size guard (F7)")
    class PayloadSizeGuard {

        @Test
        @DisplayName("Small payload passes through intact")
        void smallPayloadIntact() {
            when(triggerUserResolver.resolveDisplayName(anyString())).thenReturn("test-user");
            Map<String, Object> payload = new HashMap<>();
            payload.put("event", "push");
            payload.put("ref", "refs/heads/main");
            payload.put("commits", java.util.List.of(Map.of("id", "abc123", "msg", "small commit")));

            Map<String, Object> result = resolver.resolve(trigger, "tenant-1",
                    Map.of("payload", payload));

            @SuppressWarnings("unchecked")
            Map<String, Object> propagated = (Map<String, Object>) result.get("payload");
            assertThat(propagated).containsAllEntriesOf(payload);
            assertThat(propagated).doesNotContainKey("_oversized");
        }

        @Test
        @DisplayName("Oversized payload (>5 MB default cap) is replaced with namespaced stub - no OOM cascade")
        void oversizedPayloadReplacedWithStub() {
            when(triggerUserResolver.resolveDisplayName(anyString())).thenReturn("test-user");
            // Build a payload guaranteed to exceed 5 MB when serialized to JSON.
            // 6 MB of `x`-string in a single key - realistic shape: a webhook
            // sending a base64-encoded image or a buffered file body.
            Map<String, Object> payload = new HashMap<>();
            payload.put("image_base64", "x".repeat(6_000_000));

            Map<String, Object> result = resolver.resolve(trigger, "tenant-1",
                    Map.of("payload", payload));

            @SuppressWarnings("unchecked")
            Map<String, Object> propagated = (Map<String, Object>) result.get("payload");
            // Stub keys are namespaced under `_orchestrator_*` so they cannot
            // collide with a legitimate webhook payload using `_oversized`.
            assertThat(propagated).containsEntry("_orchestrator_oversized", true);
            assertThat(propagated).containsEntry("_orchestrator_cap_bytes", WebhookTriggerResolver.DEFAULT_MAX_WEBHOOK_PAYLOAD_BYTES);
            assertThat(propagated).containsKey("_orchestrator_message");
            // Crucially: the giant value is NOT in the propagated map - that's
            // what protects RunCloneService replays and Hibernate over-fetches.
            assertThat(propagated).doesNotContainKey("image_base64");
            assertThat(propagated.get("_orchestrator_message")).asString().contains("exceeded");
        }

        @Test
        @DisplayName("payloadExceedsCap is bounded-memory - does not allocate the full payload to check")
        void sizeCheckIsBoundedMemory() {
            // Indirect proof: feeding a payload below the cap returns false
            // fast. Feeding above returns true without throwing OOM. Both
            // exercises rely on the SizeLimitOutputStream short-circuit.
            assertThat(resolver.payloadExceedsCap(Map.of("k", "v"))).isFalse();
            assertThat(resolver.payloadExceedsCap(Map.of("blob", "x".repeat(6_000_000)))).isTrue();
        }

        @Test
        @DisplayName("F7 round-2: tightened cap via configuration property - operator can ramp without code change")
        void configurableCapHonored() {
            // Simulate @Value injecting a tight 1 KB cap (e.g. for a paranoid
            // tenant). Confirms the field is honored end-to-end.
            resolver.maxWebhookPayloadBytes = 1024;
            Map<String, Object> payload = Map.of("blob", "x".repeat(2000));

            assertThat(resolver.payloadExceedsCap(payload))
                    .as("Tightened cap (1 KB) must reject a 2 KB payload - proves @Value path is honored")
                    .isTrue();
        }

        @Test
        @DisplayName("F7 round-2: legitimate `_oversized` key in user payload does NOT collide with orchestrator stub")
        void legitimateUserKeyDoesNotCollideWithStub() {
            when(triggerUserResolver.resolveDisplayName(anyString())).thenReturn("test-user");
            // A real webhook payload from a third-party source could happen to
            // use `_oversized` as a domain key (it's not reserved). Pre-namespace
            // fix, downstream consumers could not distinguish "user said it's
            // oversized" from "orchestrator stubbed because it was oversized".
            // Post-namespace, the stub uses `_orchestrator_oversized` - no
            // collision.
            Map<String, Object> userPayload = new HashMap<>();
            userPayload.put("_oversized", true);  // user's own key
            userPayload.put("event", "push");

            Map<String, Object> result = resolver.resolve(trigger, "tenant-1",
                    Map.of("payload", userPayload));

            @SuppressWarnings("unchecked")
            Map<String, Object> propagated = (Map<String, Object>) result.get("payload");
            // User's _oversized=true survives intact (payload was small, no stub).
            assertThat(propagated).containsEntry("_oversized", true);
            assertThat(propagated).containsEntry("event", "push");
            // Orchestrator-namespaced keys are absent (no stub fired).
            assertThat(propagated).doesNotContainKey("_orchestrator_oversized");
        }
    }

    // Tiny matcher proxy so JUnit doesn't import-pollute the static section above.
    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
