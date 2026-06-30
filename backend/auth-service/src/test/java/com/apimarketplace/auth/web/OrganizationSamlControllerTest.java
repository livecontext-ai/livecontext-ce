package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.UpsertOrganizationSamlConnectionRequest;
import com.apimarketplace.auth.service.OrganizationSamlService;
import com.apimarketplace.auth.service.SamlProvisioningException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationSamlController")
class OrganizationSamlControllerTest {

    @Mock private OrganizationSamlService samlService;

    @Test
    @DisplayName("provisioningFailureReturnsBadGateway")
    void provisioningFailureReturnsBadGateway() {
        UUID orgId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UpsertOrganizationSamlConnectionRequest request = new UpsertOrganizationSamlConnectionRequest(
                "Acme SSO",
                "https://idp.example.com/metadata",
                "https://idp.example.com/sso",
                "AQIDBA==",
                true);
        when(samlService.upsert(eq(orgId), eq(42L), any()))
                .thenThrow(new SamlProvisioningException("KC down"));

        OrganizationSamlController controller = new OrganizationSamlController(samlService);
        ResponseEntity<?> response = controller.upsert(orgId, 42L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertThat(body).containsEntry("errorCode", "KEYCLOAK_PROVISIONING_FAILED");
        assertThat(body).containsEntry("message", "KC down");
    }
}
