package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.config.GlobalExceptionHandler;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.service.ApiService;
import com.apimarketplace.catalog.service.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: the six pricing / monetization writes on /api/apis had no ownership check
 * while every sibling write (PUT /{id}, /basic-info, /config, /tools/{toolId}, DELETE /{id})
 * calls {@link AuthorizationService#verifyApiOwnership}. Any signed-in user could re-price
 * or re-plan any other developer's API. The real AuthorizationService runs here (only the
 * repository is mocked) so the owner rule itself is exercised, not a stub of it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiController pricing writes: owner only")
class ApiControllerPricingAuthzTest {

    private static final String OWNER = "owner-1";
    private static final String OTHER = "intruder-2";
    private static final UUID API_ID = UUID.randomUUID();
    private static final UUID TOOL_ID = UUID.randomUUID();

    private static final String PRICING_MODELS_BODY =
            "{\"apiId\":\"" + API_ID + "\",\"selectedPricingModels\":[\"freemium\"]}";
    private static final String PAID_PLANS_BODY =
            "{\"selectedPlans\":{\"pro\":true},\"planTools\":{\"pro\":[]}}";
    private static final String TOOL_FREEMIUM_BODY =
            "{\"apiId\":\"" + API_ID + "\",\"apiToolId\":\"" + TOOL_ID
                    + "\",\"monetizationType\":\"freemium\",\"config\":{\"freeRequests\":10}}";
    private static final String BATCH_FREEMIUM_BODY =
            "{\"apiId\":\"" + API_ID + "\",\"monetizationType\":\"freemium\",\"toolsConfig\":{\""
                    + TOOL_ID + "\":{\"freeRequests\":10}}}";
    private static final String TOOL_PAID_BODY =
            "{\"apiId\":\"" + API_ID + "\",\"apiToolId\":\"" + TOOL_ID
                    + "\",\"monetizationType\":\"paid\",\"config\":{\"planName\":\"pro\",\"quota\":100}}";
    private static final String BATCH_PAID_BODY =
            "{\"apiId\":\"" + API_ID + "\",\"monetizationType\":\"paid\",\"toolsConfig\":{\""
                    + TOOL_ID + "\":{\"planName\":\"pro\",\"quota\":100}}}";

