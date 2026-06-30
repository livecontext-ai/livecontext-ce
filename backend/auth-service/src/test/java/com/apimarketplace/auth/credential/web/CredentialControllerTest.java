package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialController")
class CredentialControllerTest {

    @Mock
    private CredentialService credentialService;

    @Mock
    private TenantResolver tenantResolver;

    @Mock
    private RequestParameterExtractor extractor;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private CredentialController controller;

    @Test
    @DisplayName("clear-default maps only-credential default guard to 409 instead of leaking a 500")
    void clearDefaultOnlyCredentialReturnsConflict() {
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn(null);
        doThrow(new IllegalStateException("Cannot remove default status: this is the only credential for github"))
                .when(credentialService).clearDefault("tenant-1", null, 42L);

        ResponseEntity<Void> response = controller.clearDefault(42L, request, null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(tenantResolver).validate("tenant-1");
        verify(credentialService).clearDefault("tenant-1", null, 42L);
    }
}
