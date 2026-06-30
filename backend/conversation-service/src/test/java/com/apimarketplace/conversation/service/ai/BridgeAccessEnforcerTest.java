package com.apimarketplace.conversation.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BridgeAccessEnforcer - CLI bridge access gate")
class BridgeAccessEnforcerTest {

    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
    }

    private BridgeAccessEnforcer enforcer(String authUrl) {
        return new BridgeAccessEnforcer(restTemplate, authUrl);
    }

    @Nested
    @DisplayName("Short-circuit - no HTTP call")
    class ShortCircuit {

        @Test
        @DisplayName("Skips check entirely when provider is not a CLI bridge")
        void nonBridgeProviderSkipsCheck() {
            // Arrange
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act
            e.enforce("u1", "USER", "openai");

            // Assert
            verify(restTemplate, never()).exchange(any(String.class), any(HttpMethod.class), any(), any(Class.class));
        }

        @Test
        @DisplayName("Skips check when auth-service URL is blank - legacy deploy without guard infra")
        void blankAuthUrlSkipsCheck() {
            // Arrange - simulates legacy deploy where services.auth-url is unset
            BridgeAccessEnforcer e = enforcer("");

            // Act
            e.enforce("u1", "USER", "claude-code");

            // Assert - no HTTP call means no accidental 401 from a missing auth-service
            verify(restTemplate, never()).exchange(any(String.class), any(HttpMethod.class), any(), any(Class.class));
        }

        @Test
        @DisplayName("Null provider short-circuits - defensive against unset request provider")
        void nullProviderNoException() {
            // Arrange
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act / Assert - must not NPE, must not call auth-service
            assertThatCode(() -> e.enforce("u1", "USER", null)).doesNotThrowAnyException();
            verify(restTemplate, never()).exchange(any(String.class), any(HttpMethod.class), any(), any(Class.class));
        }
    }

    @Nested
    @DisplayName("Allow path - upstream returns allowed=true")
    class AllowPath {

        @Test
        @DisplayName("Returns without exception when upstream allows")
        void allowedNoException() {
            // Arrange
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("allowed", true, "remainingRequestsToday", 42)));
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act / Assert
            assertThatCode(() -> e.enforce("u1", "USER", "claude-code")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Sends X-User-ID and X-User-Roles headers so auth-service trusts the identity")
        void forwardsUserHeaders() {
            // Arrange
            ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), captor.capture(), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("allowed", true)));
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act
            e.enforce("user-42", "ADMIN,USER", "codex");

            // Assert
            HttpHeaders sent = captor.getValue().getHeaders();
            assertThat(sent.getFirst("X-User-ID")).isEqualTo("user-42");
            assertThat(sent.getFirst("X-User-Roles")).isEqualTo("ADMIN,USER");
        }

        @Test
        @DisplayName("Defaults missing userRoles to USER so auth-service never sees a blank role")
        void defaultsBlankRolesToUser() {
            // Arrange
            ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), captor.capture(), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("allowed", true)));
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act
            e.enforce("u1", null, "gemini-cli");

            // Assert
            assertThat(captor.getValue().getHeaders().getFirst("X-User-Roles")).isEqualTo("USER");
        }

        @Test
        @DisplayName("Lowercases provider name in the bridge query param")
        void lowercasesProviderInQuery() {
            // Arrange - matches auth-service's lowercase bridge_policies.bridge column
            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            when(restTemplate.exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("allowed", true)));
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act
            e.enforce("u1", "USER", "Claude-Code");

            // Assert
            assertThat(urlCaptor.getValue()).contains("bridge=claude-code");
        }

        @Test
        @DisplayName("Sends incrementUsage=true so a successful dispatch counts against today's quota")
        void sendsIncrementUsageTrue() {
            // Arrange - counter side-effect must fire on real dispatches, pre-flight
            // filters use a separate check() endpoint we don't call here.
            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            when(restTemplate.exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("allowed", true)));
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act
            e.enforce("u1", "USER", "claude-code");

            // Assert
            assertThat(urlCaptor.getValue()).contains("incrementUsage=true");
        }
    }

    @Nested
    @DisplayName("Deny path - upstream returns allowed=false")
    class DenyPath {

        @Test
        @DisplayName("Throws BridgeAccessDeniedException with the upstream reason")
        void deniedThrowsWithReason() {
            // Arrange
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of(
                            "allowed", false,
                            "reason", "not_in_allowlist",
                            "remainingRequestsToday", 0)));
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act / Assert
            assertThatThrownBy(() -> e.enforce("u1", "USER", "claude-code"))
                    .isInstanceOf(BridgeAccessDeniedException.class)
                    .satisfies(ex -> {
                        BridgeAccessDeniedException d = (BridgeAccessDeniedException) ex;
                        assertThat(d.getProviderName()).isEqualTo("claude-code");
                        assertThat(d.getReason()).isEqualTo("not_in_allowlist");
                        assertThat(d.getRemainingRequestsToday()).isEqualTo(0);
                        assertThat(d.isQuotaExhausted()).isFalse();
                    });
        }

        @Test
        @DisplayName("isQuotaExhausted() reports true when reason=daily_quota_exhausted so handler returns 429")
        void quotaExhaustedFlaggedSeparately() {
            // Arrange - GlobalExceptionHandler branches on this flag for 429 vs 403
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of(
                            "allowed", false,
                            "reason", "daily_quota_exhausted")));
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act / Assert
            assertThatThrownBy(() -> e.enforce("u1", "USER", "codex"))
                    .isInstanceOf(BridgeAccessDeniedException.class)
                    .satisfies(ex -> assertThat(((BridgeAccessDeniedException) ex).isQuotaExhausted()).isTrue());
        }
    }

    @Nested
    @DisplayName("Fail-CLOSED - transport or body errors")
    class FailClosed {

        @Test
        @DisplayName("RestClientException → denial reason=guard_unavailable (must NOT silently allow)")
        void transportErrorFailsClosed() {
            // Arrange - protects the shared admin subscription when auth-service is down
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new RestClientException("connection refused"));
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act / Assert
            assertThatThrownBy(() -> e.enforce("u1", "USER", "claude-code"))
                    .isInstanceOf(BridgeAccessDeniedException.class)
                    .satisfies(ex -> assertThat(((BridgeAccessDeniedException) ex).getReason())
                            .isEqualTo("guard_unavailable"));
        }

        @Test
        @DisplayName("Empty response body → denial reason=guard_unavailable (defensive against edge proxies)")
        void emptyBodyFailsClosed() {
            // Arrange
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(null));
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act / Assert
            assertThatThrownBy(() -> e.enforce("u1", "USER", "gemini-cli"))
                    .isInstanceOf(BridgeAccessDeniedException.class)
                    .satisfies(ex -> assertThat(((BridgeAccessDeniedException) ex).getReason())
                            .isEqualTo("guard_unavailable"));
        }

        @Test
        @DisplayName("Missing reason field in deny response → falls back to guard_unavailable")
        void missingReasonFallsBack() {
            // Arrange - auth-service always sends a reason on deny, but defensive fallback
            // prevents NPE if the contract ever drifts
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("allowed", false)));
            BridgeAccessEnforcer e = enforcer("http://auth");

            // Act / Assert
            assertThatThrownBy(() -> e.enforce("u1", "USER", "mistral-vibe"))
                    .isInstanceOf(BridgeAccessDeniedException.class)
                    .satisfies(ex -> assertThat(((BridgeAccessDeniedException) ex).getReason())
                            .isEqualTo("guard_unavailable"));
        }
    }

    @Nested
    @DisplayName("isBridgeProvider static helper")
    class IsBridgeHelper {

        @Test
        @DisplayName("Returns true for each of the 4 supported CLI bridges")
        void allFourBridges() {
            assertThat(BridgeAccessEnforcer.isBridgeProvider("claude-code")).isTrue();
            assertThat(BridgeAccessEnforcer.isBridgeProvider("codex")).isTrue();
            assertThat(BridgeAccessEnforcer.isBridgeProvider("gemini-cli")).isTrue();
            assertThat(BridgeAccessEnforcer.isBridgeProvider("mistral-vibe")).isTrue();
        }

        @Test
        @DisplayName("Returns false for API providers - openai, anthropic, google, mistral, deepseek")
        void apiProvidersNotBridges() {
            assertThat(BridgeAccessEnforcer.isBridgeProvider("openai")).isFalse();
            assertThat(BridgeAccessEnforcer.isBridgeProvider("anthropic")).isFalse();
            assertThat(BridgeAccessEnforcer.isBridgeProvider("google")).isFalse();
            assertThat(BridgeAccessEnforcer.isBridgeProvider("mistral")).isFalse();
            assertThat(BridgeAccessEnforcer.isBridgeProvider("deepseek")).isFalse();
        }

        @Test
        @DisplayName("Case-insensitive - matches Claude-Code / CODEX / Gemini-CLI")
        void caseInsensitive() {
            assertThat(BridgeAccessEnforcer.isBridgeProvider("Claude-Code")).isTrue();
            assertThat(BridgeAccessEnforcer.isBridgeProvider("CODEX")).isTrue();
            assertThat(BridgeAccessEnforcer.isBridgeProvider("Gemini-CLI")).isTrue();
        }

        @Test
        @DisplayName("Null / blank provider → false")
        void nullSafe() {
            assertThat(BridgeAccessEnforcer.isBridgeProvider(null)).isFalse();
        }
    }
}
