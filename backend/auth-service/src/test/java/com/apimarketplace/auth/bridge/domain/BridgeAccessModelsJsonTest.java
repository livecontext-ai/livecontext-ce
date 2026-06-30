package com.apimarketplace.auth.bridge.domain;

import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.AccessMode;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.BridgeAccessPolicy;
import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.UpdatePolicyRequest;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeAccessModelsJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void serializesAccessModeUsingApiContractValue() throws Exception {
        BridgeAccessPolicy policy = new BridgeAccessPolicy(
                1L,
                "codex",
                AccessMode.ADMIN_ONLY,
                25,
                Instant.parse("2026-06-05T00:00:00Z"),
                "admin-user"
        );

        String json = mapper.writeValueAsString(policy);

        assertThat(json).contains("\"accessMode\":\"admin_only\"");
        assertThat(json).doesNotContain("ADMIN_ONLY");
    }

    @Test
    void deserializesAccessModeFromApiContractValue() throws Exception {
        UpdatePolicyRequest request = mapper.readValue("""
                {
                  "accessMode": "all_users",
                  "maxRequestsPerUserPerDay": 12
                }
                """, UpdatePolicyRequest.class);

        assertThat(request.accessMode()).isEqualTo(AccessMode.ALL_USERS);
        assertThat(request.maxRequestsPerUserPerDay()).isEqualTo(12);
    }
}
