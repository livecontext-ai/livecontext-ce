package com.apimarketplace.auth.service;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.scope.OrgAccessCacheInvalidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrgRestrictionQueryService")
class OrgRestrictionQueryServiceTest {

    private static final String ORG = "org-1";
    private static final String MEMBER = "user-1";

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private EventBus eventBus;

    private OrgRestrictionQueryService service;

    @BeforeEach
    void setUp() {
        service = new OrgRestrictionQueryService(jdbc, eventBus);
    }

    @Test
    @DisplayName("restrictAccess publishes an org access cache invalidation event")
    void restrictAccessPublishesOrgAccessCacheInvalidation() {
        service.restrictAccess(ORG, MEMBER, "agent", "agent-1", "owner-1", "DENY");

        verify(jdbc).update(
                contains("INSERT INTO org_resource_restrictions"),
                eq(ORG),
                eq(MEMBER),
                eq("agent"),
                eq("agent-1"),
                eq("owner-1"),
                eq("DENY"));
        verify(eventBus).publish(
                OrgAccessCacheInvalidation.CHANNEL,
                OrgAccessCacheInvalidation.messageFor(ORG, MEMBER));
    }

    @Test
    @DisplayName("restrictAccess keeps the committed restriction when cache invalidation has a linkage failure")
    void restrictAccessKeepsCommittedRestrictionWhenCacheInvalidationHasLinkageFailure() {
        doThrow(new NoClassDefFoundError("com/apimarketplace/common/scope/OrgAccessCacheInvalidation"))
                .when(eventBus)
                .publish(eq(OrgAccessCacheInvalidation.CHANNEL), eq(OrgAccessCacheInvalidation.messageFor(ORG, MEMBER)));

        service.restrictAccess(ORG, MEMBER, "agent", "agent-1", "owner-1", "DENY");

        verify(jdbc).update(
                contains("INSERT INTO org_resource_restrictions"),
                eq(ORG),
                eq(MEMBER),
                eq("agent"),
                eq("agent-1"),
                eq("owner-1"),
                eq("DENY"));
        verify(eventBus).publish(
                OrgAccessCacheInvalidation.CHANNEL,
                OrgAccessCacheInvalidation.messageFor(ORG, MEMBER));
    }

    @Test
    @DisplayName("restrictAccess with READ permission stores the read-only level")
    void restrictAccessStoresReadPermission() {
        service.restrictAccess(ORG, MEMBER, "file", "file-1", "owner-1", "READ");

        verify(jdbc).update(
                contains("INSERT INTO org_resource_restrictions"),
                eq(ORG),
                eq(MEMBER),
                eq("file"),
                eq("file-1"),
                eq("owner-1"),
                eq("READ"));
    }
}
