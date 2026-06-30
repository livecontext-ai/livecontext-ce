package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSearchCallbackController")
class WebSearchCallbackControllerTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private EventBus eventBus;
    @Mock private InterfaceClient interfaceClient;

    private WebSearchCallbackController controller;

    private static final String STREAM_ID = "stream-1";
    private static final String TOOL_ID = "tool-abc";
    private static final String URL = "https://example.com";
    private static final String SCREENSHOT_KEY = "screenshots/abc.png";
    private static final UUID INTERFACE_ID = UUID.randomUUID();
    private static final String TENANT_ID = "tenant-42";

    @BeforeEach
    void setUp() {
        controller = new WebSearchCallbackController(redisTemplate, eventBus, new ObjectMapper(), interfaceClient);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private Map<String, Object> payload() {
        Map<String, Object> p = new HashMap<>();
        p.put("url", URL);
        p.put("screenshot_key", SCREENSHOT_KEY);
        p.put("screenshot_index", 0);
        p.put("is_final", true);
        return p;
    }

    @Test
    @DisplayName("new format mapping (tenantId|interfaceId) updates interface with real tenantId")
    void newFormatMapping_updatesInterface() {
        when(valueOps.get(WebSearchCallbackController.TOOL_IFACE_PREFIX + TOOL_ID))
                .thenReturn(TENANT_ID + "|" + INTERFACE_ID);
        when(interfaceClient.updateWebSearchScreenshot(eq(INTERFACE_ID), eq(URL), eq(SCREENSHOT_KEY), eq(TENANT_ID)))
                .thenReturn(true);

        ResponseEntity<Void> response = controller.receiveScreenshot(STREAM_ID, TOOL_ID, null, payload());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(interfaceClient).updateWebSearchScreenshot(INTERFACE_ID, URL, SCREENSHOT_KEY, TENANT_ID);
    }

    @Test
    @DisplayName("old format mapping (interfaceId only) is rejected - no interface update")
    void oldFormatMapping_skipsInterfaceUpdate() {
        when(valueOps.get(WebSearchCallbackController.TOOL_IFACE_PREFIX + TOOL_ID))
                .thenReturn(INTERFACE_ID.toString());

        ResponseEntity<Void> response = controller.receiveScreenshot(STREAM_ID, TOOL_ID, null, payload());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(interfaceClient, never()).updateWebSearchScreenshot(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("missing mapping → no interface update, still publishes event")
    void missingMapping_publishesEventOnly() {
        when(valueOps.get(WebSearchCallbackController.TOOL_IFACE_PREFIX + TOOL_ID)).thenReturn(null);

        ResponseEntity<Void> response = controller.receiveScreenshot(STREAM_ID, TOOL_ID, null, payload());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(interfaceClient, never()).updateWebSearchScreenshot(any(), anyString(), anyString(), anyString());
        verify(eventBus, atLeastOnce()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("InterfaceClient returns false → still 200, no exception")
    void interfaceClientFailure_swallowed() {
        when(valueOps.get(WebSearchCallbackController.TOOL_IFACE_PREFIX + TOOL_ID))
                .thenReturn(TENANT_ID + "|" + INTERFACE_ID);
        when(interfaceClient.updateWebSearchScreenshot(any(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        ResponseEntity<Void> response = controller.receiveScreenshot(STREAM_ID, TOOL_ID, null, payload());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("invalid screenshot_key (path traversal) returns 400")
    void invalidScreenshotKey_returnsBadRequest() {
        Map<String, Object> bad = payload();
        bad.put("screenshot_key", "screenshots/../etc/passwd");

        ResponseEntity<Void> response = controller.receiveScreenshot(STREAM_ID, TOOL_ID, null, bad);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(interfaceClient);
    }

    @Test
    @DisplayName("conversationId publishes to ws channel additionally")
    void conversationId_publishesWsChannel() {
        when(valueOps.get(anyString())).thenReturn(null);

        controller.receiveScreenshot(STREAM_ID, TOOL_ID, "conv-1", payload());

        ArgumentCaptor<String> channelCap = ArgumentCaptor.forClass(String.class);
        verify(eventBus, times(2)).publish(channelCap.capture(), anyString());
        assertThat(channelCap.getAllValues()).anyMatch(c -> c.startsWith("ws:conversation:conv-1"));
    }
}
