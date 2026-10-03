package com.apimarketplace.orchestrator.services.notification;

import org.springframework.stereotype.Component;

/**
 * Bell-side name resolver for {@code CREATOR_FOLLOWED}: someone subscribed to the recipient.
 * The subscriber's account lives in auth-service, so publication-service ships their chosen
 * display name inline as {@code payload.subjectName} (and their @handle as
 * {@code payload.profileHandle}, which the bell links to).
 */
@Component
public class UserSubjectNameResolver extends PayloadSubjectNameResolver {

    @Override
    public String subjectType() {
        return SubjectNameResolver.USER;
    }
}
