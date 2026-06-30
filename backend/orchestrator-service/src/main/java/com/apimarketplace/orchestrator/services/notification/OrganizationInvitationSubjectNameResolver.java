package com.apimarketplace.orchestrator.services.notification;

import org.springframework.stereotype.Component;

/**
 * Bell-side name resolver for CE organization invitation notifications.
 * Reads {@code payload.subjectName} populated by auth-service when emitting
 * {@code ORG_INVITATION_PENDING}.
 */
@Component
public class OrganizationInvitationSubjectNameResolver extends PayloadSubjectNameResolver {

    @Override
    public String subjectType() {
        return SubjectNameResolver.ORG_INVITATION;
    }
}
