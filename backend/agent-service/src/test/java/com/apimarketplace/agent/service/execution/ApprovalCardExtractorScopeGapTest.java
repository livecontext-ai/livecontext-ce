package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hop where a service entry is rebuilt, and therefore where a field disappears.
 *
 * <p>{@code toApprovalInfos} does not forward the producer's map, it constructs a new one key
 * by key. Anything the producer sets and this method does not name is dropped between the tool
 * result and the browser, with no error and no log line, and the card then renders whatever it
 * can from what survived. For a scope gap that meant "connect this service" over an account
 * that was already connected.
 *
 * <p>Both suites either side of this seam passed while it was broken: the backend one asserted
 * the fields were set on the tool result, the frontend one hand-built the props. This is the
 * test that sits ON the seam.
 */
class ApprovalCardExtractorScopeGapTest {

    private static final String READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String SEND = "https://www.googleapis.com/auth/gmail.send";

    private final ApprovalCardExtractor extractor = new ApprovalCardExtractor(new ObjectMapper());

    private static ToolResult resultWithService(Map<String, Object> service, boolean needsAttention) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("serviceApprovalRequested", true);
        metadata.put("services", List.of(service));
        metadata.put("reason", "read the inbox");
        if (needsAttention) {
            metadata.put("needsAttention", true);
        }
        return ToolResult.builder().success(true).metadata(metadata).build();
    }

    private static Map<String, Object> gmailShortOfReadonly() {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("serviceType", "gmail");
        service.put("serviceName", "Gmail");
        service.put("iconSlug", "gmail");
        service.put("toolName", "List Messages");
        service.put("requiredScopes", List.of(READONLY));
        service.put("grantedScopes", List.of(SEND));
        service.put("missingScopes", List.of(READONLY));
        service.put("credentialType", "OAuth2");
        return service;
    }

    @Test
    @DisplayName("the scope gap survives the rebuild and reaches the card")
    void scopeFieldsAreCarriedOver() {
        Optional<ApprovalCardExtractor.ApprovalCard> card =
                extractor.extract(resultWithService(gmailShortOfReadonly(), true));

        assertThat(card).isPresent();
        Map<String, Object> info = card.get().services().get(0);
        assertThat(info.get("requiredScopes")).isEqualTo(List.of(READONLY));
        assertThat(info.get("grantedScopes")).isEqualTo(List.of(SEND));
        assertThat(info.get("missingScopes")).isEqualTo(List.of(READONLY));
        assertThat(info.get("credentialType")).isEqualTo("OAuth2");
    }

    @Test
    @DisplayName("the fields it always carried are unchanged")
    void ordinaryFieldsAreUntouched() {
        Map<String, Object> info = extractor.extract(resultWithService(gmailShortOfReadonly(), true))
                .orElseThrow().services().get(0);

        assertThat(info).containsEntry("serviceType", "gmail")
                .containsEntry("serviceName", "Gmail")
                .containsEntry("iconSlug", "gmail")
                .containsEntry("toolName", "List Messages");
    }

    /**
     * Absent must stay absent. A `requiredScopes: null` on every ordinary connect request would
     * be indistinguishable from an empty gap to a reader that checks for the key rather than
     * its contents, and the card's whole branch turns on that key being there.
     */
    @Test
    @DisplayName("an ordinary connect request gains no scope keys at all")
    void ordinaryRequestIsUnchanged() {
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("serviceType", "slack");
        plain.put("serviceName", "Slack");
        plain.put("iconSlug", "slack");

        Map<String, Object> info = extractor.extract(resultWithService(plain, false))
                .orElseThrow().services().get(0);

        assertThat(info).doesNotContainKeys(
                "requiredScopes", "grantedScopes", "missingScopes", "credentialType");
    }

    /**
     * The card auto-approves and dismisses itself when every service it names already has a
     * credential and needsAttention is false. A scope gap is exactly that shape, so the flag is
     * what keeps the card on screen at all.
     */
    @Test
    @DisplayName("needsAttention rides through, and it is what keeps the card from self-dismissing")
    void needsAttentionIsCarried() {
        assertThat(extractor.extract(resultWithService(gmailShortOfReadonly(), true))
                .orElseThrow().needsAttention()).isTrue();
        assertThat(extractor.extract(resultWithService(gmailShortOfReadonly(), false))
                .orElseThrow().needsAttention()).isFalse();
    }
}
