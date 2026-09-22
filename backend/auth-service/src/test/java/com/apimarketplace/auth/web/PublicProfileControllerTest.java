package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.service.UserService;
import com.apimarketplace.auth.service.VerifiedAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicProfileController")
class PublicProfileControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private VerifiedAccountService verifiedAccountService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new PublicProfileController(userService, verifiedAccountService)).build();
    }

    private User user() {
        User u = new User();
        u.setId(7L);
        u.setUsername("alice");
        u.setEnabled(true);
        return u;
    }

    private PublicProfileDto sampleProfile() {
        return new PublicProfileDto(7L, "Alice A.", "alice_a", "/api/users/7/avatar",
                "Builder", LocalDateTime.of(2024, 3, 1, 0, 0), false, false);
    }

    @Test
    @DisplayName("GET /by-id/{userId} → 200 with display name + public @handle; never the raw username, email or roles")
    void byIdReturnsProfile() throws Exception {
        User u = user();
        when(userService.findById(7L)).thenReturn(Optional.of(u));
        when(userService.getPublicProfile(u)).thenReturn(Optional.of(sampleProfile()));

        mockMvc.perform(get("/api/users/public/by-id/7").header("X-User-ID", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.displayName").value("Alice A."))
                .andExpect(jsonPath("$.handle").value("alice_a"))
                .andExpect(jsonPath("$.bio").value("Builder"))
                // Privacy: the raw OAuth account username (can be the real name), website, social
                // links, email and roles must never appear - only the chosen @handle is public.
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.websiteUrl").doesNotExist())
                .andExpect(jsonPath("$.socialLinks").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist());
    }

    @Test
    @DisplayName("GET /by-handle/{handle} → 200; resolves by the public @handle, not the numeric id")
    void byHandleReturnsProfile() throws Exception {
        User u = user();
        when(userService.findByHandle("alice_a")).thenReturn(Optional.of(u));
        when(userService.getPublicProfile(u)).thenReturn(Optional.of(sampleProfile()));

        mockMvc.perform(get("/api/users/public/by-handle/alice_a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle").value("alice_a"))
                .andExpect(jsonPath("$.displayName").value("Alice A."))
                .andExpect(jsonPath("$.username").doesNotExist());
    }

    @Test
    @DisplayName("GET /by-handle/{handle} → 404 when no profile has that handle")
    void byHandleUnknownReturns404() throws Exception {
        when(userService.findByHandle("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/public/by-handle/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /by-id/{userId} → 404 when the user does not exist")
    void byIdUnknownReturns404() throws Exception {
        when(userService.findById(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/public/by-id/404").header("X-User-ID", "42"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /by-id/{userId} → 404 when the profile is PRIVATE (service returns empty)")
    void byIdPrivateReturns404() throws Exception {
        User u = user();
        when(userService.findById(7L)).thenReturn(Optional.of(u));
        when(userService.getPublicProfile(u)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/public/by-id/7").header("X-User-ID", "42"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /by-id/{userId} → 404 for an ANONYMOUS caller, and the profile is never looked up")
    void byIdAnonymousIsRefused() throws Exception {
        // Found by e2e against a live CE stack: by-id answered 200 with a full
        // profile to an unauthenticated caller. CE has no gateway, so the cloud
        // allowlist (which exposes only /by-handle) never applied there, and the
        // monolith filter passes credential-less requests straight through for
        // "the controller layer to decide". The id is sequential, so that let
        // anyone walk 1..N and harvest every display name and handle.
        mockMvc.perform(get("/api/users/public/by-id/7"))
                .andExpect(status().isNotFound());

        // 404 rather than 401 keeps it indistinguishable from a missing or
        // private profile, so it cannot be used to probe which ids exist.
        org.mockito.Mockito.verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("GET /by-id/{userId} → 404 when the caller header is present but blank")
    void byIdBlankRequesterIsRefused() throws Exception {
        mockMvc.perform(get("/api/users/public/by-id/7").header("X-User-ID", "   "))
                .andExpect(status().isNotFound());

        org.mockito.Mockito.verifyNoInteractions(userService);
    }

    // ---------------------- verified badges (id-keyed, authenticated) ----------------------

    @Test
    @DisplayName("GET /verified-badges → only the ids that carry the badge, in one answer")
    void verifiedBadgesReturnsTheVerifiedSubset() throws Exception {
        when(verifiedAccountService.verifiedAmong(java.util.List.of(1L, 2L, 3L)))
                .thenReturn(java.util.Set.of(2L));

        mockMvc.perform(get("/api/users/public/verified-badges?ids=1,2,3").header("X-User-ID", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified.length()").value(1))
                .andExpect(jsonPath("$.verified[0]").value(2));
    }

    @Test
    @DisplayName("GET /verified-badges → empty for an ANONYMOUS caller, and nothing is looked up")
    void verifiedBadgesAnonymousAnswersEmpty() throws Exception {
        // Same enumeration risk as /by-id: the ids are sequential, so an anonymous
        // version would let anyone page out every verified account. Answering an empty
        // list rather than 401 keeps a badge lookup from ever failing a page.
        mockMvc.perform(get("/api/users/public/verified-badges?ids=1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified.length()").value(0));

        org.mockito.Mockito.verifyNoInteractions(verifiedAccountService);
    }

    @Test
    @DisplayName("GET /verified-badges → 400 above the batch cap, so one request cannot become an unbounded IN (...)")
    void verifiedBadgesRejectsOversizedBatch() throws Exception {
        String ids = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(get("/api/users/public/verified-badges?ids=" + ids).header("X-User-ID", "42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("too_many_ids"));

        org.mockito.Mockito.verifyNoInteractions(verifiedAccountService);
    }

    @Test
    @DisplayName("GET /verified-badges → a non-numeric id is dropped, not fatal to the rest of the page")
    void verifiedBadgesDropsJunkIds() throws Exception {
        when(verifiedAccountService.verifiedAmong(java.util.List.of(5L))).thenReturn(java.util.Set.of(5L));

        mockMvc.perform(get("/api/users/public/verified-badges?ids=5,abc").header("X-User-ID", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified[0]").value(5));
    }

    @Test
    @DisplayName("GET /verified-badges → no ids at all is an empty answer, not an error")
    void verifiedBadgesWithoutIdsIsEmpty() throws Exception {
        when(verifiedAccountService.verifiedAmong(java.util.List.of())).thenReturn(java.util.Set.of());

        mockMvc.perform(get("/api/users/public/verified-badges").header("X-User-ID", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified.length()").value(0));
    }

    // ---------------------- verified badges (handle-keyed, anonymous) ----------------------

    @Test
    @DisplayName("GET /verified-handles → answers an ANONYMOUS caller: handles are not enumerable")
    void verifiedHandlesIsAnonymouslyReadable() throws Exception {
        when(verifiedAccountService.verifiedHandlesAmong(java.util.List.of("ada", "linus")))
                .thenReturn(java.util.Set.of("ada"));

        mockMvc.perform(get("/api/users/public/verified-handles?handles=ada,linus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified.length()").value(1))
                .andExpect(jsonPath("$.verified[0]").value("ada"));
    }

    @Test
    @DisplayName("GET /verified-handles → 400 above the batch cap")
    void verifiedHandlesRejectsOversizedBatch() throws Exception {
        String handles = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(i -> "user" + i)
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(get("/api/users/public/verified-handles?handles=" + handles))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("too_many_handles"));

        org.mockito.Mockito.verifyNoInteractions(verifiedAccountService);
    }

    @Test
    @DisplayName("GET /verified-handles → blank entries are dropped before the lookup")
    void verifiedHandlesDropsBlanks() throws Exception {
        when(verifiedAccountService.verifiedHandlesAmong(java.util.List.of("ada")))
                .thenReturn(java.util.Set.of());

        // .param() carries the DECODED value, which is what a real request delivers -
        // spelling it inside the URL would test MockMvc's lack of percent-decoding.
        mockMvc.perform(get("/api/users/public/verified-handles").param("handles", "ada,,   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified.length()").value(0));
    }
}
