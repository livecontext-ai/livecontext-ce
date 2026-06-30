package com.apimarketplace.orchestrator.controllers.internal;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.tools.websearch.CdpTokenIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC integration test for
 * {@link BrowserAgentTakeoverController#refreshToken}.
 *
 * <p>The plain {@code BrowserAgentTakeoverControllerTest} drives the
 * controller method directly with mocks - fast and focused. This
 * companion test exercises the SAME endpoint through Spring's MVC
 * dispatcher (standalone MockMvc): HTTP path matching, JSON
 * deserialization of the body, {@code @PathVariable} binding,
 * {@code @RequestHeader} extraction, and JSON response serialization.
 * A regression in any of those wiring layers would slip past a
 * pure-method test.</p>
 *
 * <p>We use standalone MockMvc rather than {@code @WebMvcTest} because
 * the orchestrator's main app class enables JPA repositories at the
 * config level, which forces the test slice to wire an
 * {@code entityManagerFactory} this controller doesn't need. The
 * standalone setup gives us the full dispatcher path-matching + JSON
 * pipeline without booting the rest of the context.</p>
 *
 * <p>{@link CdpTokenIssuer} is the real bean (not a mock) so the JWT
 * actually gets minted against a deterministic test secret - proves
 * the issuer + URL builder produce a token+url pair the frontend can
 * consume on a real reconnect.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserAgentTakeoverController - Spring MVC integration")
class BrowserAgentTakeoverControllerWebMvcTest {

    // Deterministic 64-hex secret so the issued JWT is reproducible
    // (we don't decode it - we just check it's a 3-segment HS256 token).
    private static final String SECRET =
            "4366a5e4d7a50d752468bdb4beb14a29e9810405515fb9ab6bf17729ccce1f64";
    private static final int TTL_SECONDS = 300;

    @Mock
    private UnifiedSignalService signalService;

    @Mock
    private WebSearchConfig webSearchConfig;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Real CdpTokenIssuer - exercises the actual JWT signing path
        // through the Spring dispatcher.
        CdpTokenIssuer issuer = new CdpTokenIssuer(SECRET, TTL_SECONDS);
        BrowserAgentTakeoverController controller = new BrowserAgentTakeoverController(
                signalService, /* webSearchRestTemplate */ null, webSearchConfig, issuer);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /cdp-token-refresh: 200 with cdp_token + cdp_ws_url through the dispatcher")
    void refresh200ThroughDispatcher() throws Exception {
        when(webSearchConfig.getPublicWsBase()).thenReturn("https://websearch-host.test.example.com");

        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(42L);
        entity.setRunId("run_42");
        entity.setNodeId("node_42");
        entity.setSignalType(SignalType.BROWSER_USER_TAKEOVER);
        entity.setEpoch(1);
        entity.setSignalConfig(Map.of("sessionId", "sess_real_42"));
        when(signalService.getActiveSignals("run_42")).thenReturn(List.of(entity));

        MvcResult result = mockMvc.perform(post(
                "/api/internal/browser-agent/runs/run_42/nodes/node_42/cdp-token-refresh")
                        .header("X-User-ID", "user_42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"sess_real_42\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        // Decode the JWT (auth0 lib is already on classpath - same one
        // CdpTokenIssuer signs with) and assert claims explicitly. A
        // structural check (3 segments + length > 80) would still pass
        // if a regression made the controller pass null/empty params
        // to issuer.issue() - only claim-level assertion proves the
        // dispatcher carried (rid, nid, sub, sid) through to the JWT.
        String token = body.get("cdp_token").asText();
        DecodedJWT decoded = JWT.decode(token);
        assertThat(decoded.getClaim("sid").asString()).isEqualTo("sess_real_42");
        assertThat(decoded.getClaim("rid").asString()).isEqualTo("run_42");
        assertThat(decoded.getClaim("nid").asString()).isEqualTo("node_42");
        assertThat(decoded.getClaim("sub").asString()).isEqualTo("user_42");
        // URL built via the shared CdpUrls util - proves WebSearchConfig
        // injection works through the dispatcher.
        assertThat(body.get("cdp_ws_url").asText())
                .isEqualTo("wss://websearch-host.test.example.com/cdp/sess_real_42");
        assertThat(body.get("session_id").asText()).isEqualTo("sess_real_42");
    }

    @Test
    @DisplayName("POST /cdp-token-refresh: 404 when no active takeover signal")
    void refresh404WhenNoSignal() throws Exception {
        when(signalService.getActiveSignals("run_x")).thenReturn(List.of());

        mockMvc.perform(post(
                "/api/internal/browser-agent/runs/run_x/nodes/node_x/cdp-token-refresh")
                        .header("X-User-ID", "user_x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"sess_x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /cdp-token-refresh: 400 when body is missing session_id")
    void refresh400WhenMissingSessionId() throws Exception {
        mockMvc.perform(post(
                "/api/internal/browser-agent/runs/run_y/nodes/node_y/cdp-token-refresh")
                        .header("X-User-ID", "user_y")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /cdp-token-refresh: 400 when stored sessionId mismatches body session_id")
    void refresh400WhenSessionIdMismatch() throws Exception {
        SignalWaitEntity entity = new SignalWaitEntity();
        entity.setId(7L);
        entity.setRunId("run_z");
        entity.setNodeId("node_z");
        entity.setSignalType(SignalType.BROWSER_USER_TAKEOVER);
        entity.setEpoch(1);
        entity.setSignalConfig(Map.of("sessionId", "sess_legit"));
        when(signalService.getActiveSignals("run_z")).thenReturn(List.of(entity));

        mockMvc.perform(post(
                "/api/internal/browser-agent/runs/run_z/nodes/node_z/cdp-token-refresh")
                        .header("X-User-ID", "user_z")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"sess_attacker\"}"))
                .andExpect(status().isBadRequest());
    }
}
