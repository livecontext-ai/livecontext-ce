package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Deleting a tenant's own OAuth client (a BYOK {@code auth.platform_credentials} row) and the
 * credentials that deletion orphans. The impact count and the cascade go through the same
 * lookup, so the number the user confirms is the number that is revoked.
 *
 * <p>Only what the deleted client issued and nothing else can keep refreshing is revoked
 * ({@link CredentialService#findByokDependents}): the rows still holding the same client id,
 * the tenant's other BYOK rows in any workspace and the platform rows, are passed along.
 */
@Service
public class ByokDeleteService {

    /** Outcome of {@link #deleteWithCascade}. */
    public record Result(boolean deleted, int revokedCredentialCount) {
    }

    private final PlatformCredentialService platformCredentialService;
    private final CredentialService credentialService;

    public ByokDeleteService(PlatformCredentialService platformCredentialService,
                             CredentialService credentialService) {
        this.platformCredentialService = platformCredentialService;
        this.credentialService = credentialService;
    }

    /** How many active credentials {@link #deleteWithCascade} would revoke right now. */
    public int impact(String integrationName, String tenantId, String organizationId) {
        return platformCredentialService.findOwnedRow(integrationName, tenantId, organizationId)
                .map(row -> credentialService.countDependentForByokDelete(tenantId, row, stillHolding(row, tenantId)))
                .orElse(0);
    }

    /**
     * Revoke the orphaned credentials, THEN delete the row, in one transaction: a failure
     * mid-cascade leaves the row in place for a retry. No row in this workspace = nothing to
     * delete, so nothing is revoked.
     */
    @Transactional
    public Result deleteWithCascade(String integrationName, String tenantId, String organizationId) {
        Optional<PlatformCredential> row = platformCredentialService.findOwnedRow(integrationName, tenantId, organizationId);
        int revoked = row
                .map(r -> credentialService.revokeForByokDelete(tenantId, r, stillHolding(r, tenantId)))
                .orElse(0);
        boolean deleted = platformCredentialService.deleteCredential(integrationName, tenantId, organizationId);
        return new Result(deleted, revoked);
    }

    private List<PlatformCredential> stillHolding(PlatformCredential row, String tenantId) {
        return platformCredentialService.rowsHoldingClient(row.clientId(), tenantId, row.id());
    }
}
