package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ByokDeleteService - delete a BYOK client and only what it orphans")
class ByokDeleteServiceTest {

    private static final String TENANT = "1";
    private static final String ORG = "org-a";

    @Mock private PlatformCredentialService platformCredentialService;
    @Mock private CredentialService credentialService;
    @InjectMocks private ByokDeleteService service;

    private static final PlatformCredential ROW = new PlatformCredential(181L, "tiktok", "TikTok", AuthType.OAUTH2,
            "sandbox-client", "secret", null, null, null, "https://a", "https://t", null,
            "tiktok", null, null, true, true, Map.of(), BigDecimal.ZERO, 0,
            Instant.now(), Instant.now(), null, TENANT, "primary", ORG);
    private static final List<PlatformCredential> STILL_HOLDING = List.of();

    private void stubRow() {
        when(platformCredentialService.findOwnedRow("tiktok", TENANT, ORG)).thenReturn(Optional.of(ROW));
        when(platformCredentialService.rowsHoldingClient("sandbox-client", TENANT, 181L)).thenReturn(STILL_HOLDING);
    }

    @Test
    @DisplayName("impact counts the dependents of the row being deleted, with the rows still holding its client")
    void impactCountsTheRowsDependents() {
        stubRow();
        when(credentialService.countDependentForByokDelete(TENANT, ROW, STILL_HOLDING)).thenReturn(1);

        assertThat(service.impact("tiktok", TENANT, ORG)).isEqualTo(1);
    }

    @Test
    @DisplayName("deleteWithCascade revokes the dependents BEFORE deleting the row, so a failure mid-cascade leaves the row for a retry")
    void revokesThenDeletes() {
        stubRow();
        when(credentialService.revokeForByokDelete(TENANT, ROW, STILL_HOLDING)).thenReturn(1);
        when(platformCredentialService.deleteCredential("tiktok", TENANT, ORG)).thenReturn(true);

        ByokDeleteService.Result result = service.deleteWithCascade("tiktok", TENANT, ORG);

        InOrder order = inOrder(credentialService, platformCredentialService);
        order.verify(credentialService).revokeForByokDelete(TENANT, ROW, STILL_HOLDING);
        order.verify(platformCredentialService).deleteCredential("tiktok", TENANT, ORG);
        assertThat(result).isEqualTo(new ByokDeleteService.Result(true, 1));
    }

    @Test
    @DisplayName("no BYOK row in this workspace: nothing is revoked and the impact is 0")
    void noRowRevokesNothing() {
        when(platformCredentialService.findOwnedRow("tiktok", TENANT, ORG)).thenReturn(Optional.empty());
        when(platformCredentialService.deleteCredential("tiktok", TENANT, ORG)).thenReturn(false);

        assertThat(service.impact("tiktok", TENANT, ORG)).isZero();
        assertThat(service.deleteWithCascade("tiktok", TENANT, ORG)).isEqualTo(new ByokDeleteService.Result(false, 0));
        verifyNoInteractions(credentialService);
    }
}
