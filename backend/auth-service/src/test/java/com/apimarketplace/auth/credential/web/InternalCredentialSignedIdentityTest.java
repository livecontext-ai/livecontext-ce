package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CASA LC-008: user-scoped internal credential endpoints authorize on the signed
 * {@code X-User-ID} header, never on the unsigned {@code userId} parameter or body field.
 */
@DisplayName("Internal credential endpoints bind to the signed caller identity (LC-008)")
class InternalCredentialSignedIdentityTest {

    private CredentialRepository repository;
    private InternalCredentialService internalService;
    private CredentialService userCredentialService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(CredentialRepository.class);
        internalService = mock(InternalCredentialService.class);
        InternalCredentialController controller = new InternalCredentialController(
                internalService, userCredentialService = mock(CredentialService.class), null, null, null, null);
        mvc = MockMvcBuilders.standaloneSetup(
                new InternalCredentialLookupController(repository), controller).build();
    }

    @Test
    @DisplayName("/all for another user than the signed one is refused, and nothing is read")
    void allForAnotherUserRefused() throws Exception {
        mvc.perform(get("/api/internal/credentials/all").queryParam("userId", "victim")
                        .header("X-User-ID", "attacker"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("/all without X-User-ID is refused")
    void allWithoutSignedIdentityRefused() throws Exception {
        mvc.perform(get("/api/internal/credentials/all").queryParam("userId", "victim"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("/all for the signed user still works (the CredentialClient shape)")
    void allForSignedUserAllowed() throws Exception {
        when(repository.findAllByTenantId("42")).thenReturn(List.of());
        mvc.perform(get("/api/internal/credentials/all").queryParam("userId", "42")
                        .header("X-User-ID", "42"))
                .andExpect(status().isOk());
        verify(repository).findAllByTenantId("42");
    }

    @Test
    @DisplayName("/access-token for another user is refused before any token is decrypted")
    void accessTokenForAnotherUserRefused() throws Exception {
        mvc.perform(get("/api/internal/credentials/access-token").queryParam("userId", "victim")
                        .queryParam("name", "gmail").header("X-User-ID", "attacker"))
                .andExpect(status().isForbidden());
        verify(internalService, never()).getAccessTokenInfo(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("/refresh-token with a body userId that differs from the signed header is refused")
    void refreshTokenBodyUserRefused() throws Exception {
        mvc.perform(post("/api/internal/credentials/refresh-token")
                        .header("X-User-ID", "attacker")
                        .contentType("application/json")
                        .content("{\"userId\":\"victim\",\"credentialName\":\"gmail\"}"))
                .andExpect(status().isForbidden());
        verify(internalService, never()).refreshAccessToken(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("/default for the signed user reaches the repository with that user")
    void defaultForSignedUser() throws Exception {
        when(repository.findDefaultByTenantIdAndIntegration("42", "gmail")).thenReturn(Optional.empty());
        mvc.perform(get("/api/internal/credentials/default").queryParam("userId", "42")
                        .queryParam("integration", "gmail").header("X-User-ID", "42"))
                .andExpect(status().isNotFound());
        verify(repository).findDefaultByTenantIdAndIntegration("42", "gmail");
    }

    /** Every user-scoped endpoint bound to the signed identity (15), victim named by the caller. */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> boundEndpoints() {
        String base = "/api/internal/credentials";
        String body = "{\"userId\":\"victim\",\"credentialName\":\"gmail\",\"credentialId\":1}";
        return java.util.stream.Stream.of(
                get(base + "/1").queryParam("userId", "victim"),
                get(base + "/default").queryParam("userId", "victim").queryParam("integration", "gmail"),
                get(base + "/all").queryParam("userId", "victim"),
                get(base + "/identities").queryParam("userId", "victim"),
                get(base + "/configured-integrations/victim"),
                get(base + "/state-version").queryParam("userId", "victim"),
                get(base + "/access-token").queryParam("userId", "victim").queryParam("name", "gmail"),
                get(base + "/access-token/by-id").queryParam("userId", "victim").queryParam("credentialId", "1"),
                post(base + "/refresh-token").contentType("application/json").content(body),
                post(base + "/force-refresh-token").contentType("application/json").content(body),
                post(base + "/force-refresh-token/by-id").contentType("application/json").content(body),
                get(base + "/data-map").queryParam("userId", "victim").queryParam("name", "gmail"),
                get(base + "/data-map/by-id").queryParam("userId", "victim").queryParam("credentialId", "1"),
                get(base + "/scopes").queryParam("userId", "victim").queryParam("name", "gmail"),
                get(base + "/scopes/by-id").queryParam("userId", "victim").queryParam("credentialId", "1"))
                .map(org.junit.jupiter.params.provider.Arguments::of);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("boundEndpoints")
    @DisplayName("all 15 bound endpoints refuse a caller asking for another user, and read nothing")
    void everyBoundEndpointRefusesAnotherUser(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.header("X-User-ID", "attacker")).andExpect(status().isForbidden());
        verifyNoInteractions(repository, internalService, userCredentialService);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("boundEndpoints")
    @DisplayName("all 15 bound endpoints refuse a call with no X-User-ID at all")
    void everyBoundEndpointRequiresSignedIdentity(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(repository, internalService, userCredentialService);
    }
}