    @Mock
    private ApiService apiService;
    @Mock
    private ApiRepository apiRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ApiEntity api = new ApiEntity();
        api.setCreatedBy(OWNER);
        lenient().when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));

        ApiController controller = new ApiController(apiService, new AuthorizationService(apiRepository));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder json(String url, String body) {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Nested
    @DisplayName("a signed-in user who does not own the API is refused with 403")
    class NonOwnerRefused {

        @Test
        @DisplayName("PUT /{id}/pricing-models")
        void pricingModels() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/pricing-models", PRICING_MODELS_BODY)
                            .header("X-User-ID", OTHER))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("PUT /{id}/paid-plans")
        void paidPlans() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/paid-plans", PAID_PLANS_BODY)
                            .header("X-User-ID", OTHER))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("PUT /{apiId}/tools/{apiToolId}/freemium-config")
        void toolFreemium() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/tools/" + TOOL_ID + "/freemium-config",
                            TOOL_FREEMIUM_BODY).header("X-User-ID", OTHER))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("PUT /{apiId}/tools/freemium-config/batch")
        void batchFreemium() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/tools/freemium-config/batch", BATCH_FREEMIUM_BODY)
                            .header("X-User-ID", OTHER))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("PUT /{apiId}/tools/{apiToolId}/paid-config")
        void toolPaid() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/tools/" + TOOL_ID + "/paid-config", TOOL_PAID_BODY)
                            .header("X-User-ID", OTHER))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("PUT /{apiId}/tools/paid-config/batch")
        void batchPaid() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/tools/paid-config/batch", BATCH_PAID_BODY)
                            .header("X-User-ID", OTHER))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(apiService);
        }
    }

    @Nested
    @DisplayName("a caller with no identity is refused with 403")
    class AnonymousRefused {

        @Test
        @DisplayName("PUT /{id}/pricing-models without X-User-ID, even with a Bearer header")
        void pricingModelsWithoutUser() throws Exception {
            // Pre-fix the controller replaced a missing X-User-ID by "authenticated-user"
            // whenever any Bearer header was present.
            mockMvc.perform(json("/api/apis/" + API_ID + "/pricing-models", PRICING_MODELS_BODY)
                            .header("Authorization", "Bearer anything"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("PUT /{apiId}/tools/paid-config/batch without X-User-ID")
        void batchPaidWithoutUser() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/tools/paid-config/batch", BATCH_PAID_BODY))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(apiService);
        }
    }

    @Nested
    @DisplayName("the owner keeps working (frontend Monetize tab)")
    class OwnerAllowed {

        @Test
        @DisplayName("PUT /{id}/pricing-models reaches the service with the owner id")
        void pricingModels() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/pricing-models", PRICING_MODELS_BODY)
                            .header("X-User-ID", OWNER))
                    .andExpect(status().isOk());
            verify(apiService).updatePricingModels(eq(API_ID), any(), eq(OWNER));
        }

        @Test
        @DisplayName("PUT /{id}/paid-plans reaches the service")
        void paidPlans() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/paid-plans", PAID_PLANS_BODY)
                            .header("X-User-ID", OWNER))
                    .andExpect(status().isOk());
            verify(apiService).updatePaidPlans(eq(API_ID), any(), eq(OWNER));
        }

        @Test
        @DisplayName("owner id comparison is case-insensitive, like the sibling writes")
        void ownerCaseInsensitive() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/tools/" + TOOL_ID + "/freemium-config",
                            TOOL_FREEMIUM_BODY).header("X-User-ID", OWNER.toUpperCase()))
                    .andExpect(status().isOk());
            verify(apiService).updateToolFreemiumConfig(eq(API_ID), eq(TOOL_ID), any(), eq(OWNER.toUpperCase()));
        }

        @Test
        @DisplayName("batch writes reach the service (tools are then filtered to this API)")
        void batches() throws Exception {
            mockMvc.perform(json("/api/apis/" + API_ID + "/tools/freemium-config/batch", BATCH_FREEMIUM_BODY)
                            .header("X-User-ID", OWNER))
                    .andExpect(status().isOk());
            mockMvc.perform(json("/api/apis/" + API_ID + "/tools/paid-config/batch", BATCH_PAID_BODY)
                            .header("X-User-ID", OWNER))
                    .andExpect(status().isOk());
            mockMvc.perform(json("/api/apis/" + API_ID + "/tools/" + TOOL_ID + "/paid-config", TOOL_PAID_BODY)
                            .header("X-User-ID", OWNER))
                    .andExpect(status().isOk());

            verify(apiService).batchUpdateToolsFreemiumConfig(eq(API_ID), any(), eq(OWNER));
            verify(apiService).batchUpdateToolsPaidConfig(eq(API_ID), any(), eq(OWNER));
            verify(apiService).updateToolPaidConfig(eq(API_ID), eq(TOOL_ID), any(), eq(OWNER));
        }
    }

    @Test
    @DisplayName("an unknown API id is left to the service (404 path), as for the sibling writes")
    void unknownApiFallsThroughToService() throws Exception {
        UUID unknown = UUID.randomUUID();
        org.mockito.Mockito.when(apiRepository.findById(unknown)).thenReturn(Optional.empty());
        org.mockito.Mockito.when(apiService.updatePricingModels(eq(unknown), any(), eq(OTHER)))
                .thenThrow(new RuntimeException("API not found with ID: " + unknown));

        mockMvc.perform(json("/api/apis/" + unknown + "/pricing-models", PRICING_MODELS_BODY)
                        .header("X-User-ID", OTHER))
                .andExpect(status().isNotFound());
        verify(apiService, never()).updatePaidPlans(any(), any(), any());
    }
}
