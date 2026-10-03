package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest;
import com.apimarketplace.catalog.service.ApiService;
import com.apimarketplace.catalog.service.AuthorizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Item 2 (LC-002, CASA readiness): {@code POST /api/apis/configuration/process} is user-reachable
 * through {@code /api/apis/**}. It must go through the sanitising entry point, and only a caller
 * presenting the internal admin token is treated as the importer.
 */
@DisplayName("ApiController configuration/process - user submissions are sanitised")
class ApiControllerSubmissionSanitizeTest {

    private final ApiService apiService = mock(ApiService.class);
    private final ApiController controller = new ApiController(apiService, mock(AuthorizationService.class));
    private final ApiConfigurationRequest request = mock(ApiConfigurationRequest.class);

    @Test
    @DisplayName("regression: a user call reaches the sanitising entry point, never the raw one")
    void userCallIsSanitised() {
        ReflectionTestUtils.setField(controller, "catalogAdminToken", "the-secret");

        controller.processApiConfiguration(request, "user-1", null, null);
        controller.processApiConfiguration(request, "user-1", null, "wrong");

        verify(apiService, org.mockito.Mockito.times(2)).processSubmittedApiConfiguration(request, "user-1", false);
        verify(apiService, never()).processApiConfiguration(any(), any());
    }

    @Test
    @DisplayName("the importer presenting the admin token is trusted")
    void adminTokenIsTrusted() {
        ReflectionTestUtils.setField(controller, "catalogAdminToken", "the-secret");

        controller.processApiConfiguration(request, "system", null, "the-secret");

        verify(apiService).processSubmittedApiConfiguration(request, "system", true);
    }

    @Test
    @DisplayName("an unset admin secret trusts no token")
    void blankSecretTrustsNobody() {
        ReflectionTestUtils.setField(controller, "catalogAdminToken", "");

        controller.processApiConfiguration(request, "user-1", null, "");

        verify(apiService).processSubmittedApiConfiguration(eq(request), eq("user-1"), eq(false));
    }
}
