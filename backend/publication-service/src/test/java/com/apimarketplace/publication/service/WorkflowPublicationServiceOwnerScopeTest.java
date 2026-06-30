package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkflowPublicationService#isCallerInOwnerScope}.
 *
 * <p>This is the server-authoritative ownership signal behind the publication
 * detail's {@code ownedByMe} flag (and the update/unpublish ownership checks). It
 * decides ownership three ways and must never trust a client guess:</p>
 * <ul>
 *   <li><b>USER-owned</b> publication: owner iff the caller's tenant equals
 *       {@code owner_id} (org-independent).</li>
 *   <li><b>ORG-owned</b> publication: owner iff the caller's ACTIVE organization
 *       equals {@code owner_id} (and the org scope is present, not blank).</li>
 *   <li><b>Legacy / not-yet-persisted</b> row (no assigned owner scope): falls
 *       back to {@code publisher_id} equality with the caller's tenant.</li>
 * </ul>
 *
 * <p>It is a pure function of the publication row plus the caller's
 * (tenant, organization) scope - no repository or client interaction - so these
 * tests construct the service with mocked collaborators and never stub them.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService.isCallerInOwnerScope - ownership resolution")
class WorkflowPublicationServiceOwnerScopeTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationSnapshotVersionRepository snapshotVersionRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private PublicationReviewRepository reviewRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private AuthClient authClient;

    private WorkflowPublicationService service;

    private static final String TENANT_ID = "user-77";
    private static final String OTHER_TENANT = "user-99";
    private static final String ORG_ID = "org-acme";
    private static final String OTHER_ORG = "org-globex";

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository,
                snapshotVersionRepository,
                receiptRepository,
                reviewRepository,
                orchestratorClient,
                agentClient,
                interfaceClient,
                dataSourceClient,
                breakdownService,
                new ObjectMapper(),
                snapshotCloneService,
                entitlementGuard,
                authClient);
    }

    /** Never equal to any caller tenant - proves the assigned-owner-scope path does NOT fall back to publisher_id. */
    private static final String UNUSED_PUBLISHER = "legacy-publisher-should-not-be-consulted";

    /** USER-owned publication (hasAssignedOwnerScope == true via owner_type + owner_id). */
    private WorkflowPublicationEntity userOwned(String ownerUserId) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity();
        p.setOwnerType(WorkflowPublicationEntity.OwnerType.USER);
        p.setOwnerId(ownerUserId);
        // Distinct from owner_id so a USER-branch -> publisher_id fall-through mutation is caught.
        p.setPublisherId(UNUSED_PUBLISHER);
        return p;
    }

    /** ORG-owned publication (hasAssignedOwnerScope == true). */
    private WorkflowPublicationEntity orgOwned(String ownerOrgId) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity();
        p.setOwnerType(WorkflowPublicationEntity.OwnerType.ORG);
        p.setOwnerId(ownerOrgId);
        p.setPublisherId(TENANT_ID);
        return p;
    }

    /** Legacy / not-yet-persisted row: owner_id unset -> hasAssignedOwnerScope() false -> publisher_id fallback. */
    private WorkflowPublicationEntity legacy(String publisherId) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity();
        p.setPublisherId(publisherId);
        return p;
    }

    // ==================== USER-owned ====================

    @Test
    @DisplayName("USER-owned: caller whose tenant equals owner_id is the owner (org-independent)")
    void userOwnerMatchingTenantIsOwner() {
        // A different active org must not matter for a USER-owned publication.
        assertThat(service.isCallerInOwnerScope(userOwned(TENANT_ID), TENANT_ID, OTHER_ORG)).isTrue();
    }

    @Test
    @DisplayName("USER-owned: a different tenant is NOT the owner")
    void userOwnerDifferentTenantIsNotOwner() {
        assertThat(service.isCallerInOwnerScope(userOwned(OTHER_TENANT), TENANT_ID, ORG_ID)).isFalse();
    }

    // ==================== ORG-owned ====================

    @Test
    @DisplayName("ORG-owned: caller whose active org equals owner_id is the owner")
    void orgOwnerMatchingOrgIsOwner() {
        assertThat(service.isCallerInOwnerScope(orgOwned(ORG_ID), TENANT_ID, ORG_ID)).isTrue();
    }

    @Test
    @DisplayName("ORG-owned: a different active org is NOT the owner")
    void orgOwnerDifferentOrgIsNotOwner() {
        assertThat(service.isCallerInOwnerScope(orgOwned(ORG_ID), TENANT_ID, OTHER_ORG)).isFalse();
    }

    @Test
    @DisplayName("ORG-owned: a null active org (anonymous / personal scope) is NOT the owner")
    void orgOwnerNullOrgIsNotOwner() {
        assertThat(service.isCallerInOwnerScope(orgOwned(ORG_ID), TENANT_ID, null)).isFalse();
    }

    @Test
    @DisplayName("ORG-owned: a blank active org is NOT the owner")
    void orgOwnerBlankOrgIsNotOwner() {
        assertThat(service.isCallerInOwnerScope(orgOwned(ORG_ID), TENANT_ID, "   ")).isFalse();
    }

    // ==================== Legacy publisher_id fallback ====================

    @Test
    @DisplayName("legacy row (no assigned owner scope): caller equal to publisher_id is the owner")
    void legacyMatchingPublisherIsOwner() {
        // owner_type/owner_id unset -> hasAssignedOwnerScope() false -> publisher_id fallback.
        assertThat(service.isCallerInOwnerScope(legacy(TENANT_ID), TENANT_ID, ORG_ID)).isTrue();
    }

    @Test
    @DisplayName("legacy row: caller not equal to publisher_id is NOT the owner")
    void legacyDifferentPublisherIsNotOwner() {
        assertThat(service.isCallerInOwnerScope(legacy(OTHER_TENANT), TENANT_ID, ORG_ID)).isFalse();
    }

    // ==================== Defensive guards ====================

    @Test
    @DisplayName("null publication is NOT owned (defensive)")
    void nullPublicationIsNotOwner() {
        assertThat(service.isCallerInOwnerScope(null, TENANT_ID, ORG_ID)).isFalse();
    }

    @Test
    @DisplayName("null tenant (anonymous /by-id path) is NOT the owner")
    void nullTenantIsNotOwner() {
        assertThat(service.isCallerInOwnerScope(userOwned(TENANT_ID), null, ORG_ID)).isFalse();
    }
}
