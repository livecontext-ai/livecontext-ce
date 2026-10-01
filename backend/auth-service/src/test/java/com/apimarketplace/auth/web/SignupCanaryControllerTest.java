package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.SignupCanaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignupCanaryController")
class SignupCanaryControllerTest {

    @Mock private SignupCanaryService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SignupCanaryController(service)).build();
    }

    private org.springframework.test.web.servlet.ResultActions call() throws Exception {
        return mockMvc.perform(post("/api/signup-canary/identity")
                .header("X-Signup-Canary-Token", "tok")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"pw\"}"));
    }

    @Test
    @DisplayName("maps each outcome to its status, so the canary can name what failed")
    void mapsOutcomes() throws Exception {
        when(service.createIdentity("tok", "pw")).thenReturn(SignupCanaryService.Result.CREATED);
        call().andExpect(status().isCreated());
        when(service.createIdentity("tok", "pw")).thenReturn(SignupCanaryService.Result.ALREADY_EXISTS);
        call().andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("identity_exists"));
        when(service.createIdentity("tok", "pw")).thenReturn(SignupCanaryService.Result.UNAUTHORIZED);
        call().andExpect(status().isUnauthorized());
        when(service.createIdentity("tok", "pw")).thenReturn(SignupCanaryService.Result.DISABLED);
        call().andExpect(status().isNotFound());
        when(service.createIdentity("tok", "pw")).thenReturn(SignupCanaryService.Result.INVALID_PASSWORD);
        call().andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a Keycloak failure answers 502, never a success")
    void keycloakFailureIs502() throws Exception {
        when(service.createIdentity(any(), any())).thenThrow(new IllegalStateException("kc down"));
        call().andExpect(status().isBadGateway());
    }
}
