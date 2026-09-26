package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.MarketingConsentResponse;
import com.apimarketplace.auth.dto.ProfileContextRequest;
import com.apimarketplace.auth.lifecycle.UserLifecycleContextService;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController - lifecycle context and marketing consent endpoints")
class UserControllerLifecycleContextTest {

    @Mock private UserService userService;
    @Mock private OnboardingService onboardingService;
    @Mock private UserLifecycleContextService lifecycleContextService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "onboardingService", onboardingService);
        ReflectionTestUtils.setField(controller, "lifecycleContextService", lifecycleContextService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void userExists(long id) {
        User user = new User();
        user.setId(id);
        when(userService.findById(id)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("PUT /profile/context hands the body and both Cloudflare headers to the service, 204")
    void contextForwardsBodyAndHeaders() throws Exception {
        userExists(7L);

        mvc.perform(put("/api/users/profile/context")
                        .header("X-User-ID", "7")
                        .header("CF-IPCountry", "FR")
                        .header("CF-Connecting-IP", "203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"fr\",\"localeExplicit\":true,\"timeZone\":\"Europe/Paris\","
                                + "\"acquisition\":{\"utmSource\":\"google\",\"landingPath\":\"/fr\"},\"unknown\":1}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<ProfileContextRequest> body = ArgumentCaptor.forClass(ProfileContextRequest.class);
        verify(lifecycleContextService).updateContext(eq(7L), body.capture(), eq("FR"), eq("203.0.113.7"));
        assertThat(body.getValue().locale()).isEqualTo("fr");
        assertThat(body.getValue().localeExplicit()).isTrue();
        assertThat(body.getValue().timeZone()).isEqualTo("Europe/Paris");
        assertThat(body.getValue().acquisition().utmSource()).isEqualTo("google");
    }

    @Test
    @DisplayName("PUT /profile/context for an unknown user is 404 and writes nothing")
    void contextUnknownUser() throws Exception {
        when(userService.findById(9L)).thenReturn(Optional.empty());

        mvc.perform(put("/api/users/profile/context").header("X-User-ID", "9")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        verify(lifecycleContextService, never()).updateContext(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /profile/marketing-consent returns consent and updatedAt")
    void getConsent() throws Exception {
        userExists(7L);
        when(lifecycleContextService.getMarketingConsent(7L))
                .thenReturn(Optional.of(new MarketingConsentResponse(true, Instant.parse("2026-09-24T10:00:00Z"))));

        mvc.perform(get("/api/users/profile/marketing-consent").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consent").value(true))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    @DisplayName("PUT /profile/marketing-consent stores the choice, 204")
    void setConsent() throws Exception {
        userExists(7L);
        when(lifecycleContextService.setMarketingConsent(7L, false)).thenReturn(true);

        mvc.perform(put("/api/users/profile/marketing-consent").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consent\":false}"))
                .andExpect(status().isNoContent());

        verify(lifecycleContextService).setMarketingConsent(7L, false);
    }

    @Test
    @DisplayName("PUT /profile/marketing-consent without a boolean is 400")
    void setConsentWithoutValue() throws Exception {
        mvc.perform(put("/api/users/profile/marketing-consent").header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(lifecycleContextService, never()).setMarketingConsent(anyLong(), anyBoolean());
    }
}
