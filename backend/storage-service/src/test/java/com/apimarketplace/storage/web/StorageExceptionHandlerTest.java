package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.exception.QuotaExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "This account is full" must not reach a caller disguised as "the server broke".
 *
 * <p>storage-service had no exception handler at all, so a {@link QuotaExceededException} came
 * back as a bare 500 from every internal endpoint: workflow step outputs, byte uploads, and the
 * generic upload used by media generation and chat attachments. Downstream, {@code StorageClient}
 * collapses any failure to null, so the caller could not tell a full account from a broken
 * bucket, and media generation, which reaches the store step only after the customer has been
 * charged, could not say which had happened.
 */
@DisplayName("StorageExceptionHandler")
class StorageExceptionHandlerTest {

    private final StorageExceptionHandler handler = new StorageExceptionHandler();

    @Test
    @DisplayName("a quota refusal is 413, not 500")
    void quotaBecomes413() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleQuotaExceeded(new QuotaExceededException("hard limit reached"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("the body matches the public FileController's, so both routes look the same to a client")
    void bodyMatchesThePublicRoute() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleQuotaExceeded(new QuotaExceededException("hard limit reached"));

        assertThat(resp.getBody()).containsEntry("error", "Storage quota exceeded");
    }

    @Test
    @DisplayName("the tenant-carrying constructor is handled too, and leaks no tenant id into the body")
    void tenantVariantIsHandledWithoutLeaking() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleQuotaExceeded(new QuotaExceededException("hard limit reached", "tenant-42"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(resp.getBody().toString()).doesNotContain("tenant-42");
    }

    /**
     * The conditional is load-bearing, not decoration: in the CE monolith this package is
     * component-scanned alongside {@code com.apimarketplace.orchestrator}, whose
     * {@code GlobalExceptionHandler} already maps the same exception to 413 with a different
     * body. Two unordered advices for one exception make the winner a bean-ordering accident. A
     * typo in the property name would re-open exactly that, silently and only in CE, so the
     * registration itself is pinned here rather than assumed.
     */
    @Nested
    @DisplayName("registration is gated on deployment mode")
    class Registration {

        private final ApplicationContextRunner contexts =
                new ApplicationContextRunner().withUserConfiguration(StorageExceptionHandler.class);

        @Test
        @DisplayName("absent in the CE monolith, which already has the orchestrator's advice")
        void absentInMonolith() {
            contexts.withPropertyValues("deployment.mode=monolith")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(StorageExceptionHandler.class));
        }

        @Test
        @DisplayName("present in microservice mode, the deployment that had no handler at all")
        void presentInMicroservice() {
            contexts.withPropertyValues("deployment.mode=microservice")
                    .run(ctx -> assertThat(ctx).hasSingleBean(StorageExceptionHandler.class));
        }

        @Test
        @DisplayName("present when the property is unset, matching every other storage internal endpoint")
        void presentWhenPropertyMissing() {
            contexts.run(ctx -> assertThat(ctx).hasSingleBean(StorageExceptionHandler.class));
        }
    }
}
