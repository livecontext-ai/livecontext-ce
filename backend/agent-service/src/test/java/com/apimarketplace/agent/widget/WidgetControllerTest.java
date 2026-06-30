package com.apimarketplace.agent.widget;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentWidgetConfigEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.AgentWidgetConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WidgetController Tests")
class WidgetControllerTest {

    @Mock private AgentWidgetConfigService widgetConfigService;
    @Mock private WidgetSessionService sessionService;
    @Mock private AgentRepository agentRepository;
    @Mock private HttpServletRequest request;

    private WidgetController controller;

    private static final String TOKEN = "wgt_test123";
    private static final String SESSION_ID = "sess-abc";
    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        controller = new WidgetController(widgetConfigService, sessionService, agentRepository, "");
        lenient().when(request.getHeader("Origin")).thenReturn("https://example.com");
    }

    private AgentWidgetConfigEntity activeWidget() {
        AgentWidgetConfigEntity w = new AgentWidgetConfigEntity(AGENT_ID);
        w.setWidgetToken(TOKEN);
        w.setIsActive(true);
        w.setAllowedOrigins("*");
        return w;
    }

    private AgentEntity activeAgent() {
        AgentEntity a = new AgentEntity();
        a.setId(AGENT_ID);
        a.setTenantId("tenant-1");
        a.setName("TestBot");
        a.setIsActive(true);
        a.setModelName("claude-sonnet-4-20250514");
        a.setModelProvider("anthropic");
        a.setOrganizationId("org-1");
        return a;
    }

    private WidgetSessionService.WidgetSession validSession() {
        return new WidgetSessionService.WidgetSession(
                SESSION_ID, "conv-1", AGENT_ID, "tenant-1", "org-1",
                "10.0.0.1", "Mozilla", java.time.Instant.now());
    }

    private WidgetSessionService.WidgetSession legacySessionWithoutOrg() {
        return new WidgetSessionService.WidgetSession(
                SESSION_ID, "conv-1", AGENT_ID, "tenant-1", null,
                "10.0.0.1", "Mozilla", java.time.Instant.now());
    }

    // ---------------------------------------------------------------------------
    // chat()
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("chat()")
    class ChatTests {

        @Test
        @DisplayName("should return success with content on successful sync chat")
        void shouldReturnContent() {
            when(widgetConfigService.findActiveByWidgetToken(TOKEN)).thenReturn(Optional.of(activeWidget()));
            when(widgetConfigService.validateOrigin(any(), any())).thenReturn(true);
            when(sessionService.validateSession(SESSION_ID, null)).thenReturn(true);
            when(sessionService.getSession(SESSION_ID)).thenReturn(validSession());
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(activeAgent()));
            when(sessionService.sendMessage(eq("tenant-1"), eq("conv-1"), eq("Hello"), any()))
                    .thenReturn(Map.of("success", true, "content", "Hi there!"));
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getRemoteAddr()).thenReturn(null);

            ResponseEntity<?> response = controller.chat(TOKEN, SESSION_ID, Map.of("message", "Hello"), request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            WidgetResponse body = (WidgetResponse) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.status()).isEqualTo("success");
            assertThat(body.data()).containsEntry("content", "Hi there!");
            assertThat(body.data()).containsEntry("conversationId", "conv-1");
        }

        @Test
        @DisplayName("should return 500 when agent execution fails")
        void shouldReturn500OnFailure() {
            when(widgetConfigService.findActiveByWidgetToken(TOKEN)).thenReturn(Optional.of(activeWidget()));
            when(widgetConfigService.validateOrigin(any(), any())).thenReturn(true);
            when(sessionService.validateSession(SESSION_ID, null)).thenReturn(true);
            when(sessionService.getSession(SESSION_ID)).thenReturn(validSession());
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(activeAgent()));
            when(sessionService.sendMessage(anyString(), anyString(), anyString(), any()))
                    .thenReturn(Map.of("success", false, "error", "Model rate limited"));
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getRemoteAddr()).thenReturn(null);

            ResponseEntity<?> response = controller.chat(TOKEN, SESSION_ID, Map.of("message", "Hello"), request);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            WidgetResponse body = (WidgetResponse) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.status()).isEqualTo("error");
            assertThat(body.message()).isEqualTo("Model rate limited");
        }

        @Test
        @DisplayName("should reject empty message")
        void shouldRejectEmptyMessage() {
            when(widgetConfigService.findActiveByWidgetToken(TOKEN)).thenReturn(Optional.of(activeWidget()));
            when(widgetConfigService.validateOrigin(any(), any())).thenReturn(true);
            when(sessionService.validateSession(SESSION_ID, null)).thenReturn(true);
            when(sessionService.getSession(SESSION_ID)).thenReturn(validSession());
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(activeAgent()));
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getRemoteAddr()).thenReturn(null);

            ResponseEntity<?> response = controller.chat(TOKEN, SESSION_ID, Map.of("message", "  "), request);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("should reject missing session")
        void shouldRejectMissingSession() {
            when(widgetConfigService.findActiveByWidgetToken(TOKEN)).thenReturn(Optional.of(activeWidget()));
            when(widgetConfigService.validateOrigin(any(), any())).thenReturn(true);

            ResponseEntity<?> response = controller.chat(TOKEN, null, Map.of("message", "Hi"), request);

            assertThat(response.getStatusCode().value()).isEqualTo(401);
        }

        @Test
        @DisplayName("should return 404 for unknown token")
        void shouldReturn404ForUnknownToken() {
            when(widgetConfigService.findActiveByWidgetToken("bad_token")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.chat("bad_token", SESSION_ID, Map.of("message", "Hi"), request);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ---------------------------------------------------------------------------
    // getHistory()
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("getHistory()")
    class GetHistoryTests {

        @Test
        @DisplayName("should return wrapped messages list")
        void shouldReturnMessages() {
            when(widgetConfigService.findActiveByWidgetToken(TOKEN)).thenReturn(Optional.of(activeWidget()));
            when(widgetConfigService.validateOrigin(any(), any())).thenReturn(true);
            when(sessionService.validateSession(SESSION_ID, null)).thenReturn(true);
            when(sessionService.getSession(SESSION_ID)).thenReturn(validSession());
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getRemoteAddr()).thenReturn(null);

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", "Hello"),
                    Map.of("role", "assistant", "content", "Hi!")
            );
            when(sessionService.getHistory("tenant-1", "conv-1", "org-1")).thenReturn(messages);

            ResponseEntity<?> response = controller.getHistory(TOKEN, SESSION_ID, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            WidgetResponse body = (WidgetResponse) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.status()).isEqualTo("success");
            assertThat(body.data()).containsKey("messages");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> returned = (List<Map<String, Object>>) body.data().get("messages");
            assertThat(returned).hasSize(2);
        }

        @Test
        @DisplayName("legacy sessions without organizationId use the widget agent organization for history")
        void legacySessionWithoutOrganizationUsesAgentOrganizationForHistory() {
            when(widgetConfigService.findActiveByWidgetToken(TOKEN)).thenReturn(Optional.of(activeWidget()));
            when(widgetConfigService.validateOrigin(any(), any())).thenReturn(true);
            when(sessionService.validateSession(SESSION_ID, null)).thenReturn(true);
            when(sessionService.getSession(SESSION_ID)).thenReturn(legacySessionWithoutOrg());
            when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(activeAgent()));
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getRemoteAddr()).thenReturn(null);
            List<Map<String, Object>> messages = List.of(Map.of("role", "assistant", "content", "Hi"));
            when(sessionService.getHistory("tenant-1", "conv-1", "org-1")).thenReturn(messages);

            ResponseEntity<?> response = controller.getHistory(TOKEN, SESSION_ID, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(sessionService).getHistory("tenant-1", "conv-1", "org-1");
        }

        @Test
        @DisplayName("should reject invalid session")
        void shouldRejectInvalidSession() {
            when(widgetConfigService.findActiveByWidgetToken(TOKEN)).thenReturn(Optional.of(activeWidget()));
            when(widgetConfigService.validateOrigin(any(), any())).thenReturn(true);
            when(sessionService.validateSession(eq("expired"), any())).thenReturn(false);
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getRemoteAddr()).thenReturn(null);

            ResponseEntity<?> response = controller.getHistory(TOKEN, "expired", request);

            assertThat(response.getStatusCode().value()).isEqualTo(401);
        }
    }
}
