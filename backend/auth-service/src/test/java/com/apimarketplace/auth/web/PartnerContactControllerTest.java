package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.PartnerContactService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartnerContactControllerTest {

    private final PartnerContactService service = mock(PartnerContactService.class);

    private MockMvc mvc(boolean unlimited) {
        return MockMvcBuilders.standaloneSetup(new PartnerContactController(service, unlimited)).build();
    }

    @Test
    @DisplayName("GET my-partner: the client's partner, the id as a string (DM threads key users by string)")
    void myPartner() throws Exception {
        when(service.myPartner(7L)).thenReturn(Optional.of(new PartnerContactService.MyPartner(42L, "Northwind Studio", "northwind", "gold")));

        mvc(false).perform(get("/api/billing/partner/my-partner").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner.user_id").value("42"))
                .andExpect(jsonPath("$.partner.name").value("Northwind Studio"))
                .andExpect(jsonPath("$.partner.handle").value("northwind"))
                .andExpect(jsonPath("$.partner.tier").value("gold"));
    }

    @Test
    @DisplayName("GET my-partner: none is a 200 with partner null, so the messages list asks without a 404")
    void none() throws Exception {
        when(service.myPartner(7L)).thenReturn(Optional.empty());

        mvc(false).perform(get("/api/billing/partner/my-partner").header("X-User-ID", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("GET my-partner: no caller is 401, a self-hosted build is 503")
    void refusals() throws Exception {
        mvc(false).perform(get("/api/billing/partner/my-partner")).andExpect(status().isUnauthorized());
        mvc(false).perform(get("/api/billing/partner/my-partner").header("X-User-ID", "x")).andExpect(status().isUnauthorized());
        mvc(true).perform(get("/api/billing/partner/my-partner").header("X-User-ID", "7"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("not_available_in_ce"));
        verifyNoInteractions(service);
    }
}
