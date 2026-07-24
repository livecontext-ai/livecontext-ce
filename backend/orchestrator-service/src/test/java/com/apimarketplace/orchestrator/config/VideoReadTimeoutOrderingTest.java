package com.apimarketplace.orchestrator.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the cross-layer timeout ordering between this service and the screenshot-renderer
 * sidecar. The sidecar's smooth video path finalises a (possibly truncated) clip AT its
 * wall-clock budget and only then encodes and replies, so the client's read window must
 * outlast the sidecar's worst case. If it does not, the call aborts client-side, the
 * best-effort contract turns the render into {@code Optional.empty()}, and the node emits
 * NO video - strictly worse than the short clip the budget was raised to prevent.
 *
 * <p>The invariant is arithmetic over two constants that live in DIFFERENT repositories'
 * languages (Java here, {@code infra/screenshot-renderer/lib.js} there), so nothing but a
 * test like this keeps them honest when either side is tuned.
 */
@DisplayName("Video read timeout vs sidecar wall budget ordering")
class VideoReadTimeoutOrderingTest {

    /**
     * Mirrors {@code DEFAULT_SMOOTH_WALL_TIMEOUT_MS} in infra/screenshot-renderer/lib.js, whose
     * own suite pins that value ("smooth wall-clock budget fits a 60fps 20s clip..."). Raising
     * the budget there fails THAT test, which points back here; this test then fails if the read
     * window was not raised with it. The two together are what keep the ordering honest.
     */
    private static final int SIDECAR_SMOOTH_WALL_BUDGET_MS = 450_000;

    /**
     * Mirrors {@code MAX_TIMEOUT_MS} in infra/screenshot-renderer/lib.js. The sidecar arms its
     * wall deadline only AFTER page.setContent, so the load window is additive - the budget is
     * not the sidecar's total.
     */
    private static final int SIDECAR_MAX_LOAD_MS = 30_000;

    private static int declaredDefault(String propertyFragment) throws Exception {
        for (Field field : RestTemplateConfig.class.getDeclaredFields()) {
            Value value = field.getAnnotation(Value.class);
            if (value != null && value.value().contains(propertyFragment)) {
                String expr = value.value();
                return Integer.parseInt(expr.substring(expr.indexOf(':') + 1, expr.indexOf('}')));
            }
        }
        throw new AssertionError("no @Value default found for " + propertyFragment);
    }

    @Test
    @DisplayName("video-read default outlasts the sidecar's load + wall budget, or a finished clip is thrown away")
    void videoReadTimeoutOutlastsTheSidecarWorstCase() throws Exception {
        int videoReadTimeout = declaredDefault("video-read");
        int sidecarWorstCase = SIDECAR_MAX_LOAD_MS + SIDECAR_SMOOTH_WALL_BUDGET_MS;

        assertTrue(videoReadTimeout > sidecarWorstCase,
            "video-read (" + videoReadTimeout + "ms) must exceed the sidecar's load + wall budget ("
                + sidecarWorstCase + "ms), leaving room for the ffmpeg finalise and body transfer; "
                + "below it the orchestrator aborts a render the sidecar was about to return and the "
                + "node emits no video at all");
    }

    @Test
    @DisplayName("video-read is NOT inherited by ordinary calls (a 9-minute read window must stay opt-in)")
    void ordinaryReadTimeoutStaysShort() throws Exception {
        int ordinary = declaredDefault("timeout.read");
        int videoReadTimeout = declaredDefault("video-read");

        assertTrue(ordinary < videoReadTimeout,
            "the shared RestTemplate must keep its short read window; a service-to-service call "
                + "hanging for the video budget would pile threads across the orchestrator");
    }
}
