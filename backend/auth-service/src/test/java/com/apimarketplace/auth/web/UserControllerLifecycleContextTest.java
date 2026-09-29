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
                                + "\"timeZoneExplicit\":true,\"timeZoneFollowsDevice\":true,"
                                + "\"acquisition\":{\"utmSource\":\"google\",\"landingPath\":\"/fr\"},\"unknown\":1}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<ProfileContextRequest> body = ArgumentCaptor.forClass(ProfileContextRequest.class);
        verify(lifecycleContextService).updateContext(eq(7L), body.capture(), eq("FR"), eq("203.0.113.7"));
        assertThat(body.getValue().locale()).isEqualTo("fr");
        assertThat(body.getValue().localeExplicit()).isTrue();
        assertThat(body.getValue().timeZone()).isEqualTo("Europe/Paris");
        assertThat(body.getValue().acquisition().utmSource()).isEqualTo("google");

        // The two flags that SELECT which of the three time-zone writes runs: pin, release, or
        // leave a browser report in charge. This is the only test in the module that binds this
        // record from real JSON - every other one constructs it positionally in Java, which
        // bypasses Jackson entirely. So nothing else can see a renamed field, a stray
        // @JsonProperty, or a build that loses record parameter names: the endpoint would go on
        // answering 204 while silently ignoring both flags, and the symptom is the pre-V540 bug
        // the migration exists to fix - a browser report quietly un-pinning a chosen zone.
        assertThat(body.getValue().timeZoneExplicit()).isTrue();
        assertThat(body.getValue().timeZoneFollowsDevice()).isTrue();
    }

    @Test
    @DisplayName("PUT /profile/context binds the two time-zone flags as FALSE when the body says so, "
            + "rather than reading a missing field as either")
    void contextBindsTimeZoneFlagsAsFalse() throws Exception {
        // false and absent are different answers here, and they pick different writes: false means
        // "the browser is reporting this zone, do not overwrite a pick", absent means the same by
        // default. A Boolean that bound as null when the body said false would send an implicit
        // report down the explicit path and pin a zone nobody chose.
        userExists(7L);

        mvc.perform(put("/api/users/profile/context")
                        .header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeZone\":\"Asia/Tokyo\",\"timeZoneExplicit\":false,"
                                + "\"timeZoneFollowsDevice\":false}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<ProfileContextRequest> body = ArgumentCaptor.forClass(ProfileContextRequest.class);
        verify(lifecycleContextService).updateContext(eq(7L), body.capture(), any(), any());
        assertThat(body.getValue().timeZone()).isEqualTo("Asia/Tokyo");
        assertThat(body.getValue().timeZoneExplicit()).isFalse();
        assertThat(body.getValue().timeZoneFollowsDevice()).isFalse();
    }

    @Test
    @DisplayName("PUT /profile/context leaves both time-zone flags NULL when the body omits them, "
            + "so an old client cannot un-pin a zone")
    void contextLeavesTimeZoneFlagsNullWhenAbsent() throws Exception {
        // A client that predates this feature sends a zone and no flags. The service reads null as
        // "implicit", which is what keeps such a report from touching a pinned zone; binding it as
        // FALSE would look the same here and differently in the release branch.
        userExists(7L);

        mvc.perform(put("/api/users/profile/context")
                        .header("X-User-ID", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeZone\":\"Asia/Tokyo\"}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<ProfileContextRequest> body = ArgumentCaptor.forClass(ProfileContextRequest.class);
        verify(lifecycleContextService).updateContext(eq(7L), body.capture(), any(), any());
        assertThat(body.getValue().timeZoneExplicit()).isNull();
        assertThat(body.getValue().timeZoneFollowsDevice()).isNull();
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
