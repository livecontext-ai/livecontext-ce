package com.apimarketplace.interfaces.integration;

import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.repository.InterfaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression IT for {@code POST /api/internal/interfaces/{id}/touch}: bumps
 * {@code interfaces.updated_at} so the bell's Activity tab surfaces the row on
 * user action fire (the orchestrator-side path calls this endpoint via
 * {@code InterfaceClient.touchUpdatedAt}, async fire-and-forget).
 *
 * <p>Pinned behaviour:
 * <ul>
 *   <li>Touch on an existing id → 204 No Content + {@code updated_at} advances.</li>
 *   <li>Touch on a non-existent id → still 204 (fire-and-forget contract - caller is
 *       async and swallows any error; missing rows must not 404 here).</li>
 * </ul>
 *
 * <p>Without this guard, a future schema change or repo refactor could silently
 * remove the {@code touchUpdatedAt} JPQL or the {@code @PostMapping("/{id}/touch")}
 * route and the Activity tab would freeze on the last config edit timestamp.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class InterfaceTouchEndpointIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InterfaceRepository interfaceRepository;

    private static final String TENANT = "touch-tenant";

    @Test
    void touchEndpoint_advancesUpdatedAt() throws Exception {
        // Arrange: create an interface so we can read its updated_at before/after.
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Touch Target",
                "htmlTemplate", "<div>hi</div>",
                "cssTemplate", "",
                "jsTemplate", ""));

        MvcResult create = mockMvc.perform(post("/api/interfaces")
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        UUID interfaceId = UUID.fromString(
                objectMapper.readTree(create.getResponse().getContentAsString()).get("id").asText());

        Instant before = readUpdatedAt(interfaceId);
        // Sleep so the timestamp delta is observable on fast clocks.
        Thread.sleep(20);

        // Act
        mockMvc.perform(post("/api/internal/interfaces/{id}/touch", interfaceId))
                .andExpect(status().isNoContent());

        // Assert
        Instant after = readUpdatedAt(interfaceId);
        assertThat(after)
                .as("Activity-tab contract: touch endpoint must advance interfaces.updated_at")
                .isAfter(before);
    }

    @Test
    void touchEndpoint_onUnknownId_returns204_andDoesNotThrow() throws Exception {
        UUID phantom = UUID.randomUUID();
        // Fire-and-forget contract: missing rows must not 404 (caller swallows
        // exceptions anyway; throwing here would inflate WARN logs uselessly).
        mockMvc.perform(post("/api/internal/interfaces/{id}/touch", phantom))
                .andExpect(status().isNoContent());

        assertThat(interfaceRepository.findById(phantom))
                .as("phantom id remains absent post-touch")
                .isEmpty();
    }

    private Instant readUpdatedAt(UUID interfaceId) {
        Optional<InterfaceEntity> entity = interfaceRepository.findById(interfaceId);
        assertThat(entity).as("interface row %s exists", interfaceId).isPresent();
        return entity.get().getUpdatedAt();
    }
}
