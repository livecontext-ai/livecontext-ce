package com.apimarketplace.orchestrator.services.approvalchannel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApprovalDelegationConfig#fromSignalConfig(Map)}: the parse
 * of the resolved delegation block back out of a USER_APPROVAL signal_config,
 * including the optional {@code image} value (FileRef Map or String URL) added for
 * photo delegation.
 */
@DisplayName("ApprovalDelegationConfig")
class ApprovalDelegationConfigTest {

    private Map<String, Object> signalConfigWithDelegation(Map<String, Object> delegation) {
        Map<String, Object> config = new HashMap<>();
        config.put("type", "USER_APPROVAL");
        config.put("delegation", delegation);
        return config;
    }

    @Nested
    @DisplayName("fromSignalConfig() - image")
    class FromSignalConfigImage {

        @Test
        @DisplayName("parses a FileRef Map image AS-IS (the shape the catalog photo upload consumes)")
        void parsesFileRefMapImageAsIs() {
            Map<String, Object> fileRef = Map.of(
                    "_type", "file",
                    "path", "tenant-1/wf/run/screenshot.png",
                    "name", "screenshot.png",
                    "mimeType", "image/png");
            Map<String, Object> delegation = new HashMap<>();
            delegation.put("channel", "telegram");
            delegation.put("chatId", "123456");
            delegation.put("image", fileRef);

            ApprovalDelegationConfig config =
                    ApprovalDelegationConfig.fromSignalConfig(signalConfigWithDelegation(delegation));

            assertThat(config).isNotNull();
            assertThat(config.image()).isSameAs(fileRef);
        }

        @Test
        @DisplayName("parses a String URL image verbatim")
        void parsesStringUrlImage() {
            Map<String, Object> delegation = new HashMap<>();
            delegation.put("channel", "telegram");
            delegation.put("chatId", "123456");
            delegation.put("image", "https://example.com/img.png");

            ApprovalDelegationConfig config =
                    ApprovalDelegationConfig.fromSignalConfig(signalConfigWithDelegation(delegation));

            assertThat(config).isNotNull();
            assertThat(config.image()).isEqualTo("https://example.com/img.png");
        }

        @Test
        @DisplayName("regression: absent image -> null (plain text delegation, pre-image blocks keep parsing)")
        void absentImageIsNull() {
            Map<String, Object> delegation = new HashMap<>();
            delegation.put("channel", "telegram");
            delegation.put("credentialId", 42);
            delegation.put("chatId", "123456");
            delegation.put("message", "Please approve");

            ApprovalDelegationConfig config =
                    ApprovalDelegationConfig.fromSignalConfig(signalConfigWithDelegation(delegation));

            assertThat(config).isNotNull();
            assertThat(config.image()).isNull();
            assertThat(config.channel()).isEqualTo("telegram");
            assertThat(config.credentialId()).isEqualTo(42L);
            assertThat(config.chatId()).isEqualTo("123456");
            assertThat(config.message()).isEqualTo("Please approve");
        }

        @Test
        @DisplayName("a blank-string image is normalised to null (as good as no image)")
        void blankStringImageIsNormalisedToNull() {
            Map<String, Object> delegation = new HashMap<>();
            delegation.put("channel", "telegram");
            delegation.put("chatId", "123456");
            delegation.put("image", "   ");

            ApprovalDelegationConfig config =
                    ApprovalDelegationConfig.fromSignalConfig(signalConfigWithDelegation(delegation));

            assertThat(config).isNotNull();
            assertThat(config.image()).isNull();
        }
    }

    @Nested
    @DisplayName("fromSignalConfig() - block guards")
    class FromSignalConfigGuards {

        @Test
        @DisplayName("no delegation block -> null (the common non-delegated approval)")
        void noDelegationBlockIsNull() {
            Map<String, Object> config = new HashMap<>();
            config.put("type", "USER_APPROVAL");

            assertThat(ApprovalDelegationConfig.fromSignalConfig(config)).isNull();
        }

        @Test
        @DisplayName("a delegation block without a channel -> null (never written by the node)")
        void channelLessBlockIsNull() {
            Map<String, Object> delegation = new HashMap<>();
            delegation.put("chatId", "123456");
            delegation.put("image", "https://example.com/img.png");

            assertThat(ApprovalDelegationConfig.fromSignalConfig(
                    signalConfigWithDelegation(delegation))).isNull();
        }

        @Test
        @DisplayName("allowedUserIds still parse alongside an image")
        void allowedUserIdsParseAlongsideImage() {
            Map<String, Object> delegation = new HashMap<>();
            delegation.put("channel", "telegram");
            delegation.put("chatId", "123456");
            delegation.put("image", "https://example.com/img.png");
            delegation.put("allowedUserIds", List.of("777", "888"));

            ApprovalDelegationConfig config =
                    ApprovalDelegationConfig.fromSignalConfig(signalConfigWithDelegation(delegation));

            assertThat(config).isNotNull();
            assertThat(config.allowedUserIds()).containsExactly("777", "888");
        }
    }

    @Nested
    @DisplayName("fromSignalConfig() - custom button labels")
    class FromSignalConfigButtonLabels {

        @Test
        @DisplayName("parses custom approveLabel and rejectLabel when present")
        void parsesCustomButtonLabels() {
            Map<String, Object> delegation = new HashMap<>();
            delegation.put("channel", "telegram");
            delegation.put("chatId", "123456");
            delegation.put("approveLabel", "👍 Ship it");
            delegation.put("rejectLabel", "👎 Hold");

            ApprovalDelegationConfig config =
                    ApprovalDelegationConfig.fromSignalConfig(signalConfigWithDelegation(delegation));

            assertThat(config).isNotNull();
            assertThat(config.approveLabel()).isEqualTo("👍 Ship it");
            assertThat(config.rejectLabel()).isEqualTo("👎 Hold");
        }

        @Test
        @DisplayName("regression: absent labels -> null (the notifier keeps its channel defaults)")
        void absentLabelsAreNull() {
            Map<String, Object> delegation = new HashMap<>();
            delegation.put("channel", "telegram");
            delegation.put("chatId", "123456");

            ApprovalDelegationConfig config =
                    ApprovalDelegationConfig.fromSignalConfig(signalConfigWithDelegation(delegation));

            assertThat(config).isNotNull();
            assertThat(config.approveLabel()).isNull();
            assertThat(config.rejectLabel()).isNull();
        }
    }
}
